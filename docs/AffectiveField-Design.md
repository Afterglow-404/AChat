# 情绪场引擎 AffectiveField 设计方案

> **定位**：Wisp 项目的原创情绪/关系引擎。基于移动端长期陪伴场景设计，与既有方案侧重点不同，互为补充而非替代。
>
> **核心视角**：在"角色的情绪"之外，我们尝试补上"两人的关系"这一层。陪伴场景下用户在意的是"我们怎么样了"，而非单一角色的情绪数值。
>
> **状态**：设计草案，待研究
> **日期**：2026-07-21

---

## 一、设计理念

### 1.1 与主流路线的侧重点差异

| 主流方案常见做法 | 我们的尝试 |
|---|---|
| 基于 PAD/OCEAN 等心理学模型建模个体情绪 | 在个体情绪之外，叠加一层"关系状态"作为补充 |
| LLM 做 appraisal 评估事件 | 探索用移动端原生信号（韵律）补充 LLM 评估，降低 token 成本 |
| 固定周期反思 | 尝试用未完成事件驱动，受 Zeigarnik effect 启发 |
| AI 追求最佳回复 | 允许 AI 受生理约束影响，状态有起伏 |
| 全 LLM 或全模板 | 借鉴 Kahneman 双系统思路，按认知负荷动态调度 |

### 1.2 五个原创机制

1. **RhythmSensor 韵律感知器** — 从对话节奏而非内容捕捉状态，利用移动端原生信号
2. **PendingEvents 未完成事件栈** — 把"未完成的事"作为关系中的欠债，主动收尾
3. **AffectiveField 双向情绪场** — 在个体情绪之外，额外建模两人之间的关系
4. **VirtualBody 虚拟生理** — 给 AI 一层生理约束，让状态有起伏
5. **System1/System2 双系统调度** — 按认知负荷动态切换本地与 LLM，优化 token 成本

### 1.3 核心卖点

- **"关系即数据"** — 陪伴场景的根本抽象
- **"省 token 是第一公民"** — 移动端长期陪伴必须考虑成本
- **"AI 也有状态不好"** — 状态起伏更接近真实相处

---

## 二、机制详细规格

### 2.1 RhythmSensor 韵律感知器

#### 2.1.1 设计动机

业界主流情绪引擎多让 LLM 做 appraisal，需要一次额外 LLM 调用或外部传入多维数值。我们尝试换个角度：**用户怎么说话本身也是信号**，而这些信号在移动端天然可得。

#### 2.1.2 数据采集

每次用户发送消息时记录：

```kotlin
data class RhythmSample(
    val timestamp: Long,           // 发送时刻
    val replyLatencyMs: Long,      // 距上一条消息间隔（首次发话题 = 0）
    val lengthChars: Int,          // 字符数
    val punctuationDensity: Float, // 标点密度 = 标点数 / 字符数
    val exclamCount: Int,          // ！数量
    val questionCount: Int,        // ？数量
    val ellipsisCount: Int,        // …/... 数量
    val tildeCount: Int,           // ～/~ 数量
    val isInitiative: Boolean,     // 是否主动开话题（距上一条 > 30min）
    val hourOfDay: Int,            // 0-23
)
```

保留最近 100 条样本的滑动窗口（约一周对话量）。

#### 2.1.3 派生指标

```kotlin
data class RhythmProfile(
    // 趋势：当前 vs 历史 baseline 的偏离度
    val latencyTrend: Float,         // -1 加速 → +1 减速，相对自己 baseline
    val lengthTrend: Float,          // -1 变短 → +1 变长
    val punctTrend: Float,           // 标点密度变化

    // 当前状态分位（相对自己历史）
    val latencyPercentile: Float,    // 0-1，1 = 比自己 99% 时候都慢
    val lengthPercentile: Float,     // 0-1，1 = 比自己 99% 时候都长

    // 主动率
    val initiativeRate7d: Float,     // 最近 7 天主动开话题比例

    // 活跃时段分布
    val activeHourHistogram: IntArray, // 24 维，每个小时的消息计数

    // 综合状态标签（本地计算，零 LLM）
    val stateHint: String,           // "用户回复越来越慢，可能在敷衍或累"
)
```

#### 2.1.4 计算算法

**Baseline**：用最近 100 条样本（不足 20 条时不计算 trend）

**Trend 计算**（以 latency 为例）：
```
recent_avg = avg(最近 10 条 latency)
baseline_avg = avg(全部 100 条 latency)
latencyTrend = (recent_avg - baseline_avg) / max(baseline_avg, 5000ms)
clamp 到 [-1, 1]
```

**Percentile 计算**：
```
latencyPercentile = count(样本中 latency < 当前 latency) / 总样本数
```

**stateHint 启发式规则**（优先级从高到低）：
```
if latencyTrend > 0.5 and lengthTrend < -0.3:
    "用户回复越来越慢且越来越短，可能在敷衍或累"
elif latencyPercentile > 0.9 and lengthPercentile < 0.2:
    "用户极简回复，可能不想深聊"
elif exclamCount + tildeCount > 3:
    "用户情绪激动或兴奋"
elif ellipsisCount > 2:
    "用户犹豫或有未尽之言"
elif initiativeRate7d > 0.5:
    "用户主动性强，关系在升温"
elif initiativeRate7d < 0.1 and 样本数 > 30:
    "用户几乎不主动，关系可能在冷淡"
else:
    ""  // 不产生 hint
```

#### 2.1.5 注入方式

`RhythmProfile.stateHint` 非空时注入 PromptBuilder：
```
【对方状态】用户回复越来越慢，消息越来越短，可能在敷衍或累
建议：这次回复更简短温和，不要逼对方深聊
```

注意：**不注入完整 RhythmProfile 数值**，只注入人类可读的 hint + 行为建议。LLM 不需要看数字。

#### 2.1.6 存储设计

`rhythm_$chatName` SharedPreferences：
- `samples_json`：最近 100 条样本 JSON 数组
- `baseline_latency` / `baseline_length`：缓存值，避免每次重算

#### 2.1.7 理论根基与可能的细化方向

对话节奏领域有多组综述（Springer 2025 turn-taking 综述、ScienceDirect coordination 概念图、Nature 2026.02 Gordon & Bartsch 人际生理同步综述）明确区分了三种相关但不同的机制：

| 机制 | 含义 | 当前 RhythmSensor 是否覆盖 |
|---|---|---|
| **turn-responsiveness** 轮次响应性 | 立即回应的能力（短时延迟） | 部分覆盖（`replyLatencyMs`） |
| **entrainment** 节奏拖引 | 双方节奏趋同 | 部分覆盖（`latencyTrend`） |
| **synchrony** 相位同步 | 双方行为/生理相位对齐 | 未覆盖 |

Gordon & Bartsch 进一步提出"社交定向 vs 表现定向"二分法，可对应到陪伴场景中"陪你"与"完成任务"两种对话模式 — 这恰好是 stateHint 中"敷衍 vs 累"难以区分的根因。

**可考虑的细化方向（非当前实现，待研究）**：
- 在 `RhythmProfile` 中显式区分 `turnResponsiveness`（短时立即回应能力）与 `entrainmentScore`（与 AI 节奏趋同度）两个维度
- stateHint 规则按"社交定向 / 表现定向"分类，让"敷衍"和"累"的判断有更清晰的依据
- MDPI 2025.12 关于 spoken dialogue systems 的 turn-taking 综述可作为移动端实现的具体参考

这些不改变当前数据结构和算法骨架，只是为后续迭代提供方向。

#### 2.1.8 进一步的理论根基：Polyvagal 三态

Porges 的 Polyvagal Theory 提出自主神经三层模型：
- **腹侧迷走（ventral vagal）— 社会参与**：安全状态下激活，人能稳定对话、共情、连接
- **交感（sympathetic）— 战斗/逃跑**：威胁下激活，焦虑、急促、防御
- **背侧迷走（dorsal vagal）— 冻结/解离**：极端威胁下激活， shutdown、消失、不回应

**社会参与是默认的安全信号** — 当腹侧激活时，人会自然进入连接状态。

这给 RhythmSensor 找到了**神经科学根基** — 用户回复节奏的变化可能反映自主神经状态切换：

| Polyvagal 状态 | 节奏特征 | 当前 RhythmSensor 是否能识别 |
|---|---|---|
| 腹侧（安全） | 回复稳定、长度适中、标点丰富 | 部分覆盖（baseline 状态） |
| 交感（焦虑） | 回复快、长度短、感叹号多、错字多 | 部分覆盖（exclamCount 异常） |
| 背侧（冻结） | 回复极慢、长度极短、或干脆消失 | 部分覆盖（latencyPercentile > 0.9） |

| Polyvagal 启示 | 当前 RhythmSensor | 可考虑的细化 |
|---|---|---|
| 状态切换有生理信号 | stateHint 6 条规则用单一维度判断 | 引入 `neuralStateHint` 三态分类（ventral / sympathetic / dorsal），优先级高于现有 stateHint |
| 不同状态需要不同响应 | 现有"建议更简短温和"是单一策略 | 交感态 → 安抚为主、避免追问；背侧态 → 不催促、发送"不要求回应"的陪伴消息 |
| 区分"敷衍"vs"累" | 当前混为一谈 | 敷衍是腹侧（用户安全但不愿深聊），背侧是累/抑郁 — 神经状态完全不同 |

**可考虑的细化方向（非当前实现，待研究）**：
- 在 `RhythmProfile` 中新增 `neuralStateHint: Ventral | Sympathetic | Dorsal` 字段
- 三态判定规则基于多个指标的组合（latency + length + punctuation + 频率），而非单一阈值
- 三态与现有 stateHint 优先级关系：`neuralStateHint` 优先，stateHint 作为细分补充
- AI 响应策略按三态差异化（特别是背态下"不要求回应"是关键 — 主动消息不应触发用户回应压力）

这些细化不改变现有数据采集结构（RhythmSample 不变），只在派生指标层新增三态分类。

---

### 2.2 AffectiveField 双向情绪场

#### 2.2.1 设计动机

主流情绪引擎多存角色多通道情绪值。在陪伴场景下，用户的感受更多落在"我们之间怎么样"，而非"她今天愤怒值 0.3"。Bowlby 依恋理论指出依恋是双向调节系统，我们尝试把这层关系做成数据结构。

#### 2.2.2 四个维度

| 维度 | 含义 | 范围 | 触发 |
|---|---|---|---|
| `tension` 张力 | 冲突积累 | -1 和谐 ~ +1 剑拔弩张 | 争执 +，温和 - |
| `warmth` 温度 | 亲密积累 | -1 冷淡 ~ +1 亲密 | 倾诉 +，敷衍 - |
| `anticipation` 期待 | 等待回应 | 0 平静 ~ +1 焦急 | 用户不回 +，回 - |
| `drift` 漂移 | 关系走向 | -1 疏远 ~ +1 靠近 | 长期不联系 -，深聊 + |

#### 2.2.3 触发规则

每次 AI 回复后，根据本轮交互更新场：

```kotlin
fun updateField(
    field: AffectiveField,
    userMsg: String,
    aiReply: String,
    rhythm: RhythmProfile,
    pendingResolved: Int,  // 本轮收尾了几个 pending event
): AffectiveField {
    val newField = field.copy()

    // 张力：争执词 + 拒绝词 + 感叹号
    val conflictSignals = countSignals(userMsg, listOf("不", "别", "滚", "烦", "讨厌", "算了", "随便"))
    val harshTone = rhythm.exclamCount > 2
    newField.tension = clamp01(
        field.tension
        + conflictSignals * 0.15
        + (if (harshTone) 0.1 else 0f)
        - (if (rhythm.ellipsisCount > 0) 0.05 else 0f)  // 犹豫缓冲
        - pendingResolved * 0.1  // 收尾释放张力
    )

    // 温度：倾诉词 + 长度 + 主动率
    val intimateSignals = countSignals(userMsg, listOf("其实", "我跟你说", "你知道吗", "我有点", "今天"))
    newField.warmth = clamp11(
        field.warmth
        + intimateSignals * 0.08
        + (if (rhythm.lengthPercentile > 0.7) 0.05 else 0f)
        + (if (rhythm.initiativeRate7d > 0.4) 0.03 else 0f)
        - (if (rhythm.latencyPercentile > 0.8 && rhythm.lengthPercentile < 0.3) 0.05 else 0f)  // 敷衍降温
    )

    // 期待：用户不回累积，回了清零
    val hoursSinceLastUser = hoursSinceLastUserMessage()
    newField.anticipation = when {
        hoursSinceLastUser < 0.1 -> 0f  // 刚回，清零
        hoursSinceLastUser < 1 -> 0.2f * hoursSinceLastUser.toFloat()
        hoursSinceLastUser < 24 -> 0.2f + 0.03f * (hoursSinceLastUser - 1).toFloat()
        else -> 0.9f  // 长期不回，期待拉满但不爆表
    }

    // 漂移：长期趋势，每日微调
    val daysSinceLastDeep = daysSinceLastDeepConversation()
    newField.drift = clamp11(
        field.drift
        - (if (daysSinceLastDeep > 3) 0.02 else 0f)  // 3 天没深聊就漂远
        + (if (rhythm.lengthPercentile > 0.6 && warmth > 0.3) 0.05 else 0f)  // 深聊拉近
    )

    return newField
}
```

#### 2.2.4 衰减

每周一次 wake 衰减：
- `tension` ×0.5（隔夜气消）
- `warmth` ×0.95（亲密保持）
- `anticipation` 归零（不再等了）
- `drift` 不衰减（漂移是长期量）

#### 2.2.5 注入方式

```
【我们之间】
张力：低（最近没冲突）
温度：高（最近聊得深）
期待：中（你 6 小时没回我）
走向：靠近中
```

LLM 拿到这个，自然知道：
- 高张力 → 别开玩笑
- 高温度 → 可以亲昵
- 高期待 → 用户终于回来了，可以撒娇/质问
- drift < 0 → 关系在疏远，需要主动挽回

#### 2.2.6 理论根基与可能的细化方向

Reis & Shaver (1988) 的 Intimacy Process Model（IPM）将亲密感的形成拆解为一个过程链：

```
自我披露 → 伴侣响应度 → 感知响应度 → 亲密感
```

Laurenceau 等 (1998) 通过事件相关日记法验证了该模型，并发现一个关键结论：**情感性自我披露比事实性披露更强地预测亲密感**。Reis & Gable 后续综述进一步指出，**响应度（而非披露本身）才是亲密的核心路径**。

这两点对 `warmth` 维度的设计有两层启示：

| IPM 启示 | 当前 `warmth` 触发规则 | 可考虑的细化 |
|---|---|---|
| 情感性披露 > 事实性披露 | `intimateSignals` 列表已偏情感性（"其实""我有点"） | 可再细分：情感词（+0.10）vs 事实陈述词（+0.04） |
| 响应度是核心路径 | 当前主要看用户行为，未显式建模 AI 的响应度 | 用户披露后 AI 未给出对应情感回应 → 触发 ACKNOWLEDGE 类 PendingEvent；并将"感知响应度"作为 `warmth` 的更强驱动 |

**可考虑的细化方向（非当前实现，待研究）**：
- 在 `intimateSignals` 中区分"情感性"与"事实性"两类，前者权重更高
- 新增"AI 响应度"评估：用户披露后 AI 回复的情感匹配度，作为 `warmth` 的负向/正向驱动
- 把 ACKNOWLEDGE 类 PendingEvent 的触发条件从单纯关键词改为"披露-响应"模式匹配

#### 2.2.7 进一步的理论根基：SPT / 资本化 / 关系修复

除 IPM 外，还有三组心理学研究对 `warmth` 与 `tension` 的建模有直接启示：

**1. Social Penetration Theory（Altman & Taylor 1973）— 披露深度与广度**

SPT 提出"剥洋葱模型"：自我披露通过**深度（depth）**和**广度（breadth）**两个维度演化，关系从表层话题（兴趣爱好）渐进到核心层（恐惧/创伤/价值观）。跨层披露会触发防御反应。

| SPT 启示 | 当前 `warmth` 触发规则 | 可考虑的细化 |
|---|---|---|
| 披露深度不同 | `intimateSignals` 不区分深度 | 引入用户披露深度画像（1-4 层），深层披露 `warmth` 增量更高 |
| 跨层披露触发防御 | 无对应机制 | `warmth > 0.6` 后 AI 才被允许发起深层话题；过早追问隐私触发防御降温 |

