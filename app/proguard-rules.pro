# Keep data classes used by Room
-keep class com.motoeye.remote.macro.** { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
