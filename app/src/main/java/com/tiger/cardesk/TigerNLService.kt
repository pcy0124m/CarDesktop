package com.tiger.cardesk

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * 媒体信息读取：通知使用权服务（NotificationListenerService）。
 *
 * 这是「原装桌面也能显示歌名」的同款原理：
 *   车机上所有播放器（酷我 / QQ音乐 / 网易云 / 抖音…）在通知栏挂媒体通知时，
 *   都会同时注册一个 MediaSession；拥有「通知使用权」的 App 就能通过
 *   MediaSessionManager.getActiveSessions() 拿到【正在播放的歌名 / 歌手 / 播放状态】，
 *   完全不用对每个播放器做私有适配，也不碰通知正文内容（只读媒体会话）。
 *
 * 授权方式：系统设置 → 通知使用权（Notification access）→ 允许「车机桌面」。
 * 没授权时本服务根本不会被绑定，桌面顶栏显示「未授权读取媒体」。
 *
 * 推送时机（都在后台线程算好，经 [JsPipe] 切 UI 线程 evaluateJavascript）：
 *   - 服务绑定成功后 0.3s / 1.5s / 4s 各推一次（兜住启动瞬间）
 *   - 任意通知 posted / removed 后 500ms 推一次（播放器切歌必发通知，够灵敏）
 */
class TigerNLService : NotificationListenerService() {

    private val ui = Handler(Looper.getMainLooper())
    private val pushRun = Runnable { pushMedia() }

    override fun onListenerConnected() {
        schedulePush(300)
        schedulePush(1500)
        schedulePush(4000)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = schedulePush(500)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = schedulePush(500)

    override fun onDestroy() {
        ui.removeCallbacks(pushRun)
        super.onDestroy()
    }

    private fun schedulePush(delay: Long) {
        ui.removeCallbacks(pushRun)
        ui.postDelayed(pushRun, delay)
    }

    /** 找出「正在播放」的媒体会话，把歌名/歌手/App 名/状态推给网页 */
    private fun pushMedia() {
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = mgr.getActiveSessions(ComponentName(this, TigerNLService::class.java))

            var picked: MediaController? = null
            var playing = false
            for (c in list) {
                if (c.metadata == null) continue
                val st = c.playbackState?.state ?: 0
                val isPlaying = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                if (isPlaying) { picked = c; playing = true; break }
                if (picked == null) picked = c
            }
            if (picked == null || picked.metadata == null) {
                JsPipe.send("window.onMedia&&window.onMedia({\"ok\":0})")
                return
            }

            val md = picked.metadata!!
            var t = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            var a = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (a.isNullOrBlank()) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            if (a.isNullOrBlank()) a = md.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            a = a ?: ""
            // 有些播放器（酷我常见）把「歌名 - 歌手」整串塞 title，没给独立歌手字段就拆一下
            if (a.isBlank() && t.contains(" - ")) {
                val parts = t.split(" - ")
                t = parts[0].trim()
                a = parts.subList(1, parts.size).joinToString(" - ").trim()
            }
            val app = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(picked.packageName, 0)
                ).toString()
            } catch (e: Exception) { "" }

            val o = JSONObject()
            o.put("ok", 1)
            o.put("t", t)
            o.put("a", a)
            o.put("app", app)
            o.put("p", playing)
            JsPipe.send("window.onMedia&&window.onMedia($o)")
        } catch (e: SecurityException) {
            // 通知使用权被用户关掉了
            JsPipe.send("window.onMedia&&window.onMedia({\"ok\":-1})")
        } catch (e: Exception) {
            // 服务解绑瞬间等场景，静默跳过，等下一次推送兜底
        }
    }
}