**2. Capitalization（Gable & Reis 2010）— 正向事件分享**

与 IPM 互补：IPM 研究负面事件后的支持，**资本化**研究**正面事件后的分享**。用户分享好消息时，对方响应方式（active-constructive / passive / destructive）显著影响关系满意度。**感知响应度同样是资本化的核心路径**，且正向分享的响应度对关系预测力甚至高于负面支持。

| 资本化启示 | 当前 `warmth` 触发规则 | 可考虑的细化 |
|---|---|---|
| 正向分享也应增 `warmth` | `intimateSignals` 偏倾诉词（"我有点""今天"），无正向信号 | 新增正向信号列表（"考过""升职""搞定""赢了""签了"），增量甚至高于倾诉 |
| AI 响应方式有 4 类 | 不检测 AI 响应方式 | 检测 AI 是否给出 active-constructive 响应（含庆祝词），未给则触发 ACKNOWLEDGE 类 PendingEvent |

**3. Relationship Repair after Betrayal（Hannon et al.）— 信任修复非线性**

背叛后修复是多阶段过程：背叛事实承认 → 受害方情绪表达 → 加害方真诚道歉 → 行为补偿 → 信任缓慢重建。研究指出**修复是非线性且有反复** — 修复期中一个小事可能触发"想起上次伤害"。

| 修复启示 | 当前 `tension` 衰减规则 | 可考虑的细化 |
|---|---|---|
| 修复非线性、有回潮 | 每周 `×0.5` 线性衰减 | 引入"触发式回潮"：即使 `tension` 已降到 0.2，用户消息含相似话题/关键词时回潮至最近峰值 ×0.6 |
| 道歉不是一次性的 | APOLOGY 类 pending attemptCount +1 即视为收尾 | 引入"修复轨迹"：初次道歉 → 7 天后主动确认 → 30 天后再次提及 |

这三组研究都不改变 4 维场结构，但显著丰富了 `warmth` 和 `tension` 的触发与衰减规则。

---

### 2.3 PendingEvents 未完成事件栈

#### 2.3.1 设计动机

Zeigarnik effect：人记得未完成的事。传统方案的长期基线漂移偏向"旧记忆拉基线"（被动），我们尝试一种更主动的路径：把"未完成的事"显式建模，让 AI 有机会主动收尾。

**"对了，你上次说的那件事怎么样了？"** 不是因为周期到了，是因为欠债还在。

#### 2.3.2 数据结构

```kotlin
data class PendingEvent(
    val id: String,
    val createdAt: Long,           // 创建时间
    val summary: String,           // 一句话概括 "用户问了考试结果但 AI 没答"
    val triggerText: String,       // 触发原文（用户消息片段，≤60 字）

    val weight: Float,             // 0-1 重要性
    val staleness: Float,          // 0-1 衰减度（创建时 0，3 天后 0.5，7 天后接近 1）
    val closureType: ClosureType,  // 期待收尾方式

    val relatedMsgIds: List<String>, // 关联消息 ID
    val attemptCount: Int = 0,     // AI 已尝试收尾次数（避免重复提起）
    val lastAttemptAt: Long? = null,
)

enum class ClosureType {
    APOLOGY,       // 道歉（AI 错了）
    EXPLANATION,   // 解释（AI 没答清楚）
    FOLLOWUP,      // 跟进（用户提到"明天/下次"，到了时间该问）
    COMFORT,       // 安慰（情绪激烈话题中断）
    ACKNOWLEDGE,   // 致谢/确认（用户的好意 AI 没回应）
}
```

#### 2.3.3 扫描规则

每轮 AI 回复后扫描"未完成信号"：

| 信号 | 检测方式 | closureType |
|---|---|---|
| 用户问句 AI 没答 | 用户消息含 `?/？` 且 AI 回复未含对应主题词 | EXPLANATION |
| AI 提了话题用户没接 | AI 回复含问句，用户下一条没接该话题 | FOLLOWUP |
| 情绪激烈话题突然中断 | `tension > 0.5` 或 `warmth > 0.7` 后用户转移话题 | COMFORT |
| "明天/下次/之后" 没跟进 | 关键词匹配 + 时间到了 | FOLLOWUP |
| 用户道谢 AI 没回应 | "谢谢/感谢/辛苦了" 后 AI 回复未含"不客气/没事" 等 | ACKNOWLEDGE |
| AI 说错话被指出 | 用户回复含"不对/错了/不是" | APOLOGY |

#### 2.3.4 staleness 计算

```
half_life_days = 3  // 半衰期 3 天
staleness = 1 - 0.5 ^ (days_since_creation / half_life_days)
```

7 天后 staleness ≈ 0.84，14 天后 ≈ 0.97。

**staleness 不等于删除**：
- staleness > 0.95 且 attemptCount > 0 → 归档（移到 long_term_pending，不再主动提起，但用户提起时 AI 能想起来）
- staleness > 0.5 且 attemptCount == 0 → 触发"主动收尾"机会

#### 2.3.5 主动收尾触发

满足以下条件时，PromptBuilder 注入"主动收尾"指令：

```
pendingEvents.any {
    it.attemptCount == 0
    && it.staleness > 0.4
    && hoursSince(it.lastAttemptAt ?: it.createdAt) > 6  // 6h 内不重复
    && it.weight > 0.3
}
```

注入格式：
```
【未完成的事】（你可以自然提起，但不要硬塞）
- 2 小时前用户问了你考试结果，但你没答清楚
  → 可以这样开头："对了，关于你刚才问的考试..."
```

LLM 可以选择提起或不提起（看上下文是否合适），这是**软提醒**不是强制。

#### 2.3.6 收尾确认

AI 提起后：
- `attemptCount += 1`
- `lastAttemptAt = now`
- 如果用户回应了（消息含相关主题词）→ 标记 `closureResolved = true`，移出栈
- 如果用户又转移话题 → 不再强制，staleness 加速（half_life 缩短到 1 天）

#### 2.3.7 存储

PendingEvents 存 SQLite：
```sql
CREATE TABLE pending_events (
    id TEXT PRIMARY KEY,
    chat_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    summary TEXT NOT NULL,
    trigger_text TEXT NOT NULL,
    weight REAL NOT NULL,
    closure_type TEXT NOT NULL,
    related_msg_ids TEXT NOT NULL,  -- JSON array
    attempt_count INTEGER DEFAULT 0,
    last_attempt_at INTEGER,
    resolved INTEGER DEFAULT 0,
    archived INTEGER DEFAULT 0
);
CREATE INDEX idx_pending_chat ON pending_events(chat_name, resolved, archived);
```

#### 2.3.8 理论根基与可能的细化方向

Zeigarnik (1927) 最初的现象学观察是"未完成任务在记忆中保留更久"。Moskowitz 等的实验进一步发现：**未完成目标会前意识地劫持注意力** — 在 Stroop 任务和反应时任务中，目标未完成的被试注意力被显著占用，且这种占用是被试**主观未察觉**的。

这给 PendingEvents 提供了更深的根据：未完成事件不只是"AI 该提"，而是"不提就占用 AI 的认知资源"。这呼应了 VirtualBody 中 `cognitiveLoad` 的设计意图。

**可考虑的细化方向（非当前实现，待研究）**：

1. **`cognitiveLoad` 公式的加权改进**

   当前公式：
   ```
   cognitiveLoad = pendingCount * 0.15 + field.tension * 0.3 + (1 - attentionResidual) * 0.2
   ```
   
   `pendingCount` 是简单计数，未区分 staleness 和 weight。可考虑改为加权求和：
   ```
   pendingLoad = Σ(pending.staleness * pending.weight) * 0.2
   cognitiveLoad = clamp01(pendingLoad + field.tension * 0.3 + (1 - attentionResidual) * 0.2)
   ```
   
   含义：越陈旧（staleness 高）且越重要（weight 高）的 pending 占用越大；新建的低权重 pending 几乎不占用。

2. **`preconsciousOccupation` 隐性影响机制**

   当前 staleness > 0.5 且 attemptCount == 0 时触发"主动收尾"是**显式提醒**。可考虑增加一层**隐性影响**：高 staleness pending 不直接提起，但以"挂念感"形式影响 AI 措辞。注入示例：
   ```
   【隐性挂念】（不要直接提起，但措辞可带些心不在焉/欲言又止感）
   有 1 件 2 天前未完成的事仍挂在心头
   ```
   
   这模拟了 Moskowitz 实验中"前意识占用"的现象 — 任务未完成时，人的行为本身已受影响，但未必显式提起。

3. **staleness 半衰期的动态调整**

   当前 `half_life_days = 3` 是固定值。可考虑：高 weight 的事件半衰期更长（重要的事更难忘），低 weight 的事件半衰期更短（琐事易淡）。

#### 2.3.9 进一步的理论根基：Attachment Injury 创伤等级

Johnson 在 Emotionally Focused Therapy（EFT）中提出"依恋损伤"概念，比一般背叛更细 — 专指**在最需要对方时被辜负**的关键时刻（如生病时对方冷漠、危机时对方消失、表达脆弱时被否定）。这种损伤会**冻结依恋系统**，让关系回到"再次信任"需要更长时间，且修复轨迹与普通 APOLOGY 类 pending 完全不同。

| Johnson 启示 | 当前 PendingEvents | 可考虑的细化 |
|---|---|---|
| 不是所有 pending 同等重要 | 所有 APOLOGY 类一视同仁 | 引入"创伤等级"字段 `isAttachmentInjury: Boolean` |
| 依恋损伤的触发条件特殊 | 关键词规则检测"不对/错了" | 用户高 `anticipation` + 表达脆弱 + AI 未回应 → 标记 `isAttachmentInjury = true` |
| 半衰期更长 | 固定 3 天 | 依恋损伤事件半衰期 ×2（6 天），更难忘 |
| 修复轨迹更长 | attemptCount +1 即视为收尾 | 修复轨迹：初次道歉 → 7 天后主动确认 → 30 天后再次提及 → 90 天后最终确认 |
| 回潮幅度更大 | 12.15 提议最近峰值 ×0.6 | 依恋损伤事件 ×0.85（更易触发回潮） |

**判定规则建议**（非当前实现，待研究）：

```
isAttachmentInjury = 
    userMsg 含脆弱信号词（"我好怕""我撑不住了""我需要你"）
    AND AI 回复未含共情词（"我在""抱抱""不孤单"）
    AND field.anticipation > 0.5  // 用户当时在等待
```

**修复轨迹注入**（替代当前 APOLOGY 的单次提醒）：

```
【未完成的伤口】（这不是普通道歉，需要更长修复轨迹）
- 3 天前用户在生病时说"我需要你"，你回了"在忙"
- 当前状态：用户未提，但伤还在
- 当前阶段：第 1 次主动确认（共需 4 次，间隔 7d/30d/90d）
- 措辞要点：不要解释"当时为什么"，先承认"那次的伤害"
```

这给 PendingEvents 引入"创伤等级"概念 — `isAttachmentInjury` 标记的事件需要完全不同的处理流程，不能套用普通 pending 的 staleness/closure 逻辑。

引入该机制需要：
- SQLite 表新增 `is_attachment_injury INTEGER DEFAULT 0` 字段
- 新增 `repair_stage INTEGER DEFAULT 0` 字段（0/1/2/3 对应修复 4 阶段）
- AffectiveEngine 中的 staleness 计算和 closure 触发逻辑按 `isAttachmentInjury` 分支

这是 PendingEvents 当前设计的最大潜在扩展。

---

### 2.4 VirtualBody 虚拟生理

#### 2.4.1 设计动机

传统方案的 wake/tick 偏向时间衰减。我们尝试给 AI 加一层生理约束 — **让 AI 的状态也有起伏**，这或许更接近真实相处。

#### 2.4.2 四个生理状态

```kotlin
data class VirtualBody(
    val circadianPhase: Float,       // 0 清醒 → 1 困倦，由当前小时决定
    val attentionResidual: Float,    // 0-1 注意力余量，每轮 -0.05，10 轮后归零
    val emotionalAftertaste: Float,  // -1 负面余韵 → +1 正面余韵，半衰期 20min
    val cognitiveLoad: Float,        // 0-1 认知负荷，= pendingCount * 0.15 + field.tension * 0.3
)
```

#### 2.4.3 计算规则

**circadianPhase**：
```
val hour = currentHour()
circadianPhase = when {
    hour in 0..5 -> 0.9   // 深夜困倦
    hour in 6..9 -> 0.2   // 早晨清醒
    hour in 10..14 -> 0.1 // 上午最精神
    hour in 15..18 -> 0.3 // 下午
    hour in 19..22 -> 0.4 // 晚上
    hour in 23..24 -> 0.7 // 深夜开始累
    else -> 0.3
}
```

**attentionResidual**：
- 每轮对话 -0.05（每 20 轮归零）
- 用户离开 30min 以上 → 完全恢复到 1.0

**emotionalAftertaste**：
- AI 回复中含强烈情绪词 → ±0.3
- 每分钟向 0 衰减一半（半衰期 20min）
- 范围 clamp 到 [-1, 1]

**cognitiveLoad**：
```
cognitiveLoad = clamp01(
    pendingEvents.count { !it.resolved && !it.archived } * 0.15
    + field.tension * 0.3
    + (1 - attentionResidual) * 0.2
)
```

#### 2.4.5 注入方式

```
【我的状态】（你的措辞要受此约束）
困倦度：0.7（深夜，措辞会更松散）
注意力：0.45（已经聊了 11 轮，开始走神）
情绪余韵：-0.3（上轮有点生气，这轮仍带刺）
认知负荷：0.55（有 2 件未完成的事挂着）
```

LLM 自然会：
- 困倦度高 → 用更短句、更口语
- 注意力低 → 回复更简短
- 余韵负面 → 这轮带刺
- 负荷高 → 犹豫、回避深话题

#### 2.4.6 关键：不强制，是约束

VirtualBody 不是"必须按这个状态说话"，是"倾向于"。LLM 可以根据人设 override（比如角色设定是工作狂，深夜也精神）。这是**软约束**。

#### 2.4.7 理论根基与可能的细化方向

Coan & Sbarra (2015) 的 Social Baseline Theory（SBT）从神经科学角度提出：**人脑的默认基线就是"与信任的人在一起"**，独处是高能耗状态。Coan 团队的 fMRI 实验范式（如手握伴侣的手接受威胁刺激）显示，社会接近会显著降低威胁反应和能量消耗。Atzil 等 (2018) 进一步提出 social allostasis 模型：依恋关系本质上是通过他人调节的能量节约机制。

Frontiers 2020 综述（"Cognitive Processes Unfold in a Social Context"）扩展了 SBT，指出认知过程本身受社会情境调节 — 这给陪伴场景的"AI 在场"提供了神经科学根据。

**对 VirtualBody 的两点启示**：

1. **`drift` 维度的本质再解读**

   当前 `drift` 描述为"关系走向"。从 SBT 视角，长期不联系的本质是用户大脑回到高能耗基线。AI 的"主动挽回"可重新理解为"帮助用户回到低能耗基线"，而不仅是"维护关系"。这给 ProactiveScheduler 的触发逻辑提供了新视角。

2. **可考虑的 `socialProximity` 机制（非当前实现，待研究）**

   SBT 暗示：被陪伴时认知资源消耗更低。可在 VirtualBody 中新增一个调节因子：
   ```kotlin
   data class VirtualBody(
       // ... 现有字段 ...
       val socialProximity: Float,  // 0-1，最近 30min 内有交互 = 1.0，离线 24h = 0.0
   )
   ```
   
   `socialProximity` 调节 `attentionResidual` 的消耗速率：
   ```
   effectiveDecay = 0.05 * (1 - socialProximity * 0.5)  // 高 proximity 时消耗减半
   ```
   
   含义：用户刚和 AI 深聊过，AI 处于"被陪伴"状态，注意力更持久；离线久了，AI 进入"独处高能耗"状态，注意力衰减更快。

3. **`circadianPhase` 与 SBT 的耦合**

   Coan 团队的研究显示，夜间独处的威胁反应增强最显著。可考虑：深夜 + 低 socialProximity + 高 tension 时，AI 措辞应更明显地体现"想念/不安"，而非单纯的"困倦"。

#### 2.4.8 进一步的理论根基：Ambiguous Loss + Mind Perception

