package com.aftglw.devapi.core.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频解码工具：把 [VoiceRecorder] 输出的 AAC 文件解码为 Whisper 所需的
 * 16kHz mono PCM float[]。
 *
 * 流程：
 * 1. [MediaExtractor] 读 AAC 文件并选音频轨道
 * 2. [MediaCodec] 异步解码 AAC → PCM 16bit（原始采样率，通常 44100Hz）
 * 3. 重采样到 16kHz（线性抽取，音质足够 STT 用）
 * 4. 转 float[]（归一化到 [-1, 1]）
 *
 * 失败场景：
 * - 文件不存在 / 不是有效 AAC → 返回空数组
 * - MediaCodec 配置失败 → 返回空数组
 *
 * 注：MediaCodec 不支持重采样，输入 44.1kHz 输出仍是 44.1kHz。
 *     所以重采样在 Java 层做（最简单的线性抽取）。
 */
object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16_000

    /**
     * 把音频文件（AAC/WAV/M4A/MP3 等）解码为 16kHz mono float[]。
     * @param audioPath 音频文件路径
     * @return 16kHz mono PCM float[]，长度 ≤ 30s × 16000 = 480000；失败返回空数组
     */
    @JvmStatic
    fun decodeToPcmFloat(audioPath: String): FloatArray {
        val raw = decodeToPcm16(audioPath) ?: return FloatArray(0)
        if (raw.isEmpty()) return FloatArray(0)
        // PCM 16bit little-endian → float[]（归一化到 [-1, 1]）
        val numSamples = raw.size / 2
        val samples = FloatArray(numSamples)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
        for (i in 0 until numSamples) {
            samples[i] = buf.short.toFloat() / 32768.0f
        }
        return samples
    }

    /** 解码到 16kHz mono PCM 16bit byte[]（含重采样） */
    private fun decodeToPcm16(audioPath: String): ByteArray? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(audioPath) }
            var audioTrackIdx = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || inputFormat == null) {
                Log.e(TAG, "No audio track in: $audioPath")
                return null
            }
            extractor.selectTrack(audioTrackIdx)
            val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // 预分配 1MB，避免每次写入触发扩容+拷贝（PCM 通常远大于默认 32 字节）
            val pcmOut = ByteArrayOutputStream(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            decoder.stop()
            val rawPcm = pcmOut.toByteArray()
            // 多声道 → 单声道（取左声道）
            val monoPcm = if (srcChannels > 1) toMono(rawPcm, srcChannels) else rawPcm
            // 重采样到 16kHz
            return if (srcSampleRate != TARGET_SAMPLE_RATE) {
                resampleLinear(monoPcm, srcSampleRate, TARGET_SAMPLE_RATE)
            } else {
                monoPcm
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeToPcm16 failed", e)
            return null
        } finally {
            try { decoder?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /** 多声道 PCM 16bit → 单声道（取左声道） */
    private fun toMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val bytesPerSample = 2
        val frameSize = bytesPerSample * channels
        val numFrames = pcm.size / frameSize
        val result = ByteArray(numFrames * bytesPerSample)
        for (i in 0 until numFrames) {
            result[i * 2] = pcm[i * frameSize]
            result[i * 2 + 1] = pcm[i * frameSize + 1]
        }
        return result
    }

    /**
     * 线性重采样（最简单的抽取 / 插值）。
     * 对 STT 用途足够（Whisper 不要求高保真）。
     */
    private fun resampleLinear(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return pcm
        val ratio = fromRate.toDouble() / toRate.toDouble()
        val srcSamples = pcm.size / 2
        val dstSamples = (srcSamples / ratio).toInt()
        val result = ByteArray(dstSamples * 2)
        val srcBuf = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder())
        val dstBuf = ByteBuffer.wrap(result).order(ByteOrder.nativeOrder())
        for (i in 0 until dstSamples) {
            val srcPos = (i * ratio).toInt()
            if (srcPos < srcSamples) {
                val sample = srcBuf.getShort(srcPos * 2)
                dstBuf.putShort(sample)
            } else {
                dstBuf.putShort(0)
            }
        }
        return result
    }
}
