# Pontes JS das WebViews
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Terminal do Termux (JNI)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
# Modelos serializados
-keep class dev.cueopen.app.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontobfuscate
-keep class com.caverock.androidsvg.** { *; }
