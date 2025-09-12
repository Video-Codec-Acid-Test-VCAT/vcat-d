# Keep JNI lookups and the public entry points in the extension.
-keep class com.roncatech.exoplayer.dav1d.** { *; }
-keepclasseswithmembers class * { native <methods>; }
