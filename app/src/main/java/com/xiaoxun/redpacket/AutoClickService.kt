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
 * 無障礙服務 v1.6
 *  - 只用來派發手勢點擊 (不再做文字掃描)
 *  - 影像識別在 ScreenCaptureService + ButtonMatcher 那一側
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        private const val CLICK_GAP_MS = 25L
        private const val GESTURE_DURATION_MS = 20L

        @Volatile var instance: AutoClickService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
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
