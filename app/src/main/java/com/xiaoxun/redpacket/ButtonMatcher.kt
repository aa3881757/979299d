package com.xiaoxun.redpacket

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 「去看看」黃色膠囊按鈕偵測 v1.10。
 *
 *  策略：兩條路線並行
 *    A) Banner-then-Button：寬鬆 HSV 找 banner → 右側嚴格黃色 → 取按鈕
 *    B) Direct Button：直接在整張畫面用「寬鬆-中等」黃色 HSV 找按鈕形狀的 contour
 *  兩條結果都吐出去，由 dedupe 去除重複位置。
 *
 *  HSV (OpenCV H 0-179)：
 *    Wide   :  H 10-42, S  60-255, V 120-255   (橘色 banner + 黃色按鈕)
 *    Medium :  H 18-42, S 100-255, V 170-255   (大部分黃色按鈕)
 *    Strict :  H 22-40, S 130-255, V 190-255   (純亮黃)
 */
object ButtonMatcher {

    private const val TAG = "ButtonMatcher"
    private const val SCREEN_DOWN_WIDTH = 480
    private const val MAX_RESULTS = 4
    private const val RIGHT_SEARCH_RATIO = 0.55

    data class Result(val centerX: Float, val centerY: Float, val score: Float)

    fun findButtons(
        screen: Bitmap,
        template: Bitmap?,
        threshold: Float
    ): List<Result> {
        var src: Mat? = null
        var small: Mat? = null
        var hsv: Mat? = null
        try {
            src = Mat()
            Utils.bitmapToMat(screen, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            val scale = SCREEN_DOWN_WIDTH.toDouble() / src.width()
            val sw = (src.width() * scale).toInt().coerceAtLeast(1)
            val sh = (src.height() * scale).toInt().coerceAtLeast(1)
            small = Mat()
            Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()))

            hsv = Mat()
            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)

            val totalArea = sw.toDouble() * sh
            val allResults = mutableListOf<Result>()

            // ============ 路線 A: Banner → Button ============
            allResults += routeA(hsv, sw, sh, totalArea, scale)

            // ============ 路線 B: Direct Button ============
            allResults += routeB(hsv, sw, sh, totalArea, scale)

            // 去重 + 篩選 threshold
            val filtered = allResults.filter { it.score >= threshold }
            return dedupe(filtered, minDist = 80f / scale.toFloat())
                .sortedByDescending { it.score }
                .take(MAX_RESULTS)
        } catch (t: Throwable) {
            Log.e(TAG, "findButtons error", t)
            return emptyList()
        } finally {
            src?.release(); small?.release(); hsv?.release()
        }
    }

    /** A: 寬鬆 HSV 找 banner → 右側 ROI 用嚴格黃色找按鈕 */
    private fun routeA(
        hsv: Mat, sw: Int, sh: Int, totalArea: Double, scale: Double
    ): List<Result> {
        val out = mutableListOf<Result>()
        val wideMask = Mat()
        Core.inRange(
            hsv,
            Scalar(10.0, 60.0, 120.0),
            Scalar(42.0, 255.0, 255.0),
            wideMask
        )
        val k1 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 9.0))
        Imgproc.morphologyEx(wideMask, wideMask, Imgproc.MORPH_CLOSE, k1)
        k1.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(wideMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        wideMask.release()

        for (c in contours) {
            val rect = Imgproc.boundingRect(c)
            c.release()
            val area = rect.width.toDouble() * rect.height
            if (area < totalArea * 0.003 || area > totalArea * 0.25) continue
            val ar = rect.width.toDouble() / rect.height
            if (ar < 1.4) continue

            val btn = findStrictYellowInside(hsv, rect, sw, sh) ?: continue
            val bx = (btn.x + btn.width / 2.0) / scale
            val by = (btn.y + btn.height / 2.0) / scale
            val btnAr = btn.width.toDouble() / btn.height
            val score = (if (btnAr in 1.6..4.5) 0.95 else 0.85).toFloat()
            out += Result(bx.toFloat(), by.toFloat(), score)
            Log.i(TAG, "routeA: ($bx, $by) AR=$btnAr score=$score")
        }
        return out
    }

    /** B: 整張畫面用中等黃色 HSV 找按鈕形狀的 contour */
    private fun routeB(
        hsv: Mat, sw: Int, sh: Int, totalArea: Double, scale: Double
    ): List<Result> {
        val out = mutableListOf<Result>()
        val medMask = Mat()
        Core.inRange(
            hsv,
            Scalar(18.0, 100.0, 170.0),
            Scalar(42.0, 255.0, 255.0),
            medMask
        )
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 7.0))
        Imgproc.morphologyEx(medMask, medMask, Imgproc.MORPH_CLOSE, k)
        k.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(medMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        medMask.release()

        for (c in contours) {
            val rect = Imgproc.boundingRect(c)
            val cArea = Imgproc.contourArea(c)
            c.release()
            val area = rect.width.toDouble() * rect.height
            // 按鈕大小範圍 (相對螢幕)
            if (area < totalArea * 0.001 || area > totalArea * 0.04) continue
            val ar = rect.width.toDouble() / rect.height
            // 按鈕長寬比 1.5-5
            if (ar < 1.5 || ar > 5.5) continue
            // 長方形度
            if (cArea / area < 0.55) continue

            val cx = (rect.x + rect.width / 2.0) / scale
            val cy = (rect.y + rect.height / 2.0) / scale
            val score = when {
                ar in 2.0..3.5 -> 0.90f
                ar in 1.7..4.5 -> 0.80f
                else -> 0.70f
            }
            out += Result(cx.toFloat(), cy.toFloat(), score)
            Log.i(TAG, "routeB: ($cx, $cy) AR=$ar score=$score")
        }
        return out
    }

    /** 在 [bannerRect] 的右側用嚴格黃色找按鈕 contour */
    private fun findStrictYellowInside(
        hsv: Mat, bannerRect: Rect, sw: Int, sh: Int
    ): Rect? {
        val rightW = (bannerRect.width * RIGHT_SEARCH_RATIO).toInt().coerceAtLeast(20)
        val rightX = bannerRect.x + bannerRect.width - rightW
        val rx = rightX.coerceAtLeast(0)
        val ry = bannerRect.y.coerceAtLeast(0)
        val rw = (rightX + rightW - rx).coerceAtMost(sw - rx)
        val rh = bannerRect.height.coerceAtMost(sh - ry)
        if (rw < 10 || rh < 6) return null

        val roi = hsv.submat(Rect(rx, ry, rw, rh))
        val mask = Mat()
        // 略放寬 strict 條件
        Core.inRange(
            roi,
            Scalar(22.0, 110.0, 180.0),
            Scalar(40.0, 255.0, 255.0),
            mask
        )
        roi.release()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k)
        k.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()

        var best: Rect? = null
        var bestArea = 0
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            c.release()
            val a = r.width * r.height
            if (a < 60) continue
            val ar = r.width.toDouble() / r.height
            if (ar < 1.2 || ar > 6.0) continue
            if (a > bestArea) { bestArea = a; best = r }
        }
        return best?.let { Rect(rx + it.x, ry + it.y, it.width, it.height) }
    }

    private fun dedupe(list: List<Result>, minDist: Float): List<Result> {
        val out = mutableListOf<Result>()
        for (r in list.sortedByDescending { it.score }) {
            val tooClose = out.any { o ->
                val dx = o.centerX - r.centerX
                val dy = o.centerY - r.centerY
                dx * dx + dy * dy < minDist * minDist
            }
            if (!tooClose) out += r
        }
        return out
    }
}
