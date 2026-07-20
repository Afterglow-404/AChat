package com.aftglw.devapi.core.voice.whisper;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Whisper TFLite 推理引擎：加载 .tflite 模型 + filters_vocab.bin 词表，
 * 接受 16kHz mono PCM float[] 输入，输出转写文本。
 *
 * 移植自 vilassn/whisper_android 的 WhisperEngineJava，主要改动：
 * - 包名改为 com.aftglw.devapi.core.voice.whisper
 * - 实现 transcribeBuffer(samples)（原版返回 null），支持内存 PCM 输入
 * - 删除未使用的 GPU/NNAPI 注释代码
 *
 * 模型路径优先级：
 * 1. 用户在设置中显式选择的模型（stt_whisper_model）
 * 2. filesDir/whisper/ 下第一个 .tflite 文件
 * 词表必须存在：filesDir/whisper/filters_vocab_multilingual.bin
 */
public class WhisperEngineJava implements WhisperEngine {
    private static final String TAG = "WhisperEngineJava";
    private final WhisperUtil mWhisperUtil = new WhisperUtil();
    private final Context mContext;
    private boolean mIsInitialized = false;
    private Interpreter mInterpreter = null;

    public WhisperEngineJava(Context context) {
        mContext = context;
    }

    @Override
    public boolean isInitialized() {
        return mIsInitialized;
    }

    @Override
    public boolean initialize(String modelPath, String vocabPath, boolean multilingual) throws IOException {
        loadModel(modelPath);
        Log.d(TAG, "Model is loaded: " + modelPath);
        boolean ret = mWhisperUtil.loadFiltersAndVocab(multilingual, vocabPath);
        mIsInitialized = ret;
        if (ret) {
            Log.d(TAG, "Filters and Vocab are loaded: " + vocabPath);
        } else {
            Log.e(TAG, "Failed to load Filters and Vocab");
        }
        return mIsInitialized;
    }

    @Override
    public void deinitialize() {
        if (mInterpreter != null) {
            mInterpreter.close();
            mInterpreter = null;
        }
        mIsInitialized = false;
    }

    /** 文件转写：读取 WAV 文件并转写 */
    @Override
    public String transcribeFile(String wavePath) {
        Log.d(TAG, "Calculating Mel spectrogram from file...");
        float[] melSpectrogram = getMelSpectrogramFromFile(wavePath);
        Log.d(TAG, "Mel spectrogram is calculated");
        return runInference(melSpectrogram);
    }

    /** 内存 PCM 转写：直接接受 16kHz mono float[] 样本，避免中间落盘 WAV */
    @Override
    public String transcribeBuffer(float[] samples) {
        if (samples == null || samples.length == 0) return "";
        Log.d(TAG, "Calculating Mel spectrogram from buffer (samples=" + samples.length + ")...");
        // 固定输入长度为 30s × 16kHz = 480000（不足补零，超出截断）
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);
        int cores = Runtime.getRuntime().availableProcessors();
        float[] melSpectrogram = mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);
        Log.d(TAG, "Mel spectrogram is calculated");
        return runInference(melSpectrogram);
    }

    private void loadModel(String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = fileInputStream.getChannel();
        long startOffset = 0;
        long declaredLength = fileChannel.size();
        ByteBuffer tfliteModel = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        mInterpreter = new Interpreter(tfliteModel, options);
    }

    private float[] getMelSpectrogramFromFile(String wavePath) {
        // 通过 AudioDecoder 直接拿 PCM float[]，避免依赖 WAV 文件格式
        float[] samples = com.aftglw.devapi.core.voice.AudioDecoder.decodeToPcmFloat(wavePath);
        int fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE;
        float[] inputSamples = new float[fixedInputSize];
        int copyLength = Math.min(samples.length, fixedInputSize);
        System.arraycopy(samples, 0, inputSamples, 0, copyLength);
        int cores = Runtime.getRuntime().availableProcessors();
        return mWhisperUtil.getMelSpectrogram(inputSamples, inputSamples.length, cores);
    }

    private String runInference(float[] inputData) {
        Tensor inputTensor = mInterpreter.getInputTensor(0);
        Tensor outputTensor = mInterpreter.getOutputTensor(0);

        // 打印模型所有输入/输出 tensor 信息（便于排查多输入模型）
        Log.d(TAG, "Input tensor count: " + mInterpreter.getInputTensorCount() + ", Output tensor count: " + mInterpreter.getOutputTensorCount());
        for (int i = 0; i < mInterpreter.getInputTensorCount(); i++) {
            Tensor t = mInterpreter.getInputTensor(i);
            int[] s = t.shape();
            StringBuilder sb = new StringBuilder("  Input[").append(i).append("] name=").append(t.name()).append(" shape=[");
            for (int j = 0; j < s.length; j++) { if (j > 0) sb.append(","); sb.append(s[j]); }
            sb.append("] dtype=").append(t.dataType());
            Log.d(TAG, sb.toString());
        }
        for (int i = 0; i < mInterpreter.getOutputTensorCount(); i++) {
            Tensor t = mInterpreter.getOutputTensor(i);
            int[] s = t.shape();
            StringBuilder sb = new StringBuilder("  Output[").append(i).append("] name=").append(t.name()).append(" shape=[");
            for (int j = 0; j < s.length; j++) { if (j > 0) sb.append(","); sb.append(s[j]); }
            sb.append("] dtype=").append(t.dataType());
            Log.d(TAG, sb.toString());
        }

        // 输入：直接构造 direct ByteBuffer（TFLite 原生支持，无需 TensorBuffer 包装）
        int inputSize = inputTensor.shape()[0] * inputTensor.shape()[1] * inputTensor.shape()[2] * Float.BYTES;
        ByteBuffer inputBuf = ByteBuffer.allocateDirect(inputSize);
        inputBuf.order(ByteOrder.nativeOrder());
        for (float v : inputData) {
            inputBuf.putFloat(v);
        }
        inputBuf.rewind();

        // 输出：用 int[][] 接收 token ids（whisper.tflite 模型输出 shape = [1, max_tokens]）
        int outDim0 = outputTensor.shape()[0];
        int outDim1 = outputTensor.shape()[1];
        int[][] outputArray = new int[outDim0][outDim1];

        mInterpreter.run(inputBuf, outputArray);

        int outputLen = outputArray[0].length;
        Log.d(TAG, "output_len: " + outputLen);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < outputLen; i++) {
            int token = outputArray[0][i];
            if (token == mWhisperUtil.getTokenEOT()) break;
            if (token < mWhisperUtil.getTokenEOT()) {
                String word = mWhisperUtil.getWordFromToken(token);
                result.append(word);
            } else {
                String word = mWhisperUtil.getWordFromToken(token);
                Log.d(TAG, "Skipping token: " + token + ", word: " + word);
            }
        }
        return result.toString();
    }
}
