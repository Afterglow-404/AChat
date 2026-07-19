# Add project specific ProGuard rules here.

# --- Compose ---
-keep class androidx.compose.** { *; }

# --- SnakeYAML (CharacterImporter) ---
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# --- ONNX Runtime ---
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- Kotlinx Serialization (if used) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# --- Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# --- App models (kept so SharedPreferences JSON deserialization works) ---
-keep class com.aftglw.devapi.model.** { *; }

# --- Agent / Tools (kept for reflection in ToolRegistry) ---
-keep class com.aftglw.devapi.core.tools.** { *; }
-keep class com.aftglw.devapi.core.ai.** { *; }

# --- Mood detector ---
-keep class com.aftglw.devapi.core.mood.** { *; }

# --- Keep R classes ---
-keepclassmembers class **.R$* { public static <fields>; }

# --- Room ---
# Room 的实体类与 DAO 必须保留：编译期 KSP 生成的代码会反射访问实体字段
-keep class com.aftglw.devapi.core.storage.room.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- EncryptedSharedPreferences (androidx.security.crypto) ---
# SecureKeyStore 使用 EncryptedSharedPreferences，内部依赖 Tink 与 JSON 反射
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.crypto.**
-dontwarn com.google.crypto.tink.**

# --- Coil (image loader，使用反射加载模型) ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- LlamaEngine (JNI 桥接，native 方法名不能被混淆) ---
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.aftglw.devapi.core.ai.LlamaEngine { *; }

# --- WorkManager (后台任务，反射实例化 Worker) ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# --- TensorFlow Lite (引用了 com.google.auto.value.AutoValue，但该库未打包) ---
-dontwarn com.google.auto.value.AutoValue
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn org.tensorflow.lite.support.**

# --- sherpa-onnx STT (JNI 调用，需要保留 native 方法与 Kotlin API 类) ---
-keep class com.k2fsa.sherpa.onnx.** { *; }
# --- 旧版 tflite Whisper 残留（已废弃，保留 keep 避免编译期引用报错） ---
-keep class com.aftglw.devapi.core.voice.whisper.** { *; }
# --- 讯飞 RTASR Provider（HmacSHA1/MD5/Base64 反射调用） ---
-keep class com.aftglw.devapi.core.voice.XfyunSttProvider { *; }
-keep class com.aftglw.devapi.core.voice.LocalSenseVoiceSttProvider { *; }

# --- 隐私：release 构建移除低级别日志（Log.v / Log.d），避免泄露请求细节 ---
# Log.i / Log.w / Log.e 保留用于关键诊断，已审计确认不输出 API Key 或请求体
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
