-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-repackageclasses 'x'
-allowaccessmodification
-keep class com.smartcrm.monitor.MonitorService { *; }
-keep class com.smartcrm.monitor.BootReceiver { *; }
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
