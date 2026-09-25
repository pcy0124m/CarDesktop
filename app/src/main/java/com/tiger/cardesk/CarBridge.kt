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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 暴露给网页的桥接对象（网页里叫 window.CarBridge）。
 *
 * 命名与网页端 [BRIDGE] 段落注释一一对应：
 *   getPref / setPref  —— 设置持久化（SharedPreferences）
 *   media / setVolume  —— 媒体上一首/播放/停止/下一首、音量加减
 *   openApp / hasApp   —— 按包名拉起别的 App、探测某个包装没装
 *   listApps           —— 枚举车机上所有可启动的 App（应用选择器数据源）
 *   mapWindow          —— 魔改高德悬浮地图广播
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

    /**
     * 调媒体音量，返回调整后的 "当前/最大"（如 "6/15"），网页端拿它画音量条。
     * 不用 FLAG_SHOW_UI：系统那个音量条在车机横屏上位置不可控，
     * 桌面自己画 HUD（还能把「静音 0/15」标红），反馈更直观。
     * AudioManager 允许在任意线程调用，这里就不 post 了，保证返回值是调完之后的。
     */
    @JavascriptInterface
    fun setVolume(step: Int): String {
        return try {
            val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (step >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
            volString(am)
        } catch (e: Exception) {
            ""
        }
    }

    /** 当前媒体音量 "当前/最大"，网页端打开面板时可以先显示一次 */
    @JavascriptInterface
    fun getVolume(): String = try {
        volString(act.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
    } catch (e: Exception) {
        ""
    }

    private fun volString(am: AudioManager): String =
        "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"

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

    // ------------------------------------------------ 应用选择器（从已装应用里挑地图）
    /**
     * 枚举车机上所有「有启动入口」的 App，给设置面板的应用选择器用。
     * 返回 JSON 数组字符串：[{"p":"包名","l":"应用名"},...]，按应用名排好序。
     *
     * 只列带 LAUNCHER 入口的（点得开的），排除自己；系统服务类没有入口的自然被过滤掉。
     * targetSdk 30+ 需要 QUERY_ALL_PACKAGES / <queries> 权限，Manifest 已声明。
     *
     * 注意：本方法跑在 WebView 的 JavaBridge 线程（不是主线程），扫包再慢也不卡界面，
     * 网页端拿到字符串后自行 JSON.parse。
     */
    @JavascriptInterface
    fun listApps(): String {
        return try {
            val ctx = act.applicationContext
            val pm = ctx.packageManager
            val self = ctx.packageName
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val ris = pm.queryIntentActivities(query, 0)
            val seen = HashSet<String>()
            val pairs = ArrayList<Pair<String, String>>()
            for (ri in ris) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == self || pkg.isBlank() || !seen.add(pkg)) continue
                val label = try { ri.loadLabel(pm).toString().trim() } catch (e: Exception) { "" }
                if (label.isEmpty()) continue
                pairs.add(Pair(pkg, label))
            }
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            pairs.sortWith { a, b -> collator.compare(a.second, b.second) }
            val arr = JSONArray()
            for (p in pairs) {
                val o = JSONObject()
                o.put("p", p.first)
                o.put("l", p.second)
                arr.put(o)
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
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

    // ------------------------------------------------ 悬浮地图（魔改高德广播协议）
    /**
     * 让魔改版高德把自己的地图以悬浮小窗显示在桌面右侧地图块的位置。
     *
     * 原理（氢桌面同款思路，不需要 root）：
     *   魔改版高德内部注册了广播接收器，收到
     *     <前缀>.showmap  （extra: x / y / w / h，单位像素）
     *   就以 TYPE_APPLICATION_OVERLAY 悬浮窗把自己的地图画到指定矩形；
     *     <前缀>.closemap
     *   则关掉。悬浮窗权限（显示在其他应用上层）授给【高德】，
     *   我们只负责发广播和算坐标。
     *
     * 坐标由网页端用 getBoundingClientRect() 算好传过来（WebView 全屏沉浸，
     * 网页坐标 = 屏幕坐标）；分屏比例/旋转变化时网页端会重新调一次。
     * setPackage 定向广播，避免误触发其他 App 的同名接收器。
     */
    @JavascriptInterface
    fun mapWindow(show: Boolean, x: Int, y: Int, w: Int, h: Int, pkg: String, actionPrefix: String) {
        val p = pkg.trim()
        val prefix = actionPrefix.trim().ifBlank { "com.autonavi.plus" }
        val action = if (show) "$prefix.showmap" else "$prefix.closemap"
        try {
            val i = Intent(action)
            if (p.isNotBlank()) i.setPackage(p)
            if (show) {
                /* 网页端 getBoundingClientRect() 给的是 CSS 像素，悬浮窗用的是物理像素。
                 * 实测（1600x900 / density 1.5 车机）：不乘密度，地图会缩在屏幕左上
                 * 2/3 处 —— 窗口出现在 x=403，而正确位置是 401*1.5≈602。 */
                val d = act.resources.displayMetrics.density
                val ix = Math.round(x * d)
                val iy = Math.round(y * d)
                val iw = Math.round(w * d)
                val ih = Math.round(h * d)
                i.putExtra("x", ix).putExtra("y", iy)
                i.putExtra("w", iw).putExtra("h", ih)
                /* 实测这套魔改包对 w/h 两个 extra 不一定都认（窗口用了自己的默认尺寸），
                 * 把常见别名一起塞上 —— 接收方只读自己认识的键，多余的会被忽略。 */
                i.putExtra("left", ix).putExtra("top", iy)
                i.putExtra("width", iw).putExtra("height", ih)
            }
            act.sendBroadcast(i)
        } catch (e: Exception) {
            ui.post { Toast.makeText(act, "悬浮地图广播失败：${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        /** 与网页端 CFG_KEY 同名的存储空间，BootReceiver 也读这一份 */
        const val PREF = "cardesk"
    }
}
