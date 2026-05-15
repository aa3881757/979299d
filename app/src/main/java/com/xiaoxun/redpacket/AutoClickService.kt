package com.xiaoxun.redpacket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 無障礙服務：
 *  - 透過 dispatchGesture 在指定座標執行模擬點擊（給 ScreenCaptureService 用）
 *  - 自帶 **文字偵測**：定時掃描整個 UI tree 找「去看看」文字並自動點擊
 *
 * v1.2: 加入文字偵測 (findTextAndClick) 因為對「去看看」按鈕來說，
 *       讀 UI tree 比 OCR / 顏色匹配可靠太多。
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        @Volatile var instance: AutoClickService? = null
            private set
        // 要尋找的關鍵字 (可放多個)
        private val KEYWORDS = arrayOf("去看看")

        /** ScreenCaptureService 在啟動/停止時呼叫 */
        @Volatile var textScanEnabled: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastClickTs = 0L
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不處理事件，僅輪詢 */ }
    override fun onInterrupt() { }

    private fun startScanLoop() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (textScanEnabled) scanForKeywords()
                mainHandler.postDelayed(this, scanIntervalMs)
            }
        })
    }

    /** 在當前 window 樹中尋找 KEYWORDS，找到後直接點 */
    private fun scanForKeywords() {
        try {
            val root = rootInActiveWindow ?: return
            for (kw in KEYWORDS) {
                val found = findNodeContaining(root, kw) ?: continue
                val now = System.currentTimeMillis()
                if (now - lastClickTs < 800) {
                    found.recycle(); continue
                }
                val rect = Rect()
                found.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) {
                    found.recycle(); continue
                }
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                lastClickTs = now
                Log.i(TAG, "text match '$kw' -> click ($cx,$cy)")
                // 優先用節點本身的 click（更穩）
                val clickedByNode =
                    found.isClickable && found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clickedByNode) {
                    // 否則用 gesture
                    performClickAt(cx, cy)
                }
                FloatingOverlayService.flashHit(this)
                found.recycle()
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scan error: ${t.message}")
        }
    }

    private fun findNodeContaining(node: AccessibilityNodeInfo?, keyword: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(keyword) || desc.contains(keyword)) {
            // 若節點本身不可點，往父層找可點的
            return findClickableAncestor(node) ?: node
        }
        for (i in 0 until node.childCount) {
            val r = findNodeContaining(node.getChild(i), keyword)
            if (r != null) return r
        }
        return null
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

    /** 對指定座標執行單擊 (給 ScreenCaptureService 找到目標時呼叫) */
    fun performClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 40L))
            .build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "click ($x,$y) ok")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "click ($x,$y) cancelled")
            }
        }, null)
    }
}
