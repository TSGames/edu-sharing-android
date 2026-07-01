# Keep the JavaScript bridge intact — R8 must not rename/strip @JavascriptInterface methods
# that the WebView SPA calls by name (window.eduBridge.*).
-keepclassmembers class org.edusharing.app.EduBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# WebView with JS interface (AndroidX/AGP already bundle sensible WebView rules).
-keepattributes JavascriptInterface
