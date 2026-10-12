# Navigation routes are @Serializable objects; keep their serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.calegix.mumblevideo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# protobuf-lite reads message fields reflectively by name.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# BouncyCastle resolves algorithm implementations by class name.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
