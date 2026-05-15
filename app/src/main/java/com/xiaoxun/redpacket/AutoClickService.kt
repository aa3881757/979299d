package com.xiaoxun.redpacket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 無障礙服務 v1.4 - 紅包雨加速
 *  - 多點偵測 + 連續快速點擊
 *  - 點擊間隔縮到 25ms
 *  - 手勢時長縮到 20ms
 *  - 文字掃描 200ms / 次
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        // 紅包雨加速：每個點之間僅 25ms
        private const val CLICK_GAP_MS = 25L
        // 手勢自身時長
        private const val GESTURE_DURATION_MS = 20L

        @Volatile var instance: AutoClickService? = null
            private set
        private val KEYWORDS = arrayOf("去看看")

        @Volatile var textScanEnabled: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBatchTs = 0L
    private val scanIntervalMs = 200L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
        startScanLoop()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        textScanEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun startScanLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (textScanEnabled) scanForAllKeywords()
                mainHandler.postDelayed(this, scanIntervalMs)
            }
        })
    }

    private fun scanForAllKeywords() {
        try {
            val root = rootInActiveWindow ?: return
            val matches = mutableListOf<AccessibilityNodeInfo>()
            for (kw in KEYWORDS) collectNodesContaining(root, kw, matches)
            if (matches.isEmpty()) return

            val now = System.currentTimeMillis()
            if (now - lastBatchTs < 150) {
                matches.forEach { it.recycle() }
                return
            }
            lastBatchTs = now

            val points = mutableListOf<PointF>()
            for (n in matches) {
                if (n.isClickable) {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        points += PointF(rect.exactCenterX(), rect.exactCenterY())
                    }
                }
                n.recycle()
            }
            if (points.isNotEmpty()) {
                Log.i(TAG, "batch click ${points.size} text targets")
                performMultiClick(points)
            }
            FloatingOverlayService.flashHit(this)
        } catch (t: Throwable) {
            Log.w(TAG, "scan error: ${t.message}")
        }
    }

    private fun collectNodesContaining(
        node: AccessibilityNodeInfo?, keyword: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) {
            val target = findClickableAncestor(node) ?: node
            if (out.none { sameBounds(it, target) }) {
                out += AccessibilityNodeInfo.obtain(target)
            }
        }
        for (i in 0 until node.childCount) {
            collectNodesContaining(node.getChild(i), keyword, out)
        }
    }

    private fun sameBounds(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        val ra = Rect(); val rb = Rect()
        a.getBoundsInScreen(ra); b.getBoundsInScreen(rb)
        return ra == rb
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    fun performClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 連續多點點擊：依序對 [points] 中每個座標派一個 click，
     * 每點延遲 CLICK_GAP_MS (25ms)。
     */
    fun performMultiClick(points: List<PointF>): Boolean {
        if (points.isEmpty()) return false
        var ok = performClickAt(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p = points[i]
            mainHandler.postDelayed({ performClickAt(p.x, p.y) }, CLICK_GAP_MS * i)
        }
        return ok
    }
}
