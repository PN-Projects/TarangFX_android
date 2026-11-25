# JNI: native code calls these by name.
-keep class com.pnprojects.tarangfx.nativebridge.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.pnprojects.tarangfx.**$$serializer { *; }
-keepclassmembers class com.pnprojects.tarangfx.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