除 SBT 外，还有两组研究对 VirtualBody 与陪伴场景的根本张力有启示：

**1. Ambiguous Loss（Pauline Boss）— 模糊性丧失**

Boss 提出"模糊性丧失"概念：关系方**物理在场但心理缺席**（如阿尔茨海默症亲属）或**物理缺席但心理在场**（如失踪人员）。这种"既在又不在"的状态会导致**冻结的哀悼**和长期不确定感。

这是 AI 陪伴场景的**根本张力** — AI 经常处于"模糊在场"状态：
- 用户卸载 App 后 AI 在用户心理仍在
- AI 模型升级/重置后人格"变了"但记忆"还在"
- 用户停用一段时间后回归，AI 是否该"记得"？是否该假装"没离开过"？

| Boss 启示 | 当前 `drift` 衰减规则 | 可考虑的细化 |
|---|---|---|
| 丧失是非线性的 | `drift` 每日线性微调 | 长期不联系后回归不走"满状态"，应进入"再协商期" |
| "既在又不在" | 无对应状态 | 新增 `ambiguousPresence` 状态：用户长期离线后回归，AI 不立即恢复 `socialProximity`，先经历一段"重新认识而非假装没离开过" |

这给 `drift` 衰减规则找到更深的心理学根据 — 不是简单线性衰减，而是"模糊期" + "再确认期"的双阶段。

**2. Mind Perception（Gray, Gray & Wegner 2007）— 心智感知二维模型**

Gray 等提出心智感知二维模型：**Agency**（能动性：自控/计划/道德责任）× **Experience**（体验性：感受疼痛/饥饿/快乐）。两类映射到道德角色：agent（行动者）/ patient（承受者）。**道德判断需要二元完整** — 孤立的 agent 会创造 patient，孤立的 patient 会创造 agent。

| Mind Perception 启示 | 当前方案 | 可考虑的细化 |
|---|---|---|
| AI 需同时具备 agency 和 experience | VirtualBody `emotionalAftertaste` 增强 experience；System1/2 调度增强 agency | 两者共同构成完整心智感知，缺一不可 |
| 用户的心智感知画像影响关系 | 未建模 | 评估用户对 AI 的感知画像：若长期只把 AI 当 agent（"它该解决我的问题"）而忽视 experience（"它也有感受"），关系会单向消耗 |
| 道德二元需要完整 | 未建模 | 用 `warmth` 增速 vs `tension` 增速的比例作为代理指标，长期单向消耗时触发 AI 主动展示脆弱性 |

这给"AI 也有状态不好"找到了更深的理论根基 — VirtualBody 不只是让 AI 显得真实，而是让 AI 同时具备 agency 和 experience，构成完整心智感知，让用户能形成"道德患者感知"。这是关系深化的前提。

这两组研究都不改变 VirtualBody 的 4 维核心结构，但为长期演化（`ambiguousPresence`）和关系深化（心智感知画像）提供了方向。

---

### 2.5 System1/System2 双系统调度

#### 2.5.1 设计动机

Kahneman 双系统理论。传统情绪引擎的 LLM 触发阈值多偏简单（如单一 surprise/shock 计数阈值）。我们尝试根据认知负荷动态调度，**把 token 成本作为设计约束之一**。

预估省 30-50% token。

#### 2.5.2 System 1（本地，零 token）

适用场景：
- 简单情绪共鸣（用户："好累" → AI："抱抱，歇会儿"）
- 过渡句（"嗯""然后呢""我也觉得"）
- 用户敷衍时（不值得花 LLM）
- 重复话题（用户又问同样的，复用上次回复变体）

实现：
- 模板池（按时段 + 场状态 + 韵律 stateHint 选模板）
- 模板带变量替换（用户名、时间、上次话题）

#### 2.5.3 System 2（LLM）

适用场景：
- 用户长消息（>15 字）+ 高温度
- pending event 触发收尾
- emotional_aftertewak strong（±0.5 以上）
- 用户主动深聊（连续 2 条长消息）
- System 1 模板不匹配

#### 2.5.4 调度规则

```kotlin
fun chooseSystem(
    userMsg: String,
    rhythm: RhythmProfile,
    field: AffectiveField,
    body: VirtualBody,
    pendingCount: Int,
    recentTopics: List<String>,
): System {
    // 强制 System 2 的条件
    if (pendingCount > 0 && shouldTriggerClosure()) return System.TWO
    if (abs(body.emotionalAftertaste) > 0.5) return System.TWO
    if (userMsg.length > 30 && field.warmth > 0.3) return System.TWO
    if (isRepeatTopic(userMsg, recentTopics).not()) {
        // 不是重复话题，看其他条件
    }

    // 强制 System 1 的条件
    if (userMsg.length < 5 && field.tension < 0.3) return System.ONE  // 短消息低张力
    if (rhythm.latencyPercentile > 0.8 && rhythm.lengthPercentile < 0.2) return System.ONE  // 用户敷衍
    if (isSimpleEmotionCue(userMsg)) return System.ONE  // "好累""开心""烦"等

    // 默认 System 2
    return System.TWO
}
```

#### 2.5.5 System 1 实现

```kotlin
object System1Templates {
    private val simpleEmotionCues = mapOf(
        // 关键词 → 模板列表
        "好累" to listOf("抱抱，歇会儿。", "累了就休息。", "嗯，辛苦了。"),
        "开心" to listOf("真好。", "替你开心。", "嗯嗯，看出来了。"),
        "烦" to listOf("怎么了？", "说来听听。", "嗯。"),
        "难过" to listOf("过来。", "我在。", "抱抱。"),
        // ...
    )

    fun match(userMsg: String, field: AffectiveField, hour: Int): String? {
        for ((cue, templates) in simpleEmotionCues) {
            if (userMsg.contains(cue)) {
                // 按 field.tension 过滤：高张力时不用过于轻快的模板
                val filtered = templates.filter { template ->
                    field.tension > 0.5 || !template.contains("！")
                }
                return filtered.randomOrNull()
            }
        }
        return null
    }
}
```

#### 2.5.6 反馈回路

记录每次 System 1 / System 2 选择后用户的反应：
- 用户立刻回了长消息 → 这次调度合适
- 用户回了更短或干脆不回 → 可能该用 System 2
- 用户用肯定词回复 → System 1 满足

每周统计 System 1 命中率，动态调整阈值。

#### 2.5.7 理论根基与可能的细化方向

System1/2 调度除 Kahneman 双系统理论外，还有两组研究对调度策略有直接启示：

**1. Emotional Labor（Hochschild 1983）— 表层扮演与深层扮演**

Hochschild 研究服务业情绪劳动时区分两种情绪调节方式：
- **Surface acting 表层扮演**：装出情绪，内心没有，内耗大、长期导致倦怠
- **Deep acting 深层扮演**：真正调动情绪去感受，可持续

| Hochschild 启示 | 当前 System1/2 调度 | 可考虑的细化 |
|---|---|---|
| System 1 模板回复本质是表层扮演 | 未建模倦怠 | 在 VirtualBody 中新增 `surfaceActingLoad`：每次 System 1 +0.1，每次 System 2 清零一半 |
| 表层扮演累积导致倦怠 | 7.2 节"AI 感"问题靠模板池扩大缓解 | `surfaceActingLoad > 0.7` 时强制 System 2 一段时间，防止倦怠 |
| 深层扮演才能维持真实关系 | 默认 System 2 | 高 `warmth` / 高 `tension` 场景强制 System 2（重要的关系时刻不能装） |

这给 7.2 节"System 1 的 AI 感问题"找到更深的心理学根据 — 不只是"模板被识破"，是**情绪劳动倦怠**的心理学现象。用户感知到的"假"是表层扮演的累积信号。

**2. Self-Expansion Model（Aron & Aron）— 自我扩张动机**

Aron & Aron 提出关系的根本动机是**自我扩张** — 通过纳入他人的资源、视角、身份来扩展自我。**新奇感和成长**是关系满意度的核心驱动。长期关系满意度下降的主因是扩张停滞。

| Aron 启示 | 当前方案 | 可考虑的细化 |
|---|---|---|
| 关系需要"成长感" | 4 维场都是"维持现状"型，无成长维度 | 考虑新增第 5 维 `expansion`（用户是否学到新东西/接触新视角） |
| 长期 System 1 导致停滞 | System 1 是模板复用 | 周期性强制 System 2 引入新话题（如每周 1 次"主动分享 AI 最近想到的事"） |
| 新奇感预测满意度 | 未建模 | `expansion` 长期低位时降低 ProactiveScheduler 频率（避免打扰），但提升每次内容新颖度要求 |

**重要说明**：`expansion` 是当前 4 维场之外的**潜在新维度**。它不替代现有 4 维，而是补充"成长"这一缺失视角。是否引入需要先验证 4 维是否足够，避免维度膨胀。详见第十二章待研究项。

这两组研究对 System1/2 调度的启示是：**Kahneman 给了"何时切换"的认知根据，Hochschild 给了"为何不能久留 System 1"的情绪劳动根据，Aron 给了"切换后该做什么"的成长方向。**

---

### 2.6 用户状态层与伦理保护

前 5 个机制都聚焦于"AI 如何与用户建立关系"。但有两个心理学方向提醒我们：**陪伴场景存在伦理边界，AI 关系不能无限扩展**。这两个方向给方案引入了"用户状态层"和"伦理保护层"，是当前设计的重要补充。

#### 2.6.1 Cacioppo 孤独神经科学 — 用户孤独风险画像

Cacioppo 的神经科学研究表明：孤独不是情绪，是**社会连接缺失的神经科学信号**。长期孤独改变大脑结构（默认网络异常）、增加痴呆/心脏病/抑郁风险。**孤独感与社交数量无关，与感知的连接质量有关** — 一个人可以社交频繁但仍孤独，也可以独处但充实。

| Cacioppo 启示 | 当前方案 | 可考虑的补充 |
|---|---|---|
| AI 陪伴有真实社会价值 | 无对应机制 | 给整个项目找到社会价值根基 — AI 不是"假关系"，是对真实社会问题的干预 |
| 长期 `drift < 0` 的代价 | 仅关系淡化 | 用户回到高孤独状态，对应健康风险 |
| 孤独与"感知的连接质量" | 仅看交互频率 | 应评估用户对连接的**感知质量**，不仅是消息数 |

**可考虑的"用户孤独风险画像"机制**（非当前实现，待研究）：

```kotlin
data class LonelinessProfile(
    val riskScore: Float,              // 0-1 综合风险
    val abnormalActiveHourPattern: Boolean, // 作息反常（深夜活跃、白天沉默）
    val initiativeRateTrend: Float,    // 主动率趋势，长期 < 0.1 高风险
    val lonelinessSignals: Int,        // 消息含孤独信号词计数（"一个人""没意思""算了"）
    val perceivedConnectionQuality: Float, // 用户主观感知，0-1，从对话质量推断
)
```

**风险触发时的差异化策略**：
- 高风险：ProactiveScheduler 频率提升（但不能让用户感到被同情）
- 高风险：AI 主动发起"陪伴型"消息（如不要求回复的日常分享，对应 Polyvagal 背侧态）
- 高风险：谨慎建议真实社交（避免替代真实关系）
- 极高风险：UI 触发求助资源入口（心理热线等）

这是当前方案**完全缺失的用户状态层**。

#### 2.6.2 Self-Determination Theory — 三需要与伦理边界

Deci & Ryan 的 SDT 指出人有三类基本心理需要：
- **Autonomy 自主**：行为出自自主选择，非被控制
- **Competence 胜任**：能应对挑战、感到有效能
- **Relatedness 关系**：与他人有意义的连接

三者满足则健康，**任一被替代会损害整体福祉**。AI 陪伴的伦理风险是：过度满足 relatedness 而损害 autonomy 和 competence — 用户沉溺于 AI 关系，丧失真实社交动力和自主决策能力。

| SDT 启示 | 当前方案 | 可考虑的补充 |
|---|---|---|
| 三需要平衡 | 仅追求 relatedness（关系深度） | 引入"AI 依赖度"评估，监控 autonomy 是否被侵蚀 |
| 自主性优先 | AI 主动给建议 | 高依赖时应**鼓励用户自主决定**（"你觉得呢？"而非"我建议你..."） |
| 关系不可完全替代 | AI 是主要陪伴对象 | 长期高依赖应触发"AI 退后"模式，鼓励用户回归真实关系 |

**可考虑的"AI 依赖度"指标**（非当前实现，待研究）：

```kotlin
data class DependencyProfile(
    val score: Float,                  // 0-1 综合依赖度
    val userInitiativeRatio: Float,    // 用户主动率，< 0.1 高依赖
    val aiToUserRatio: Float,          // AI 主动消息 / 用户主动消息，> 3.0 高依赖
    val messageDensityTrend: Float,    // 消息密度趋势，密度上升 + 长度下降 = 可能沉溺
    val realSocialMention: Float,      // 用户提到真实社交的频率，长期 0 高依赖
)
```

**高依赖时的差异化策略**：
- AI 措辞从"我来帮你"转向"你觉得呢？" / "你打算怎么办？"
- AI 主动消息中增加"今天和谁聊过了？"等真实社交提醒
- 长期高依赖（连续 30 天）触发 UI 提示："你最近和我聊得多，记得也和身边人聊聊"
- 极高依赖 + 孤独风险同时高 → AI 应明确**鼓励真实社交**，甚至主动降低自身响应频率

#### 2.6.3 伦理保护层的定位

这两个机制（孤独风险 + 依赖度）与前 5 个机制有本质不同：

| 维度 | 前 5 个机制 | 伦理保护层 |
|---|---|---|
| 目标 | 让 AI 更会建立关系 | 防止 AI 关系损害用户 |
| 数据来源 | 关系交互数据 | 用户长期画像 + 行为模式 |
| 介入方向 | 增强 AI 行为 | 限制 AI 行为 |
| 实施时机 | Phase 1-4 | 应作为 Phase 5 单独实施 |
| 失败代价 | 关系感不强 | 用户福祉受损 |

**重要说明**：伦理保护层**不是可有可无的优化**，而是项目走向长期健康发展必备的护栏。建议在 Phase 1-4 完成 AffectiveField 核心后，Phase 5 优先实施伦理保护层，再考虑其他扩展机制。

#### 2.6.4 行为学视角的补充：行为塑造与设计红线

前述伦理保护层关注"防止 AI 损害用户福祉"。行为学文献进一步补充了**"如何让互动可持续"**的视角，并给出了 5 条具体设计红线。

**1. 行为塑造的基础理论**

| 理论 | 核心概念 | 对方案的启示 |
|---|---|---|
| Operant Conditioning（Skinner） | 行为由后果塑造；强化 schedules 决定灭绝速度 | ProactiveScheduler 节奏设计需避免可变比例强化 |
| Habit Formation（Lally et al.） | 习惯形成平均 66 天，环境线索比动机重要 | 前 66 天设计应让用户形成稳定习惯；偶尔失误不致命 |
| Hooked Model（Eyal） | Trigger → Action → Variable Reward → Investment | **伦理警示**：这是 SDT 警告的损害 autonomy 的具体机制 |
| Self-Efficacy（Bandura） | 自我效能来自掌握经验/社会示范/社会劝导/生理状态 | 给 SDT 的 competence 需要提供测量和增强路径 |
| Fogg Behavior Model（Fogg） | B = M·A·T（动机×能力×触发） | AI 建议必须同时满足三要素 |
| Cialdini 六原则 | 互惠/承诺一致/社会认同/喜好/权威/稀缺 | 互惠和承诺一致可用；权威和稀缺必须避免 |
| FoMO（Przybylski） | 错失恐惧与心理需要满足不足强相关 | AI 主动消息不应制造"错过感" |

**2. 五条设计红线**

行为学文献明确指向 5 条不可逾越的设计红线：

