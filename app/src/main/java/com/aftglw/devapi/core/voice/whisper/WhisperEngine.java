package com.aftglw.devapi.core.voice.whisper;

/**
 * Whisper 推理引擎接口（与 vilassn/whisper_android 保持兼容）。
 *
 * 实现类：[WhisperEngineJava]（纯 TFLite Java API）
 */
public interface WhisperEngine {
    boolean isInitialized();
    boolean initialize(String modelPath, String vocabPath, boolean multilingual) throws java.io.IOException;
    void deinitialize();

    /** 文件转写：读取 WAV/AAC 文件并返回文本 */
    String transcribeFile(String wavePath);

    /** 内存 PCM 转写：直接接受 16kHz mono float[] 样本 */
    String transcribeBuffer(float[] samples);
}
