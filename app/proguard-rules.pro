# Navigation routes are @Serializable objects; keep their serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.calegix.mumblevideo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
