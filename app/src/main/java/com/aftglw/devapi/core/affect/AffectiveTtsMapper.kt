package com.aftglw.devapi.core.affect

/**
 * 关系场 → TTS 提示映射器（P1.4 新增）。
 *
 * 设计目标（设计文档第十四章 P1.4）：
 * - 将 AffectiveField 四维状态映射到 TTS 语速/停顿/语气提示
 * - **不改变角色音色身份**（voice_id 由 TtsVoiceRouter 独立路由，本映射器不介入）
 * - 纯函数式，无 IO，无 LLM 依赖（AffectiveEngine.snapshot 不可用时仍能工作）
 * - 低风险、可逆、温和（设计文档 14.4.3 R-S3 约束）
 *
 * 输出三类提示：
 * 1. [promptBlock] — 注入 PromptBuilder，引导 LLM 生成带语气的文本 + 多用/少用【顿】
 * 2. [instruction] — 传给 TtsProviderManager.speak 的 instructionOverride（仅 Qwen3 真正使用）
 * 3. [pauseMs] — 顺序朗读协程的段间停顿（对所有引擎生效，最普适的停顿通道）
 *
 * 非目标（P1.4 不做）：
 * - 不扩展 TtsProvider.speak 接口（避免改所有 Provider + 缓存 key）
 * - 不修改 SystemTtsProvider 的 speechRate/pitch（需改 VoiceTts 全局态，有并发风险）
 * - 不动 TtsVoiceRouter（角色音色身份不变）
 *
 * 设计文档第十四章 P1.4 + 14.4.3 R-S3（只能影响低风险行为）。
 */
object AffectiveTtsMapper {

    /**
     * 映射结果。
     *
     * @param promptBlock 注入 PromptBuilder 的【说话方式】块（引导 LLM 生成带语气的文本）
     * @param instruction 传给 TtsProviderManager.speak 的 instructionOverride（仅 Qwen3 生效）
     * @param pauseMs 顺序朗读协程的段间停顿（毫秒，对所有引擎生效）
     */
    data class TtsHints(
        val promptBlock: String,
        val instruction: String,
        val pauseMs: Long,
    )

    /**
     * 从 AffectiveField 计算 TTS 提示。
     *
     * 映射规则（低风险保守版）：
     *
     * **promptBlock【说话方式】块**：
     * - warmth > 0.6（亲密）→ 温柔亲密，多用【顿】让节奏舒缓
     * - warmth ∈ (0.2, 0.6]（熟络）→ 自然熟络，正常节奏
     * - warmth ∈ (-0.2, 0.2]（初识）→ 礼貌得体，少用【顿】
     * - warmth ≤ -0.2（疏远/冷淡）→ 平淡克制，极少【顿】
     * - tension > 0.6（剑拔弩张）→ 紧绷克制，不激化
     * - anticipation > 0.7（焦急等待）→ 带一点急切
     * - drift < -0.5（渐行渐远）→ 平淡，不主动拉近
     *
     * **instruction（Qwen3 instruct 字段）**：
     * - 把 promptBlock 中的语气词精简成一句指令，如"语气温柔亲密"
     * - 为空时返回 ""（不影响 Qwen3 默认行为）
     *
     * **pauseMs（段间停顿）**：
     * - warmth > 0.6 → 600ms（亲密时多停顿，让对话有呼吸感）
     * - warmth > 0.2 → 400ms（正常）
     * - warmth > -0.2 → 250ms（初识时少停顿，保持简洁）
     * - else → 150ms（疏远时极少停顿，避免拖沓）
     * - tension > 0.6 时再减 150ms（紧张时节奏更快）
     * - anticipation > 0.7 时再减 100ms（急切时少停顿）
     *
     * @param field AffectiveField 快照（可为 null，null 时返回默认值）
     */
    fun fromField(field: AffectiveField?): TtsHints {
        // field 为 null（AffectiveEngine.snapshot 失败/未启用）时返回默认值，不阻塞 TTS
        if (field == null) {
            return TtsHints(
                promptBlock = "",
                instruction = "",
                pauseMs = 400L,
            )
        }

        // ── 1. promptBlock【说话方式】块 ──
        val promptParts = mutableListOf<String>()
        val instructionParts = mutableListOf<String>()

        // warmth 主导语气基调
        when {
            field.warmth > 0.6f -> {
                promptParts.add("语气温柔亲密，像对老朋友说话")
                promptParts.add("可以多用【顿】让节奏舒缓，像在认真陪对方")
                instructionParts.add("语气温柔亲密")
            }
            field.warmth > 0.2f -> {
                promptParts.add("语气自然熟络")
                instructionParts.add("语气自然")
            }
            field.warmth > -0.2f -> {
                promptParts.add("语气礼貌得体，不过分亲昵")
                promptParts.add("少用【顿】，保持简洁")
                instructionParts.add("语气礼貌")
            }
            else -> {
                promptParts.add("语气平淡克制，不主动拉近关系")
                promptParts.add("极少用【顿】，避免拖沓")
                instructionParts.add("语气克制")
            }
        }

        // tension 修正（优先级高，覆盖 warmth 的柔和基调）
        when {
            field.tension > 0.6f -> {
                promptParts.add("关系剑拔弩张，语气紧绷克制，绝不激化矛盾")
                instructionParts.clear()
                instructionParts.add("语气紧绷克制")
            }
            field.tension > 0.4f -> {
                promptParts.add("关系有点紧张，说话小心一点")
            }
        }

        // anticipation 修正
        if (field.anticipation > 0.7f) {
            promptParts.add("你有点急切地想跟对方说，可以带一点迫不及待的感觉")
            instructionParts.add("略带急切")
        }

        // drift 修正
        if (field.drift < -0.5f) {
            promptParts.add("关系在疏远，不要刻意热情，平淡一些就好")
        }

        val promptBlock = if (promptParts.isEmpty()) "" else buildString {
            append("\n\n【说话方式】")
            promptParts.forEach { append("\n").append(it) }
        }
        val instruction = instructionParts.joinToString("，").ifBlank { "" }

        // ── 2. pauseMs（段间停顿）──
        var pause = when {
            field.warmth > 0.6f -> 600L
            field.warmth > 0.2f -> 400L
            field.warmth > -0.2f -> 250L
            else -> 150L
        }
        if (field.tension > 0.6f) pause = (pause - 150L).coerceAtLeast(100L)
        if (field.anticipation > 0.7f) pause = (pause - 100L).coerceAtLeast(100L)

        return TtsHints(
            promptBlock = promptBlock,
            instruction = instruction,
            pauseMs = pause,
        )
    }
}
