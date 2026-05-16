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
import kotlin.math.abs

/**
 * 「去看看」黃色圓角按鈕偵測 (OpenCV)。
 *
 * 流程：
 *  1. Bitmap → Mat → 縮放到寬 480
 *  2. BGR → HSV
 *  3. inRange 過濾黃色 (H 15-35, S 80-255, V 150-255)
 *  4. 形態學 close 填滿縫隙
 *  5. findContours 取所有輪廓
 *  6. 過濾 (a) 面積 (b) 寬高比 1.5-6 (c) 長方形度
 *  7. 對候選 ROI 跑 multi-scale matchTemplate 驗證
 *  8. 通過閾值的回傳，按 score 排序
 */
object ButtonMatcher {

    private const val TAG = "ButtonMatcher"
    private const val SCREEN_DOWN_WIDTH = 480
    private const val MAX_RESULTS = 6

    data class Result(val centerX: Float, val centerY: Float, val score: Float)

    /**
     * @param screen 整個螢幕的截圖
     * @param template target_button.png 範本
     * @param threshold 最低相似度 (0.5~1.0)
     */
    fun findButtons(
        screen: Bitmap,
        template: Bitmap?,
        threshold: Float
    ): List<Result> {
        var src: Mat? = null
        var small: Mat? = null
        var hsv: Mat? = null
        var mask: Mat? = null
        try {
            // 1. Bitmap → Mat
            src = Mat()
            Utils.bitmapToMat(screen, src)
            // bitmapToMat 是 RGBA, 轉成 BGR
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            // 2. Downscale
            val scale = SCREEN_DOWN_WIDTH.toDouble() / src.width()
            val sw = (src.width() * scale).toInt().coerceAtLeast(1)
            val sh = (src.height() * scale).toInt().coerceAtLeast(1)
            small = Mat()
            Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()))

            // 3. BGR → HSV
            hsv = Mat()
            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)

            // 4. 黃色遮罩 (OpenCV H 範圍 0-179)
            mask = Mat()
            Core.inRange(
                hsv,
                Scalar(15.0, 80.0, 150.0),
                Scalar(40.0, 255.0, 255.0),
                mask
            )

            // 5. 形態學 close 把按鈕中央的文字洞填起來
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 9.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // 6. 找輪廓
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            hierarchy.release()

            val totalArea = sw.toDouble() * sh
            val candidates = mutableListOf<Rect>()
            for (c in contours) {
                val rect = Imgproc.boundingRect(c)
                val area = rect.width.toDouble() * rect.height
                val arNum = rect.width.toDouble() / rect.height
                // 區域 0.3% ~ 8% 螢幕
                if (area < totalArea * 0.003 || area > totalArea * 0.08) {
                    c.release(); continue
                }
                // 長寬比 1.5 ~ 6 (圓角長條按鈕)
                if (arNum < 1.5 || arNum > 6.0) {
                    c.release(); continue
                }
                // 長方形度：輪廓面積 / 邊框面積 > 0.7
                val cArea = Imgproc.contourArea(c)
                if (cArea / area < 0.65) {
                    c.release(); continue
                }
                candidates += rect
                c.release()
            }

            if (candidates.isEmpty()) return emptyList()

            // 7. 對每個候選做 multi-scale matchTemplate (若 template 提供)
            val results = mutableListOf<Result>()
            if (template != null) {
                val tplMat = Mat()
                Utils.bitmapToMat(template, tplMat)
                Imgproc.cvtColor(tplMat, tplMat, Imgproc.COLOR_RGBA2BGR)

                for (rect in candidates) {
                    val score = matchAtMultiScale(small, tplMat, rect)
                    if (score < threshold) continue
                    val cx = (rect.x + rect.width / 2.0) / scale
                    val cy = (rect.y + rect.height / 2.0) / scale
                    results += Result(cx.toFloat(), cy.toFloat(), score)
                }
                tplMat.release()
            } else {
                // 沒範本就純靠形狀，給固定 0.65 分
                for (rect in candidates) {
                    val cx = (rect.x + rect.width / 2.0) / scale
                    val cy = (rect.y + rect.height / 2.0) / scale
                    results += Result(cx.toFloat(), cy.toFloat(), 0.65f)
                }
            }

            return results
                .sortedByDescending { it.score }
                .take(MAX_RESULTS)
        } catch (t: Throwable) {
            Log.e(TAG, "findButtons error", t)
            return emptyList()
        } finally {
            src?.release(); small?.release(); hsv?.release(); mask?.release()
        }
    }

    /**
     * 在 [smallScreen] 內 [rect] 區域 (對應縮小後座標) 中找 [tpl] 範本，
     * 試多種縮放比例。回傳最高相似度 (0~1)。
     */
    private fun matchAtMultiScale(smallScreen: Mat, tpl: Mat, rect: Rect): Float {
        val scales = doubleArrayOf(0.7, 0.85, 1.0, 1.15, 1.3)
        var best = 0f

        // 把 ROI 略放大 10% 留邊距讓 template 有滑動空間
        val pad = (max(rect.width, rect.height) * 0.1).toInt().coerceAtLeast(2)
        val rx = (rect.x - pad).coerceAtLeast(0)
        val ry = (rect.y - pad).coerceAtLeast(0)
        val rw = (rect.width + pad * 2).coerceAtMost(smallScreen.width() - rx)
        val rh = (rect.height + pad * 2).coerceAtMost(smallScreen.height() - ry)
        if (rw < 10 || rh < 6) return 0f
        val roi = smallScreen.submat(Rect(rx, ry, rw, rh))

        for (s in scales) {
            // 範本縮放到 rect 的對應大小
            val targetW = (rect.width * s).toInt().coerceAtLeast(8)
            val targetH = (tpl.height().toDouble() / tpl.width() * targetW).toInt().coerceAtLeast(6)
            if (targetW > rw - 4 || targetH > rh - 4) continue
            val tplSize = Size(targetW.toDouble(), targetH.toDouble())
            val tplResized = Mat()
            Imgproc.resize(tpl, tplResized, tplSize)
            try {
                val resultCols = rw - targetW + 1
                val resultRows = rh - targetH + 1
                if (resultCols < 1 || resultRows < 1) continue
                val result = Mat(resultRows, resultCols, org.opencv.core.CvType.CV_32F)
                Imgproc.matchTemplate(roi, tplResized, result, Imgproc.TM_CCOEFF_NORMED)
                val mmr = Core.minMaxLoc(result)
                if (mmr.maxVal > best) best = mmr.maxVal.toFloat()
                result.release()
            } finally {
                tplResized.release()
            }
        }
        roi.release()
        return best
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}
