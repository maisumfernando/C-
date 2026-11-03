# JNI entry points are looked up by name from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Callbacks invoked from native code.
-keep class com.fernandogodinho.teclado.engine.NativeBridge { *; }
-keep interface com.fernandogodinho.teclado.engine.TokenCallback { *; }
-keep class * implements com.fernandogodinho.teclado.engine.TokenCallback { *; }

# Release builds never log: strip verbose/debug/info logging entirely so no text can end up in logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Model catalog / release JSON parsing uses org.json from the platform.
-dontwarn org.json.**
