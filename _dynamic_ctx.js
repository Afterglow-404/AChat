const fs = require('fs');

// ===== OpenAiService.kt =====
let o = fs.readFileSync('app/src/main/java/com/aftglw/devapi/network/OpenAiService.kt', 'latin1');

const oldOpenAi = [
  '        val ctxWindow = prefs.getInt("context_window", 0)',
  '        val windowSize = if (ctxWindow > 0) ctxWindow',
  '            else if (prefs.getBoolean("long_context_mode", true)) 20 else 10',
  '        val recent = history.takeLast(windowSize)',
  '        for ((i, msg) in recent.withIndex()) {',
  '            if (ctxWindow <= 0 && !prefs.getBoolean("long_context_mode", true) && i > 0 && i % 10 == 0 && systemPrompt.isNotBlank()) {',
  '                put(JSONObject().apply { put("role", "system"); put("content", "\u3010\u4eba\u8bbe\u63d0\u9192\u3011$systemPrompt") })',
  '            }',
  '            put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })',
  '        }'
].join('\n');

const newOpenAi = [
  '        val ctxWindow = prefs.getInt("context_window", 0)',
  '        val maxTokens = if (ctxWindow > 0) ctxWindow',
  '            else if (prefs.getBoolean("long_context_mode", true)) 4096 else 2048',
  '        val recent = mutableListOf<ChatMessage>()',
  '        var tokCount = estimateTokenCount(systemPrompt) + estimateTokenCount(userMessage) + 10',
  '        for (msg in history.reversed()) {',
  '            val t = estimateTokenCount(msg.content) + 4',
  '            if (tokCount + t > maxTokens) break',
  '            tokCount += t',
  '            recent.add(0, msg)',
  '        }',
  '        val needReinject = ctxWindow <= 0 && !prefs.getBoolean("long_context_mode", true) && systemPrompt.isNotBlank()',
  '        for ((i, msg) in recent.withIndex()) {',
  '            if (needReinject && i > 0 && i % 10 == 0) {',
  '                put(JSONObject().apply { put("role", "system"); put("content", "\u3010\u4eba\u8bbe\u63d0\u9192\u3011$systemPrompt") })',
  '            }',
  '            put(JSONObject().apply { put("role", msg.role); put("content", msg.content) })',
  '        }'
].join('\n');

if (!o.includes(oldOpenAi)) { console.log('OpenAi old not found'); process.exit(1); }
o = o.replace(oldOpenAi, newOpenAi);
fs.writeFileSync('app/src/main/java/com/aftglw/devapi/network/OpenAiService.kt', o, 'latin1');
console.log('OpenAi: dynamic context window added');

// ===== ClaudeAiService.kt =====
let c = fs.readFileSync('app/src/main/java/com/aftglw/devapi/network/ClaudeAiService.kt', 'latin1');

const oldClaude = [
  '            val ctxWindow = prefs.getInt("context_window", 0)',
  '            val windowSize = if (ctxWindow > 0) ctxWindow',
  '                else if (prefs.getBoolean("long_context_mode", true)) 20 else 10',
  '            val recent = history.takeLast(windowSize)',
  '            for (msg in recent) { put(JSONObject().apply { put("role", msg.role); put("content", msg.content) }) }'
].join('\n');

const newClaude = [
  '            val ctxWindow = prefs.getInt("context_window", 0)',
  '            val maxTokens = if (ctxWindow > 0) ctxWindow',
  '                else if (prefs.getBoolean("long_context_mode", true)) 4096 else 2048',
  '            val recent = mutableListOf<ChatMessage>()',
  '            var tokCount = estimateTokenCount(systemPrompt) + estimateTokenCount(userMessage) + 10',
  '            for (msg in history.reversed()) {',
  '                val t = estimateTokenCount(msg.content) + 4',
  '                if (tokCount + t > maxTokens) break',
  '                tokCount += t',
  '                recent.add(0, msg)',
  '            }',
  '            for (msg in recent) { put(JSONObject().apply { put("role", msg.role); put("content", msg.content) }) }'
].join('\n');

if (!c.includes(oldClaude)) { console.log('Claude old not found'); process.exit(1); }
c = c.replace(oldClaude, newClaude);
fs.writeFileSync('app/src/main/java/com/aftglw/devapi/network/ClaudeAiService.kt', c, 'latin1');
console.log('Claude: dynamic context window added');