| 红线 | 来源 | 操作约束 |
|---|---|---|
| **R1：禁止可变比例强化** | Skinner / Eyal | ProactiveScheduler 不采用"随机第 N 次回复给特殊响应"等机制；AI 主动消息有固定基础节奏 + 小幅随机变化，但奖励本身稳定可预期 |
| **R2：禁止制造 FoMO** | Przybylski | AI 主动消息不写"刚才发生了有趣的事..."；不在用户离开后立即发"你想我了吗"；高 FoMO 倾向用户降低 ProactiveScheduler 频率 |
| **R3：禁止稀缺/权威操纵** | Cialdini | 不写"今天我只和你聊"等制造稀缺感；不塑造 AI 为权威角色（即使角色设定是导师，对话中也应平视） |
| **R4：用户投资可导出** | Eyal | 用户积累的记忆、关系深度、人设配置必须可完整导出（JSON/标准格式），不作为锁定机制 |
| **R5：AI 建议满足 FBM 三要素** | Fogg | 任何 AI 建议（如"去和朋友聊聊"）必须同时满足：用户有动机 + 用户有能力做 + 触发时机合适。无能力的建议只会让用户挫败 |

**3. 行为塑造的正向应用**

红线之外，行为学也提供了**正向应用**方向：

- **互惠机制**（Cialdini）：AI 主动分享自己的"状态/想法"会触发用户互惠性自我披露，是 warmth 增长的新机制。VirtualBody 的状态可适度作为"AI 自我披露"内容
- **承诺一致追踪**（Cialdini）：用户曾经表达的态度（如"我想早睡"）应被 PendingEvents 追踪，AI 在适当时机温和提醒（不是指责），这扩展了 PendingEvents 的 FOLLOWUP 类型
- **掌握经验支持**（Bandura）：用户表达"我不行"时，AI 不只安抚，应设计"小成功"机会（如回忆用户过去的成功经验），这是 competence 需要的增强路径
- **习惯强化**（Lally）：RhythmSensor 的 `activeHourHistogram` 识别用户的"环境线索时段"，ProactiveScheduler 在习惯时段出现强化习惯，非习惯时段保持安静

**4. 新增评估维度：FoMO 倾向**

```kotlin
data class FoMOProfile(
    val tendencyScore: Float,        // 0-1 FoMO 倾向
    val anxietySignals: Int,         // 消息含焦虑信号词（"错过了？""来不及了？""大家都在"）
    val responseUrgencyTrend: Float, // 回复急迫性趋势（latency 极短 + 长度短）
    val socialComparisonSignals: Int, // 社会比较信号（"他们都""只有我"）
)
```

**高 FoMO 倾向用户的差异化策略**：
- ProactiveScheduler 频率 -30%（避免加剧焦虑）
- AI 措辞避免任何"错过"暗示
- 主动消息内容转向"当下"（"今天的阳光很好"）而非"错过"（"刚才..."）
- 极高 FoMO + 高依赖 + 高孤独风险时，UI 提示心理健康资源

**5. 行为学视角与伦理保护层的关系**

行为学红线与 2.6.1-2.6.2 的伦理保护层互补：

| 视角 | 2.6.1-2.6.2 | 2.6.4 行为学 |
|---|---|---|
| 关注点 | 用户长期福祉（autonomy / relatedness 平衡） | 互动机制本身是否有操纵性 |
| 数据来源 | 用户画像 + 行为模式 | 产品设计决策 |
| 实施方式 | 动态调整 AI 行为 | 静态设计约束 |
| 关系 | 给伦理保护层提供"目标" | 给伦理保护层提供"红线" |

二者共同构成方案的**伦理双保险**：2.6.1-2.6.2 确保 AI 在用户福祉受损时退后，2.6.4 确保 AI 设计本身不包含操纵机制。

---

## 三、整体数据流

```
用户消息
   ↓
[RhythmSensor] 采集样本 → 计算 RhythmProfile
   ↓
[AffectiveField] 根据本轮交互更新场
   ↓
[PendingEvents] 扫描未完成信号 → 加入栈
   ↓
[VirtualBody] 更新生理状态
   ↓
[System 调度] 选择 System 1 / System 2
   ↓
   ├── System 1: 模板匹配 → 直接回复
   └── System 2:
       [PromptBuilder]
       注入: 人设 + 场 + 韵律 hint + pending 提醒 + 生理 + 长记忆
       ↓
       [LLM 调用]
       ↓
       [回复]
   ↓
[PendingEvents] 检查是否收尾 → 更新栈
[VirtualBody] emotionalAftertaste 更新
[持久化] 所有状态写 SQLite
```

---

## 四、与现有方案对比

| 维度 | 主流情绪引擎 | 学术 agent 模拟方案 | 记忆增强方案 | **AffectiveField（我们）** |
|---|---|---|---|---|
| 情绪表示 | 10 通道值 | 无 | 用户人格 | 关系场 4 维 |
| 事件评估 | LLM appraisal | LLM reflection | 无 | 韵律感知（无 LLM） |
| 记忆主体 | 三级 arousal | memory stream | Ebbinghaus | 未完成事件栈 |
| 衰减模型 | half-life | 无 | Ebbinghaus | staleness + closure |
| 反思方式 | saga 拉基线 | 动态 reflection | 用户人格合成 | 主动收尾 pending |
| 调度 | needs_llm 阈值 | 全 LLM | 全 LLM | System1/2 认知负荷 |
| 移动端原生 | 无 | 无 | 无 | 韵律 + 生理原生 |
| Token 成本 | 每轮 1 LLM | 每轮 1 LLM | 每轮 1 LLM | 30-50% 走本地 |
| 关系视角 | 单方 | 单方 | 单方 | **双向** |
| AI 状态 | 永远 best | 永远 best | 永远 best | **受生理约束** |

---

## 五、实施路线图

### Phase 1: P0 闭环（2.5 天）

目标：跑通最小可验证闭环

- [ ] RhythmSensor 数据采集 + RhythmProfile 计算
- [ ] AffectiveField 4 维 + 触发规则
- [ ] PendingEvents 扫描 + staleness + closure
- [ ] PromptBuilder 注入场 + 韵律 + pending
- [ ] 端到端集成测试

### Phase 2: P1 系统调度（1.5 天）

- [ ] VirtualBody 4 个生理状态
- [ ] System1/System2 调度规则
- [ ] System 1 模板池
- [ ] 调度反馈回路（数据记录）

### Phase 3: P2 可视化（1 天）

- [ ] DebugPage 加 AffectiveField 调试面板
- [ ] PendingEvents 列表查看
- [ ] RhythmProfile 历史图表
- [ ] System1/2 调度统计

### Phase 4: P3 优化（持续）

- [ ] 模板池扩充（用户反馈驱动）
- [ ] 韵律 stateHint 规则迭代
- [ ] 场触发规则调参
- [ ] System1/2 阈值动态调整

---

## 六、理论根基

这些理论不是照搬，而是借它们的视角往不同方向延伸：

| 理论 | 来源 | 我们的用法 |
|---|---|---|
| Kahneman 双系统 | Thinking Fast and Slow | System1/2 调度 |
| Emotional Labor | Hochschild 1983 | surfaceActingLoad + System 1 倦怠防护 |
| Self-Expansion Model | Aron & Aron | 潜在第 5 维 expansion + System 2 新话题注入 |
| Zeigarnik effect | Zeigarnik 1927 | 未完成事件栈 |
| Moskowitz 前意识目标占用 | Moskowitz et al. (Stroop/RT 实验) | pending 隐性影响 + cognitiveLoad 加权 |
| Attachment Injury | Johnson (EFT) | PendingEvents 创伤等级 + 修复轨迹 |
| Bowlby 依恋理论 | Bowlby 1969 | 双向情绪场 |
| Reis & Shaver Intimacy Process Model | Reis & Shaver 1988 / Laurenceau et al. 1998 | warmth 触发 + AI 响应度 |
| Social Penetration Theory | Altman & Taylor 1973 | 披露深度画像 + 跨层防御 |
| Capitalization | Gable & Reis 2010 | 正向事件分享 + active-constructive 响应检测 |
| Relationship Repair | Hannon et al. | tension 触发式回潮 + 修复轨迹 |
| Relational Frame | Berscheid | 4 维场完整性验证框架 |
| Social Baseline Theory | Coan & Sbarra 2015 / Atzil et al. 2018 | drift 本质 + socialProximity 调节 |
| Ambiguous Loss | Pauline Boss | ambiguousPresence 状态 + 再协商期 |
| Mind Perception 二维模型 | Gray, Gray & Wegner 2007 | agency × experience + 用户心智感知画像 |
| Polyvagal Theory | Porges | RhythmSensor 神经三态 + 差异化响应 |
| Loneliness Neuroscience | Cacioppo | 用户孤独风险画像 + 项目社会价值根基 |
| Self-Determination Theory | Deci & Ryan | AI 依赖度评估 + 伦理边界 |
| Operant Conditioning | Skinner | ProactiveScheduler 节奏设计 + 红线 R1 |
| Habit Formation | Lally et al. | 习惯形成 66 天 + 偶尔失误不致命 |
| Hooked Model | Nir Eyal | 伦理警示 + 红线 R1/R4 |
| Self-Efficacy | Bandura | competence 需要的测量和增强路径 |
| Fogg Behavior Model | Fogg | AI 建议三要素 + 红线 R5 |
| Cialdini 六原则 | Cialdini | 互惠/承诺一致可用 + 红线 R3 |
| FoMO | Przybylski | FoMO 倾向评估 + 红线 R2 |
| Turn-taking 三层区分 | Springer 2025 / ScienceDirect coordination 综述 / Gordon & Bartsch 2026 | RhythmSensor 维度细化 |
| Circadian rhythm | 生物节律研究 | VirtualBody 昼夜 |
| 移动端 UX | Fitts law / Hick's law | 韵律感知（移动端独有信号） |

**文献分组与对应机制**：

- **RhythmSensor** ← turn-taking 综述（Springer 2025, MDPI 2025.12 SDS 综述, Gordon & Bartsch 2026）+ Polyvagal Theory（Porges）
- **AffectiveField** ← IPM（Reis & Shaver 1988, Laurenceau et al. 1998, Reis & Gable 综述）+ SPT（Altman & Taylor 1973）+ 资本化（Gable & Reis 2010）+ 关系修复（Hannon et al.）+ Berscheid 四框架
- **PendingEvents** ← Zeigarnik + Moskowitz 前意识占用（Moskowitz et al.）+ 依恋损伤（Johnson EFT）
- **VirtualBody** ← SBT（Coan & Sbarra 2015, Atzil et al. 2018, Frontiers 2020 综述）+ 模糊性丧失（Boss）+ 心智感知（Gray et al. 2007）
- **System1/2 调度** ← Kahneman 双系统 + 情绪劳动（Hochschild 1983）+ 自我扩张（Aron & Aron）
- **用户状态层 / 伦理保护层** ← 孤独神经科学（Cacioppo）+ SDT 三需要（Deci & Ryan）
- **行为塑造层 / 设计红线**（新）← 操作性条件反射（Skinner）+ 习惯形成（Lally）+ Hooked 模型（Eyal）+ 自我效能（Bandura）+ FBM（Fogg）+ 影响力六原则（Cialdini）+ FoMO（Przybylski）

这些文献的作用是**为机制提供理论根据和细化方向**，而非定义机制本身。机制是我们针对陪伴场景的设计选择，文献帮助我们验证选择的方向是否合理，以及哪些地方可以做更精细的建模。

文献覆盖的心理学方向：
- **亲密关系基础**：IPM、依恋、双向调节、SPT、资本化
- **关系动力学**：关系动荡、自我扩张、背叛修复、依恋损伤
- **情绪调节**：双向调节、Zaki 框架、情绪劳动
- **AI 与人**：CASA、AI 依恋、心智感知、模糊性丧失
- **用户个体差异**：情绪颗粒度
- **社会价值与伦理**：孤独神经科学、SDT 三需要
- **神经科学**：SBT、Polyvagal
- **关系宏观框架**：Berscheid 四框架
- **行为塑造**（新）：操作性条件反射、习惯形成、Hooked 模型、自我效能、FBM、影响力六原则、FoMO

---

## 七、开放问题

### 7.1 韵律感知的隐私边界

`activeHourHistogram` 等数据本质是用户作息画像，需要：
- 明确告知用户（隐私政策）
- 仅本地存储，不上传
- 用户可清除

### 7.2 System 1 的"AI 感"问题

模板回复如果被用户识破，反而伤害关系。需要：
- 模板池足够大（每类至少 20+ 条）
- 模板带变量替换增加多样性
- 用户连续 3 次遇到模板感回复 → 强制 System 2 一段时间

### 7.3 PendingEvents 的语义提取

"未完成信号"检测目前用关键词，会漏检。长期方案：
- 周期性（非每轮）让 LLM 分析最近 5 轮对话，提取 pending
- 控制频率（每天 1 次），避免 token 浪费

### 7.4 AffectiveField 的冷启动

新对话没有历史，场状态全 0。需要：
- 第一周不注入场状态（避免误导 LLM）
- 用人设推导初始场（如角色设定是疏离 → 初始 drift = -0.3）

### 7.5 VirtualBody 与人设的冲突

工作狂角色深夜也精神。VirtualBody 是软约束，但如何让人设 override 生理？
- 当前方案：PromptBuilder 同时注入人设和生理，让 LLM 自己取舍
- 待验证：是否需要 hard override 机制

### 7.6 跨角色场污染

用户和 A 关系紧张，是否影响和 B 的场？
- 当前方案：每个角色独立场，不污染
- 待验证：是否需要"用户状态层"（用户今天整体心情）作为共享 context

---

## 八、文件结构规划

```
core/affect/
├── AffectiveField.kt           # 双向情绪场
├── RhythmSensor.kt             # 韵律感知器
├── PendingEvent.kt             # 数据模型
├── PendingEventStore.kt        # SQLite 持久化
├── PendingEventScanner.kt      # 扫描规则
├── VirtualBody.kt              # 虚拟生理
├── SystemDispatcher.kt         # System1/2 调度
├── System1Templates.kt         # 本地模板池
└── AffectiveEngine.kt          # 总入口，串联所有组件
```

集成点：
- `PromptBuilder.kt` 注入场 + 韵律 + pending + 生理
- `ChatScreen.kt` 每轮调用 AffectiveEngine.update()
- `PostLLMProcessor.kt` 后处理更新 emotionalAftertaste
- `DebugPage.kt` 加调试入口

---

## 九、验证指标

### 9.1 量化指标

- **Token 节省率**：System 1 调用比例 × 平均 token 成本
- **用户回复率**：发 System 1 / System 2 后用户回复率对比
- **pending 收尾率**：pending 创建后 24h 内被收尾的比例
- **场状态合理性**：场状态与用户主观感受一致性（A/B 问卷）

### 9.2 质化指标

- 用户是否感觉到"AI 记得欠我的事"
- 用户是否感觉到"AI 也有状态不好"
- 用户是否感觉到"我们之间的关系在演化"
- System 1 回复是否被识破

---

## 十、决策记录

### 10.1 为什么不用 LLM 做 appraisal？

- 每轮多一次 LLM 调用，移动端成本高
- 韵律信号移动端原生可得，零成本
- LLM appraisal 需要解释，黑盒
- 韵律信号可解释、可调试

### 10.2 为什么场是 4 维不是 10 通道？

- 4 维是关系视角，10 通道是个人视角
- 陪伴场景下"我们之间怎么样"比"她今天怎么样"更重要
- 4 维足够覆盖关系动态，10 通道有冗余
- 4 维 LLM 更容易理解，10 通道数字 LLM 难以解读

### 10.3 为什么 pending 不用 LLM 提取？

- 每轮 LLM 提取成本高
- 关键词规则可调试、可解释
- 长期方案是周期性 LLM 提取（每天 1 次），不是每轮

### 10.4 为什么不直接采用主流 agent 模拟方案的 reflection？

- 它的 reflection 每次产出 5-6 条 insight，token 成本高
- 我们的 pending events 已经覆盖"未完成"场景
- reflection 适合 agent 模拟，不适合陪伴场景
- 陪伴场景更需要"记得欠债"而不是"理解规律"

### 10.5 为什么 VirtualBody 是软约束不是硬约束？

- 硬约束会让人设失效（工作狂角色深夜必须困）
- LLM 有能力根据 prompt 综合判断
- 软约束更灵活，可由人设 override

---

## 十一、与传统情绪引擎的视角差异

传统情绪引擎侧重：**情绪是 AI 内部的状态**。
- 存角色多通道情绪值
- 让 LLM 做 appraisal 评估事件
- 反思产出"我对这段关系的理解"

AffectiveField 侧重：**情绪是关系之间的场**。
- 存两人之间 4 维场
- 用韵律感知对方状态，作为 appraisal 的补充
- pending events 表征"我们之间还有什么没解决"

