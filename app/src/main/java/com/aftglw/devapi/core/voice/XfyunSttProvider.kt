package com.aftglw.devapi.core.voice

import android.content.Context
import android.util.Base64
import android.util.Log
import com.aftglw.devapi.core.security.SecureKeyStore
import com.aftglw.devapi.network.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * 讯飞实时语音转写（RTASR）STT Provider：在线 WebSocket API。
 *
 * 协议要点（参考 https://www.xfyun.cn/doc/asr/rtasr/API.html）：
 * - 鉴权：signa = Base64(HmacSHA1(MD5(appid + ts), api_key))
 * - 握手 URL：wss://rtasr.xfyun.cn/v1/ws?appid=X&ts=Y&signa=Z
 * - 音频要求：16kHz / 16bit / mono PCM
 * - 上传：每 40ms 发送 1280 字节（即 640 samples）
 * - 结束：发送文本帧 "end"
 * - 返回：JSON，action=started 握手成功，action=result 转写结果（含 ls=1 最终 / ls=0 中间），
 *   action=error 异常
 *
 * 转写结果 data 内为 JSON：{bg, ed, ls, rg:[{cg:[{cn:[{cw:[{w, wp}]}]},{...}]},...]}
 * 累积式：每个 rg 内 cg.cn.cw.w 拼起来是一句话；多次 result 之间文本累积。
 *
 * 配置项（wechat_settings + SecureKeyStore）：
 *   - stt_xfyun_appid       : 讯飞 APPID（明文存 SharedPreferences，与 ai_api_url 同级别）
 *   - stt_xfyun_api_key     : 讯飞 apiKey（SecureKeyStore 加密）
 *
 * 失败场景：
 * - APPID / APIKey 未配置 → isAvailable=false
 * - WebSocket 握手失败 → Failed
 * - 转写超时 / action=error → Failed
 *
 * 注意：与 [CloudSttProvider] 不同，讯飞 RTASR 是流式协议，但我们当前是文件转写，
 * 所以把音频文件读为 PCM 后按 1280 字节块流式发送，等所有 result 累积后返回最终文本。
 */
class XfyunSttProvider(ctx: Context) : SttProvider {

    override val id: String = "xfyun"

