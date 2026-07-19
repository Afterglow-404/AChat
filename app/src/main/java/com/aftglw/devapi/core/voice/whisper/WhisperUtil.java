package com.aftglw.devapi.core.voice.whisper;

import static java.lang.Math.cos;
import static java.lang.Math.log10;
import static java.lang.Math.sin;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whisper 工具类：Mel 频谱图计算 + 词表加载 + token 解码。
 *
 * 移植自 vilassn/whisper_android（基于 nyadla-sys/whisper.tflite）。
 * 纯 Java 实现，无 Android 依赖（除 Log），便于维护。
 *
 * 关键常量：
 * - WHISPER_SAMPLE_RATE = 16000（Whisper 固定 16kHz 输入）
 * - WHISPER_N_MEL = 80（Mel 滤波器组数）
 * - WHISPER_CHUNK_SIZE = 30（30 秒一个 chunk）
 * - WHISPER_MEL_LEN = 3000（30s × 100Hz = 3000 帧）
 */
public class WhisperUtil {
    private static final String TAG = "WhisperUtil";
    public static final int WHISPER_SAMPLE_RATE = 16000;
    public static final int WHISPER_N_FFT = 400;
    public static final int WHISPER_N_MEL = 80;
    public static final int WHISPER_HOP_LENGTH = 160;
    public static final int WHISPER_CHUNK_SIZE = 30;
    public static final int WHISPER_MEL_LEN = 3000;
    private final WhisperVocab vocab = new WhisperVocab();
    private final WhisperFilter filters = new WhisperFilter();
    private final WhisperMel mel = new WhisperMel();

    public int getTokenTranslate() { return vocab.tokenTRANSLATE; }
    public int getTokenTranscribe() { return vocab.tokenTRANSCRIBE; }
    public int getTokenEOT() { return vocab.tokenEOT; }
    public int getTokenSOT() { return vocab.tokenSOT; }
    public int getTokenPREV() { return vocab.tokenPREV; }
    public int getTokenSOLM() { return vocab.tokenSOLM; }
    public int getTokenNOT() { return vocab.tokenNOT; }
    public int getTokenBEG() { return vocab.tokenBEG; }
    public String getWordFromToken(int token) { return vocab.tokenToWord.get(token); }

