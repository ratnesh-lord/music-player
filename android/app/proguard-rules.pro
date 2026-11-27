# Media3, Room, Compose, OkHttp and kotlinx.serialization ship their own consumer rules.
# Keep @Serializable classes' generated serializers (belt and braces for R8 full mode).
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