    private val ctx = ctx.applicationContext

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val appid = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
            .getString("stt_xfyun_appid", "") ?: ""
        val apiKey = SecureKeyStore.getString(ctx, "stt_xfyun_api_key")
        appid.isNotEmpty() && apiKey.isNotEmpty()
    }

    override suspend fun transcribe(
        audioPath: String,
        lang: String,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ): SttOutcome = withContext(Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            return@withContext SttOutcome.Failed("音频文件不存在或为空: $audioPath")
        }

        val prefs = ctx.getSharedPreferences("wechat_settings", Context.MODE_PRIVATE)
        val appid = prefs.getString("stt_xfyun_appid", "") ?: ""
        val apiKey = SecureKeyStore.getString(ctx, "stt_xfyun_api_key")
        if (appid.isEmpty() || apiKey.isEmpty()) {
            return@withContext SttOutcome.Failed("讯飞 APPID / APIKey 未配置")
        }

        // 解码为 16kHz mono PCM samples（float[]），转 16-bit little-endian bytes
        val samples = try {
            AudioDecoder.decodeToPcmFloat(audioPath)
        } catch (e: Exception) {
            return@withContext SttOutcome.Failed("音频解码失败: ${e.message?.take(60)}", e)
        }
        if (samples.isEmpty()) {
            return@withContext SttOutcome.Failed("音频解码失败: $audioPath")
        }
        val pcmBytes = samplesToPcm16Bytes(samples)
        Log.d(TAG, "PCM: ${pcmBytes.size} bytes, ${samples.size / 16000f}s")

        // 生成签名
        val ts = (System.currentTimeMillis() / 1000).toString()
        val signa = try {
            buildSigna(appid, ts, apiKey)
        } catch (e: Exception) {
            return@withContext SttOutcome.Failed("签名生成失败: ${e.message?.take(60)}", e)
        }
        val wsUrl = "wss://rtasr.xfyun.cn/v1/ws?appid=$appid&ts=$ts&signa=${java.net.URLEncoder.encode(signa, "UTF-8")}"

        // 用 suspendCancellableCoroutine + withTimeout 替代 CountDownLatch 阻塞等待
        val resultBuilder = StringBuilder()
        val handshakeOk = AtomicBoolean(false)

        val request = Request.Builder().url(wsUrl).build()
        // 独立的发送协程作用域：onOpen 回调中启动流式发送协程，delay(40) 非阻塞
        val sendJob = Job()
        val sendScope = CoroutineScope(Dispatchers.IO + sendJob)
        var wsRef: WebSocket? = null

        var outcome: SttOutcome = SttOutcome.Failed("讯飞转写未启动")
        try {
            outcome = withTimeout(30_000L) {
                suspendCancellableCoroutine<SttOutcome> { cont ->
                    var resumed = false
                    fun safeResume(o: SttOutcome) {
                        if (!resumed && cont.isActive) {
                            resumed = true
                            cont.resume(o)
                        }
                    }

                    val ws = HttpClient.client.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            Log.i(TAG, "WebSocket onOpen, streaming PCM...")
                            // 流式发送 PCM，每 1280 字节一帧；协程内 delay(40) 非阻塞
                            sendScope.launch {
                                val chunkSize = 1280
                                var pos = 0
                                while (pos < pcmBytes.size && isActive) {
                                    val end = (pos + chunkSize).coerceAtMost(pcmBytes.size)
                                    // 用 vararg ByteString.of 发送 PCM 切片。
                                    // okio 3.x 把三参 of(ByteArray, Int, Int) 标记为 ERROR 级别 deprecation（强制迁移到扩展函数），
                                    // 而扩展函数 toByteString 在当前 OkHttp 4.12 transitive 依赖下无法解析。
                                    // 1280 字节切片的 copyOfRange 开销可忽略（每 40ms 一次）。
                                    val chunk = ByteString.of(*pcmBytes.copyOfRange(pos, end))
                                    webSocket.send(chunk)
                                    pos = end
                                    // 40ms 节流，避免压垮服务端
                                    delay(40)
                                }
                                // 发送结束标识
                                webSocket.send("end")
                                Log.i(TAG, "PCM stream end sent")
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                val json = JSONObject(text)
                                val action = json.optString("action")
                                val code = json.optString("code")
                                when (action) {
                                    "started" -> {
                                        if (code == "0") {
                                            handshakeOk.set(true)
                                            Log.i(TAG, "Handshake ok")
                                        } else {
                                            safeResume(SttOutcome.Failed("讯飞握手失败 code=$code desc=${json.optString("desc")}"))
                                        }
                                    }
                                    "result" -> {
                                        if (code == "0") {
                                            val data = json.optString("data")
                                            val seg = parseRtasrResult(data)
                                            if (seg.isNotEmpty()) {
                                                resultBuilder.append(seg)
                                                Log.d(TAG, "Seg: $seg, total=${resultBuilder}")
                                            }
                                        } else {
                                            Log.w(TAG, "result code=$code desc=${json.optString("desc")}")
                                        }
                                    }
                                    "error" -> {
                                        safeResume(SttOutcome.Failed("讯飞错误 code=$code desc=${json.optString("desc")}"))
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "onMessage parse failed: $text", e)
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            Log.i(TAG, "WebSocket onClosed: $code $reason")
                            if (!handshakeOk.get()) {
                                safeResume(SttOutcome.Failed("讯飞握手未完成"))
                            } else {
                                val text = resultBuilder.toString().trim()
                                Log.i(TAG, "Final: '$text'")
                                safeResume(SttOutcome.Success(text))
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            Log.e(TAG, "WebSocket onFailure", t)
                            safeResume(SttOutcome.Failed("WebSocket 异常: ${t.message?.take(60)}", t))
                        }
                    })
                    wsRef = ws

                    cont.invokeOnCancellation {
                        sendScope.cancel()
                        try { ws.cancel() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            outcome = SttOutcome.Failed("讯飞转写超时")
        } finally {
            sendScope.cancel()
            try { wsRef?.close(1000, "done") } catch (_: Exception) {}
        }
        outcome
    }

    override fun stop() {
        // 同步等待，无独立 stop
    }

    override fun shutdown() {
        // 无状态
    }

    companion object {
        private const val TAG = "XfyunSttProvider"

        /**
         * signa = Base64(HmacSHA1(MD5(appid + ts), api_key))
         */
        private fun buildSigna(appid: String, ts: String, apiKey: String): String {
            val baseString = appid + ts
            val md5 = MessageDigest.getInstance("MD5").digest(baseString.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA1"))
            val raw = mac.doFinal(md5.toByteArray())
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        }

        /**
         * float[] samples (range [-1, 1]) → 16-bit little-endian PCM bytes.
         */
        private fun samplesToPcm16Bytes(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            var p = 0
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                out[p++] = (v.toInt() and 0xFF).toByte()
                out[p++] = ((v.toInt() shr 8) and 0xFF).toByte()
            }
            return out
        }

        /**
         * 解析 RTASR result.data：
         * {"ls":bool, "rg":[{"cg":[{"cn":[{"cw":[{"w":"word","wp":"n"}]}]}]}], ...}
         *
         * 返回本段 result 累积的文本（拼接所有 cw.w）。
         * 讯飞设计为：每个 result 是当前一段话的累积，多段 result 之间是不同句子的累积，
         * 因此这里把当前 result 的所有 w 拼起来即可。
         */
        private fun parseRtasrResult(data: String): String {
            return try {
                val obj = JSONObject(data)
                val rg = obj.optJSONArray("rg") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until rg.length()) {
                    val cg = rg.optJSONObject(i)?.optJSONArray("cg") ?: continue
                    for (j in 0 until cg.length()) {
                        val cn = cg.optJSONObject(j)?.optJSONArray("cn") ?: continue
                        for (k in 0 until cn.length()) {
                            val cw = cn.optJSONObject(k)?.optJSONArray("cw") ?: continue
                            for (m in 0 until cw.length()) {
                                val wObj = cw.optJSONObject(m) ?: continue
                                val w = wObj.optString("w")
                                if (w.isNotEmpty()) sb.append(w)
                            }
                        }
                    }
                }
                sb.toString()
            } catch (e: Exception) {
                Log.e(TAG, "parseRtasrResult failed: $data", e)
                ""
            }
        }
    }
}
