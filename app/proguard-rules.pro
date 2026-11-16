# Default rules.

# 1.0.0 stability: JavaScript bridges. WebView finds bridge methods by name and by the
# @JavascriptInterface annotation at runtime, so both must survive R8 in release builds.
# (The default proguard-android-optimize.txt keeps annotated methods too; this makes it explicit.)
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.khalid.browser.PageBridge { *; }
