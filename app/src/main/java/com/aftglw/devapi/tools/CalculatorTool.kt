package com.aftglw.devapi.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * 数学表达式求值工具。
 * 支持 + - * / % ( ) 和常用函数。
 * 纯 Kotlin 实现，无需第三方库。
 */
class CalculatorTool : AiTool {
    override val name = "calculator"
    override val description = "计算数学表达式。支持加减乘除(+-*/)、幂(^)、取模(%)、括号和数学函数(sin/cos/sqrt/abs/floor/ceil/round/log/ln)。"
    override val inputSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("expr", JSONObject().apply {
                put("type", "string")
                put("description", "数学表达式，如 (3 + 5) * 2 或 sqrt(144)")
            })
        })
        put("required", JSONArray().apply { put("expr") })
    }

    override suspend fun execute(ctx: Context, args: JSONObject): String {
        val expr = args.optString("expr", "").trim()
        if (expr.isBlank()) return "请提供数学表达式"
        return try {
            val result = evaluate(expr)
            "$expr = $result"
        } catch (e: Exception) {
            "表达式错误：${e.message}"
        }
    }

    private fun evaluate(expr: String): Double {
        val tokens = tokenize(expr)
        val parsed = parseExpr(tokens, 0).value
        return parsed
    }

    private fun tokenize(s: String): MutableList<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isWhitespace()) { i++; continue }
            if (c in "+-*/%^(),") {
                if (sb.isNotEmpty()) { result.add(sb.toString()); sb.clear() }
                result.add(c.toString())
            } else if (c.isLetterOrDigit() || c == '.') {
                sb.append(c)
            } else {
                throw IllegalArgumentException("不支持的字符: '$c'")
            }
            i++
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    private fun parseExpr(it: MutableList<String>, index: Int = 0): ParseResult {
        var pos = index
        var result = parseTerm(it, pos).let { pos = it.pos; it.value }
        while (pos < it.size) {
            when (it[pos]) {
                "+" -> { pos++; parseTerm(it, pos).let { result += it.value; pos = it.pos } }
                "-" -> { pos++; parseTerm(it, pos).let { result -= it.value; pos = it.pos } }
                else -> break
            }
        }
        return ParseResult(result, pos)
    }

    private fun parseTerm(it: MutableList<String>, index: Int): ParseResult {
        var pos = index
        var result = parseFactor(it, pos).let { pos = it.pos; it.value }
        while (pos < it.size) {
            val op = it[pos]
            if (op in listOf("*", "/", "%")) {
                pos++
                val right = parseFactor(it, pos)
                result = when (op) {
                    "*" -> result * right.value
                    "/" -> if (right.value == 0.0) throw ArithmeticException("除以零") else result / right.value
                    "%" -> result % right.value
                    else -> result
                }
                pos = right.pos
            } else break
        }
        return ParseResult(result, pos)
    }

    private fun parseFactor(it: MutableList<String>, index: Int): ParseResult {
        var pos = index
        if (pos >= it.size) throw IllegalArgumentException("表达式不完整")
        val token = it[pos]
        pos++
        var base = when {
            token == "(" -> {
                val inner = parseExpr(it, pos)
                if (inner.pos >= it.size || it[inner.pos] != ")") throw IllegalArgumentException("缺少右括号")
                ParseResult(inner.value, inner.pos + 1)
            }
            token == "-" -> { val f = parseFactor(it, pos); ParseResult(-f.value, f.pos) }
            token == "+" -> parseFactor(it, pos)
            token.toDoubleOrNull() != null -> ParseResult(token.toDouble(), pos)
            token.all { it.isLetter() } -> {
                if (pos >= it.size || it[pos] != "(") throw IllegalArgumentException("函数名后需要 (")
                pos++
                val arg = parseExpr(it, pos)
                pos = arg.pos
                if (pos >= it.size || it[pos] != ")") throw IllegalArgumentException("函数调用缺少右括号")
                pos++
                val v = when (token.lowercase()) {
                    "sin" -> sin(arg.value)
                    "cos" -> cos(arg.value)
                    "tan" -> tan(arg.value)
                    "sqrt" -> sqrt(arg.value)
                    "abs" -> abs(arg.value)
                    "floor" -> floor(arg.value)
                    "ceil" -> ceil(arg.value)
                    "round" -> round(arg.value).toDouble()
                    "log" -> log10(arg.value)
                    "ln" -> ln(arg.value)
                    "exp" -> exp(arg.value)
                    else -> throw IllegalArgumentException("未知函数: $token")
                }
                ParseResult(v, pos)
            }
            else -> throw IllegalArgumentException("无法解析: $token")
        }
        // 幂运算 ^（右结合，优先级高于 * /）
        pos = base.pos
        if (pos < it.size && it[pos] == "^") {
            val exp = parseFactor(it, pos + 1)
            base = ParseResult(base.value.pow(exp.value), exp.pos)
        }
        return base
    }

    private data class ParseResult(val value: Double, val pos: Int)
}