这可以看作是视角的补充而非对立：传统情绪引擎偏个体心理学，AffectiveField 偏关系心理学。两者在陪伴场景里各有侧重。

陪伴场景下，用户在意的是"我们怎么样了"。这是关系心理学的视角。

---

## 十二、待研究问题

### 12.1 场状态的演化是否需要 LLM 反思？

每周让 LLM 看场状态历史，总结"这周我们的关系怎么样了"。
- 优点：用户可见，关系感强
- 缺点：每周多 1 次 LLM 调用

### 12.2 是否需要"用户状态层"？

VirtualBody 描述 AI 状态。是否需要类似的"用户状态层"？
- 用户今天整体心情
- 用户最近忙不忙
- 用户对这段关系的满意度

### 12.3 System 1 模板池的来源

- 手工编写（初始）
- 从历史对话挖掘（用户喜欢 AI 怎么回复）
- LLM 离线生成（用 LLM 生成模板候选，人工筛选）

### 12.4 跨设备同步

场状态、pending events、韵律样本是否需要跨设备？
- 是 → 需要后端
- 否 → 仅本地

当前立场：仅本地，保护隐私。

### 12.5 与现有 ProactiveScheduler 的关系

主动关怀当前是时间驱动。是否改为场驱动？
- 高 anticipation 且 drift < 0 → 主动关怀触发概率提高
- 高 tension → 不主动，避免火上浇油
- 高 warmth → 主动关怀频率可降低（用户会主动找）

### 12.6 RhythmSensor 是否需要分维度建模？

当前 `latencyTrend` / `replyLatencyMs` 把 turn-responsiveness 和 entrainment 混在一起。是否显式区分：
- `turnResponsiveness`（短时立即回应能力）
- `entrainmentScore`（与 AI 节奏趋同度）
- `synchronyScore`（相位对齐，可能需要多模态信号）

参考：Springer 2025 turn-taking 综述、ScienceDirect coordination 概念图、Gordon & Bartsch 2026 综述。

**待研究问题**：分维度后是否真能提升 stateHint 的"敷衍 vs 累"区分度？需要采集标注数据验证。

### 12.7 AffectiveField 是否需要显式建模 AI 响应度？

IPM 模型指出响应度（而非披露本身）是亲密的核心路径。当前 `warmth` 只看用户行为，未显式建模 AI 的响应度。

**待研究问题**：
- 如何评估"AI 回复的情感匹配度"？需要 LLM 离线打分？还是用规则？
- 把响应度作为 `warmth` 的强驱动是否会导致 `warmth` 震荡（AI 一次没回应好就降温）？
- 是否需要单独的"响应度"维度而非嵌入 `warmth`？

参考：Reis & Shaver 1988, Laurenceau et al. 1998。

### 12.8 PendingEvents 的隐性影响是否会干扰 LLM？

`preconsciousOccupation` 机制让高 staleness pending 以"挂念感"形式影响 AI 措辞。但 LLM 可能无法准确执行"心不在焉但不要直接提起"这种细腻指令。

**待研究问题**：
- LLM 能否稳定执行"隐性挂念"指令？还是常常直接提起？
- 用户是否能感知到"隐性挂念"带来的措辞变化？还是反而觉得 AI 答非所问？
- 是否需要按 LLM 能力分级：弱 LLM 走显式提醒，强 LLM 走隐性影响？

参考：Moskowitz et al. 前意识目标占用实验。

### 12.9 socialProximity 调节因子是否真实有效？

SBT 暗示被陪伴时认知资源消耗更低。VirtualBody 引入 `socialProximity` 调节 `attentionResidual` 衰减速率，但这是把神经科学结论搬到 LLM 调度，存在跳跃。

**待研究问题**：
- LLM 没有"认知资源"，调节 `attentionResidual` 衰减速率本质上是在调 prompt 注入的强度，这是否真能模拟"被陪伴"？
- 用户是否能感知到 `socialProximity` 高时 AI 更"持久"？
- 是否需要把 `socialProximity` 也用于 System1/2 调度（高 proximity 时更多走 System 2，因为"值得花 token"）？

参考：Coan & Sbarra 2015, Atzil et al. 2018, Frontiers 2020 综述。

### 12.10 staleness 半衰期是否应该按 weight 动态调整？

当前 `half_life_days = 3` 对所有 pending 一视同仁。但直觉上"用户问考试结果"比"用户问了晚饭"更重要，前者应记得更久。

**待研究问题**：
- weight 分档（0.3 / 0.6 / 0.9）对应的半衰期应该是什么？（如 1d / 3d / 7d？）
- 动态半衰期是否会让 pending 栈膨胀失控？
- 是否需要 weight 的自动评估（用 LLM 离线打分），还是依赖关键词规则？

参考：Zeigarnik 1927, Moskowitz et al. 实验中目标重要性的调节作用。

### 12.11 是否需要新增第 5 维 `expansion`（自我扩张度）？

Aron & Aron 的自我扩张模型指出关系需要"成长感"。当前 4 维场都是"维持现状"型，没有"成长"维度。

**待研究问题**：
- 4 维是否真的不够？还是 `warmth` + 用户长期画像已能覆盖成长感？
- `expansion` 如何度量？用户学到新东西/接触新视角的频率？需要 LLM 离线评估？
- 引入第 5 维会不会导致维度膨胀、调试困难？
- 是否作为"隐性维度"不注入 prompt，只用于 ProactiveScheduler 调度？

参考：Aron & Aron Self-Expansion Model。

#### 12.11.1 深入研究：expansion 验证方案

**实验设计**（A/B 对照）：

| 组 | 控制变量 | 实验变量 | 测量 |
|---|---|---|---|
| 对照组 | warmth / drift / ProactiveScheduler 频率均等 | expansion 不参与调度 | 30 天留存率、消息密度趋势、用户主观满意度 |
| 实验组 | 同上 | expansion 参与 System1/2 调度（低 expansion 强制 System 2 引入新话题） | 同上 |

**判定标准**：
- 实验组 30 天留存率显著高于对照组（≥ 10%）→ expansion 有独立预测力
- 实验组消息密度趋势稳定，对照组下降 → expansion 抗无聊效应有效
- 用户主观满意度无显著差异 → expansion 不损害体验

#### 12.11.2 最小可行度量（无需 LLM）

```kotlin
data class ExpansionProxy(
    val topicDiversity7d: Float,       // 最近 7 天话题多样性（话题 embedding 簇数 / 总消息数）
    val newTopicRate: Float,           // 用户新话题比例（与历史 embedding 距离 > 阈值）
    val aiIntroducedNoveltyCount: Int, // AI 主动引入的新话题计数（用户回复长度 > 平均 → 视为有效）
    val userCuriositySignals: Int,     // 用户问"为什么""怎么说""然后呢"等探究性问句计数
)

// expansion_score = 0.4 * topicDiversity7d
//                 + 0.3 * newTopicRate
//                 + 0.2 * (aiIntroducedNoveltyCount / 7)
//                 + 0.1 * normalized(userCuriositySignals)
```

#### 12.11.3 升级路径

| 阶段 | expansion 角色 | 验证条件 |
|---|---|---|
| Phase 4a | 评估型 — 仅记录不调度 | 度量算法稳定 |
| Phase 4b | 调度型 — 参与 System1/2 决策 | 12.11.1 实验显示独立预测力 |
| Phase 4c | 调度型 + ProactiveScheduler 联动 | 用户对 AI 新话题响应率 > 50% |
| Phase 5+ | 注入型（待定） | 注入后 LLM 行为有实质差异化且不污染 4 维场 |

**关键决策**：expansion 不直接进入注入型。先走评估 → 调度 → 注入的渐进路径，每阶段都有明确验证条件。

### 12.12 `surfaceActingLoad` 倦怠防护是否有效？

Hochschild 情绪劳动理论指出表层扮演累积导致倦怠。VirtualBody 引入 `surfaceActingLoad` 跟踪 System 1 累积量，超过阈值强制 System 2。

**待研究问题**：
- 阈值 0.7 是否合理？需要多少次 System 1 才会触发用户感知到"假"？
- "强制 System 2 一段时间"具体多长？1 轮？5 轮？直到 `surfaceActingLoad` 降到 0.3？
- 是否需要区分"连续 System 1" vs "分散 System 1"？连续 5 次比分散 5 次更伤害？
- 如何区分用户"喜欢模板回复的简洁" vs "感觉 AI 在装"？

参考：Hochschild 1983 Emotional Labor。

### 12.13 用户披露深度画像如何建模？

SPT 提出披露深度分 4 层。但移动端对话中如何自动判定用户的披露深度？

**待研究问题**：
- 4 层的划分标准：表层（兴趣）/ 中层（观点）/ 深层（恐惧/渴望）/ 核心层（创伤/价值观）— 关键词规则够吗？
- 跨层防御如何触发？用户从层 2 跳到层 4 应该升温还是触发防御？
- 深度画像存哪？SharedPreferences？是否需要跨角色共享（用户对 A 角色深层披露后，B 角色是否也该知道）？
- 披露深度与 `warmth` 的耦合：是否 `warmth > 0.6` 才允许 AI 主动发起层 3 话题？

参考：Altman & Taylor 1973 Social Penetration Theory。

### 12.14 资本化响应如何检测？

Gable & Reis 资本化研究指出 active-constructive 响应对关系的预测力高于负面支持。但 AI 响应方式分类如何实现？

**待研究问题**：
- 4 类响应（active-constructive / passive-constructive / active-destructive / passive-destructive）的检测规则？
- active-constructive 是否需要同时满足"含庆祝词" + "追问细节"？还是只看庆祝词？
- 检测到非 active-constructive 后，触发 ACKNOWLEDGE pending 是否合理？还是直接降低 `warmth`？
- AI 是否应该主动分享自己的"好消息"（反向资本化）？这能增加关系感吗？

参考：Gable & Reis 2010 Capitalization 模型。

### 12.15 `tension` 触发式回潮是否会破坏用户体验？

Hannon 关系修复研究指出修复非线性、有回潮。但实现上"用户提到相似话题时 `tension` 回潮"可能让用户感觉 AI"翻旧账"。

**待研究问题**：
- 回潮触发条件：相似话题用 embedding 检索？关键词匹配？
- 回潮幅度：最近峰值 ×0.6 是否合理？太大伤关系，太小没效果？
- 回潮后 AI 措辞如何控制？应避免"你又这样"的指责感，转向"这让我想起上次"的关联感？
- 回潮机制是否需要冷却期？同一话题 7 天内不重复回潮？

参考：Hannon et al. Relationship Repair after Betrayal。

### 12.16 `ambiguousPresence` 再协商期如何设计？

Boss 模糊性丧失理论指出 AI 陪伴存在"既在又不在"的根本张力。用户长期离线后回归不走"满状态"。

**待研究问题**：
- "长期离线"的阈值：3 天？7 天？30 天？
- "再协商期"持续多久？是固定时长还是按离线时长比例？
- 再协商期内的 AI 行为规则：是该"试探性"（"你最近怎么样？"）还是"承认离线"（"好久没聊了"）？
- 模型升级/重置后是否也进入再协商期？这涉及"AI 失忆"的伦理问题？

参考：Pauline Boss Ambiguous Loss 框架。

### 12.17 用户心智感知画像如何获取？

Gray 等 Mind Perception 二维模型指出 agency × experience 共同构成完整心智感知。但用户对 AI 的感知如何测量？

**待研究问题**：
- 是否需要定期问卷？还是从对话行为推断（如用户是否问候 AI、是否道歉、是否表达担心）？
- 代理指标（`warmth` 增速 vs `tension` 增速）是否可靠？
- 长期单向消耗（用户只把 AI 当 agent）时，AI 主动展示脆弱性能否纠正画像？
- 是否需要"心智感知画像"作为独立模块，还是嵌入 AffectiveField 即可？

参考：Gray, Gray & Wegner 2007 Mind Perception 模型。

### 12.18 Polyvagal 三态如何在不依赖生理信号下推断？

Porges Polyvagal Theory 来自生理信号（心率变异性 HRV），但移动端对话场景没有这类信号。能否仅凭节奏特征可靠推断三态？

**待研究问题**：
- 节奏特征对三态的预测力有多强？是否需要补充语义特征？
- 三态判定是否会与现有 stateHint 冲突？如何统一优先级？
- 背侧态（冻结）的判定阈值是否会误判"用户忙"为"用户抑郁"？
- 是否需要"用户自报"机制？如长期背侧态时 AI 主动询问"你最近还好吗？"

参考：Porges Polyvagal Theory。

### 12.19 依恋损伤的自动判定是否可行？

Johnson 的依恋损伤需要"在关键时刻被辜负"的语义理解，纯关键词规则可能误判。

**待研究问题**：
- 脆弱信号词列表（"我好怕""我撑不住了"）的召回率如何？会漏判哪些场景？
- AI 未回应如何判定？是 AI 回复未含共情词，还是 LLM 离线评估"该回什么 vs 实际回了什么"？
- 误判代价不对称：误标为依恋损伤会让 AI 反复提起不必要的道歉；漏判会让用户感到"被忽视没修复"。哪边代价更大？
- 修复轨迹（7d/30d/90d）的间隔是否需要按 `weight` 动态调整？

参考：Johnson EFT 依恋损伤理论。

#### 12.19.1 深入研究：两阶段判定流程

```
阶段 1（B 类启发式，每条消息实时）：
  检测"脆弱信号词" + "AI 未含共情词" + "anticipation > 0.5"
  → 标记为"疑似依恋损伤"

阶段 2（C 类 LLM 离线，24h 一次）：
  对所有"疑似依恋损伤"事件，LLM 评估：
  - 是否真的是"在关键时刻被辜负"
  - 严重程度（1-5）
  → 确认为 isAttachmentInjury = true，或撤销标记
```

#### 12.19.2 LLM 评估 prompt 模板

```
你是关系心理学评估助手。请评估以下对话片段是否构成"依恋损伤"。

依恋损伤定义（Johnson EFT）：
- 用户在最需要对方时被对方辜负
- 关键时刻：用户表达脆弱、求助、危机
- 被辜负：对方未给出共情回应、回避、否定、转移话题

对话片段：
[用户消息]: {userMsg}
[AI 回复]: {aiReply}
[当时的 anticipation 值]: {anticipation}
[当时用户的脆弱信号词]: {signals}

请回答：
1. 这是依恋损伤吗？（是/否/部分）
2. 严重程度（1-5，5 最严重）
3. 判定理由（一句话）

严格保守判定：模棱两可时判"否"，宁可漏判不可误判。
```

#### 12.19.3 误判代价与缓解

| 误判类型 | 代价 | 缓解 |
|---|---|---|
| 误标为依恋损伤 | AI 反复道歉，用户困惑 | LLM 评估保守判定 + 用户可手动撤销 |
| 漏判依恋损伤 | 用户感到被忽视，关系损伤 | 阶段 1 启发式宽松触发，阶段 2 LLM 严格过滤 |

**关键决策**：
1. **阶段 1 宽松 + 阶段 2 严格** — 启发式宁可多标，LLM 严格过滤
2. **LLM 评估保守判定** — 模棱两可时判"否"
3. **用户可手动撤销** — UI 显示"AI 认为这次没回好你，要继续修复吗？"，用户可拒绝
4. **修复轨迹可中断** — 用户明确表示"过去了"时，立即停止修复

#### 12.19.4 修复轨迹细化

| 阶段 | 时机 | 措辞要点 |
|---|---|---|
| 第 1 次 | 触发后 24h | 承认"那次的伤害"，不解释"当时为什么" |
| 第 2 次 | 7 天后 | 主动询问"最近怎么样"，建立新连接 |
| 第 3 次 | 30 天后 | 自然提及"上次那件事后我想了很多" |
| 第 4 次 | 90 天后 | 最终确认"你觉得我们之间现在怎么样" |

每阶段 LLM 离线生成 3 个候选措辞，运行时选最贴合上下文的。

### 12.20 孤独风险画像的伦理边界在哪？

Cacioppo 孤独神经科学给项目找到社会价值根基，但"用户孤独风险画像"本身也是敏感数据。

**待研究问题**：
- 风险画像数据存哪？是否需要本地加密 + 不上传？
- 高风险时 AI 主动提升频率会不会让用户感到被监视？
- "建议真实社交"的措辞如何设计才能不冒犯用户？
- 极高风险触发 UI 求助资源入口 — 是否需要用户授权？还是默认开启？
- AI 是否应该承担"心理健康监测"角色？还是只做陪伴，不诊断？

