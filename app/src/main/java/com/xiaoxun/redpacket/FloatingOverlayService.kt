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
    private var titleText: TextView? = null
    private var playText: TextView? = null
    private var pongText: TextView? = null
    private var chiText: TextView? = null
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

    fun updateTextInternal(text: String) {
        main.post {
            if (currentMode == ScreenCaptureService.Mode.MAHJONG) {
                updateMahjongCard(text)
            } else {
                statusText?.text = text
            }
        }
    }

    private fun updateMahjongCard(text: String) {
        val lines = text.lines().filter { it.isNotBlank() }
        titleText?.text = "🀄 麻將助手"
        playText?.text = lines.getOrNull(0) ?: text
        pongText?.text = lines.getOrNull(1) ?: "碰: 等待辨識"
        chiText?.text = lines.getOrNull(2) ?: "吃: 等待辨識"
        statusText?.text = lines.joinToString("  ")
    }

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
                playText?.text = "已暫停"
                (box.background as? GradientDrawable)?.setColor(Color.parseColor("#757575"))
                btn.text = "▶"
            } else {
                t.text = if (currentMode == ScreenCaptureService.Mode.MAHJONG)
                    "🀄 算牌中" else getString(R.string.overlay_running)
                playText?.text = if (currentMode == ScreenCaptureService.Mode.MAHJONG) "辨識中…" else playText?.text
                (box.background as? GradientDrawable)?.setColor(if (currentMode == ScreenCaptureService.Mode.MAHJONG) Color.parseColor("#102A43") else Color.parseColor("#D32F2F"))
                btn.text = "⏸"
            }
        }
    }

    private fun setModeInternal(m: ScreenCaptureService.Mode) {
        main.post {
            currentMode = m
            applyMahjongBtnVisibility()
            // 麻將模式時顯示本機分析卡片
            if (!paused) {
                statusText?.text = if (m == ScreenCaptureService.Mode.MAHJONG)
                    "🀄 算牌中" else getString(R.string.overlay_running)
                titleText?.visibility = if (m == ScreenCaptureService.Mode.MAHJONG) View.VISIBLE else View.GONE
                playText?.visibility = if (m == ScreenCaptureService.Mode.MAHJONG) View.VISIBLE else View.GONE
                pongText?.visibility = if (m == ScreenCaptureService.Mode.MAHJONG) View.VISIBLE else View.GONE
                chiText?.visibility = if (m == ScreenCaptureService.Mode.MAHJONG) View.VISIBLE else View.GONE
                counterText?.visibility = if (m == ScreenCaptureService.Mode.MAHJONG) View.GONE else View.VISIBLE
                (statusBox?.background as? GradientDrawable)?.setColor(
                    if (m == ScreenCaptureService.Mode.MAHJONG) Color.parseColor("#102A43") else Color.parseColor("#D32F2F")
                )
                if (m == ScreenCaptureService.Mode.MAHJONG) {
                    titleText?.text = "🀄 麻將助手"
                    playText?.text = "辨識中…"
                    pongText?.text = "碰: 等待辨識"
                    chiText?.text = "吃: 等待辨識"
                }
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
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#D32F2F"))
                setStroke(dp(1), Color.parseColor("#FFE082"))
            }
        }

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(8)
            layoutParams = lp
        }

        titleText = TextView(this).apply {
            text = "🀄 麻將助手"
            setTextColor(Color.parseColor("#FFE082"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            visibility = View.GONE
        }
        playText = infoLine("辨識中…", 14, true).apply { visibility = View.GONE }
        pongText = infoLine("碰: 等待辨識", 12, false).apply { visibility = View.GONE }
        chiText = infoLine("吃: 等待辨識", 12, false).apply { visibility = View.GONE }

        val tv = TextView(this).apply {
            text = getString(R.string.overlay_running)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }
        statusText = tv

        infoColumn.addView(titleText)
        infoColumn.addView(playText)
        infoColumn.addView(pongText)
        infoColumn.addView(chiText)
        infoColumn.addView(tv)

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
            minWidth = dp(28)
        }
        counterText = cnt

        val mahjong = roundButton("🀄", "#FF9800", 30, 14).apply {
            visibility = View.GONE
            setOnClickListener { onMahjongAnalyzeClicked() }
        }
        mahjongBtn = mahjong

        val pause = roundButton("⏸", "#80FFFFFF", 28, 16).apply {
            setOnClickListener { onPauseClicked() }
        }
        pauseBtn = pause

        val stop = roundButton("✕", "#80000000", 26, 13).apply {
            setOnClickListener { onStopClicked() }
        }
        stopBtn = stop

        container.addView(infoColumn)
        container.addView(cnt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp(6)
        })
        container.addView(mahjong, LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER_VERTICAL; marginEnd = dp(4) })
        container.addView(pause, LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL; marginEnd = dp(4) })
        container.addView(stop, LinearLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER_VERTICAL })
        statusBox = container
        root.addView(container)
        return root
    }

    private fun infoLine(textValue: String, sp: Int, bold: Boolean): TextView = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }

    private fun roundButton(textValue: String, color: String, sizeDp: Int, sp: Int): TextView = TextView(this).apply {
        text = textValue
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
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
