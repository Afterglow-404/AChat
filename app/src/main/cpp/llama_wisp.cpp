#include <jni.h>
#include <string>
#include <vector>

// llama.cpp API 头文件（需要 llama.cpp 源码就位后取消注释）
// #include "llama.h"
// #include "common.h"
// #include "sampling.h"

// ============================================================
// JNI 包装层 — 将 llama.cpp C API 暴露给 Kotlin
// ============================================================
//
// 设计说明：
// - 每个模型实例对应一个 Java 侧的 LlamaEngine 对象
// - Java 持有 long 类型的 nativeHandle，指向 C++ 侧的 ModelContext
// - 所有资源在 close() 时释放
//
// 编译前提：
// 1. 将 llama.cpp 源码放在项目根目录 llama.cpp/ 下
// 2. CMakeLists.txt 中启用 add_subdirectory
// 3. NDK 版本 r27+

struct ModelContext {
    // llama_model* model = nullptr;
    // llama_context* ctx = nullptr;
    // common_params params;
    bool loaded = false;
    std::string model_path;
};

// ==================== JNI 函数实现 ====================

extern "C" JNIEXPORT jlong JNICALL
Java_com_aftglw_devapi_core_ai_LlamaEngine_nativeLoad(
    JNIEnv* env, jobject /*this*/, jstring model_path) {

    auto* ctx = new ModelContext();
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    ctx->model_path = path;
    env->ReleaseStringUTFChars(model_path, path);

    // TODO: 实际加载 llama.cpp 模型
    // common_params params;
    // params.model = ctx->model_path;
    // params.n_ctx = 2048;
    // params.n_batch = 512;
    // params.n_threads = 4;
    // 
    // ctx->model = llama_load_model_from_file(params.model.c_str(), params.to_model_builder());
    // if (!ctx->model) { delete ctx; return 0; }
    // 
    // ctx->ctx = llama_new_context_with_model(ctx->model, params.to_context_builder());
    // if (!ctx->ctx) { llama_free_model(ctx->model); delete ctx; return 0; }
    // 
    // ctx->loaded = true;

    // 桩代码：模拟加载成功
    ctx->loaded = true;
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aftglw_devapi_core_ai_LlamaEngine_nativeIsLoaded(
    JNIEnv* env, jobject /*this*/, jlong handle) {
    auto* ctx = reinterpret_cast<ModelContext*>(handle);
    return ctx ? ctx->loaded : false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aftglw_devapi_core_ai_LlamaEngine_nativeGenerate(
    JNIEnv* env, jobject /*this*/, jlong handle,
    jstring prompt, jint max_tokens, jint temperature) {

    auto* ctx = reinterpret_cast<ModelContext*>(handle);
    if (!ctx || !ctx->loaded) {
        return env->NewStringUTF("[模型未加载]");
    }

    // TODO: 实际推理
    // 1. tokenize prompt
    // 2. 循环采样 generate
    // 3. detokenize output
    // 4. 检测停止条件

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string result = "[llama.cpp 推理引擎就绪，等待模型文件加载]\n";
    result += "提示词长度: ";
    result += std::to_string(strlen(prompt_str));
    result += " 字符\n最大 tokens: ";
    result += std::to_string(max_tokens);
    result += "\n温度: ";
    result += std::to_string(temperature / 100.0f);

    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aftglw_devapi_core_ai_LlamaEngine_nativeClose(
    JNIEnv* env, jobject /*this*/, jlong handle) {
    auto* ctx = reinterpret_cast<ModelContext*>(handle);
    if (ctx) {
        // TODO: 释放 llama.cpp 资源
        // if (ctx->ctx) llama_free(ctx->ctx);
        // if (ctx->model) llama_free_model(ctx->model);
        delete ctx;
    }
}
