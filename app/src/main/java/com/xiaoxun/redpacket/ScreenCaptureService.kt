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
 * 前景服務：
 *   1. 使用 MediaProjection + ImageReader 持續擷取畫面
 *   2. 在背景執行緒做圖片匹配
 *   3. 找到目標時呼叫 AutoClickService 模擬點擊
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "xiaoxun_redpacket"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_INTERVAL_MS = "interval"

        @Volatile var isRunning: Boolean = false
            private set

        fun updateConfig(ctx: Context, sensitivity: Float, intervalMs: Long) {
            if (!isRunning) return
            val i = Intent(ctx, ScreenCaptureService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_SENSITIVITY, sensitivity)
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
            }
            ctx.startService(i)
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

    private var template1: Bitmap? = null   // 黃色「去看看」按鈕
    private var template2: Bitmap? = null   // 紅色金幣

    @Volatile private var sensitivity: Float = 0.80f
    @Volatile private var intervalMs: Long = 500L
    @Volatile private var lastClickTs: Long = 0L

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

        // 載入範本
        template1 = BitmapFactory.decodeResource(resources, R.drawable.target_button)
        template2 = BitmapFactory.decodeResource(resources, R.drawable.target_coin)

        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        bgHandler = Handler(handlerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, 0.80f)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 500L)
                if (data != null) {
                    startProjection(rc, data)
                }
            }
            ACTION_UPDATE_CONFIG -> {
                sensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, sensitivity)
                intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, intervalMs)
                Log.i(TAG, "config updated: sensitivity=$sensitivity, interval=$intervalMs")
            }
        }
        return START_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped")
                    stopSelf()
                }
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
        loopJob = scope.launch { runDetectionLoop() }
    }

    private suspend fun runDetectionLoop() {
        while (scope.isActive && isRunning) {
            try {
                val bmp = grabLatestFrame()
                if (bmp != null) {
                    processFrame(bmp)
                    bmp.recycle()
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
        val t1 = template1 ?: return
        val t2 = template2 ?: return

        // 兩張範本都跑一次，取最佳；先比較大的（按鈕），通常更明確
        val r1 = ImageMatcher.findTemplate(frame, t1, sensitivity)
        val r2 = ImageMatcher.findTemplate(frame, t2, sensitivity)

        val best = listOfNotNull(r1, r2).maxByOrNull { it.score }
        if (best != null) {
            val now = System.currentTimeMillis()
            // 簡單防抖：兩次點擊至少間隔 800ms
            if (now - lastClickTs > 800) {
                lastClickTs = now
                Log.i(TAG, "match at (${best.centerX}, ${best.centerY}) score=${best.score}")
                withContext(Dispatchers.Main) {
                    AutoClickService.instance?.performClickAt(best.centerX, best.centerY)
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        scope.cancel()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        handlerThread?.quitSafely(); handlerThread = null
        template1?.recycle(); template1 = null
        template2?.recycle(); template2 = null
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
