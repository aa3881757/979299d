package com.xiaoxun.redpacket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 無障礙服務 v1.10
 *  - 多筆點擊改成 GestureDescription 多 stroke 「同時」派發
 *    Android 一次最多 MAX_STROKES (預設 10)，所以分批，每批同時觸發
 *  - 紅包數量爆多時，原本 12 點 sequential = 300ms，現在 1 批 = ~30ms
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        /** Android 平台預設 10. 超過會丟例外 */
        private const val MAX_STROKES_PER_GESTURE = 10
        /** 同一批內每個 stroke 的時長 */
        private const val GESTURE_DURATION_MS = 18L
        /** 兩批之間的延遲 (避免被 OS 視為連續同手指拖曳) */
        private const val BATCH_GAP_MS = 30L

        @Volatile var instance: AutoClickService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected, maxStrokes=${GestureDescription.getMaxStrokeCount()}")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun performClickAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 多點同時點擊：把 [points] 分批 (每批至多 10 點)，
     * 每批用一個 GestureDescription 同時派發，批與批之間延遲 BATCH_GAP_MS。
     */
    fun performMultiClick(points: List<PointF>): Boolean {
        if (points.isEmpty()) return false
        val maxStrokes = GestureDescription.getMaxStrokeCount()
            .coerceAtMost(MAX_STROKES_PER_GESTURE).coerceAtLeast(1)
        val batches = points.chunked(maxStrokes)
        var delay = 0L
        var anyDispatched = false
        for (batch in batches) {
            val builder = GestureDescription.Builder()
            for (p in batch) {
                val path = Path().apply { moveTo(p.x, p.y) }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS))
            }
            val gesture = builder.build()
            if (delay == 0L) {
                anyDispatched = dispatchGesture(gesture, null, null) || anyDispatched
            } else {
                mainHandler.postDelayed({ dispatchGesture(gesture, null, null) }, delay)
            }
            delay += BATCH_GAP_MS
        }
        Log.i(TAG, "performMultiClick: ${points.size} points in ${batches.size} batches")
        return anyDispatched
    }
}
