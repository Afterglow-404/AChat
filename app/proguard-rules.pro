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
