# WebView 里用 @JavascriptInterface 暴露的方法，混淆后名字会变，网页就调不到了。
# 虽然当前 isMinifyEnabled = false，但留着能防以后有人打开混淆踩坑。
-keepclassmembers class com.tiger.cardesk.CarBridge {
    public *;
}
-keepattributes *Annotation*
