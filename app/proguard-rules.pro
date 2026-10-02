# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class app.caret.latin.dictionary.Dictionary
-keep class app.caret.latin.NgramContext
-keep class app.caret.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# FUTO Swipe library: native code creates and reads these by name
-keep class org.futo.ml.inference.** { *; }

# optional classes that androidx.window looks up at runtime
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
