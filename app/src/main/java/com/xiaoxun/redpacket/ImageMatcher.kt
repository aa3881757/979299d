package com.xiaoxun.redpacket

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 紅包金幣偵測：HSV + 模板混合，**支援回傳多個目標**。
 *
 *  Step 1: 找畫面中夠飽和的紅色連通區（候選紅包）
 *  Step 2: 對每個紅色 blob，檢查中心是否有「金黃色圓形」
 *  Step 3: 對候選 blob 用 RGB 範本作驗證
 *
 *  v1.3：findRedCoin → **findRedCoins**，回傳所有通過驗證的目標。
 */
object ImageMatcher {

    data class MatchResult(
        val centerX: Float,
        val centerY: Float,
        val score: Float,
        val label: String
    )

    private const val SCREEN_DOWN = 240
    private const val MAX_RESULTS = 6   // 一次最多回傳 6 個目標

    /**
     * 在 [screen] 上尋找所有紅色金幣（紅包）。回傳清單按 score 由高到低排序。
     */
    fun findRedCoins(
        screen: Bitmap,
        template: Bitmap?,
        threshold: Float
    ): List<MatchResult> {
        val sScale = SCREEN_DOWN.toFloat() / screen.width
        val sW = (screen.width * sScale).toInt().coerceAtLeast(1)
        val sH = (screen.height * sScale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(screen, sW, sH, true)
        val px = IntArray(sW * sH)
        small.getPixels(px, 0, sW, 0, 0, sW, sH)

        val redBlobs = findAllBlobs(px, sW, sH, ::isSaturatedRed)
        small.recycle()
        if (redBlobs.isEmpty()) return emptyList()

        // 過濾尺寸/比例不合的
        val candidates = redBlobs
            .filter {
                val ratio = it.area.toFloat() / (sW * sH)
                val ar = it.width().toFloat() / max(1, it.height())
                ratio in 0.0006f..0.08f && ar in 0.4f..2.5f
            }
            .sortedByDescending { it.area }
            .take(MAX_RESULTS * 2)
        if (candidates.isEmpty()) return emptyList()

        val results = mutableListOf<MatchResult>()
        for (blob in candidates) {
            val centerScore = checkGoldCenter(px, sW, sH, blob)
            if (centerScore < 0.15f) continue

            val templScore = if (template != null) {
                verifyWithTemplate(screen, template,
                    (blob.cx() / sScale).toInt(),
                    (blob.cy() / sScale).toInt(),
                    (blob.width() / sScale * 1.2f).toInt().coerceAtLeast(30))
            } else 0.6f
            if (templScore < 0.45f) continue

            val finalScore = (centerScore * 0.4f + templScore * 0.6f).coerceIn(0f, 1f)
            if (finalScore < threshold) continue

            results += MatchResult(
                blob.cx() / sScale,
                blob.cy() / sScale,
                finalScore,
                "coin"
            )
        }

        // 重複去重：距離小於 80px 視為同一目標
        return dedupe(results, minDistance = 80f)
            .sortedByDescending { it.score }
            .take(MAX_RESULTS)
    }

    /** 兩個距離夠近的目標只保留分數較高那個 */
    private fun dedupe(list: List<MatchResult>, minDistance: Float): List<MatchResult> {
        val out = mutableListOf<MatchResult>()
        for (r in list.sortedByDescending { it.score }) {
            val tooClose = out.any { o ->
                val dx = o.centerX - r.centerX
                val dy = o.centerY - r.centerY
                dx * dx + dy * dy < minDistance * minDistance
            }
            if (!tooClose) out += r
        }
        return out
    }

    // ====================================================
    // HSV 顏色判斷
    // ====================================================

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
    // Connected component
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
                            blobs[i].merge(newBlob); merged = true; break
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

    private fun verifyWithTemplate(
        screen: Bitmap, template: Bitmap,
        cx: Int, cy: Int, boxSize: Int
    ): Float {
        val tW = boxSize
        val tH = (template.height.toFloat() / template.width * boxSize).toInt().coerceAtLeast(8)
        if (tW < 8 || tH < 8) return 0f
        val tScaled = Bitmap.createScaledBitmap(template, tW, tH, true)
        val tPx = IntArray(tW * tH)
        tScaled.getPixels(tPx, 0, tW, 0, 0, tW, tH)
        tScaled.recycle()

        val x0 = (cx - tW / 2).coerceIn(0, screen.width - tW)
        val y0 = (cy - tH / 2).coerceIn(0, screen.height - tH)
        val sPx = IntArray(tW * tH)
        screen.getPixels(sPx, 0, tW, x0, y0, tW, tH)

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
