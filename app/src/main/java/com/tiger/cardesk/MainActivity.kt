package com.tiger.cardesk

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * 车机桌面主体：一个全屏横屏的 WebView，装 assets/index.html。
 *
 * 为什么用 WebView 而不是纯原生 UI：
 *   1) 界面改一行 HTML 就能出新版，不用重装 APK；
 *   2) 车机屏幕尺寸差异极大，Web 的百分比/vh 布局天生自适应；
 *   3) 本工程零第三方依赖，APK 很小，老车机上启动快。
 * 代价：极老的车机（Android 4.x）WebView 内核旧，所以 index.html 是
 *       按 Chrome 33 的标准写的（不用 CSS 变量 / flex gap / grid / ES6）。
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var bridge: CarBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 桌面常亮，熄屏由车机自己的电源策略管
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#E3E5E9"))

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true          // 网页端 localStorage 兜底要用
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.textZoom = 100
        s.cacheMode = WebSettings.LOAD_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 高德 JS API 内部会发 http 请求（瓦片/服务），页面是 https 假域名，
            // 不放开混合内容会被拦掉
            s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        web.isLongClickable = false          // 车里别弹文字选择菜单
        web.isHapticFeedbackEnabled = false
        web.setOnLongClickListener { true }
        bridge = CarBridge(this)
        web.addJavascriptInterface(bridge, "CarBridge")

        // 原生 → JS 推送管道：GPS/天气/媒体会话的数据都从这条道进网页。
        // evaluateJavascript 必须在 UI 线程调，runOnUiThread 从任意线程过来都安全。
        JsPipe.sink = { js ->
            runOnUiThread {
                if (::web.isInitialized) {
                    try { web.evaluateJavascript(js, null) } catch (e: Exception) { }
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // 桌面内的跳转全部留在 WebView，不外抛浏览器
                return false
            }
        }

        setContentView(web)
        loadDesktop()

        // 定位权限（读车速/海拔/天气的前提）：API 23+ 运行时申请，弹一次系统对话框；
        // 授权结果见 onRequestPermissionsResult。21/22 安装即授予，不用弹。
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOC
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC) bridge.startFeeds(null)   // 授权了就立刻把数据链跑起来
    }

    private fun loadDesktop() {
        val html = try {
            assets.open(ASSET_HTML).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body style='background:#12151a;color:#e1251b;font:20px sans-serif;padding:40px'>" +
                "assets/$ASSET_HTML 缺失：构建时没把根目录的 index.html 复制进来</body></html>"
        }
        // 用 https 假域名当 baseURL，原因：
        //   1) file:// 的 origin 为空，高德 JS API 会以 INVALID_USER_DOMAIN 拒绝
        //   2) https origin 下 localStorage 行为最接近真实浏览器
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统弹窗（音量条等）会打断沉浸态，重新拿回焦点时补一次
        if (hasFocus) goFullscreen()
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
        web.onResume()
        // 页面回来时补一把数据链（startFeeds 自带幂等保护，重复调不会叠加监听）
        if (::bridge.isInitialized) bridge.startFeeds(null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 桌面常驻：返回键不退出，避免车上误触把桌面关掉。
        // 真要退出/换桌面，走设置面板里的「系统设置」。
        Toast.makeText(this, "车机桌面常驻运行（设置 → 系统设置可退出）", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.stopFeeds()
        JsPipe.sink = null
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val ASSET_HTML = "index.html"
        private const val BASE_URL = "https://cardesk.local/"
        private const val REQ_LOC = 1
    }
}
