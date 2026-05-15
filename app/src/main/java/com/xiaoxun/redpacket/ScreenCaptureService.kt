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

/**
 * v1.4 紅包雨加速版：
 *  - 預設偵測間隔 120ms (相當於 8 次/秒)
 *  - 滑桿最低可開到 80ms
 *  - 不再有「批次防抖」延遲整個流程
 *  - 改用「最近點擊位置快取」(0.4 秒內 90px 內視為同一目標跳過)
 */
class ScreenCaptureService : Service() {

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

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isPaused: Boolean = false
            private set

        fun updateConfig(ctx: Context, sensitivity: Float, intervalMs: Long) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_SENSITIVITY, sensitivity)
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
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

    @Volatile private var sensitivity: Float = 0.65f
    @Volatile private var intervalMs: Long = 120L

    // 最近點擊過的位置 (px, py, 時間)，0.4 秒內 90px 內視為同一目標
    private data class TimedPoint(val x: Float, val y: Float, val t: Long)
    private val recentClicks = ArrayDeque<TimedPoint>(32)
    private val recentWindowMs = 400L
    private val recentDedupeDistSq = 90f * 90f

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
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        bgHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, 0.65f)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 120L)
                if (data != null) {
                    startProjection(rc, data)
                    AutoClickService.textScanEnabled = true
                    FloatingOverlayService.show(this)
                }
            }
            ACTION_UPDATE_CONFIG -> {
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, sensitivity)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs)
            }
            ACTION_PAUSE -> {
                isPaused = true
                AutoClickService.textScanEnabled = false
                FloatingOverlayService.setPausedUi(true)
            }
            ACTION_RESUME -> {
                isPaused = false
                AutoClickService.textScanEnabled = true
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
        val coins = ImageMatcher.findRedCoins(frame, coinTemplate, sensitivity)
        if (coins.isEmpty()) return

        val now = System.currentTimeMillis()
        // 清掉太舊的 recentClicks
        while (recentClicks.isNotEmpty() && now - recentClicks.first().t > recentWindowMs) {
            recentClicks.removeFirst()
        }

        // 過濾掉近期已點過的位置；取分數最高的當作這一輪目標
        val target = coins.firstOrNull { c ->
            recentClicks.none {
                val dx = it.x - c.centerX; val dy = it.y - c.centerY
                dx * dx + dy * dy < recentDedupeDistSq
            }
        } ?: return

        // 紅包雨軌跡點擊：往下生成 3 個位置 (對應掉落軌跡)
        // 每點間距 = 螢幕高度 4% (1080p 約 80px)
        val trailStep = screenHeight * 0.04f
        val trail = listOf(
            PointF(target.centerX, target.centerY),
            PointF(target.centerX, (target.centerY + trailStep).coerceAtMost(screenHeight - 4f)),
            PointF(target.centerX, (target.centerY + trailStep * 2).coerceAtMost(screenHeight - 4f))
        )

        // 記錄成 recent，避免下次又點到同一顆
        recentClicks.addLast(TimedPoint(target.centerX, target.centerY, now))

        Log.i(TAG, "trail click target (${target.centerX}, ${target.centerY}) step=$trailStep, " +
                   "total ${coins.size} coins detected this frame")
        withContext(Dispatchers.Main) {
            AutoClickService.instance?.performMultiClick(trail)
        }
        FloatingOverlayService.flashHit(this)
    }

    override fun onDestroy() {
        isRunning = false
        isPaused = false
        AutoClickService.textScanEnabled = false
        loopJob?.cancel()
        scope.cancel()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        handlerThread?.quitSafely(); handlerThread = null
        coinTemplate?.recycle(); coinTemplate = null
        FloatingOverlayService.hide(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
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
