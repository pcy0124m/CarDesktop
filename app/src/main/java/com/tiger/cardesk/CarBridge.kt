package com.tiger.cardesk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 暴露给网页的桥接对象（网页里叫 window.CarBridge）。
 *
 * 命名与网页端 [BRIDGE] 段落注释一一对应：
 *   getPref / setPref  —— 设置持久化（SharedPreferences）
 *   media / setVolume  —— 媒体上一首/播放/停止/下一首、音量加减
 *   openApp / hasApp   —— 按包名拉起别的 App、探测某个包装没装
 *   toast              —— 原生 Toast（网页的提示气泡在车机上太小，重要提示走这里）
 *
 * 注意：所有 @JavascriptInterface 方法都在 WebView 的 JS 线程被调用，
 * 凡是碰 UI 或系统服务的动作一律 post 到主线程，否则在部分 ROM 上会直接崩。
 */
class CarBridge(private val act: Activity) {

    private val prefs = act.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- 配置存取
    @JavascriptInterface
    fun getPref(key: String): String = prefs.getString(key, "") ?: ""

    @JavascriptInterface
    fun setPref(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ---------------------------------------------------------------- 提示
    @JavascriptInterface
    fun toast(msg: String) {
        ui.post { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- 媒体控制
    /**
     * 用系统媒体键去控播放器（酷我、QQ音乐、网易云都吃这一套），
     * 比对每个 App 做私有 API 适配可靠得多。
     */
    @JavascriptInterface
    fun media(action: String) {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        ui.post {
            try {
                val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            } catch (e: Exception) {
                Toast.makeText(act, "媒体控制失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun setVolume(step: Int) {
        ui.post {
            try {
                val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (step >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            } catch (e: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- 拉起别的 App
    /**
     * 判断某个包是否真的装了。网页端用它来：
     *   1) 打开设置面板时自动探测车机上的地图 App（省得手动敲包名）
     *   2) 点地图区之前先确认包名对不对，不对就直接把人送到设置里改
     * 注意：targetSdk 30+ 受「包可见性」限制，必须在 Manifest 里声明 <queries>，
     *       否则这里对第三方 App 永远返回 false。
     */
    @JavascriptInterface
    fun hasApp(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            act.applicationContext.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun openApp(pkg: String): Boolean {
        val ctx = act.applicationContext
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            ui.post { Toast.makeText(act, "未安装：$pkg", Toast.LENGTH_SHORT).show() }
            return false
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ui.post {
            try {
                ctx.startActivity(launch)
            } catch (e: Exception) {
                Toast.makeText(act, "打不开 $pkg", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    companion object {
        /** 与网页端 CFG_KEY 同名的存储空间，BootReceiver 也读这一份 */
        const val PREF = "cardesk"
    }
}