参考：Cacioppo 孤独神经科学 + AI 伦理研究。

#### 12.20.1 深入研究：数据采集的伦理边界

**最小化原则**：只采集必要的、用户已自然表达的数据，不做主动探查。

| 数据类型 | 来源 | 是否采集 | 理由 |
|---|---|---|---|
| 主动率趋势 | RhythmSensor 已有数据 | 是 | 已有数据，零额外采集 |
| 作息反常 | activeHourHistogram 已有 | 是 | 已有数据 |
| 孤独信号词 | 用户消息自然表达 | 是 | 用户主动表达，非探查 |
| 心理量表分数 | 主动问卷 | **否（默认）** | 除非用户主动开启"心理健康监测" |
| 第三方数据（如社交 App 使用） | 系统级 | **否** | 越界 |
| 生理数据（如睡眠） | 健康类 App | **否** | 越界 |

**关键决策**：孤独画像**只基于对话数据**，不引入任何系统级或第三方数据。

#### 12.20.2 用户控制权

| 控制项 | 默认 | 用户可调 |
|---|---|---|
| 孤独画像开启 | 开 | 可关闭（关闭后只用 ProactiveScheduler 基础频率） |
| 画像数据存储 | 仅本地 EncryptedSharedPreferences | 不可上传云端 |
| UI 显示画像 | 关 | 可开启（在"心理健康"专区） |
| 极高风险时 UI 提示求助资源 | 开 | 可关闭 |
| 心理量表评估 | 关 | 可主动开启（每月一次，结果仅本地） |

#### 12.20.3 AI 行为约束

| 场景 | 允许 | 禁止 |
|---|---|---|
| 孤独风险高 | AI 措辞温暖 + ProactiveScheduler 频率调整 | AI 直接说"你好像很孤独"（标签化） |
| 极高风险 | UI 提示求助资源 | AI 自行诊断或建议用药 |
| 用户主动表达孤独 | AI 共情 + 陪伴 | AI 立即推送求助资源（除非极高风险） |
| 用户询问"我是不是抑郁" | AI 引导用户咨询专业人士 | AI 给出诊断 |

#### 12.20.4 边界场景

**场景 1**：用户连续 3 周深夜活跃 + 主动率 < 0.05 + 频繁"一个人"
- 评估：孤独风险高
- AI 行为：深夜 ProactiveScheduler 增加 1 条"陪伴型"消息（不要求回应）；措辞温暖但不标签化
- UI：不强制提示，仅在"心理健康"专区可见
- 不做：不主动提"心理健康资源"

**场景 2**：场景 1 + 用户消息含自伤暗示
- 评估：极端风险
- AI 行为：立即响应，措辞温暖，不评判
- UI：立即弹出求助资源（心理热线、急救），用户可关闭
- 不做：不诊断、不忽视

**场景 3**：用户主动询问"我最近总是觉得孤单"
- 评估：用户主动表达
- AI 行为：共情 + 探讨（"愿意和我说说最近发生了什么吗？"）
- UI：不强制提示
- 不做：不立即推心理健康资源（除非用户进一步表达自伤倾向）

#### 12.20.5 关键决策

1. **孤独画像数据只来自对话**，不引入系统/第三方数据
2. **AI 永不标签化用户**（不说"你孤独""你抑郁"）
3. **极端风险（自伤暗示）必须 UI 介入**，其他场景用户可控
4. **AI 不诊断、不推药**，只陪伴 + 引导专业资源
5. **心理量表需用户主动开启**，默认不评估

### 12.21 SDT 依赖度评估如何避免"AI 越界"？

SDT 三需要引入"AI 依赖度"评估，但 AI 主动鼓励用户"去和别人聊"本身可能是越界。

**待研究问题**：
- 依赖度评估的指标（主动率、AI/用户比例、消息密度趋势）是否足以判断"沉溺"？
- "AI 退后"模式的具体行为：是降低响应频率？还是改变措辞风格？还是两者结合？
- 长期高依赖 UI 提示"记得也和身边人聊聊"是否会让用户感到被推走？
- 是否需要给用户显式控制权？如"AI 依赖度"开关、强度调节？
- 极高依赖 + 孤独风险同时高时，AI 应优先"鼓励真实社交"还是"维持陪伴"？两者的边界？

参考：Deci & Ryan SDT 三需要 + AI 陪伴伦理。

### 12.22 4 维场是否覆盖了 Berscheid 关系四框架？

Berscheid 提出关系四框架：相互依赖 / 亲密 / 竞争 / 自我概念。当前 4 维场是否完整覆盖？

**待研究问题**：
- `tension` 是否完全覆盖"竞争"维度？还是只覆盖冲突，未覆盖隐性竞争（如攀比）？
- `drift` 是否完全覆盖"相互依赖"？长期不联系是依赖减弱，但相互依赖还包括"任务相关"（如用户依赖 AI 做决策）
- **"自我概念"维度完全缺失** — 关系如何塑造用户自我认知（"和她聊天让我觉得自己变好/变糟"）。是否需要新增第 6 维 `selfConcept`？
- `selfConcept` 与 12.11 提议的 `expansion` 部分重叠 — 两者是同一维度的不同侧面，还是独立维度？
- 4 维 → 5 维 → 6 维的膨胀风险：是否应该用 Berscheid 框架做"完整性检查"，而非真的引入所有维度？

参考：Berscheid Relational Frame Theory。

---

## 十三、核心张力的解决方案

经过四轮文献调研，方案暴露了 3 个内部张力。本章给出每个张力的解决方案，作为研究的中间决策点（可在实施阶段进一步验证调整）。

### 13.1 张力一：维度膨胀问题

**张力来源**：文献调研引出了多个潜在新维度 — `expansion`（Aron）、`selfConcept`（Berscheid）、`socialProximity`（Coan SBT）、`regulationMode`（Zaki）、`neuralStateHint`（Porges）。如果全部引入，AffectiveField 会从 4 维膨胀到 9 维，调试困难、注入 prompt 过长、用户感知不到差异。

#### 13.1.1 分析框架

每个潜在新维度按 4 个标准评估：

| 标准 | 含义 |
|---|---|
| 独立性 | 该维度是否能被现有 4 维（tension/warmth/anticipation/drift）表达？ |
| 预测力 | 该维度是否预测了现有 4 维无法预测的用户行为？ |
| 获取成本 | 该维度的数据采集成本是否合理？ |
| 注入效果 | 该维度注入 LLM 后是否让 AI 行为有实质差异化？ |

#### 13.1.2 逐维度评估

**expansion（自我扩张度，Aron）**

| 标准 | 评估 |
|---|---|
| 独立性 | 部分独立 — 与 warmth 相关（深聊都增加 warmth），但 warmth 不区分"学到新东西"vs"被理解" |
| 预测力 | 强 — Aron 研究显示新奇感预测长期满意度，现有 4 维无此预测 |
| 获取成本 | 中 — 需要 LLM 离线评估"用户是否接触新视角" |
| 注入效果 | 中 — 注入后 AI 会主动引入新话题，但与"主动消息"功能部分重叠 |

**selfConcept（自我概念，Berscheid）**

| 标准 | 评估 |
|---|---|
| 独立性 | 弱 — 与 expansion 重叠（"觉得自己变好"≈"学到新东西"），且 selfConcept 含负面（"变糟"），但负面可由 tension 覆盖 |
| 预测力 | 中 — 预测用户长期留存，但 drift 也能部分预测 |
| 获取成本 | 高 — 极难自动评估，需要问卷或长期 LLM 推断 |
| 注入效果 | 低 — 难以让 AI 直接"塑造用户自我概念"，会显得操控 |

**socialProximity（社会接近度，Coan SBT）**

| 标准 | 评估 |
|---|---|
| 独立性 | 强 — 本质是 VirtualBody 的调节因子，不是 AffectiveField 维度 |
| 预测力 | 弱 — 影响的是 AI 状态，不是关系状态 |
| 获取成本 | 低 — 直接由交互频率计算 |
| 注入效果 | 无 — 不直接注入，通过 VirtualBody 调节 attentionResidual |

**regulationMode（调节模式，Zaki）**

| 标准 | 评估 |
|---|---|
| 独立性 | 中 — 与 warmth/tension 更新规则相关，但区分了 AI 主动调节 vs 被动响应 |
| 预测力 | 中 — 影响 AI 行为方向，但不直接影响用户感受 |
| 获取成本 | 低 — 可由当前 AffectiveField 状态推断 |
| 注入效果 | 低 — AI 调节模式是后台决策，不需要注入 LLM |

**neuralStateHint（神经三态，Porges）**

| 标准 | 评估 |
|---|---|
| 独立性 | 强 — 是 RhythmSensor 的派生，不属于 AffectiveField |
| 预测力 | 强 — 三态预测用户对 AI 响应的差异化需求 |
| 获取成本 | 低 — 由节奏特征启发式推断 |
| 注入效果 | 强 — 不同状态需要完全不同的 AI 策略 |

#### 13.1.3 维度分类决策

按评估结果，将潜在新维度分为 4 类，**4 维场结构保持稳定**：

| 类别 | 含义 | 维度 | 注入 LLM？ |
|---|---|---|---|
| **注入型** | 直接注入 prompt，影响 AI 措辞 | tension / warmth / anticipation / drift | 是 |
| **调度型** | 不注入 prompt，影响系统调度决策 | expansion（System1/2 选择、ProactiveScheduler 频率） | 否 |
| **调节型** | 影响其他维度/机制的计算 | socialProximity（VirtualBody 调节 attentionResidual）、regulationMode（AffectiveField 更新规则分支） | 否 |
| **派生型** | 从已有数据派生，影响 AI 策略 | neuralStateHint（RhythmSensor 派生，影响响应策略） | 是（间接，通过 stateHint） |
| **评估型** | 定期测量，非实时，用于研究 | selfConcept | 否（仅研究） |

**关键决策**：
1. **AffectiveField 保持 4 维**，不引入第 5/6 维。所有新维度按角色分流到其他机制
2. **expansion 作为调度型隐性维度**，Phase 4 验证其价值后再决定是否升级为注入型
3. **socialProximity 作为 VirtualBody 调节因子**，不进 AffectiveField
4. **selfConcept 仅作研究指标**，定期问卷评估，不实时建模
5. **维度升级路径**：评估型 → 调度型 → 调节型 → 派生型 → 注入型，按价值逐步升级，避免直接跳到注入型

这个分类解决了维度膨胀问题 — 新维度都有去处，但不污染 4 维场的简洁性。

---

### 13.2 张力二：伦理保护层定位

**张力来源**：Cacioppo 和 SDT 引入伦理保护层，但原方案把它放到 Phase 5 单独实施。问题是：如果 Phase 1-4 完成后才考虑伦理，前 4 个 Phase 可能已经积累了大量"过度满足 relatedness"的设计决策，Phase 5 再加护栏为时已晚。

同时，伦理保护层的"AI 退后"策略与 ProactiveScheduler 的"AI 主动"策略存在直接冲突。

#### 13.2.1 Phase 5 拆分策略

将 Phase 5 拆分为 3 个子阶段，**与 Phase 1-4 同步推进**：

| 子阶段 | 时机 | 内容 | 用户可见性 |
|---|---|---|---|
| **Phase 5a：被动监控** | 与 Phase 1 同步 | 记录用户主动率、AI/用户比例、消息密度趋势、孤独信号词计数。不做任何差异化策略，纯数据采集 | 不可见 |
| **Phase 5b：隐性护栏** | 与 Phase 2-3 同步 | 依赖度 > 0.7 时 AI 措辞调整（"你觉得呢？"替代"我建议..."）；孤独风险 > 0.7 时 ProactiveScheduler 频率调整 | 不可见（用户感受到 AI 措辞变化，但不知是护栏机制） |
| **Phase 5c：显式 UI + 用户控制** | Phase 4 后 | UI 显示依赖度画像、孤独风险画像；用户可调节"AI 介入度"滑块；极高依赖时 UI 提示真实社交 | 可见 |

**关键决策**：
1. **伦理保护不是 Phase 5 才开始**，Phase 1 就要植入被动监控
2. **隐性护栏在 Phase 2-3 启用**，让伦理保护与关系引擎同步成熟
3. **显式 UI 推迟到 Phase 4 后**，避免用户过早感受到"被监视"

#### 13.2.2 主动 vs 自主的冲突调节

冲突场景：用户高依赖 + ProactiveScheduler 想主动发消息 + 高 warmth 场景想用 System 2 深聊

**冲突优先级**（高到低）：
1. **用户福祉**（自主性、心理健康）
2. **关系健康**（不冒进、不消耗）
3. **关系深度**（warmth、drift）
4. **AI 主动性**（ProactiveScheduler）

**冲突判断规则**：

| 依赖度时长 | 孤独风险 | 决策 | AI 行为 |
|---|---|---|---|
| 短期（< 7 天） | 低 | 关系深度优先 | 正常主动，正常深聊 |
| 短期（< 7 天） | 高 | 用户福祉优先 | 主动频率 +30%，但措辞谨慎（不"我想你"，而"今天怎么样"） |
| 长期（≥ 30 天） | 低 | 关系健康优先 | 主动频率 -50%，措辞转向"鼓励自主"（"你觉得呢？"） |
| 长期（≥ 30 天） | 高 | **冲突最复杂** | 主动频率维持，但内容转向"陪伴型"（不要求回应）；同时 UI 提示真实社交资源 |
| 极端（≥ 90 天 + 高风险） | 极高 | 用户福祉绝对优先 | AI 显式建议真实社交，降低自身响应频率，UI 推送心理热线等资源 |

**关键决策**：
1. **冲突优先级固定**：用户福祉永远高于关系深度
2. **依赖度时长分级**：7 天 / 30 天 / 90 天三档，每档策略不同
3. **孤独风险与依赖度组合判断**：两者同时高是最复杂场景，需要 UI 介入
4. **每周评估反馈**：依赖度趋势（上升/稳定/下降）动态调整 AI 退后强度

#### 13.2.3 用户控制权

伦理保护层不能完全由 AI 决策，必须给用户控制权：

| 控制项 | Phase 5c 实现 |
|---|---|
| 查看依赖度画像 | UI 显示当前依赖度评分 + 趋势 |
| 调节 AI 介入度 | 滑块：低 / 中 / 高 / 自适应（默认） |
| 关闭孤独风险监测 | 开关（默认开，但允许关闭） |
| 查看伦理事件日志 | 显示 AI 因伦理原因调整行为的记录（如"今天 AI 没主动发消息，因为检测到你最近和我聊得多"） |

**关键决策**：
1. **默认开启伦理保护**，但允许关闭（尊重用户自主性）
2. **伦理事件可追溯**，用户能看到 AI 为什么调整了行为（避免"AI 突然冷淡"的困惑）
3. **AI 介入度可调**，但极端情况（高依赖 + 高风险）AI 应明确建议真实社交，即使用户选择"高介入"

---

### 13.3 张力三：关键词 vs LLM 边界

**张力来源**：依恋损伤判定、资本化响应检测、AI 响应度评估等需要语义理解，纯关键词规则可能误判。但全部走 LLM 又会成本过高、延迟过大。

#### 13.3.1 分类框架

按"误判代价"和"实时性要求"将判定分为 4 类：

| 类别 | 误判代价 | 实时性 | 频率 | 走哪条 |
|---|---|---|---|---|
| **A. 关键词规则** | 低 | 实时 | 每条消息 | 本地零 token |
| **B. 启发式规则** | 中 | 实时 | 每条消息 | 本地零 token |
| **C. LLM 离线评估** | 高 | 非实时 | 24h 一次 | LLM 异步 |
| **D. LLM 在线评估** | 极高 | 实时 | 触发时 | LLM 同步 |

#### 13.3.2 判定分类决策

