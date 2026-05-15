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
 * 無障礙服務 (v1.3 多點偵測 + 連續快速點擊)
 *  - performClickAt(x, y) 單點
 *  - performMultiClick(points) 連續多點 (50ms 間隔)
 *  - 自帶定時文字掃描，找出畫面中所有「去看看」並依序點完
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        private const val CLICK_GAP_MS = 60L
        @Volatile var instance: AutoClickService? = null
            private set
        private val KEYWORDS = arrayOf("去看看")

        @Volatile var textScanEnabled: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBatchTs = 0L
    private val scanIntervalMs = 350L

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

    /** 掃描整個 UI tree 找出所有 KEYWORDS 節點，連續點完 */
    private fun scanForAllKeywords() {
        try {
            val root = rootInActiveWindow ?: return
            val matches = mutableListOf<AccessibilityNodeInfo>()
            for (kw in KEYWORDS) {
                collectNodesContaining(root, kw, matches)
            }
            if (matches.isEmpty()) return

            val now = System.currentTimeMillis()
            if (now - lastBatchTs < 600) {
                matches.forEach { it.recycle() }
                return
            }
            lastBatchTs = now

            val points = mutableListOf<PointF>()
            for (n in matches) {
                if (n.isClickable) {
                    // 優先用 node click (更準)
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
            // 避免重複加入同一節點
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

    /** 對單一座標執行一次點擊 */
    fun performClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * **連續多點點擊**：依序對 [points] 中每個座標派一個 click，
     * 每點延遲 CLICK_GAP_MS。回傳是否成功送出第一個 gesture。
     */
    fun performMultiClick(points: List<PointF>): Boolean {
        if (points.isEmpty()) return false
        // 第一個立刻點，後續用 handler 延遲
        var ok = performClickAt(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val p = points[i]
            mainHandler.postDelayed({
                performClickAt(p.x, p.y)
            }, CLICK_GAP_MS * i)
        }
        return ok
    }
}
