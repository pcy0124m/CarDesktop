package com.tiger.cardesk

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生 → JS 的单向推送管道。
 * MainActivity 在 WebView 就绪后挂上 sink，GPS / 天气 / 媒体会话等后台数据
 * 都从这里切回 UI 线程 evaluateJavascript 推给网页（网页端定义 window.onXxx 接）。
 */
object JsPipe {
    @Volatile var sink: ((String) -> Unit)? = null
    fun send(js: String) { sink?.invoke(js) }
}

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

    // 真实数据链（GPS / 天气）
    private var gpsStarted = false
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastWeatherAt = 0L

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

    // ------------------------------------------------ 真实数据：GPS 车速 / 海拔 / 天气
    /**
     * 让桌面卡里显示真数据（原装桌面同款体验）：
     *   车速   —— GPS 的 location.speed（m/s ×3.6 = km/h），每秒一帧推给网页
     *   海拔   —— location.altitude，天气卡第 3 格
     *   天气   —— 定位坐标 → Open-Meteo 免费接口（失败换 wttr.in），每 30 分钟刷新
     *
     * 网页端开机会调 startFeeds("1")；定位权限是运行时权限，MainActivity 申请
     * 通过后会再补调一次。没权限就给网页推 onGpsStatus('perm') 提示用户。
     * 注意：速度只认 GPS_PROVIDER 的定位帧 —— 网络定位不带速度（恒为 0），
     * 两路混着推会让车速在行驶中 0↔真值来回跳。
     */
    @JavascriptInterface
    fun startFeeds(unused: String?) {
        val ctx = act.applicationContext
        val granted = Build.VERSION.SDK_INT < 23 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            JsPipe.send("window.onGpsStatus&&window.onGpsStatus('perm')")
            return
        }
        if (!gpsStarted) {
            gpsStarted = true
            ui.post { startGps(ctx) }
        }
        // 立刻拉一次天气（有上次的坐标就直接用），之后由定位回调按 30 分钟节流刷新
        val lat = lastLat
        val lon = lastLon
        if (lat != null && lon != null) fetchWeather(lat, lon)
    }

    /** MainActivity onDestroy 时调用，摘掉定位监听 */
    fun stopFeeds() {
        gpsStarted = false
        try {
            val lm = act.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
        } catch (e: Exception) { }
    }

    private fun startGps(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = ArrayList<String>()
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            if (providers.isEmpty()) {
                JsPipe.send("window.onGpsStatus&&window.onGpsStatus('off')")
                return
            }
            JsPipe.send("window.onGpsStatus&&window.onGpsStatus('wait')")
            // 3 参数版本把回调派发到「调用线程的 Looper」——外面套了 ui.post，所以稳落主线程
            for (p in providers) {
                try { lm.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 1000L else 3000L, 0f, locListener) } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            JsPipe.send("window.onGpsStatus&&window.onGpsStatus('off')")
        }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastLat = l.latitude
            lastLon = l.longitude
            if (l.provider == LocationManager.GPS_PROVIDER) {
                val kmh = (l.speed * 3.6f).toInt()
                JsPipe.send("window.onGpsSpeed&&window.onGpsSpeed(" + (if (kmh < 0) 0 else kmh) + ")")
            }
            JsPipe.send("window.onAlt&&window.onAlt(" + Math.round(l.altitude) + ")")
            val now = System.currentTimeMillis()
            if (now - lastWeatherAt > 30 * 60 * 1000L) {
                lastWeatherAt = now
                fetchWeather(l.latitude, l.longitude)
            }
        }
    }

    /**
     * 拉天气：主用 Open-Meteo（免 key，支持经纬度直查，默认风速单位 km/h），
     * 失败再试 wttr.in 的 JSON 格式。两个都挂在后台线程，结果经 JsPipe 推给网页：
     *   window.onWeather({ok:1, t:温度℃, h:湿度%, d:风向中文, s:风速km/h})
     */
    private fun fetchWeather(lat: Double, lon: Double) {
        Thread {
            // ---- 主：Open-Meteo ----
            try {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m&timezone=auto")
                val j = JSONObject(readAll(u.openConnection() as HttpURLConnection))
                val c = j.getJSONObject("current")
                pushWeather(
                    Math.round(c.getDouble("temperature_2m")).toInt(),
                    Math.round(c.getDouble("relative_humidity_2m")).toInt(),
                    degToCn(c.getDouble("wind_direction_10m")),
                    Math.round(c.getDouble("wind_speed_10m")).toInt()
                )
                return@Thread
            } catch (e: Exception) { }
            // ---- 备：wttr.in ----
            try {
                val u = URL("https://wttr.in/$lat,$lon?format=j1")
                val j = JSONObject(readAll(u.openConnection() as HttpURLConnection))
                val cc = j.getJSONArray("current_condition").getJSONObject(0)
                pushWeather(
                    cc.getString("temp_C").toIntOrNull() ?: 0,
                    cc.getString("humidity").toIntOrNull() ?: 0,
                    compass16ToCn(cc.optString("winddir16Point", "")),
                    cc.getString("windspeedKmph").toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
                JsPipe.send("window.onWeather&&window.onWeather({\"ok\":0})")
            }
        }.start()
    }

    private fun pushWeather(t: Int, h: Int, d: String, s: Int) {
        val o = JSONObject()
        o.put("ok", 1); o.put("t", t); o.put("h", h); o.put("d", d); o.put("s", s)
        JsPipe.send("window.onWeather&&window.onWeather($o)")
    }

    private fun readAll(conn: HttpURLConnection): String {
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 6000
            conn.setRequestProperty("User-Agent", "curl/7.88")
            return conn.inputStream.bufferedReader().use { readText(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readText(r: BufferedReader): String {
        val sb = StringBuilder()
        var line: String? = r.readLine()
        while (line != null) { sb.append(line); line = r.readLine() }
        return sb.toString()
    }

    /** 度数 → 八方位中文（北=0°，顺时针每 45° 一档） */
    private fun degToCn(deg: Double): String {
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val i = (((deg + 22.5) % 360 + 360) % 360 / 45.0).toInt() % 8
        return dirs[i]
    }

    /** wttr.in 的 16 方位缩写（NW/NNW…）→ 中文 */
    private fun compass16ToCn(p: String): String = when (p.uppercase()) {
        "N", "NNW" -> if (p == "N") "北" else "西北"
        "NNE", "NE" -> "东北"
        "ENE", "E" -> "东"
        "ESE", "SE" -> "东南"
        "SSE", "S" -> "南"
        "SSW", "SW" -> "西南"
        "WSW", "W" -> "西"
        "WNW", "NW" -> "西北"
        else -> ""
    }

    // ------------------------------------------------ 定位权限（车速/海拔/天气的前提）
    /** 网页端显示「已授权 ✓ / 去授权」用 */
    @JavascriptInterface
    fun locPerm(): Boolean = try {
        Build.VERSION.SDK_INT < 23 ||
            act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /**
     * 跳到本 App 的系统「应用详情」页，用户在那里把「位置」权限设为允许。
     * 为什么不走系统授权弹窗（requestPermissions）：
     * 实测部分车机 ROM 的弹窗按钮点不动，弹出来就是个死窗，还会把桌面整个卡住；
     * 应用详情页是标准设置界面，所有车机都能正常操作。授完权返回，
     * MainActivity.onResume 会重新调 startFeeds，数据链自动接上。
     */
    @JavascriptInterface
    fun openAppSettings() {
        ui.post {
            try {
                val i = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", act.packageName, null)
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(act, "打不开应用设置：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------ 通知使用权（读音乐信息的前提）
    /** 网页设置面板显示授权状态用：本 App 是否已被授予「通知使用权」 */
    @JavascriptInterface
    fun mediaPerm(): Boolean = try {
        val s = Settings.Secure.getString(act.contentResolver, "enabled_notification_listeners") ?: ""
        s.contains(act.packageName)
    } catch (e: Exception) {
        false
    }

    /** 跳到系统的「通知使用权」设置页，让用户手动允许 */
    @JavascriptInterface
    fun openMediaPerm(unused: String?) {
        ui.post {
            try {
                val i = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(act, "打不开通知使用权设置：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /** 与网页端 CFG_KEY 同名的存储空间，BootReceiver 也读这一份 */
        const val PREF = "cardesk"
    }
}