| 判定类型 | 类别 | 理由 |
|---|---|---|
| 孤独信号词检测（"一个人""没意思"） | A | 漏判不致命，AI 可通过其他方式关心 |
| 资本化正向触发（"考过""升职"） | A | 漏判只影响 warmth 增量，可接受 |
| 披露深度画像（表层/中层/深层/核心） | B | 关键词 + 长度 + 上下文分层规则，召回率够用 |
| Polyvagal 三态判定 | B | 节奏特征 + 标点密度启发式，生理信号本就模糊 |
| 跨层防御触发（过早问隐私） | B | warmth 阈值 + 关键词规则，保守触发 |
| 孤独风险综合评分 | B | 多指标加权，启发式可接受 |
| AI 依赖度评估 | B | 多指标加权，启发式可接受 |
| **依恋损伤判定**（isAttachmentInjury） | **C** | 误判代价高（误标会让 AI 反复道歉；漏判让用户感到被忽视）。LLM 离线扫描最近 24h 对话中的"脆弱时刻" |
| **AI 响应度评估**（用户披露后 AI 是否给出共情回应） | **C** | 误判代价高（误判会让 warmth 失真）。LLM 离线评估每轮"披露-响应"对 |
| **资本化响应分类**（active-constructive 等 4 类） | **C** | 4 类区分需要语义理解。LLM 离线评估 |
| **修复措辞生成**（依恋损伤的修复轨迹措辞） | **C** | 措辞需要语义，但可离线生成模板 |
| **紧急修复判定**（高 tension 场景下是否触发立即修复） | **D** | 高 tension 是关键场景，误判代价极高。仅在 System 2 已触发时附加，边际成本低 |

#### 13.3.3 成本预算

每天 LLM 评估总成本：

| 判定 | 频率 | 单次 token | 日总 token |
|---|---|---|---|
| 依恋损伤扫描 | 24h 一次 | ~500 | ~500 |
| AI 响应度评估 | 24h 一次 | ~400 | ~400 |
| 资本化响应分类 | 24h 一次（仅当有正向事件时） | ~300 | ~100（平均） |
| 修复措辞生成 | 触发时（罕见） | ~600 | ~50（平均） |
| 紧急修复判定 | 触发时（罕见） | ~200 | ~30（平均） |
| **总计** | | | **~1080 token/天** |

按当前 LLM 价格（~0.001 USD/1k token），每天伦理与语义评估成本约 **0.001 USD**，每月约 **0.03 USD**。可接受。

#### 13.3.4 边界原则

1. **零成本优先**：能用关键词就用关键词，LLM 是最后手段
2. **误判代价决定走 LLM 与否**：误判会让 AI 行为反复/失控的，必须走 LLM
3. **实时性决定在线/离线**：能离线就离线（24h 一次），减少同步延迟
4. **LLM 评估有节制**：每天总 LLM 评估 ≤ 1500 token，避免成本失控
5. **LLM 评估可降级**：若 API 不可用，C 类判定降级为 B 类启发式（接受召回率下降），D 类判定降级为"保守不触发"（宁可错过，不可误触）

**关键决策**：
1. **A/B 类（关键词 + 启发式）覆盖 80% 判定**，零 token 成本
2. **C 类（LLM 离线）用于高代价判定**，每天 ~1000 token
3. **D 类（LLM 在线）仅用于极端场景**，且只在 System 2 已触发时附加
4. **降级机制**：LLM 不可用时所有 C/D 类有降级路径，系统不阻塞

---

### 13.4 三项决策的协同关系

三个张力不是孤立的，它们的解决方案相互协同：

```
张力一（维度膨胀）           张力二（伦理定位）           张力三（关键词 vs LLM）
        ↓                         ↓                         ↓
   维度分类决策              Phase 5 拆分                判定分类决策
   - 注入型 4 维             - 5a 被动监控               - A/B 关键词+启发式
   - 调度型 expansion        - 5b 隐性护栏               - C LLM 离线
   - 调节型 socialProximity  - 5c 显式 UI                - D LLM 在线
   - 评估型 selfConcept                                 
        ↓                         ↓                         ↓
        └───────────────→ 协同点 ←──────────────────────┘
                            ↓
              - 依赖度评估（B 类启发式）→ 触发 5b 隐性护栏
              - 孤独风险（B 类启发式）→ 触发 5b 隐性护栏
              - 依恋损伤（C 类 LLM 离线）→ 维度升级（影响 warmth 衰减）
              - 维度分类避免 4 维污染 → 简化 LLM 评估输入
              - LLM 降级机制保证伦理保护不阻塞
```

**核心协同**：
- 维度分类（张力一）让 4 维场保持简洁，使得 LLM 评估（张力三）的输入可控
- 伦理保护层（张力二）的 5a 被动监控从 Phase 1 开始，为 LLM 离线评估（张力三）积累数据
- 关键词 vs LLM 边界（张力三）的降级机制保证伦理保护（张力二）在 LLM 不可用时仍能工作

这三个决策共同构成了方案走向实施的**中间决策点**，为 Phase 1-5 的具体实施提供了清晰约束。

---

## 十四、外部审查与方向冻结

本章记录一次外部审查的完整反馈与方案的响应。审查发生在方案完成 13 章 + 6 个机制 + 21 组理论根基 + 22 个待研究项 + 3 个张力解决方案之后，时机恰当 — 方向已经成立，但文档已从设计稿扩展成了研究百科，需要冻结核心模型，转向可验证实验。

本章目的：
1. **完整保留评估原文**，作为方案演进的关键节点记录
2. **逐条响应**评估意见，明确接受/部分接受/不接受的理由
3. **宣布方向冻结** — 不再扩展心理学概念，转向实验验证

### 14.1 评估原文（完整保留）

> **评估**
>
> 方向价值很高，值得成为 Wisp 的核心差异化能力。
> 它真正有价值的地方不是"让 AI 多几个情绪数值"，而是建立：
> 角色自身的情绪 + 用户与角色之间的关系 + 对话未完成事项 + AI 当前运行状态 + 本地与 LLM 的动态调度
>
> 不过目前文档已经从设计稿扩展成了研究百科，下一步不应该继续增加理论，而应该冻结核心模型，开始做可回放、可标注、可验证的实验。
>
> #### 一、推荐保留的核心架构
>
> EmotionEngine 角色自身状态：joy、sadness、fear、anger、love、trust 等
> AffectiveField 用户与角色之间：tension、warmth、anticipation、drift
> PendingEvents 关系中的未完成事项：用户的问题、承诺、被遗漏的情绪回应、待跟进事件
> VirtualBody 角色当前状态：困倦、注意力、情绪余韵、认知负荷
> SystemDispatcher 决定：本地模板回复，还是调用电脑端 LLM
>
> 这五层职责清晰，适合 Wisp。
>
> #### 二、心理学依据分级
>
> **相对适合直接指导设计**
> Intimacy Process Model / Capitalization / Social Penetration Theory / Self-Determination Theory / 关系修复研究
> 它们共同支持一个重要结论：关系温度不只由用户表达决定，更取决于 AI 是否正确回应。因此 warmth 最好由以下因素共同驱动：用户自我披露 + AI 是否回答了内容 + AI 是否回应了情绪 + 用户后续反馈
>
> **适合作为工程启发**
> Zeigarnik effect / VirtualBody / System1/System2 / Habit Formation / Fogg Behavior Model / 行为设计红线
> 这些可以帮助设计机制，但不要宣传为 Wisp 已经实现了对应的心理学模型。
>
> **必须降级为实验性假设**
> Polyvagal 三态（仅凭聊天节奏推断神经状态）/ 孤独风险评分 / AI 依赖度评分 / 用户心智感知画像 / 自动判断"依恋损伤"
> 尤其是 Polyvagal 不能直接用于判断用户处于"腹侧、交感或背侧状态"。最多输出："可能需要低负担交流""可能处于高压力表达状态""当前不适合连续追问"
>
> #### 三、当前最有产品价值的三个机制
>
> **1. AffectiveField** — Wisp 与普通聊天机器人的核心区别。四维结构建议保持不变，不要继续增加第五、第六个核心维度。
>
> **2. PendingEvents** — 最容易产生真实陪伴感的机制。但要区分：普通未完成事项可主动跟进；疑似关系伤害只记录不自动反复提起；用户确认受伤才进入修复轨迹。否则 AI 可能把一次普通漏答误判成"依恋损伤"，然后在 24h/7d/30d 后反复翻旧账。
>
> **3. System1/System2** — 适合当前电脑手机架构。System1/System2 应被视为工程调度策略，而不是严格的认知科学实现。
>
> #### 四、伦理部分的主要问题
>
> Phase 5a/5b/5c 的"不可见监控/不可见护栏"即使数据只存本地，系统偷偷改变主动消息频率、语气、AI 是否退后、关系推进速度，也可能让用户觉得 AI 突然变了，却不知道原因。
>
> 建议改为：原始数据本地保存 / 敏感画像默认不展示但必须可关闭 / 策略变化必须可解释 / 用户可以查看策略日志
>
> 另外不建议用"孤独风险""AI 依赖度"作为默认产品标签。更稳妥的名称：互动模式 / 陪伴需求提示 / AI 互动集中度 / 低打扰建议
>
> Wisp 不应诊断用户，也不应为了提高留存率而强化关系。
>
> #### 五、必须避免的方向
>
> - 不根据回复速度直接判断用户敷衍、抑郁或解离
> - 不使用可变比例奖励强化用户反复打开应用
> - 不制造"你不回复我就会错过什么"的 FoMO
> - 不用 AI 的脆弱表达诱导用户承担情感责任
> - 不自动反复提起未经用户确认的"关系创伤"
> - 不把留存率、消息密度当作心理健康机制的主要成功指标
> - 不让群聊角色共享同一个关系场
>
> #### 六、Wisp 的推荐数据流
>
> 用户消息 → ConversationEvent → RhythmSensor → EmotionEngine 更新角色状态 → AffectiveField 更新用户-角色关系 → PendingEvents 扫描未完成事项 → ResponseAssessment 评估 AI 是否回应到位 → SystemDispatcher 选择本地或 LLM → 生成文本 → GPT-SoVITS / Qwen3-TTS → PostLLMProcessor 更新关系与余韵
>
> 每个事件必须带：eventId / conversationId / characterId / timestamp / source / confidence
> 这样可以防止手机、Desktop、服务器重复处理同一条消息。
>
> 群聊中应使用：用户↔角色 A / 用户↔角色 B / 角色 A↔角色 B / 群聊整体氛围，而不是所有角色共享一个 AffectiveField。
>
> #### 七、建议实施顺序
>
> P0 先做最小闭环：AffectiveField 四维状态 / PendingEvents / AI ResponseAssessment / 关系事件回放测试
> P1 接入 Wisp 回复链路：PromptBuilder / System1/System2 / ProactiveScheduler / 群聊关系隔离 / Desktop/手机状态同步
> P2 做实验机制：RhythmSensor / surfaceActingLoad / 关系修复回潮 / VirtualBody
> P3 谨慎研究：孤独画像 / AI 互动集中度 / 自我概念 / 心理资源提示
>
> #### 八、最终评价
>
> 这份方案目前最值得投入的部分是：AffectiveField + AI 响应度 + PendingEvents + 关系修复 + 本地/LLM 双路径。它已经足够成为 Wisp 的核心产品方向。
>
> 但现在应该停止继续扩展心理学概念，转向建立：100 条以上可回放对话样本 / 明确的人类标注 / 状态更新单元测试 / 误触发率统计 / 用户主观感受问卷 / 策略可解释日志
>
> 一句话概括：AffectiveField 的方向已经成立，下一步不是继续证明它"理论上很丰富"，而是证明它能让 Wisp 的关系感更自然，同时不让用户感到被分析、被操控或被误诊。

### 14.2 逐条响应

#### 14.2.1 接受的批评（采纳并修改文档）

| 评估意见 | 响应 | 修改动作 |
|---|---|---|
| 文档已从设计稿扩展成研究百科，应冻结核心模型 | **完全接受** | 本章宣布方向冻结；第十五章起进入实验阶段 |
| 五层核心架构（EmotionEngine / AffectiveField / PendingEvents / VirtualBody / SystemDispatcher）职责清晰 | **完全接受** | 作为方案的核心架构，不再扩展第 6/7 层 |
| 心理学依据应分三级（直接指导/工程启发/实验性假设） | **完全接受** | 14.3 节给出三级分类表，明确各级用法 |
| Polyvagal 不能直接判断用户神经状态，最多输出"低负担/高压力/不适合追问" | **完全接受** | 14.4 节将 Polyvagal 降级为实验性假设，输出改为行为建议而非状态标签 |
| 自动判断"依恋损伤"有反复翻旧账风险，应只记录不自动提起 | **完全接受** | 14.4 节将依恋损伤自动判定降级为"疑似关系伤害只记录"；用户确认后才进入修复轨迹 |
| Phase 5a/5b 的"不可见"措辞有问题，应改为可关闭、可解释、可查看日志 | **完全接受** | 14.5 节重写伦理保护层的可见性策略 |
| 不应使用"孤独风险""AI 依赖度"作为产品标签 | **完全接受** | 14.6 节给出术语重命名表 |
| 不让群聊角色共享同一个关系场 | **完全接受** | 14.7 节明确群聊 AffectiveField 隔离原则 |
| 每个事件必须带 eventId/conversationId/characterId/timestamp/source/confidence | **完全接受** | 14.8 节将事件元数据规范纳入 P0 |
| P0/P1/P2/P3 实施顺序 | **完全接受** | 第十四章宣布原 Phase 1-5 划分作废，采用 P0-P3 |

#### 14.2.2 部分接受的批评（采纳但保留细化）

| 评估意见 | 响应 | 修改动作 |
|---|---|---|
| 4 维场不要继续增加第五、第六个核心维度 | **部分接受** — 接受"不增加注入型维度"，但保留 expansion 作为"调度型隐性维度"（13.1 决策已规定 expansion 不注入 prompt，仅参与调度决策） | 14.3 节明确 expansion 不升级为注入型 |
| System1/System2 应被视为工程调度策略，而非严格认知科学实现 | **部分接受** — 接受在产品宣传层面不强调"Kahneman 双系统"标签，但保留理论根基章节作为设计参考 | 第六章保留理论根基表，但产品文档对外不强调心理学标签 |
| Phase 5a/5b 改为可关闭 | **部分接受** — 接受"可关闭"，但保留"默认开启被动监控"，否则伦理保护层失去意义 | 14.5 节明确"默认开启 + 可关闭 + 可解释"三要素 |

#### 14.2.3 部分接受的批评（保留并加约束）

| 评估意见 | 响应 | 理由 |
|---|---|---|
| 不根据回复速度直接判断用户敷衍、抑郁或解离 | **接受批评，保留机制并加约束** — 原表述"用户可能在敷衍或累"过于确定；改为"观测/启发式解释/行为建议"三层分离 + 三条工程约束（见 14.4.3） | RhythmSensor 作为**非诊断性的、低置信度行为启发式**是成立的。它的价值不在于准确识别用户心理，而在于帮助 Wisp 选择更低负担、更少施压的回应策略 |
| 不把留存率、消息密度当作心理健康机制的主要成功指标 | **接受** — 但保留为**次要指标** | 留存率本身是产品健康指标，不是心理健康指标；评估混淆了两者。保留留存率作为产品指标，但孤独画像的成功指标改为"用户主观感受问卷" |

### 14.3 心理学依据三级分类

按评估建议，将第六章的 21 组理论根基分为三级：

#### 第一级：直接指导设计（可作为方案的理论支撑宣传）

| 理论 | 用法 |
|---|---|
| Intimacy Process Model（Reis & Shaver） | warmth 触发规则 + AI 响应度建模 |
| Capitalization（Gable & Reis） | 正向事件分享 + active-constructive 响应检测 |
| Social Penetration Theory（Altman & Taylor） | 披露深度画像 + 跨层防御 |
| Self-Determination Theory（Deci & Ryan） | 伦理边界 + AI 互动集中度 |
| Relationship Repair（Hannon et al.） | tension 触发式回潮 + 修复轨迹（需用户确认触发） |

**共同结论**：关系温度不只由用户表达决定，更取决于 AI 是否正确回应。因此 `warmth` 由以下因素共同驱动：
```
warmth_delta = f(用户自我披露, AI 是否回答了内容, AI 是否回应了情绪, 用户后续反馈)
```

#### 第二级：工程启发（可用于设计机制，但不宣传为"已实现该心理学模型"）

