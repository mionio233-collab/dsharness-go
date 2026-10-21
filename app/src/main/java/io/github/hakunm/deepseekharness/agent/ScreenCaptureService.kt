package io.github.hakunm.deepseekharness.agent

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.hakunm.deepseekharness.AppLocale
import io.github.hakunm.deepseekharness.R
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One virtual display per fresh user grant. Surface is attached only for an explicit observation. */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "data"
        /** Bounded total wait for one mappable frame; a resize must not consume it all. */
        private const val FRAME_WAIT_MILLIS = 2500L
        /** Re-attach the surface at most this many times inside that budget. */
        private const val FRAME_ATTEMPTS = 3
        private const val FRAME_RETRY_DELAY_MILLIS = 120L
        /** A frame cached within this many ms is returned when all fresh attempts time out. */
        private const val CACHE_STALENESS_MILLIS = 1500L
        private val imageEpochs = java.util.concurrent.atomic.AtomicLong()
        private val sessions = CaptureSessionState()
        val state = sessions.state
        val enabled = sessions.enabled
        @Volatile private var instance: ScreenCaptureService? = null
        fun getInstance() = instance
        fun isRunning() = state.value.active && instance?.projection != null && instance?.display != null
        fun start(context: Context, resultCode: Int, data: Intent) {
            if (isRunning() || state.value.starting) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode); putExtra(EXTRA_RESULT_DATA, data)
                })
            } catch (failure: Exception) {
                sessions.failedStart("startup_failed")
                android.util.Log.w("PhoneVisual", "start_service_failed class=${failure.javaClass.simpleName}")
                throw failure
            }
        }
        fun stop(context: Context, reason: String = "user_stop") {
            instance?.releaseCapture(reason)
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            File(context.cacheDir, "phone-visual").listFiles()?.forEach { it.delete() }
        }
    }
    @Volatile var imageEpoch: Long = imageEpochs.incrementAndGet()
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var pending: CompletableDeferred<Bitmap?>? = null
    private var requestedAt = 0L
    private var consumedGrant = false
    private var visible = true
    private var mappingReady = true
    private val frameLease = Mutex()
    // Last successfully delivered frame, valid only within the current epoch/session.
    private var lastGoodFrame: Bitmap? = null
    private var lastGoodFrameAtMs = 0L
    /** Reason the last awaitFreshFrame returned null; null when a frame was delivered. */
    @Volatile var lastFrameFailureReason: String? = null
        private set
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { invalidateFrameCache(); releaseCapture("screen_off"); stopSelf() }
    }
    val isCapturing get() = sessions.owns(this) && isRunning() && display != null
    override fun onCreate() {
        super.onCreate()
        val previous = instance
        sessions.claim(this)
        instance = this
        previous?.releaseCapture("replaced")
        android.util.Log.i("PhoneVisual", "service_created")
        // No previous projection can survive a service/process restart.
        purgeImages()
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { releaseCapture("notification_stop"); stopSelf(); return START_NOT_STICKY }
        if (consumedGrant) {
            // A stopped token is never reusable. A fresh result can replace it in this service.
            if (projection != null) return START_NOT_STICKY
            if (intent?.hasExtra(EXTRA_RESULT_DATA) != true) return START_NOT_STICKY
            sessions.claim(this)
        }
        consumedGrant = true
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) { releaseCapture("invalid_grant"); stopSelf(); return START_NOT_STICKY }
        var startupStage = "foreground"
        try {
            val localized = AppLocale.wrap(this)
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("PhoneVisual", localized.getString(R.string.visual_title), NotificationManager.IMPORTANCE_LOW))
            val stopIntent = android.app.PendingIntent.getService(this, 0, Intent(this, ScreenCaptureService::class.java).setAction("STOP"), android.app.PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "PhoneVisual")
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle(localized.getString(R.string.visual_title))
                .setContentText(localized.getString(R.string.visual_enabled)).setOngoing(true)
                .addAction(0, localized.getString(R.string.stop_screen_capture), stopIntent).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(2001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(2001, notification)
            android.util.Log.i("PhoneVisual", "foreground_started")
            startupStage = "projection"
            val bounds = if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            val mp = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data))
            projection = mp
            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === mp) { releaseCapture("projection_system_stop"); stopSelf() }
                }
                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    if (projection !== mp || !sessions.owns(this@ScreenCaptureService)) return
                    visible = isVisible
                    sessions.content(this@ScreenCaptureService, visible = isVisible)
                    android.util.Log.i("PhoneVisual", "content_visibility visible=$isVisible")
                    // Visibility is transient; it does not revoke the projection or phone consent.
                    if (!isVisible) { pending?.complete(null); invalidateFrameCache(); purgeImages() }
                }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (projection !== mp || !sessions.owns(this@ScreenCaptureService)) return
                    val physical = if (Build.VERSION.SDK_INT >= 30)
                        getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    // A selected app region has no verified full-display coordinate mapping.
                    // Suspend frames, retaining consent; never restart/reuse the projection token.
                    val nextMappingReady = width == physical.width() && height == physical.height()
                    // Android may repeat the same dimensions when a request attaches its surface.
                    // Such a callback must not cancel that request or invalidate its evidence epoch.
                    if (!captureResizeInvalidates(mappingReady, nextMappingReady,
                            reader?.width ?: 0, reader?.height ?: 0, width, height)) return
                    pending?.complete(null); invalidateFrameCache(); purgeImages()
                    mappingReady = nextMappingReady
                    sessions.content(this@ScreenCaptureService, mappingReady = mappingReady)
                    try {
                        if (mappingReady && (reader?.width != width || reader?.height != height)) {
                            display?.surface = null
                            reader?.close()
                            reader = createReader(width, height)
                            display?.resize(width, height, resources.configuration.densityDpi)
                        }
                    } catch (failure: Exception) {
                        android.util.Log.w("PhoneVisual", "resize_failed class=${failure.javaClass.simpleName}")
                        releaseCapture("resize_failed"); stopSelf()
                    }
                    android.util.Log.i("PhoneVisual", if (mappingReady) "projection_mapping_ready" else "projection_mapping_suspended")
                }
            }.also { mp.registerCallback(it, handler) }
            startupStage = "virtual_display"
            reader = createReader(bounds.width(), bounds.height())
            display = mp.createVirtualDisplay("PhoneVisual", bounds.width(), bounds.height(), resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler)
            visible = true; mappingReady = true
            sessions.started(this)
            // Consent survives request cleanup. Frames expire separately.
            android.util.Log.i("PhoneVisual", "projection_started")
        } catch (failure: Exception) {
            android.util.Log.w("PhoneVisual", "startup_failed stage=$startupStage class=${failure.javaClass.simpleName}")
            releaseCapture("startup_failed"); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        releaseCapture("service_destroyed"); scope.cancel(); runCatching { unregisterReceiver(screenOff) }
        if (instance === this) instance = null
        super.onDestroy()
    }
    private fun createReader(width: Int, height: Int) = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { ir ->
        ir.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image != null) try {
                val waiter = pending
                if (source === reader && waiter != null && !waiter.isCompleted && image.timestamp > requestedAt && visible && mappingReady && isRunning()) {
                    val bitmap = try { imageToBitmap(image) } catch (failure: Exception) {
                        android.util.Log.w("PhoneVisual", "frame_conversion_failed class=${failure.javaClass.simpleName}")
                        waiter.complete(null)
                        null
                    }
                    if (bitmap != null && !waiter.complete(bitmap)) bitmap.recycle()
                }
            } finally { image.close() }
        }, handler)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitFreshFrame(): Bitmap? = frameLease.withLock { withContext(Dispatchers.Main.immediate) {
        if (!isCapturing || !visible || !mappingReady) {
            lastFrameFailureReason = if (!mappingReady) "mapping_suspended" else "capture_not_ready"
            return@withContext lastGoodFrameCopy()
        }
        val deadlineNs = System.nanoTime() + FRAME_WAIT_MILLIS * 1_000_000L
        var result: Bitmap? = null
        for (attempt in 1..FRAME_ATTEMPTS) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) break
            val waiter = CompletableDeferred<Bitmap?>()
            pending = waiter; requestedAt = System.nanoTime()
            try {
                display?.surface = reader?.surface
                result = withTimeoutOrNull(remainingMs) { waiter.await() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                android.util.Log.w("PhoneVisual", "frame_request_failed attempt=$attempt class=${failure.javaClass.simpleName}")
                break
            } finally {
                runCatching { display?.surface = null }
                pending = null
                if (result == null && waiter.isCompleted && !waiter.isCancelled) waiter.getCompleted()?.recycle()
            }
            if (result != null) break
            android.util.Log.i("PhoneVisual", "frame_attempt_failed attempt=$attempt reason=${state.value.reason}")
            if (attempt < FRAME_ATTEMPTS) delay(FRAME_RETRY_DELAY_MILLIS)
        }
        if (result != null) {
            updateFrameCache(result)
            lastFrameFailureReason = null
        } else {
            lastFrameFailureReason = "no_frame_delivered"
            android.util.Log.i("PhoneVisual", "frame_unavailable_all_attempts cache_age_ms=${System.currentTimeMillis() - lastGoodFrameAtMs}")
            result = lastGoodFrameCopy()
        }
        result
    } }
    /** Legacy context capture deliberately disabled; model images go through the guarded tool only. */
    fun captureScreen(): Bitmap? = null

    private fun updateFrameCache(bitmap: Bitmap) {
        lastGoodFrame?.recycle()
        lastGoodFrame = runCatching { bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
        lastGoodFrameAtMs = System.currentTimeMillis()
    }

    /** Returns an independent copy of the cached frame if it is still fresh and the session is valid. */
    private fun lastGoodFrameCopy(): Bitmap? {
        val cached = lastGoodFrame ?: return null
        if (!isCapturing || !visible || !mappingReady) return null
        if (System.currentTimeMillis() - lastGoodFrameAtMs > CACHE_STALENESS_MILLIS) return null
        return runCatching { cached.copy(cached.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    private fun invalidateFrameCache() {
        lastGoodFrame?.recycle()
        lastGoodFrame = null
        lastGoodFrameAtMs = 0L
    }
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (result !== padded) padded.recycle()
            return result
        } catch (failure: Exception) {
            padded.recycle()
            throw failure
        }
    }
    private fun releaseCapture(reason: String) {
        val owned = sessions.owns(this)
        sessions.stopped(this, reason)
        android.util.Log.i("PhoneVisual", "capture_release reason=$reason owner=$owned")
        pending?.complete(null)
        invalidateFrameCache()
        val old = projection; projection = null
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        callback?.let { cb -> runCatching { old?.unregisterCallback(cb) } }; callback = null
        runCatching { old?.stop() }
        if (owned) {
            purgeImages()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }
    private fun purgeImages() {
        if (!sessions.owns(this) && instance !== this) return
        imageEpoch = imageEpochs.incrementAndGet()
        File(cacheDir, "phone-visual").listFiles()?.forEach { it.delete() }
    }
}
