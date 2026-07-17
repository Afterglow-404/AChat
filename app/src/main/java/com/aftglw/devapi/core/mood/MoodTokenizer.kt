package com.aftglw.devapi.core.mood

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 极简 BERT Tokenizer — 用于 ONNX 模型推理。
 *
 * 支持：
 * - 加载 vocab.txt（WordPiece 词表）
 * - 中文按字切分 + 英文 WordPiece
 * - padding / truncation 到 MAX_LEN
 */
object MoodTokenizer {
    private var vocab: Map<String, Int> = emptyMap()
    private var reverseVocab: Map<Int, String> = emptyMap()
    private var isReady = false

    const val MAX_LEN = 72
    private const val CLS = "[CLS]"
    private const val SEP = "[SEP]"
    private const val PAD = "[PAD]"
    private const val UNK = "[UNK]"

    /** 从 assets/vocab.txt 加载词表 */
    fun load(context: Context) {
        if (isReady) return
        try {
            val lines = mutableListOf<String>()
            val reader = BufferedReader(
                InputStreamReader(context.assets.open("vocab.txt"), "UTF-8")
            )
            reader.use { r ->
                r.lineSequence().forEach { lines.add(it.trim()) }
            }
            vocab = lines.mapIndexed { i, w -> w to i }.toMap()
            reverseVocab = lines.mapIndexed { i, w -> i to w }.toMap()
            isReady = true
            Log.i("MoodTokenizer", "Vocabulary loaded: ${vocab.size} tokens")
        } catch (e: Exception) {
            Log.e("MoodTokenizer", "Failed to load vocab.txt", e)
        }
    }

    /** 将文本 tokenizer 为模型输入张量 */
    fun tokenize(text: String): Pair<Array<LongArray>, Array<LongArray>> {
        if (!isReady) {
            return Pair(
                Array(1) { LongArray(MAX_LEN) { 0L } },
                Array(1) { LongArray(MAX_LEN) { 0L } }
            )
        }

        val tokens = mutableListOf<String>()
        tokens.add(CLS)

        // 逐字符处理（中文按字，英文按空格分词后 WordPiece）
        val chars = text.toCharArray()
        val sb = StringBuilder()
        for (c in chars) {
            if (c.isWhitespace()) {
                if (sb.isNotEmpty()) {
                    tokens.addAll(wordPieceTokenize(sb.toString()))
                    sb.clear()
                }
                continue
            }
            if (isChineseChar(c) || isPunctuation(c)) {
                if (sb.isNotEmpty()) {
                    tokens.addAll(wordPieceTokenize(sb.toString()))
                    sb.clear()
                }
                tokens.add(c.toString())
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.addAll(wordPieceTokenize(sb.toString()))
        }
        tokens.add(SEP)

        // Truncate
        val truncated = if (tokens.size > MAX_LEN) {
            tokens.subList(0, MAX_LEN - 1) + listOf(SEP)
        } else tokens

        // Pad
        val inputIds = LongArray(MAX_LEN) { i ->
            val token = truncated.getOrElse(i) { PAD }
            (vocab[token] ?: vocab[UNK] ?: 100).toLong()
        }
        val attentionMask = LongArray(MAX_LEN) { i ->
            if (i < truncated.size) 1L else 0L
        }

        return Pair(Array(1) { inputIds }, Array(1) { attentionMask })
    }

    private fun wordPieceTokenize(word: String): List<String> {
        if (word in vocab) return listOf(word)
        // 简单的 WordPiece：逐字符回退
        val result = mutableListOf<String>()
        var remaining = word
        while (remaining.isNotEmpty()) {
            var found = false
            // 尝试从最长匹配
            for (end in remaining.length downTo 1) {
                val sub = if (result.isEmpty()) remaining.substring(0, end)
                else "##${remaining.substring(0, end)}"
                if (sub in vocab) {
                    result.add(sub)
                    remaining = remaining.substring(end)
                    found = true
                    break
                }
            }
            if (!found) {
                result.add(UNK)
                break
            }
        }
        return result
    }

    private fun isChineseChar(c: Char): Boolean {
        return c in '\u4E00'..'\u9FFF' ||
               c in '\u3400'..'\u4DBF' ||
               c in '\uF900'..'\uFAFF'
    }

    private fun isPunctuation(c: Char): Boolean {
        return c in listOf('，', '。', '！', '？', '、', '；', '：',
                          '「', '」', '『', '』', '【', '】', '（', '）',
                          '…', '—', '～', '·', ',', '.', '!', '?',
                          ';', ':', '"', '\'', '(', ')', '[', ']')
    }
}
