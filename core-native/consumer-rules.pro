# JNI entry points are looked up by name.
-keepclasseswithmembernames class com.calegix.mumblevideo.client.** {
    native <methods>;
}
# Generated protobuf-lite messages are read reflectively by the runtime.
-keep class com.calegix.mumblevideo.proto.** { *; }
-dontwarn org.bouncycastle.**
