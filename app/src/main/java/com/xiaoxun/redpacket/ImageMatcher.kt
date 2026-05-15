package com.xiaoxun.redpacket

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 紅包金幣偵測：HSV 顏色 + 中心模板驗證的「混合」流程。
 *
 *  Step 1: 找畫面中夠飽和的紅色連通區（候選紅包）
 *  Step 2: 對每個紅色 blob，檢查中心是否有「金黃色圓形」
 *          - 紅色像素中心 ±25% 半徑內，金黃色像素佔比 > 0.15 → pass
 *  Step 3: 對候選 blob 用 RGB 範本作最終比對（target_coin.png）
 *          - 若範本相似度 > 0.55 → 確認是紅包
 *
 *  這樣可以濾掉純紅色 banner / 紅色文字 / 紅色背景等誤判。
 */
object ImageMatcher {

    data class MatchResult(
        val centerX: Float,
        val centerY: Float,
        val score: Float,
        val label: String
    )

    private const val SCREEN_DOWN = 240

    /**
     * 在 [screen] 上尋找紅色金幣（紅包）。
     * [template] 是 target_coin.png 縮圖供作最後驗證。
     * threshold 0.5~1.0：影響整體 score 門檻。
     */
    fun findRedCoin(
        screen: Bitmap,
        template: Bitmap?,
        threshold: Float
    ): MatchResult? {
        val sScale = SCREEN_DOWN.toFloat() / screen.width
        val sW = (screen.width * sScale).toInt().coerceAtLeast(1)
        val sH = (screen.height * sScale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(screen, sW, sH, true)
        val px = IntArray(sW * sH)
        small.getPixels(px, 0, sW, 0, 0, sW, sH)

        // Step 1: 找所有紅色 blob
        val redBlobs = findAllBlobs(px, sW, sH, ::isSaturatedRed)
        small.recycle()
        if (redBlobs.isEmpty()) return null

        // 由大到小過濾
        val candidates = redBlobs
            .filter {
                val ratio = it.area.toFloat() / (sW * sH)
                val ar = it.width().toFloat() / max(1, it.height())
                ratio in 0.0006f..0.08f && ar in 0.4f..2.5f
            }
            .sortedByDescending { it.area }
            .take(6)
        if (candidates.isEmpty()) return null

        // Step 2: 對每個候選 blob，檢查中心是否有金黃色
        var best: Pair<Blob, Float>? = null
        for (blob in candidates) {
            val centerScore = checkGoldCenter(px, sW, sH, blob)
            if (centerScore >= 0.15f) {
                if (best == null || centerScore > best.second) {
                    best = blob to centerScore
                }
            }
        }
        if (best == null) return null
        val (blob, goldRatio) = best

        // Step 3: 範本驗證（若有提供）
        val templScore = if (template != null) {
            verifyWithTemplate(screen, template,
                (blob.cx() / sScale).toInt(),
                (blob.cy() / sScale).toInt(),
                (blob.width() / sScale * 1.2f).toInt().coerceAtLeast(30))
        } else 0.6f
        if (templScore < 0.45f) return null

        val finalScore = (goldRatio * 0.4f + templScore * 0.6f).coerceIn(0f, 1f)
        if (finalScore < threshold) return null

        val cx = blob.cx() / sScale
        val cy = blob.cy() / sScale
        return MatchResult(cx, cy, finalScore, "coin")
    }

    // ====================================================
    // HSV 顏色判斷
    // ====================================================

    /** 飽和明亮的紅色（紅包底色） */
    private fun isSaturatedRed(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r < 170) return false
        if (g > 110) return false
        if (b > 120) return false
        if (r - max(g, b) < 60) return false
        return true
    }

