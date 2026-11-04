# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Preserve all native JNI methods across the codebase
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve GodMode native bridge, models, streaming, and services
-keep class com.xcloud.godmode.** { *; }
-keepclassmembers class com.xcloud.godmode.** { *; }



# Preserve type annotations and signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

