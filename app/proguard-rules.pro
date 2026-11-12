# WebView bridges are called by name from JavaScript
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-dontwarn com.google.android.gms.**
