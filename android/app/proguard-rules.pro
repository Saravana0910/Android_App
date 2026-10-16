# The JavaScript bridge is resolved reflectively by the WebView, so its
# annotated members must survive shrinking and obfuscation.
-keepclassmembers class com.renault.maeva.bridge.MaevaBridge {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
