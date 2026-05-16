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
 * 「去看看」黃色膠囊按鈕偵測 (OpenCV) — v1.7 兩階段。
 *
 *  原本：整條橘色 banner 都被當成黃色 → 點到 banner 中心 (橘色字)，沒點到按鈕。
 *  現在：
 *    Stage 1: 寬鬆 HSV (橘+黃) 找 banner candidate
 *    Stage 2: 在 banner 右半邊，用嚴格黃色 HSV 找膠囊按鈕 contour
 *             → boundingRect 中心 = 真正要點的位置
 *
 *  HSV (OpenCV H 範圍 0-179)：
 *    - Wide:   H 10-40, S  80-255, V 130-255   (橘色 banner + 黃色按鈕)
 *    - Strict: H 22-38, S 130-255, V 200-255   (只剩亮黃色按鈕本體)
 */
object ButtonMatcher {

    private const val TAG = "ButtonMatcher"
    private const val SCREEN_DOWN_WIDTH = 480
    private const val MAX_RESULTS = 4

    /** banner 右側搜尋範圍 (寬度比例) */
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
        var wideMask: Mat? = null
        try {
            // 1. Bitmap → Mat → 縮放
            src = Mat()
            Utils.bitmapToMat(screen, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

            val scale = SCREEN_DOWN_WIDTH.toDouble() / src.width()
            val sw = (src.width() * scale).toInt().coerceAtLeast(1)
            val sh = (src.height() * scale).toInt().coerceAtLeast(1)
            small = Mat()
            Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()))

            // 2. BGR → HSV
            hsv = Mat()
            Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)

            // === Stage 1: Wide mask 找 banner ===
            wideMask = Mat()
            Core.inRange(
                hsv,
                Scalar(10.0, 80.0, 130.0),
                Scalar(40.0, 255.0, 255.0),
                wideMask
            )
            // 把 banner 中央被文字打斷的縫隙縫起來
            val closeKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, Size(15.0, 9.0)
            )
            Imgproc.morphologyEx(wideMask, wideMask, Imgproc.MORPH_CLOSE, closeKernel)
            closeKernel.release()

            // 找 banner contour
            val bannerContours = mutableListOf<MatOfPoint>()
            val bannerHierarchy = Mat()
            Imgproc.findContours(
                wideMask, bannerContours, bannerHierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            bannerHierarchy.release()

            val totalArea = sw.toDouble() * sh
            val results = mutableListOf<Result>()

            for (contour in bannerContours) {
                val bannerRect = Imgproc.boundingRect(contour)
                contour.release()

                val area = bannerRect.width.toDouble() * bannerRect.height
                // 過濾過小或過大的區域
                if (area < totalArea * 0.003 || area > totalArea * 0.25) continue
                val ar = bannerRect.width.toDouble() / bannerRect.height
                // banner 通常很長; 單獨按鈕也有 1.5-4 的 AR
                if (ar < 1.4) continue

                // === Stage 2: 在 banner 右半邊找黃色膠囊按鈕 ===
                val btnRect = findYellowButtonInside(hsv, bannerRect, sw, sh, closeKernel = null)
                    ?: continue

                // 黃色按鈕中心 (在縮圖座標)
                val btnCenterX = btnRect.x + btnRect.width / 2.0
                val btnCenterY = btnRect.y + btnRect.height / 2.0

                // 還原到原始螢幕座標
                val cx = (btnCenterX / scale).toFloat()
                val cy = (btnCenterY / scale).toFloat()

                // 評分: 按鈕長寬比合理度
                val btnAr = btnRect.width.toDouble() / btnRect.height
                val arScore = when {
                    btnAr in 1.8..3.5 -> 1.0
                    btnAr in 1.4..4.5 -> 0.85
                    else -> 0.6
                }
                val score = (0.8 + arScore * 0.2).toFloat().coerceAtMost(1f)
                if (score < threshold) continue

                results += Result(cx, cy, score)
                Log.i(TAG, "button found at ($cx, $cy), AR=$btnAr, score=$score")
            }

            return results
                .sortedByDescending { it.score }
                .take(MAX_RESULTS)
        } catch (t: Throwable) {
            Log.e(TAG, "findButtons error", t)
            return emptyList()
        } finally {
            src?.release(); small?.release(); hsv?.release(); wideMask?.release()
        }
    }

    /**
     * 在 [bannerRect] 的右側用「嚴格黃色 HSV」找膠囊按鈕。
     * 回傳按鈕在縮圖座標下的 boundingRect，找不到回 null。
     */
    private fun findYellowButtonInside(
        hsv: Mat,
        bannerRect: Rect,
        sw: Int, sh: Int,
        closeKernel: Mat?
    ): Rect? {
        // 搜尋區域：banner 右 RIGHT_SEARCH_RATIO 的範圍
        // 例如 banner 寬 400px, 右側搜尋區從 x+180 到 x+400
        val rightW = (bannerRect.width * RIGHT_SEARCH_RATIO).toInt().coerceAtLeast(20)
        val rightX = bannerRect.x + bannerRect.width - rightW
        val rx = rightX.coerceAtLeast(0)
        val ry = bannerRect.y.coerceAtLeast(0)
        val rw = (rightX + rightW - rx).coerceAtMost(sw - rx)
        val rh = bannerRect.height.coerceAtMost(sh - ry)
        if (rw < 10 || rh < 6) return null

        val rightRoi = hsv.submat(Rect(rx, ry, rw, rh))
        val strictMask = Mat()
        Core.inRange(
            rightRoi,
            Scalar(22.0, 130.0, 200.0),
            Scalar(38.0, 255.0, 255.0),
            strictMask
        )
        rightRoi.release()

        // 形態學閉合把黃色文字洞填起來
        val kernel = closeKernel ?: Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT, Size(7.0, 7.0)
        )
        Imgproc.morphologyEx(strictMask, strictMask, Imgproc.MORPH_CLOSE, kernel)
        if (closeKernel == null) kernel.release()

        // 找嚴格黃色 contours
        val yellowContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            strictMask, yellowContours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        strictMask.release()

        if (yellowContours.isEmpty()) return null

        // 取面積最大的黃色 contour (它就是按鈕)
        var best: Rect? = null
        var bestArea = 0
        for (c in yellowContours) {
            val r = Imgproc.boundingRect(c)
            c.release()
            val a = r.width * r.height
            // 過濾太小的雜訊
            if (a < 80) continue
            // 過濾長寬比不對的 (按鈕大概 1.5:1 ~ 5:1)
            val arNum = r.width.toDouble() / r.height
            if (arNum < 1.3 || arNum > 6.0) continue
            if (a > bestArea) {
                bestArea = a
                best = r
            }
        }
        if (best == null) return null

        // 還原到 hsv 全圖座標
        return Rect(rx + best.x, ry + best.y, best.width, best.height)
    }
}
