package io.github.hakunm.deepseekharness.local

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hakunm.deepseekharness.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Process coordinator: service owns the execution scope, never an Activity or Compose scope. */
object ChatExecution {
    val slots = RunSlots(5)
    // A phone run holds this for its entire observe/action conversation, including provider rounds.
    // Serializing individual taps would allow another run to invalidate the first run's snapshot.
    val phoneLease = Mutex()
    private val lock = Any()
    private val pending = mutableMapOf<String, suspend CoroutineScope.() -> Unit>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancellations = mutableMapOf<String, () -> Unit>()
    fun onCancel(id: String, action: () -> Unit) = synchronized(lock) { cancellations[id] = action }
    private var service: ChatExecutionService? = null

    fun submit(context: Context, id: String, block: suspend CoroutineScope.() -> Unit): Boolean {
        synchronized(lock) {
            if (!slots.acquire(id)) return false
            pending[id] = block
            try {
                if (service != null) service!!.drain()
                else ContextCompat.startForegroundService(context.applicationContext, Intent(context, ChatExecutionService::class.java))
            } catch (failure: Exception) {
                pending.remove(id); slots.release(id)
                throw failure
            }
            return true
        }
    }
    fun cancel(id: String) = synchronized(lock) {
        cancellations[id]?.invoke()
        if (pending.remove(id) != null) slots.release(id)
        jobs[id]?.cancel()
        service?.refresh()
    }
    internal fun attach(value: ChatExecutionService) = synchronized(lock) { service = value; value.drain() }
    internal fun detach(value: ChatExecutionService) = synchronized(lock) {
        if (service === value) service = null
        jobs.keys.toList().forEach { cancel(it) }
    }
    internal fun drain(scope: CoroutineScope) = synchronized(lock) {
        pending.toMap().forEach { (id, block) ->
            pending.remove(id)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { block() } finally {
                    synchronized(lock) {
                        jobs.remove(id); cancellations.remove(id); slots.release(id)
                        service?.refresh()
                    }
                }
            }
            jobs[id] = job
            job.start()
        }
    }
}

class ChatExecutionService : Service() {
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("chat_execution", "Chat execution", NotificationManager.IMPORTANCE_LOW))
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(7001, notification)
        ChatExecution.attach(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        drain()
        return START_NOT_STICKY // Never automatically replay paid calls or phone/file side effects.
    }
    internal fun drain() { ChatExecution.drain(executionScope); refresh() }
    internal fun refresh() {
        if (ChatExecution.slots.ids().isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        else getSystemService(NotificationManager::class.java).notify(7001, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "chat_execution")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history).setContentTitle("对话任务正在执行")
            .setContentText("${ChatExecution.slots.ids().size}/5 个任务 · 点击返回对话")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        ChatExecution.slots.ids().forEach { ChatExecution.cancel(it) }
        executionScope.cancel(); stopSelf()
    }
    override fun onDestroy() { ChatExecution.detach(this); executionScope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
