# The Rust core calls nothing back, but its JNI symbols are bound by
# class and method name.
-keep class com.spotifast.android.Native { *; }

# Optional TLS providers OkHttp (through Coil) looks for at runtime.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
