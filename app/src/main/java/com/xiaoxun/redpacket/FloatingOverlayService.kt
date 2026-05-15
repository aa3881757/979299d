package com.xiaoxun.redpacket

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 在螢幕上方顯示小型懸浮窗：
 *  - 圓角紅色膠囊，顯示「偵測中」「已點擊」狀態
 *  - 可拖曳到任何位置
 *  - 雙擊 = 停止偵測
 *  - 主畫面切到背景時仍會浮在最上層
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "Overlay"
        const val ACTION_SHOW = "show"
        const val ACTION_HIDE = "hide"
        const val ACTION_HIT  = "hit"     // 觸發一閃的「已點擊」動畫
        const val ACTION_UPDATE_TEXT = "update_text"
        const val EXTRA_TEXT = "text"

        @Volatile var instance: FloatingOverlayService? = null
            private set

        fun show(ctx: Context) {
            val i = Intent(ctx, FloatingOverlayService::class.java).apply { action = ACTION_SHOW }
            ctx.startService(i)
        }
        fun hide(ctx: Context) {
            val i = Intent(ctx, FloatingOverlayService::class.java).apply { action = ACTION_HIDE }
            ctx.startService(i)
        }
        fun flashHit(ctx: Context) {
            instance?.flashHitInternal()
        }
        fun updateText(ctx: Context, text: String) {
            instance?.updateTextInternal(text)
        }
    }

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var statusText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> { hideOverlay(); stopSelf() }
            ACTION_HIT -> flashHitInternal()
            ACTION_UPDATE_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { updateTextInternal(it) }
        }
        return START_STICKY
    }

    private fun showOverlay() {
        if (rootView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = buildView()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(100)
        }

        attachDragAndDoubleTap(view, lp)

        wm?.addView(view, lp)
        rootView = view
        layoutParams = lp
    }

    private fun hideOverlay() {
        rootView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        layoutParams = null
    }

    fun flashHitInternal() {
        main.post {
            val t = statusText ?: return@post
            t.text = getString(R.string.overlay_hit)
            val bg = t.background as? GradientDrawable
            bg?.setColor(Color.parseColor("#2E7D32"))
            main.postDelayed({
                t.text = getString(R.string.overlay_running)
                (t.background as? GradientDrawable)?.setColor(Color.parseColor("#D32F2F"))
            }, 600)
        }
    }

    fun updateTextInternal(text: String) {
        main.post { statusText?.text = text }
    }

    private fun buildView(): View {
        val root = FrameLayout(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#D32F2F"))
                setStroke(dp(1), Color.parseColor("#FFE082"))
            }
        }
        val tv = TextView(this).apply {
            text = getString(R.string.overlay_running)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
        }
        box.addView(tv)
        statusText = tv
        root.addView(box)
        return root
    }

    /** 拖曳 + 雙擊偵測 */
    private fun attachDragAndDoubleTap(view: View, lp: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var downTime = 0L
        var lastTapTime = 0L
        var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    touchX = ev.rawX; touchY = ev.rawY
                    downTime = System.currentTimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > dp(6)) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    try { wm?.updateViewLayout(view, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downTime < 300) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 400) {
                            // 雙擊：停止
                            stopDetection()
                        }
                        lastTapTime = now
                    }
                }
            }
            true
        }
    }

    private fun stopDetection() {
        // 通知 ScreenCaptureService 停止
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopSelf()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }
}
