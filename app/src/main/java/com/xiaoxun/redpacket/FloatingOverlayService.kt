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
 * 懸浮窗 v1.13
 * - 紅色膠囊「偵測中」
 * - 暫停 / 開始 切換鈕
 * - 🀄 算牌鈕（只在 MAHJONG 模式顯示，本機計算）
 * - 停止鈕
 * - 可拖曳
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "Overlay"
        const val ACTION_SHOW = "show"
        const val ACTION_HIDE = "hide"
        const val ACTION_UPDATE_TEXT = "update_text"
        const val ACTION_SET_PAUSED = "set_paused"
        const val ACTION_SET_MODE = "set_mode"
        const val EXTRA_TEXT = "text"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_MODE = "mode"

        @Volatile var instance: FloatingOverlayService? = null
            private set

        fun show(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingOverlayService::class.java).apply { action = ACTION_SHOW })
        }
        fun hide(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingOverlayService::class.java).apply { action = ACTION_HIDE })
        }
        fun flashHit(ctx: Context) { instance?.flashHitInternal() }
        fun updateText(ctx: Context, text: String) { instance?.updateTextInternal(text) }
        fun setPausedUi(paused: Boolean) { instance?.setPausedUiInternal(paused) }
        fun updateCounter(ctx: Context, count: Int) { instance?.updateCounterInternal(count) }
        fun setMode(ctx: Context, mode: ScreenCaptureService.Mode) {
            ctx.startService(Intent(ctx, FloatingOverlayService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, ScreenCaptureService.modeToInt(mode))
            })
        }
    }

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var statusText: TextView? = null
    private var counterText: TextView? = null
    private var pauseBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var mahjongBtn: TextView? = null
    private var statusBox: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private var paused = false
    private var lastCounter = 0
    private var currentMode: ScreenCaptureService.Mode = ScreenCaptureService.Mode.SEMI_AUTO

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> { hideOverlay(); stopSelf() }
            ACTION_UPDATE_TEXT -> intent.getStringExtra(EXTRA_TEXT)?.let { updateTextInternal(it) }
            ACTION_SET_PAUSED -> setPausedUiInternal(intent.getBooleanExtra(EXTRA_PAUSED, false))
            ACTION_SET_MODE -> {
                val m = ScreenCaptureService.modeFromInt(intent.getIntExtra(EXTRA_MODE, 0))
                setModeInternal(m)
            }
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

        attachDrag(view, lp)
        wm?.addView(view, lp)
        rootView = view
        layoutParams = lp
        applyMahjongBtnVisibility()
    }

    private fun hideOverlay() {
        rootView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        rootView = null
        layoutParams = null
    }

    fun flashHitInternal() {
        main.post {
            val t = statusText ?: return@post
            val box = statusBox ?: return@post
            t.text = getString(R.string.overlay_hit)
            (box.background as? GradientDrawable)?.setColor(Color.parseColor("#2E7D32"))
            main.postDelayed({
                if (paused) {
                    t.text = getString(R.string.overlay_paused)
                    (box.background as? GradientDrawable)?.setColor(Color.parseColor("#757575"))
                } else {
                    t.text = if (currentMode == ScreenCaptureService.Mode.MAHJONG)
                        "🀄 算牌中" else getString(R.string.overlay_running)
                    (box.background as? GradientDrawable)?.setColor(Color.parseColor("#D32F2F"))
                }
            }, 600)
        }
    }

    fun updateTextInternal(text: String) { main.post { statusText?.text = text } }

    fun updateCounterInternal(count: Int) {
        main.post {
            lastCounter = count
            counterText?.text = count.toString()
        }
    }

    fun setPausedUiInternal(p: Boolean) {
        main.post {
            paused = p
            val t = statusText ?: return@post
            val box = statusBox ?: return@post
            val btn = pauseBtn ?: return@post
            if (p) {
                t.text = getString(R.string.overlay_paused)
                (box.background as? GradientDrawable)?.setColor(Color.parseColor("#757575"))
                btn.text = "▶"
            } else {
                t.text = if (currentMode == ScreenCaptureService.Mode.MAHJONG)
                    "🀄 算牌中" else getString(R.string.overlay_running)
                (box.background as? GradientDrawable)?.setColor(Color.parseColor("#D32F2F"))
                btn.text = "⏸"
            }
        }
    }

    private fun setModeInternal(m: ScreenCaptureService.Mode) {
        main.post {
            currentMode = m
            applyMahjongBtnVisibility()
            // 麻將模式時顯示本機分析狀態
            if (!paused) {
                statusText?.text = if (m == ScreenCaptureService.Mode.MAHJONG)
                    "🀄 算牌中" else getString(R.string.overlay_running)
            }
        }
    }

    private fun applyMahjongBtnVisibility() {
        mahjongBtn?.visibility = if (currentMode == ScreenCaptureService.Mode.MAHJONG)
            View.VISIBLE else View.GONE
    }

    private fun buildView(): View {
        val root = FrameLayout(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#D32F2F"))
                setStroke(dp(1), Color.parseColor("#FFE082"))
            }
        }

        // 狀態文字
        val tv = TextView(this).apply {
            text = getString(R.string.overlay_running)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(6)
            layoutParams = lp
            setPadding(0, dp(4), 0, dp(4))
        }
        statusText = tv

        // 計數器
        val cnt = TextView(this).apply {
            text = lastCounter.toString()
            setTextColor(Color.parseColor("#FFE082"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#33000000"))
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(6)
            layoutParams = lp
            minWidth = dp(28)
        }
        counterText = cnt

        // 麻將模式：本機算牌鈕
        val mahjong = TextView(this).apply {
            text = "🀄"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF6F00"))
            }
            val lp = LinearLayout.LayoutParams(dp(30), dp(30))
            lp.marginEnd = dp(4)
            layoutParams = lp
            visibility = View.GONE
            setOnClickListener { onMahjongAnalyzeClicked() }
        }
        mahjongBtn = mahjong

        // 暫停/開始按鈕
        val pause = TextView(this).apply {
            text = "⏸"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80FFFFFF"))
            }
            val lp = LinearLayout.LayoutParams(dp(28), dp(28))
            lp.marginEnd = dp(4)
            layoutParams = lp
            setOnClickListener { onPauseClicked() }
        }
        pauseBtn = pause

        // 停止按鈕
        val stop = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#80000000"))
            }
            val lp = LinearLayout.LayoutParams(dp(26), dp(26))
            layoutParams = lp
            setOnClickListener { onStopClicked() }
        }
        stopBtn = stop

        container.addView(tv)
        container.addView(cnt)
        container.addView(mahjong)
        container.addView(pause)
        container.addView(stop)
        statusBox = container
        root.addView(container)
        return root
    }

    private fun onMahjongAnalyzeClicked() {
        ScreenCaptureService.analyzeMahjong(this)
    }

    private fun onPauseClicked() {
        if (paused) {
            ScreenCaptureService.resume(this)
        } else {
            ScreenCaptureService.pause(this)
        }
    }

    private fun onStopClicked() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopSelf()
    }

    /** 拖曳（按下記錄位置，移動就更新） */
    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    touchX = ev.rawX; touchY = ev.rawY; moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > dp(6)) {
                        moved = true
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        try { wm?.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                    moved
                }
                MotionEvent.ACTION_UP -> moved
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }
}
