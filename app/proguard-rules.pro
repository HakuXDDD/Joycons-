# The privileged process instantiates this class by name through Shizuku.
-keep class com.joymerge.quest.privileged.JoyMergeUserService { *; }
-keep class com.joymerge.quest.privileged.** { *; }

# JNI entry points are resolved by name from libjoymerge.so.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.joymerge.quest.nativebridge.NativeBridge { *; }

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
