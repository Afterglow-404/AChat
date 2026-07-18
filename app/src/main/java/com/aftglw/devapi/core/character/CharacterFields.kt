package com.aftglw.devapi.core.character

/**
 * 字段化角色模型 — 把单一 persona 字符串拆成结构化字段，便于编辑器分字段编辑。
 *
 * 持久化策略：拼装成带 XML 风格标签的字符串存入 ChatItem.persona（保持向后兼容）。
 * 旧的无标签纯文本 persona 会被当作 [description] 解析。
 *
 * 标签格式：
 *   <description>...</description>
 *   <personality>...</personality>
 *   <scenario>...</scenario>
 *   <mes_example>...</mes_example>
 *   <system_prompt>...</system_prompt>
 */
data class CharacterFields(
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val mesExample: String = "",
    val systemPrompt: String = ""
) {
    /** 拼装为 persona 字符串（空字段跳过） */
    fun toPersona(): String {
        return buildString {
            if (description.isNotBlank()) append("<description>$description</description>\n")
            if (personality.isNotBlank()) append("<personality>$personality</personality>\n")
            if (scenario.isNotBlank()) append("<scenario>$scenario</scenario>\n")
            if (mesExample.isNotBlank()) append("<mes_example>$mesExample</mes_example>\n")
            if (systemPrompt.isNotBlank()) append("<system_prompt>$systemPrompt</system_prompt>\n")
        }.trimEnd()
    }

    companion object {
        /** 从 persona 字符串解析回字段。无标签的纯文本归入 description。 */
        fun fromPersona(persona: String): CharacterFields {
            if (persona.isBlank()) return CharacterFields()
            // 检测是否包含任何已知标签
            val hasTags = TAGS.any { persona.contains("<$it>") }
            if (!hasTags) {
                // 旧格式：纯文本当作 description
                return CharacterFields(description = persona.trim())
            }
            return CharacterFields(
                description = extractTag(persona, "description"),
                personality = extractTag(persona, "personality"),
                scenario = extractTag(persona, "scenario"),
                mesExample = extractTag(persona, "mes_example"),
                systemPrompt = extractTag(persona, "system_prompt")
            )
        }

        private val TAGS = listOf("description", "personality", "scenario", "mes_example", "system_prompt")

        private fun extractTag(text: String, tag: String): String {
            val open = "<$tag>"
            val close = "</$tag>"
            val start = text.indexOf(open)
            if (start < 0) return ""
            val contentStart = start + open.length
            val end = text.indexOf(close, contentStart)
            return if (end < 0) text.substring(contentStart).trim() else text.substring(contentStart, end).trim()
        }
    }
}
