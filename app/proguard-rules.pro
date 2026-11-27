# Pontes JS das WebViews
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# Terminal do Termux (JNI)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
# Modelos serializados
-keep class dev.fluwy.app.data.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# SnakeYAML SafeConstructor rejeita tags JavaBean; java.beans não existe no Android e essa introspecção não é usada.
-dontwarn java.beans.**
-dontobfuscate
-keep class com.caverock.androidsvg.** { *; }

# Voz Phonon (JNA)
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.fluwy.app.voice.phonon.** { *; }
-dontwarn java.awt.**
