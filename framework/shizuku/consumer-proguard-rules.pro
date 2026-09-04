-keepclassmembers class com.android.geto.framework.shizuku.UserService {
    public <init>(...);
}

# Keep AIDL files
-keep class android.content.pm.IPackageManager** { *; }
-keep class android.app.IActivityManager** { *; }
-keep class com.android.geto.framework.shizuku.IUserService** { *; }
