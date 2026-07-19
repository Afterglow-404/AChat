package com.aftglw.devapi.feature.group

/**
 * 纯函数 @提及解析器 —— 无 Android 依赖，便于单元测试。
 *
 * 解析规则：
 * - 识别形如 `@name` 的 token，name 后须紧跟空白、标点、行尾或字符串末尾。
 * - 仅返回 [candidates] 中真实存在的成员名；未匹配候选的 `@xxx` 视为普通文本。
 * - 同名多次提及只返回一次，按首次出现顺序保留。
 * - 不区分中英文，名字内允许中文、字母、数字、下划线。
 */
object MentionParser {

    /** 匹配 @ 后紧跟非空白非标点的连续字符（含中文、字母、数字、下划线） */
    private val MENTION_REGEX = Regex("@([\\p{IsHan}A-Za-z0-9_]+)")

    /**
     * 解析 [text] 中的 @提及，返回 [candidates] 中被提及的成员名列表（去重，按首次出现顺序）。
     *
     * @param text       用户输入文本
     * @param candidates 群成员名集合（合法的提及目标）
     * @return 被提及的成员名列表；空列表表示无有效提及
     */
    fun parse(text: String, candidates: Collection<String>): List<String> {
        if (text.isEmpty() || candidates.isEmpty()) return emptyList()
        val candidateSet = candidates.toSet()
        val seen = LinkedHashSet<String>()
        for (match in MENTION_REGEX.findAll(text)) {
            val name = match.groupValues[1]
            if (name in candidateSet) seen.add(name)
        }
        return seen.toList()
    }

    /**
     * 返回 [text] 中首个被提及的成员名，未提及返回 null。
     * 用于决定群聊「首位发言人」。
     */
    fun firstMention(text: String, candidates: Collection<String>): String? =
        parse(text, candidates).firstOrNull()

    /**
     * 判断 [text] 中是否提及了 [name]。
     */
    fun mentions(text: String, candidates: Collection<String>, name: String): Boolean =
        name in parse(text, candidates)

    /**
     * 检测「正在输入中的 @提及」：找到文本末尾的 `@xxx`，
     * 其中 `xxx` 是某个候选成员名的前缀（且 `@` 前须为空白或行首）。
     *
     * @return (atIndex, query) 二元组；atIndex 为 `@` 在 text 中的下标，query 为 `@` 后的字符串；无活动提及时返回 null
     */
    fun activeMentionQuery(text: String, candidates: Collection<String>): Pair<Int, String>? {
        if (text.isEmpty() || candidates.isEmpty()) return null
        val lastAt = text.lastIndexOf('@')
        if (lastAt < 0) return null
        // @ 前须为行首或空白
        if (lastAt > 0 && !text[lastAt - 1].isWhitespace()) return null
        val query = text.substring(lastAt + 1)
        // query 内不允许出现空白（用户已离开提及输入）
        if (query.any { it.isWhitespace() }) return null
        // query 必须是某个候选名的前缀（含空 query，即刚键入 @）
        if (candidates.none { it.startsWith(query) }) return null
        return lastAt to query
    }
}
