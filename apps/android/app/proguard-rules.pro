# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK directory.

# ONNX Runtime — keep all native JNI bindings
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Coil
-dontwarn okhttp3.**
-dontwarn okio.**

# WorkManager — SyncWorker must be accessible by class name via reflection
-keep class dev.janakhpon.monocr.engine.SyncWorker { public <init>(...); }

# Room — keep entity and DAO classes used via reflection by the generated code
-keep class dev.janakhpon.monocr.data.** { *; }
