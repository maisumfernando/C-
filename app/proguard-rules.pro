# JNI entry points are looked up by name from native code.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Callbacks invoked from native code.
-keep class app.escriba.engine.NativeBridge { *; }
-keep interface app.escriba.engine.TokenCallback { *; }
-keep class * implements app.escriba.engine.TokenCallback { *; }
