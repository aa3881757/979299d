package com.xiaoxun.redpacket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 純 Kotlin 的快速範本匹配。
 *
 * 為了在手機上即時運算，所有運算都在「縮圖空間」進行：
 *   - 螢幕擷取縮小到約 width = SCREEN_DOWN 像素
 *   - 範本同步縮小到對應比例
 *
 * 演算法：
 *   1. 以 stride 步長滑動範本，在每個位置計算與螢幕區域的相似度
 *   2. 相似度 = 1 - 平均 RGB 絕對差 / 255
 *   3. 回傳最佳位置 (還原為原始座標)，若低於閾值則回傳 null
 */
object ImageMatcher {

    /** 螢幕縮小後的目標寬度 */
    private const val SCREEN_DOWN = 360

    data class MatchResult(
        val centerX: Float,   // 原始螢幕座標
        val centerY: Float,
        val score: Float      // 0.0 ~ 1.0
    )

    /**
     * 把 [target] 圖在 [screen] 中尋找，回傳最佳位置與相似度。
     * [threshold] 0.5 ~ 1.0；低於閾值回傳 null。
     */
    fun findTemplate(screen: Bitmap, target: Bitmap, threshold: Float): MatchResult? {
        // 1) 縮小螢幕
        val sScale = SCREEN_DOWN.toFloat() / screen.width
        val sW = (screen.width * sScale).toInt().coerceAtLeast(1)
        val sH = (screen.height * sScale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(screen, sW, sH, true)

        // 2) 範本同步縮放：先以多個尺度嘗試（讓不同螢幕密度也能匹配）
        //    用三個尺度：0.8、1.0、1.2
        val scales = floatArrayOf(0.8f, 1.0f, 1.2f)
        var best: MatchResult? = null

        for (sc in scales) {
            val tw = (target.width * sScale * sc).toInt().coerceAtLeast(6)
            val th = (target.height * sScale * sc).toInt().coerceAtLeast(6)
            if (tw > sW || th > sH) continue
            val tmpl = Bitmap.createScaledBitmap(target, tw, th, true)

            val match = scanOnce(small, tmpl, threshold)
            if (match != null && (best == null || match.score > best.score)) {
                // 還原到原始座標
                val cx = match.centerX / sScale
                val cy = match.centerY / sScale
                best = MatchResult(cx, cy, match.score)
            }
            tmpl.recycle()
        }
        small.recycle()
        return best
    }

    /**
     * 一次完整掃描：兩階段。
     *  - 粗掃：stride = 4，找到 score >= threshold-0.1 的候選
     *  - 精掃：在候選附近 stride = 1 精確比對
     */
    private fun scanOnce(screen: Bitmap, tmpl: Bitmap, threshold: Float): MatchResult? {
        val sW = screen.width
        val sH = screen.height
        val tW = tmpl.width
        val tH = tmpl.height

        val screenPx = IntArray(sW * sH)
        screen.getPixels(screenPx, 0, sW, 0, 0, sW, sH)
        val tmplPx = IntArray(tW * tH)
        tmpl.getPixels(tmplPx, 0, tW, 0, 0, tW, tH)

        // 範本平均色（用於快速早退）
        var tAvgR = 0L; var tAvgG = 0L; var tAvgB = 0L
        for (p in tmplPx) {
            tAvgR += (p shr 16) and 0xFF
            tAvgG += (p shr 8) and 0xFF
            tAvgB += p and 0xFF
        }
        val tn = tmplPx.size
        tAvgR /= tn; tAvgG /= tn; tAvgB /= tn

        val coarseStride = 4
        var bestScore = 0f
        var bestX = -1
        var bestY = -1

        // 粗掃
        val coarseThreshold = max(0.3f, threshold - 0.15f)
        var y = 0
        while (y <= sH - tH) {
            var x = 0
            while (x <= sW - tW) {
                val score = compare(screenPx, sW, x, y, tmplPx, tW, tH, tAvgR, tAvgG, tAvgB, coarseStride)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x; bestY = y
                }
                x += coarseStride
            }
            y += coarseStride
        }

        if (bestScore < coarseThreshold || bestX < 0) return null

        // 精掃 (在最佳位置 ±coarseStride 範圍 stride=1)
        val minX = max(0, bestX - coarseStride)
        val maxX = min(sW - tW, bestX + coarseStride)
        val minY = max(0, bestY - coarseStride)
        val maxY = min(sH - tH, bestY + coarseStride)
        var fineBest = bestScore; var fineX = bestX; var fineY = bestY
        var yy = minY
        while (yy <= maxY) {
            var xx = minX
            while (xx <= maxX) {
                val s = compare(screenPx, sW, xx, yy, tmplPx, tW, tH, tAvgR, tAvgG, tAvgB, 1)
                if (s > fineBest) {
                    fineBest = s; fineX = xx; fineY = yy
                }
                xx++
            }
            yy++
        }

        if (fineBest < threshold) return null
        val cx = (fineX + tW / 2f)
        val cy = (fineY + tH / 2f)
        return MatchResult(cx, cy, fineBest)
    }

    /**
     * 比較螢幕 (x,y) 開始 tW*tH 區域與範本的相似度。
     * 採用每隔 [stride] 取樣，相似度 = 1 - 平均 RGB 絕對差 / 255
     */
    private fun compare(
        screen: IntArray, screenW: Int,
        x: Int, y: Int,
        tmpl: IntArray, tW: Int, tH: Int,
        tAvgR: Long, tAvgG: Long, tAvgB: Long,
        stride: Int
    ): Float {
        // 先比中心點顏色，差太多直接退出
        val cxs = x + tW / 2
        val cys = y + tH / 2
        val centerP = screen[cys * screenW + cxs]
        val cr = (centerP shr 16) and 0xFF
        val cg = (centerP shr 8) and 0xFF
        val cb = centerP and 0xFF
        if (abs(cr - tAvgR) + abs(cg - tAvgG) + abs(cb - tAvgB) > 360) return 0f

        var totalDiff = 0L
        var count = 0
        var ty = 0
        while (ty < tH) {
            val srcRow = (y + ty) * screenW + x
            val tmplRow = ty * tW
            var tx = 0
            while (tx < tW) {
                val sp = screen[srcRow + tx]
                val tp = tmpl[tmplRow + tx]
                val dr = abs(((sp shr 16) and 0xFF) - ((tp shr 16) and 0xFF))
                val dg = abs(((sp shr 8) and 0xFF) - ((tp shr 8) and 0xFF))
                val db = abs((sp and 0xFF) - (tp and 0xFF))
                totalDiff += dr + dg + db
                count++
                tx += stride
            }
            ty += stride
        }
        if (count == 0) return 0f
        val avg = totalDiff.toFloat() / (count * 3)   // 0 ~ 255
        return 1f - (avg / 255f)
    }

    /** 把 Bitmap 裁剪到指定 Rect（用於 ImageReader 抓到的 Bitmap 含 padding） */
    fun cropBitmap(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, w, h), Paint())
        return out
    }
}
