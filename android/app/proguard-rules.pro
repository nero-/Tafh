# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable

# Hide original source file name in stack traces
-renamesourcefileattribute SourceFile

# === JNI: keep native bridge classes ===
# Every class that declares `external fun` must keep those members, otherwise
# R8 renames/strips them and the .so cannot bind: release builds then fail with
# UnsatisfiedLinkError even though debug works.
-keepclassmembers class dev.nero.tafh.service.terminal.GhosttyBridge {
    *** native*;
}
-keepclassmembers class dev.nero.tafh.service.ssh.NativeSshBridge {
    *** native*;
}
-keepclassmembers class dev.nero.tafh.service.mosh.NativeMoshBridge {
    *** native*;
}
-keepclassmembers class dev.nero.tafh.service.terminal.NativeLocalShellBridge {
    *** native*;
}
-keepclassmembers class dev.nero.tafh.service.backup.NativeBackupBridge {
    *** native*;
}

# === Room: keep entities, DAOs, and database ===
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keepclassmembers class * {
    @androidx.room.Query <methods>;
    @androidx.room.Insert <methods>;
    @androidx.room.Update <methods>;
    @androidx.room.Delete <methods>;
}

# === Compose: keep composable functions ===
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# === Kotlin coroutines ===
-dontwarn kotlinx.coroutines.**

# === Android components ===
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends androidx.appcompat.app.AppCompatActivity

# === Serialization ===
-keepattributes *Annotation*, Signature, ExceptionHandler

# === Enum serialization ===
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}