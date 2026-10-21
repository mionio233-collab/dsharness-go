package io.github.hakunm.deepseekharness

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hakunm.deepseekharness.agent.PhoneAgentStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 对话生成结束提醒：应用不在前台时，本机会话生成完成/失败后发系统通知。
 * 点按通知回到应用并打开对应会话。
 */
object ChatNotifications {
    private const val CHANNEL = "chat_results"
    private const val EXTRA_LOCAL = "chat_result_local_session"


    /** 用户正在看应用界面时不打扰。 */
    val inForeground: Boolean get() = PhoneAgentStatus.foreground.value

    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyLocal(context: Context, sessionId: String, title: String, body: String, failed: Boolean) =
        post(context, EXTRA_LOCAL, sessionId, title, body, failed)


    private fun post(context: Context, extra: String, sessionId: String, title: String, body: String, failed: Boolean) {
        if (inForeground || !canNotify(context)) return
        val app = context.applicationContext
        val localized = AppLocale.wrap(app)
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            localized.getString(R.string.notify_channel_chat), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(app, MainActivity::class.java)
            .setAction("chat.$extra.$sessionId").putExtra(extra, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(app, sessionId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val status = localized.getString(if (failed) R.string.notify_chat_failed else R.string.notify_chat_done)
        val text = body.replace(Regex("\\s+"), " ").trim().take(160).ifBlank { localized.getString(R.string.notify_chat_open) }
        runCatching {
            manager.notify(sessionId.hashCode(), NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("$status · ${title.ifBlank { localized.getString(R.string.app_name) }}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build())
        }
    }

    /** MainActivity 收到通知点击后调用：打开对应本机会话。 */
    fun consume(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_LOCAL)?.let {
            io.github.hakunm.deepseekharness.agent.PhoneConversationReturn.requested.value = it
            intent.removeExtra(EXTRA_LOCAL)
        }
    }
}
