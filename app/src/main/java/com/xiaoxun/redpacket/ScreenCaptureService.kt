package com.xiaoxun.redpacket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class ScreenCaptureService : Service() {

    enum class Mode { SEMI_AUTO, FULL_AUTO }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "xiaoxun_redpacket"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_INTERVAL_MS = "interval"
        const val EXTRA_MODE = "mode"               // 0=SEMI 1=FULL
        const val EXTRA_COIN_Y_OFFSET = "yoff"

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isPaused: Boolean = false
            private set

        val clickCount = AtomicInteger(0)

        fun updateConfig(
            ctx: Context, sensitivity: Float, intervalMs: Long,
            mode: Mode, coinYOffset: Float
        ) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_SENSITIVITY, sensitivity)
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
                putExtra(EXTRA_MODE, if (mode == Mode.FULL_AUTO) 1 else 0)
                putExtra(EXTRA_COIN_Y_OFFSET, coinYOffset)
            })
        }
        fun pause(ctx: Context) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).apply { action = ACTION_PAUSE })
        }
        fun resume(ctx: Context) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).apply { action = ACTION_RESUME })
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var coinTemplate: Bitmap? = null
    private var buttonTemplate: Bitmap? = null

    @Volatile private var sensitivity: Float = 0.65f
    @Volatile private var intervalMs: Long = 30L
    @Volatile private var mode: Mode = Mode.SEMI_AUTO
    @Volatile private var coinYOffset: Float = 0f

    // 最近點過的位置去重 (450ms 內 80px 距離視為同個目標)
    private data class TimedPoint(val x: Float, val y: Float, val t: Long)
    private val recentClicks = ArrayDeque<TimedPoint>(64)
    private val recentWindowMs = 450L
    private val recentDedupeDistSq = 80f * 80f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat()

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        coinTemplate = BitmapFactory.decodeResource(resources, R.drawable.target_coin)
        buttonTemplate = BitmapFactory.decodeResource(resources, R.drawable.target_button)

        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        bgHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, 0.65f)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 30L)
                mode = if (intent.getIntExtra(EXTRA_MODE, 0) == 1) Mode.FULL_AUTO else Mode.SEMI_AUTO
                coinYOffset = intent.getFloatExtra(EXTRA_COIN_Y_OFFSET, 0f)
                clickCount.set(0)
                if (data != null) {
                    startProjection(rc, data)
                    FloatingOverlayService.show(this)
                }
            }
            ACTION_UPDATE_CONFIG -> {
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, sensitivity)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs)
                mode = if (intent.getIntExtra(EXTRA_MODE, if (mode == Mode.FULL_AUTO) 1 else 0) == 1)
                    Mode.FULL_AUTO else Mode.SEMI_AUTO
                coinYOffset = intent.getFloatExtra(EXTRA_COIN_Y_OFFSET, coinYOffset)
            }
            ACTION_PAUSE -> {
                isPaused = true
                FloatingOverlayService.setPausedUi(true)
            }
            ACTION_RESUME -> {
                isPaused = false
                FloatingOverlayService.setPausedUi(false)
            }
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, bgHandler)
        }
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "XiaoxunCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, bgHandler
        )
        isRunning = true
        isPaused = false
        loopJob = scope.launch { runDetectionLoop() }
    }

    private suspend fun runDetectionLoop() {
        while (scope.isActive && isRunning) {
            try {
                if (!isPaused) {
                    val bmp = grabLatestFrame()
                    if (bmp != null) {
                        processFrame(bmp)
                        bmp.recycle()
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "frame error", t)
            }
            delay(intervalMs)
        }
    }

    private fun grabLatestFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image = reader.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val bmpWidth = screenWidth + rowPadding / pixelStride
            val raw = Bitmap.createBitmap(bmpWidth, screenHeight, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            val cropped = if (rowPadding == 0) raw
                else Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)
            if (cropped !== raw) raw.recycle()
            cropped
        } finally {
            image.close()
        }
    }

    private suspend fun processFrame(frame: Bitmap) {
        val now = System.currentTimeMillis()
        while (recentClicks.isNotEmpty() && now - recentClicks.first().t > recentWindowMs) {
            recentClicks.removeFirst()
        }

        val allPoints = mutableListOf<PointF>()

        // ============ 紅包 (always) ============
        val coins = ImageMatcher.findRedCoins(frame, coinTemplate, sensitivity)
        if (coins.isNotEmpty()) {
            for (c in coins) {
                if (isRecent(c.centerX, c.centerY)) continue
                val cy = (c.centerY + coinYOffset).coerceAtMost(screenHeight - 4f)
                allPoints += PointF(c.centerX, cy)
                recentClicks.addLast(TimedPoint(c.centerX, c.centerY, now))
            }
        }

        // ============ 去看看 (僅全自動模式) ============
        if (mode == Mode.FULL_AUTO) {
            val buttons = ButtonMatcher.findButtons(frame, buttonTemplate, sensitivity)
            for (b in buttons) {
                if (isRecent(b.centerX, b.centerY)) continue
                allPoints += PointF(b.centerX, b.centerY)
                recentClicks.addLast(TimedPoint(b.centerX, b.centerY, now))
            }
        }

        if (allPoints.isEmpty()) return

        Log.i(TAG, "batch click ${allPoints.size} targets, mode=$mode")
        withContext(Dispatchers.Main) {
            AutoClickService.instance?.performMultiClick(allPoints)
        }
        // 更新計數與懸浮窗
        val newCount = clickCount.addAndGet(allPoints.size)
        FloatingOverlayService.updateCounter(this, newCount)
        FloatingOverlayService.flashHit(this)
    }

    private fun isRecent(x: Float, y: Float): Boolean {
        for (rp in recentClicks) {
            val dx = rp.x - x; val dy = rp.y - y
            if (dx * dx + dy * dy < recentDedupeDistSq) return true
        }
        return false
    }

    override fun onDestroy() {
        isRunning = false
        isPaused = false
        loopJob?.cancel()
        scope.cancel()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        handlerThread?.quitSafely(); handlerThread = null
        coinTemplate?.recycle(); coinTemplate = null
        buttonTemplate?.recycle(); buttonTemplate = null
        FloatingOverlayService.hide(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