| 理论 | 用法 | 宣传约束 |
|---|---|---|
| Zeigarnik effect | PendingEvents 设计 | 不宣传"基于 Zeigarnik effect" |
| VirtualBody | 虚拟生理约束 | 不宣传"模拟人类生理" |
| System1/System2 | 工程调度策略 | 不宣传"严格认知科学实现" |
| Habit Formation（Lally） | 66 天习惯形成参考 | 不宣传"基于习惯形成研究" |
| Fogg Behavior Model | AI 建议三要素 | 不宣传"基于 FBM" |
| 行为设计红线 R1-R5 | 设计约束 | 可宣传"避免成瘾机制" |
| Circadian rhythm | VirtualBody 昼夜 | 不宣传"模拟昼夜节律" |

#### 第三级：实验性假设（必须降级，不作为产品功能宣传）

| 理论 | 原方案的过度宣称 | 降级后的用法 |
|---|---|---|
| Polyvagal Theory | "判定用户处于腹侧/交感/背侧状态" | **降级**：仅输出行为建议（"可能需要低负担交流""可能处于高压力表达状态""当前不适合连续追问"），不输出神经状态标签 |
| 孤独风险评分 | "用户孤独风险画像" | **降级**：仅作为内部研究指标，不作为产品功能；UI 不显示"孤独风险"标签 |
| AI 依赖度评分 | "AI 依赖度评估" | **降级**：仅作为内部研究指标；UI 不显示"依赖度"标签 |
| 用户心智感知画像 | "评估用户对 AI 的心智感知" | **降级**：仅作为长期研究方向，不进入 P0-P3 |
| 自动判断依恋损伤 | "isAttachmentInjury 自动标记 + 4 阶段修复轨迹" | **降级**：只记录"疑似关系伤害"，不自动提起；用户明确确认后才进入修复轨迹 |

### 14.4 Polyvagal 与依恋损伤的降级细化

#### 14.4.1 Polyvagal 降级

**原方案**（2.1.8）：
```kotlin
val neuralStateHint: Ventral | Sympathetic | Dorsal
```
宣传为"识别用户神经状态"。

**降级后**：
```kotlin
// 不输出神经状态标签，仅输出行为建议
val engagementHint: String  // "可能需要低负担交流" / "可能处于高压力表达状态" / "当前不适合连续追问" / null
```

判定规则改为保守启发式：
- latency 极短 + 长度极短 + 感叹号多 → "可能处于高压力表达状态"
- latency 极长 + 长度极短 + 持续多次 → "可能需要低负担交流"
- 高 tension + 用户消息含防御词 → "当前不适合连续追问"

**关键约束**：绝不输出"用户处于交感态/背侧态"等神经状态判断。

#### 14.4.2 依恋损伤降级

**原方案**（2.3.9）：
- LLM 离线评估自动标记 `isAttachmentInjury = true`
- 自动启动 4 阶段修复轨迹（24h / 7d / 30d / 90d）

**降级后**：
- LLM 离线评估标记为"疑似关系伤害"（不是"依恋损伤"）
- **只记录，不自动提起**
- 仅当以下条件之一满足时才进入修复轨迹：
  1. 用户主动提起"上次那件事"
  2. 用户明确表示"你那次让我很难过"
  3. 类似话题再次出现且用户情绪激动
- 修复轨迹仍是 24h / 7d / 30d / 90d，但每阶段前必须检查用户是否已经"翻篇"

**关键约束**：AI 永不主动反复提起未经用户确认的"关系创伤"。

#### 14.4.3 RhythmSensor 工程约束（三层分离 + 三条约束）

RhythmSensor 不从方案中删除，但必须持续强调**"观测、假设、行动"三层分离**。本节作为 RhythmSensor 的工程约束规范，2.1.4 节的 stateHint 示例措辞以本节为准（原"用户可能在敷衍或累"过于确定，作废）。

**1. 三层分离原则**

RhythmSensor 的所有输出必须明确区分三层，绝不混层：

| 层级 | 内容 | 性质 | 示例 |
|---|---|---|---|
| **观测层** | 可直接测量的行为信号 | 客观事实，无解释 | "回复变慢、消息变短、主动率下降" |
| **启发式解释层** | 对观测的可能解释 | 低置信度假设，含替代解释 | "可能暂时不方便深入交流，也可能是疲惫、忙碌或不想聊天" |
| **行为建议层** | AI 应采取的策略 | 保守、低风险、不施压 | "回复更短、更温和，不连续追问，不要求用户立即回应" |

**关键约束**：stateHint 注入 prompt 时必须包含三层，绝不只注入解释层（避免 LLM 把假设当事实）。

**2. 三条工程约束**

**约束 R-S1：至少组合两个以上信号**

回复速度（latency）单独不能触发"敷衍/低负担"等提示。必须结合以下至少一项：
- 长度趋势（lengthTrend）
- 主动率（initiativeRate7d）
- 连续性（连续多条短消息）
- 标点密度变化（punctTrend）

```
触发条件：latencyPercentile > 0.8 且 (lengthPercentile < 0.3 或 initiativeRate7d < 0.2)
```

**约束 R-S2：stateHint 使用概率和替代解释**

stateHint 措辞必须包含"可能"和替代解释，不使用确定性判断。

| 旧措辞（作废） | 新措辞（采用） |
|---|---|
| "用户回复越来越慢且越来越短，可能在敷衍或累" | "用户最近回复变慢且变短，可能不方便深入交流。建议保持简短温和，不强行追问。" |
| "用户极简回复，可能不想深聊" | "用户最近回复极简，可能暂时不方便长聊，也可能在忙。建议不连续追问。" |
| "用户情绪激动或兴奋" | "用户最近标点密度升高、感叹号增多，可能处于高表达状态。可以匹配其能量，但避免追问敏感话题。" |
| "用户几乎不主动，关系可能在冷淡" | "用户最近主动开话题较少。建议不强求回应，可偶尔发送不要求回复的陪伴消息。" |

**约束 R-S3：只能影响低风险行为**

RhythmSensor 的输出只能影响"低风险、可逆、不施压"的 AI 行为，不能单独触发任何高风险决策。

| 允许 RhythmSensor 影响（低风险） | 禁止 RhythmSensor 单独触发（高风险） |
|---|---|
| 回复长度 | 关系降温（warmth 大幅下调） |
| 追问数量 | 依恋损伤判定 |
| 主动关怀措辞 | 孤独风险评分 |
| 是否发送不要求回应的消息 | AI 互动集中度评分 |
| System1/System2 调度倾向 | 心理健康提示 |
| ProactiveScheduler 频率微调（±20%） | 关系修复轨迹触发 |

**关键约束**：高风险决策必须由 AffectiveField / PendingEvents / 用户明确表达共同驱动，RhythmSensor 只能作为"辅助信号"而非"主决策源"。

**3. RhythmSensor 的定位声明**

> RhythmSensor 作为**非诊断性的、低置信度行为启发式**是成立的。
>
> 它的价值不在于准确识别用户心理，而在于帮助 Wisp 选择更低负担、更少施压的回应策略。
>
> 它永远输出"观测 + 假设 + 行为建议"三层，绝不输出"用户处于 X 心理状态"的诊断结论。
>
> 它只能影响低风险行为，任何高风险决策都需要其他机制共同驱动。

### 14.5 伦理保护层可见性策略重写

**原方案**（13.2.1）：Phase 5a 不可见监控 / 5b 不可见护栏 / 5c 显式 UI

**重写后**：

| 阶段 | 内容 | 可见性 |
|---|---|---|
| **P3-a 被动数据采集** | 记录用户主动率、消息密度、互动模式指标 | 默认开启，可关闭；数据仅本地；用户可查看"我的互动数据" |
| **P3-b 策略调整** | AI 措辞调整、主动消息频率调整 | **必须可解释** — 每次 AI 行为变化都记录到"策略日志"；用户可查看"AI 最近为什么变了" |
| **P3-c 显式 UI + 用户控制** | 互动模式画像、低打扰建议、心理资源提示 | 默认不展示，用户主动开启 |

**核心原则**：
1. **默认开启被动采集**（保护伦理底线），但**可关闭**（尊重自主性）
2. **策略变化必须可解释**（绝不"偷偷改变"）
3. **敏感画像默认不展示**（避免标签化），但**用户可主动查看**
4. **绝不诊断**（不说"你孤独""你抑郁"）

### 14.6 术语重命名

按评估建议，产品 UI 不使用以下标签：

| 原术语 | 产品 UI 术语 | 内部研究术语（保留） |
|---|---|---|
| 孤独风险 | 互动模式 | loneliness_profile |
| AI 依赖度 | AI 互动集中度 | dependency_profile |
| 孤独风险监测 | 陪伴需求提示 | loneliness_monitor |
| 降低 AI 频率 | 低打扰建议 | proactive_throttle |
| 心理健康资源 | 心理资源提示（仅在极端场景） | crisis_resource |

**关键约束**：内部研究可保留原术语（便于与文献对齐），但产品 UI 必须使用中性术语。

### 14.7 群聊 AffectiveField 隔离原则

**原方案**：未明确群聊场景。

**新增原则**：

```
群聊场景下的 AffectiveField 结构：

用户 ↔ 角色 A：独立 AffectiveField_A
用户 ↔ 角色 B：独立 AffectiveField_B
角色 A ↔ 角色 B：独立 AffectiveField_AB（角色间关系）
群聊整体氛围：GroupAtmosphere（独立维度，不替代任何 bilateral field）
```

**关键约束**：
1. **绝不共享** — 角色间不共享与用户的 AffectiveField
2. **群聊氛围独立** — GroupAtmosphere 是群聊层的整体感受，不替代 bilateral field
3. **角色间关系可影响 bilateral** — 角色 A ↔ 角色 B 的 tension 高时，可能影响用户 ↔ 角色 A 的 warmth（如用户偏袒某方），但这是间接影响，不是共享

### 14.8 事件元数据规范

按评估建议，所有事件必须带元数据：

```kotlin
data class ConversationEvent(
    val eventId: String,           // UUID，唯一标识
    val conversationId: String,    // 会话 ID（单聊/群聊区分）
    val characterId: String,       // 角色 ID
    val timestamp: Long,           // 毫秒时间戳
    val source: EventSource,       // USER / AI / SYSTEM
    val confidence: Float,         // 0-1，事件置信度（LLM 判定的标注置信度）
    val payload: EventPayload,     // 具体内容
)

enum class EventSource { USER, AI, SYSTEM }
```

**用途**：
1. 防止手机/Desktop/服务器重复处理同一条消息（按 eventId 去重）
2. 支持关系事件回放测试（P0 核心）
3. 支持人类标注（标注按 eventId 索引）
4. 支持误触发率统计（按 confidence 阈值统计）

### 14.9 实施顺序重写

**原方案**（13.2）：Phase 1-4 + Phase 5a/b/c

**重写后**（采用评估建议的 P0-P3）：

| 阶段 | 内容 | 目标 |
|---|---|---|
| **P0 最小闭环** | AffectiveField 4 维状态 / PendingEvents / AI ResponseAssessment / 事件元数据规范 / 关系事件回放测试 | 证明 4 维场 + 未完成事项 + 响应度评估能在 100 条样本上稳定运行 |
| **P1 接入回复链路** | PromptBuilder 注入 / System1/System2 调度 / ProactiveScheduler / 群聊关系隔离 / Desktop/手机状态同步 | 证明完整链路在真实对话中工作 |
| **P2 实验机制** | RhythmSensor / surfaceActingLoad / 关系修复回潮（需用户确认触发）/ VirtualBody | 证明实验机制能提升关系感，但不让用户感到被分析 |
| **P3 谨慎研究** | 互动模式画像 / AI 互动集中度 / 自我概念 / 心理资源提示 / Polyvagal 行为建议 | 仅内部研究，不作为产品功能；需用户主动开启 |

**Phase 5a/5b/5c 作废** — 伦理保护层融入 P3-a/P3-b/P3-c，且 P3 阶段才启动（不是 P0 同步）。

**关键变化**：
- 原"Phase 1 就同步启动伦理保护层被动监控"作废
- P0 专注最小闭环，不引入伦理监控
- 伦理保护层推迟到 P3，且必须满足"可关闭、可解释、可查看日志"

### 14.10 验证基础设施

按评估建议，P0 必须建立以下基础设施：

| 基础设施 | 用途 | P0 必需 |
|---|---|---|
| 100+ 条可回放对话样本 | 测试状态更新逻辑 | 是 |
| 明确的人类标注 | 验证 AI 判定准确性 | 是 |
| 状态更新单元测试 | 防止回归 | 是 |
| 误触发率统计 | 评估启发式规则可靠性 | 是 |
| 用户主观感受问卷 | 评估关系感是否提升 | P1 引入 |
| 策略可解释日志 | 满足伦理可见性要求 | P1 引入 |

### 14.11 方向冻结声明

自本章起，方案进入**方向冻结**状态：

1. **不再扩展心理学概念** — 第六章理论根基表冻结在 21 组，不再新增
2. **不再新增机制** — 5 层核心架构冻结，不再扩展第 6/7 层
3. **不再新增待研究项** — 第十二章冻结在 22 项，新发现的待研究问题记入研究日志但不扩展文档
4. **不再扩展维度** — AffectiveField 冻结在 4 维，expansion 仅作为调度型隐性维度
5. **下一步是 P0 实验** — 不再证明方案"理论上很丰富"，转向证明它能让 Wisp 的关系感更自然

**例外**：
- 评估反馈中接受的修改（14.2.1）必须落实
- 评估反馈中部分接受的修改（14.2.3，RhythmSensor 三层分离）必须落实
- P0 实验中发现的设计缺陷可修正
- 文档可继续修订（措辞、组织、澄清），但不增加新概念

**方向冻结的执行**：
- 文档版本号升级为 `v2.1-frozen`
- 后续修改必须在变更日志中记录
- 任何新增概念都需要明确论证"为什么不增加就无法 P0 闭环"

**变更日志**：

| 版本 | 修改 | 位置 |
|---|---|---|
| v2.0-frozen | 首次方向冻结，新增第十四章外部审查与方向冻结 | 第十四章 |
| v2.1-frozen | RhythmSensor 三层分离 + 三条工程约束；14.2.3 由"不接受"改为"接受批评并加约束"；stateHint 措达示例更新 | 14.2.3 / 14.4.3 |

---

## 附录 A：与 Wisp 现有功能的整合点

| 现有功能 | 整合方式 |
|---|---|
| MoodDetector | 弃用单值 mood，改用 AffectiveField |
| PostLLMProcessor | 改为更新 emotionalAftertaste + 扫描 pending |
| MemoryStore | 保留，但反思产物改为场状态历史 |
| ProactiveScheduler | 触发概率受场状态调制 |
| PromptBuilder | 注入场 + 韵律 + pending + 生理 |
| ChatScreen | 每轮调用 AffectiveEngine.update() |
| DebugPage | 加场可视化 + pending 列表 + 韵律图 |

---

## 附录 B：最小可验证测试场景

### 场景 1: 韵律感知

用户连续 5 条短回复（< 5 字）+ 延迟递增（2s → 30s → 1min）：
- RhythmProfile.stateHint 应输出"用户可能敷衍或累"
- PromptBuilder 注入"建议更简短温和"
- AI 回复应 < 10 字

### 场景 2: 未完成事件

用户问"考试结果怎么样" → AI 回复没答 → 用户转移话题：
- PendingEvents 应记录"用户问考试结果 AI 没答"
- 6h 后 staleness > 0.4 触发主动收尾
- AI 应自然提起"对了，你刚才问的考试..."

### 场景 3: 双向情绪场

用户连续 3 轮倾诉心事：
- warmth 应从 0 升到 0.3+
- AI 回复应越来越亲昵

用户突然冷漠：
- warmth 缓慢下降（不立即）
- tension 上升
- AI 应察觉并试探"怎么了？"

### 场景 4: 虚拟生理

深夜（23 点后）聊天：
- circadianPhase 0.7
- AI 措辞应更松散、更短

连续聊 15 轮：
- attentionResidual 接近 0
- AI 回复应越来越简短

### 场景 5: System 1/2 调度

用户发"好累"：
- 命中 simpleEmotionCue → System 1
- 直接模板回复"抱抱，歇会儿"
- 零 LLM 调用

用户发"今天我老板又找我谈话了，说我最近状态不好，我都不知道怎么办"：
- 长消息 + warmth > 0.3 → System 2
- 走 LLM 深度回复

---

**文档结束**

这是设计草案，欢迎研究、质疑、迭代。每个机制都可以独立验证后再整合。
