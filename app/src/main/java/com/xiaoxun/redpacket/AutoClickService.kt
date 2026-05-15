package com.xiaoxun.redpacket

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 無障礙服務：透過 dispatchGesture 在指定座標執行模擬點擊。
 * ScreenCaptureService 偵測到目標圖時呼叫 [performClickAt]。
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"
        @Volatile var instance: AutoClickService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不處理事件，僅用手勢 */ }
    override fun onInterrupt() { }

    /**
     * 對指定座標執行單擊。返回 true 表示已派發。
     */
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