    /** 金/黃 色（金幣） */
    private fun isGold(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r < 180) return false
        if (g < 140 || g > 230) return false
        if (b > 130) return false
        if (r - b < 70) return false
        return true
    }

    // ====================================================
    // Connected component (簡化版)
    // ====================================================

    private class Blob(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var area: Int) {
        fun width() = maxX - minX + 1
        fun height() = maxY - minY + 1
        fun cx() = (minX + maxX) / 2f
        fun cy() = (minY + maxY) / 2f
        fun overlapsOrNear(other: Blob, gap: Int): Boolean {
            return !(maxX + gap < other.minX || other.maxX + gap < minX ||
                    maxY + gap < other.minY || other.maxY + gap < minY)
        }
        fun merge(other: Blob) {
            minX = min(minX, other.minX); minY = min(minY, other.minY)
            maxX = max(maxX, other.maxX); maxY = max(maxY, other.maxY)
            area += other.area
        }
    }

    private fun findAllBlobs(px: IntArray, w: Int, h: Int, match: (Int) -> Boolean): List<Blob> {
        val blobs = mutableListOf<Blob>()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (match(px[y * w + x])) {
                    val start = x
                    while (x < w && match(px[y * w + x])) x++
                    val end = x - 1
                    val newBlob = Blob(start, y, end, y, end - start + 1)
                    var merged = false
                    for (i in blobs.indices) {
                        if (blobs[i].overlapsOrNear(newBlob, 4)) {
                            blobs[i].merge(newBlob)
                            merged = true; break
                        }
                    }
                    if (!merged) blobs += newBlob
                } else x++
            }
        }
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in blobs.indices) {
                for (j in i + 1 until blobs.size) {
                    if (blobs[i].overlapsOrNear(blobs[j], 4)) {
                        blobs[i].merge(blobs[j])
                        blobs.removeAt(j); changed = true; break@outer
                    }
                }
            }
        }
        return blobs
    }

    /** 計算 blob 中心 ±25% 半徑內金色像素佔比 */
    private fun checkGoldCenter(px: IntArray, w: Int, h: Int, blob: Blob): Float {
        val cx = blob.cx().toInt()
        val cy = blob.cy().toInt()
        val r = (min(blob.width(), blob.height()) * 0.30f).toInt().coerceAtLeast(2)
        var goldCount = 0
        var totalCount = 0
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val x = cx + dx; val y = cy + dy
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                totalCount++
                if (isGold(px[y * w + x])) goldCount++
            }
        }
        return if (totalCount == 0) 0f else goldCount.toFloat() / totalCount
    }

    /**
     * 用 [template] 在 [screen] 的 (cx,cy) 附近 ±boxSize 區域作範本驗證。
     * 回傳相似度 0~1。
     */
    private fun verifyWithTemplate(
        screen: Bitmap, template: Bitmap,
        cx: Int, cy: Int, boxSize: Int
    ): Float {
        // 把範本縮到 boxSize 大小
        val tW = boxSize
        val tH = (template.height.toFloat() / template.width * boxSize).toInt().coerceAtLeast(8)
        if (tW < 8 || tH < 8) return 0f
        val tScaled = Bitmap.createScaledBitmap(template, tW, tH, true)
        val tPx = IntArray(tW * tH)
        tScaled.getPixels(tPx, 0, tW, 0, 0, tW, tH)
        tScaled.recycle()

        // 從 screen 抓對應區域
        val x0 = (cx - tW / 2).coerceIn(0, screen.width - tW)
        val y0 = (cy - tH / 2).coerceIn(0, screen.height - tH)
        val sPx = IntArray(tW * tH)
        screen.getPixels(sPx, 0, tW, x0, y0, tW, tH)

        // 相似度 = 1 - 平均 RGB 差 / 255 (sample stride 2 加速)
        var totalDiff = 0L; var count = 0
        var i = 0
        while (i < tPx.size) {
            val a = sPx[i]; val b = tPx[i]
            val dr = Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = Math.abs((a and 0xFF) - (b and 0xFF))
            totalDiff += dr + dg + db
            count++
            i += 2
        }
        if (count == 0) return 0f
        val avg = totalDiff.toFloat() / (count * 3)
        return 1f - (avg / 255f)
    }
}
