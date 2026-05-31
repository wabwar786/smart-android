# ProGuard rules - Reverse engineering se bachao
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# Rename everything
-repackageclasses 'a'
-allowaccessmodification
-optimizations !code/simplification/arithmetic

# Keep main classes
-keep class com.smartcrm.monitor.MonitorService { *; }
-keep class com.smartcrm.monitor.BootReceiver { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# String encryption simulation
-adaptclassstrings
-adaptresourcefilenames
-adaptresourcefilecontents

# Remove logs in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
