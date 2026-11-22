# Only shrink: no renaming, so crash reports and reflection keep working.
-dontobfuscate
-ignorewarnings
-dontwarn **

# our own code (serialization, JNI for the vocal separation)
-keep class com.ali.files.** { *; }

# libraries that use reflection / native code
-keep class com.yausername.** { *; }
-keep class io.github.junkfood02.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.torproject.** { *; }
-keep class androidx.media3.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-keepclasseswithmembernames class * { native <methods>; }
