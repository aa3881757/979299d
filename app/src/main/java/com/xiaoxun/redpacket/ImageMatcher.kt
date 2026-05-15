package com.xiaoxun.redpacket

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 偵測器：以 HSV 顏色為主、範本匹配為輔。
 *
 * v1.1：放棄純像素範本匹配（在不同手機尺寸/字型下太脆弱），
 *       改用 HSV 顏色簽章 + Connected Component blob。
 *
 *  - 黃色按鈕：偵測「飽和明亮的黃色/橘黃」連通區，長寬比 1.5~6 (橢圓/長方形)
 *  - 紅色金幣：偵測「飽和紅色」連通區，中央含金色 (擇優)
 */
object ImageMatcher {

    /** 結果 */
    data class MatchResult(
        val centerX: Float,
        val centerY: Float,
        val score: Float,   // 0.0 ~ 1.0；signal strength
        val label: String   // "button" or "coin"
    )

    /** 螢幕縮小後的目標寬度（提速） */
    private const val SCREEN_DOWN = 240

    /**
     * 在 [screen] 上尋找紅包按鈕。
     * threshold 0.5~1.0：越高越嚴格。實際代表「最小連通像素數佔比 + 平均色純度」。
     *
     * template1, template2 暫時不用（保留簽名以相容），未來可結合範本作二次驗證。
     */
    fun findTarget(
        screen: Bitmap,
        threshold: Float
    ): MatchResult? {
        // 縮小
        val sScale = SCREEN_DOWN.toFloat() / screen.width
        val sW = (screen.width * sScale).toInt().coerceAtLeast(1)
        val sH = (screen.height * sScale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(screen, sW, sH, true)
        val px = IntArray(sW * sH)
        small.getPixels(px, 0, sW, 0, 0, sW, sH)

        // 找黃色按鈕
        val yellow = findBlob(px, sW, sH, ::isYellow)
        // 找紅色金幣
        val red = findBlob(px, sW, sH, ::isRed)

        small.recycle()

        // 計算 score：blob 大小相對螢幕比例 + 顏色濃度
        val results = mutableListOf<MatchResult>()
        if (yellow != null) {
            val ratio = yellow.area.toFloat() / (sW * sH).toFloat()
            // 黃色按鈕約佔螢幕 0.3%~5%
            val sizeOk = ratio in 0.002f..0.10f
            val ar = yellow.width().toFloat() / max(1, yellow.height())
            val arOk = ar in 1.2f..7f   // 寬大於高 (橫向按鈕)
            if (sizeOk && arOk) {
                val score = computeScore(ratio, 0.003f, 0.05f, ar, 1.5f, 5f)
                if (score >= threshold) {
                    val cx = (yellow.cx() / sScale)
                    val cy = (yellow.cy() / sScale)
                    results += MatchResult(cx, cy, score, "button")
                }
            }
        }
        if (red != null) {
            val ratio = red.area.toFloat() / (sW * sH).toFloat()
            val sizeOk = ratio in 0.0008f..0.06f
            val ar = red.width().toFloat() / max(1, red.height())
            val arOk = ar in 0.4f..2.5f  // 紅色金幣接近正方
            if (sizeOk && arOk) {
                val score = computeScore(ratio, 0.001f, 0.03f, ar, 0.7f, 1.6f)
                if (score >= threshold) {
                    val cx = (red.cx() / sScale)
                    val cy = (red.cy() / sScale)
                    results += MatchResult(cx, cy, score, "coin")
                }
            }
        }

        return results.maxByOrNull { it.score }
    }

    // --- HSV 顏色判斷 ---

    /** 是否為「黃色/橘黃」(去看看按鈕的代表色) */
    private fun isYellow(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        // 條件：R 高、G 中高、B 低；R > G > B；S 高
        if (r < 200) return false
        if (b > 130) return false
        if (g < 120 || g > 230) return false
        if (r - b < 80) return false   // 飽和度
        return true
    }

    /** 是否為「紅色」(紅包金幣底色) */
    private fun isRed(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        if (r < 170) return false
        if (g > 100) return false
        if (b > 110) return false
        if (r - max(g, b) < 60) return false
        return true
    }

    // --- Connected component (簡單版：水平 row scan + bounding box 合併) ---

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

    /** 找出符合 [match] 條件像素中最大的連通 bounding box */
    private fun findBlob(px: IntArray, w: Int, h: Int, match: (Int) -> Boolean): Blob? {
        val blobs = mutableListOf<Blob>()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (match(px[y * w + x])) {
                    val start = x
                    while (x < w && match(px[y * w + x])) x++
                    val end = x - 1
                    val newBlob = Blob(start, y, end, y, end - start + 1)
                    // 合併與現有 blob 鄰近的
                    var merged = false
                    for (i in blobs.indices) {
                        if (blobs[i].overlapsOrNear(newBlob, 4)) {
                            blobs[i].merge(newBlob)
                            merged = true
                            break
                        }
                    }
                    if (!merged) blobs += newBlob
                } else {
                    x++
                }
            }
        }
        // 二次合併：因為一次掃描可能漏掉跨 row 的鄰近
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in blobs.indices) {
                for (j in i + 1 until blobs.size) {
                    if (blobs[i].overlapsOrNear(blobs[j], 4)) {
                        blobs[i].merge(blobs[j])
                        blobs.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return blobs.maxByOrNull { it.area }
    }

    // --- Score ---

    /** 把 ratio 與 aspect-ratio 對映到 0~1 分數 */
    private fun computeScore(
        ratio: Float, ratioGood1: Float, ratioGood2: Float,
        ar: Float, arGood1: Float, arGood2: Float
    ): Float {
        // ratio 落在 [ratioGood1, ratioGood2] 給高分
        val ratioScore = when {
            ratio < ratioGood1 -> max(0f, ratio / ratioGood1) * 0.7f
            ratio > ratioGood2 -> max(0f, (1f - (ratio - ratioGood2) / ratioGood2)) * 0.8f
            else -> 1f
        }
        val arScore = when {
            ar < arGood1 -> ar / arGood1
            ar > arGood2 -> max(0f, arGood2 / ar)
            else -> 1f
        }
        return min(1f, 0.5f + 0.3f * ratioScore + 0.2f * arScore)
    }
}
