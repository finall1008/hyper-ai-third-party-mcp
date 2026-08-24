-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keep class io.github.finall1008.xiaoaimcp.BridgeApplication { *; }
-keep class io.github.finall1008.xiaoaimcp.ui.** { *; }

# Keep JNI entry points even if dependency consumer rules are not merged by a future build setup.
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}
