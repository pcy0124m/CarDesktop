package com.tiger.cardesk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * 开机自启：车机通电后自动拉起桌面。
 *
 * 是否真的自启，取决于设置面板里「开机自启」开关 ——
 * 网页把配置存成 JSON 写进 SharedPreferences("cardesk") 的 "cardesk_cfg"，
 * 这里解析同一个字段，保证「界面上的开关」和「真实行为」一致。
 *
 * Android 10+ 后台启动 Activity 会被限制，但车机跑的是 Android 8，
 * startActivity 从广播里走是允许的。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val raw = ctx.getSharedPreferences(CarBridge.PREF, Context.MODE_PRIVATE)
            .getString(CFG_KEY, "") ?: ""

        val enabled = try {
            if (raw.isBlank()) true else JSONObject(raw).optBoolean("boot", true)
        } catch (e: Exception) {
            true // 配置损坏时按「默认开启」处理，别让用户以为自启坏了
        }
        if (!enabled) return

        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 部分车机 ROM 拦后台启动，失败就算了，不影响手动点图标
        }
    }

    companion object {
        /** 与网页端 CFG_KEY / CarBridge 存储空间保持一致 */
        private const val CFG_KEY = "cardesk_cfg"
    }
}
