# Keep models and entities
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep class com.example.core.memory.database.** { *; }
-keep class com.example.core.live.** { *; }

# LibSU
-keep class com.topjohnwu.superuser.** { *; }