    /** Load filters and vocab data from pre-generated filters_vocab_multilingual.bin file */
    public boolean loadFiltersAndVocab(boolean multilingual, String vocabPath) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(vocabPath));
        ByteBuffer vocabBuf = ByteBuffer.wrap(bytes);
        vocabBuf.order(ByteOrder.nativeOrder());
        Log.d(TAG, "Vocab file size: " + vocabBuf.limit());

        int magic = vocabBuf.getInt();
        if (magic != 0x5553454e) {
            Log.e(TAG, "Invalid vocab file (bad magic: " + magic + "), " + vocabPath);
            return false;
        }

        filters.nMel = vocabBuf.getInt();
        filters.nFft = vocabBuf.getInt();
        Log.d(TAG, "n_mel:" + filters.nMel + ", n_fft:" + filters.nFft);
        byte[] filterData = new byte[filters.nMel * filters.nFft * Float.BYTES];
        vocabBuf.get(filterData, 0, filterData.length);
        ByteBuffer filterBuf = ByteBuffer.wrap(filterData);
        filterBuf.order(ByteOrder.nativeOrder());
        filters.data = new float[filters.nMel * filters.nFft];
        for (int i = 0; filterBuf.hasRemaining(); i++) {
            filters.data[i] = filterBuf.getFloat();
        }

        int nVocab = vocabBuf.getInt();
        Log.d(TAG, "nVocab: " + nVocab);
        for (int i = 0; i < nVocab; i++) {
            int len = vocabBuf.getInt();
            byte[] wordBytes = new byte[len];
            vocabBuf.get(wordBytes, 0, wordBytes.length);
            String word = new String(wordBytes);
            vocab.tokenToWord.put(i, word);
        }

        int nVocabAdditional;
        if (!multilingual) {
            nVocabAdditional = vocab.nVocabEnglish;
        } else {
            nVocabAdditional = vocab.nVocabMultilingual;
            vocab.tokenEOT++;
            vocab.tokenSOT++;
            vocab.tokenPREV++;
            vocab.tokenSOLM++;
            vocab.tokenNOT++;
            vocab.tokenBEG++;
        }
        for (int i = nVocab; i < nVocabAdditional; i++) {
            String word;
            if (i > vocab.tokenBEG) {
                word = "[_TT_" + (i - vocab.tokenBEG) + "]";
            } else if (i == vocab.tokenEOT) {
                word = "[_EOT_]";
            } else if (i == vocab.tokenSOT) {
                word = "[_SOT_]";
            } else if (i == vocab.tokenPREV) {
                word = "[_PREV_]";
            } else if (i == vocab.tokenNOT) {
                word = "[_NOT_]";
            } else if (i == vocab.tokenBEG) {
                word = "[_BEG_]";
            } else {
                word = "[_extra_token_" + i + "]";
            }
            vocab.tokenToWord.put(i, word);
        }
        return true;
    }

    /** nSamples size => WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE => 480000 */
    public float[] getMelSpectrogram(float[] samples, int nSamples, int nThreads) {
        int fftSize = WHISPER_N_FFT;
        int fftStep = WHISPER_HOP_LENGTH;
        mel.nMel = WHISPER_N_MEL;
        mel.nLen = nSamples / fftStep;
        mel.data = new float[mel.nMel * mel.nLen];
        float[] hann = new float[fftSize];
        for (int i = 0; i < fftSize; i++) {
            hann[i] = (float) (0.5 * (1.0 - cos(2.0 * Math.PI * i / fftSize)));
        }
        int nFft = 1 + fftSize / 2;

        // 多线程 Mel 计算（手机多核 CPU 友好）
        List<Thread> workers = new ArrayList<>();
        for (int iw = 0; iw < nThreads; iw++) {
            final int ith = iw;
            Thread thread = new Thread(() -> {
                Log.d(TAG, "Thread " + ith + " started.");
                float[] fftIn = new float[fftSize];
                Arrays.fill(fftIn, 0.0f);
                float[] fftOut = new float[fftSize * 2];
                for (int i = ith; i < mel.nLen; i += nThreads) {
                    int offset = i * fftStep;
                    for (int j = 0; j < fftSize; j++) {
                        if (offset + j < nSamples) {
                            fftIn[j] = hann[j] * samples[offset + j];
                        } else {
                            fftIn[j] = 0.0f;
                        }
                    }
                    fft(fftIn, fftOut);
                    for (int j = 0; j < fftSize; j++) {
                        fftOut[j] = fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1];
                    }
                    for (int j = 1; j < fftSize / 2; j++) {
                        fftOut[j] += fftOut[fftSize - j];
                    }
                    for (int j = 0; j < mel.nMel; j++) {
                        double sum = 0.0;
                        for (int k = 0; k < nFft; k++) {
                            sum += (fftOut[k] * filters.data[j * nFft + k]);
                        }
                        if (sum < 1e-10) {
                            sum = 1e-10;
                        }
                        sum = log10(sum);
                        mel.data[j * mel.nLen + i] = (float) sum;
                    }
                }
            });
            workers.add(thread);
            thread.start();
        }
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        double mmax = -1e20;
        for (int i = 0; i < mel.nMel * mel.nLen; i++) {
            if (mel.data[i] > mmax) {
                mmax = mel.data[i];
            }
        }
        mmax -= 8.0;
        for (int i = 0; i < mel.nMel * mel.nLen; i++) {
            if (mel.data[i] < mmax) {
                mel.data[i] = (float) mmax;
            }
            mel.data[i] = (float) ((mel.data[i] + 4.0) / 4.0);
        }
        return mel.data;
    }

    private void dft(float[] input, float[] output) {
        int inSize = input.length;
        for (int k = 0; k < inSize; k++) {
            float re = 0.0f;
            float im = 0.0f;
            for (int n = 0; n < inSize; n++) {
                float angle = (float) (2 * Math.PI * k * n / inSize);
                re += input[n] * cos(angle);
                im -= input[n] * sin(angle);
            }
            output[k * 2 + 0] = re;
            output[k * 2 + 1] = im;
        }
    }

    private void fft(float[] input, float[] output) {
        int inSize = input.length;
        if (inSize == 1) {
            output[0] = input[0];
            output[1] = 0.0f;
            return;
        }
        if (inSize % 2 == 1) {
            dft(input, output);
            return;
        }
        float[] even = new float[inSize / 2];
        float[] odd = new float[inSize / 2];
        int indxEven = 0;
        int indxOdd = 0;
        for (int i = 0; i < inSize; i++) {
            if (i % 2 == 0) {
                even[indxEven] = input[i];
                indxEven++;
            } else {
                odd[indxOdd] = input[i];
                indxOdd++;
            }
        }
        float[] evenFft = new float[inSize];
        float[] oddFft = new float[inSize];
        fft(even, evenFft);
        fft(odd, oddFft);
        for (int k = 0; k < inSize / 2; k++) {
            float theta = (float) (2 * Math.PI * k / inSize);
            float re = (float) cos(theta);
            float im = (float) -sin(theta);
            float reOdd = oddFft[2 * k + 0];
            float imOdd = oddFft[2 * k + 1];
            output[2 * k + 0] = evenFft[2 * k + 0] + re * reOdd - im * imOdd;
            output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd;
            output[2 * (k + inSize / 2) + 0] = evenFft[2 * k + 0] - re * reOdd + im * imOdd;
            output[2 * (k + inSize / 2) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd;
        }
    }

    private static class WhisperVocab {
        int tokenEOT = 50256;
        int tokenSOT = 50257;
        int tokenPREV = 50360;
        int tokenSOLM = 50361;
        int tokenNOT = 50362;
        int tokenBEG = 50363;
        final int tokenTRANSLATE = 50358;
        final int tokenTRANSCRIBE = 50359;
        final int nVocabEnglish = 51864;
        final int nVocabMultilingual = 51865;
        Map<Integer, String> tokenToWord = new HashMap<>();
    }

    private static class WhisperFilter {
        int nMel = 0;
        int nFft = 0;
        float[] data;
    }

    private static class WhisperMel {
        int nLen = 0;
        int nMel = 0;
        float[] data;
    }
}
