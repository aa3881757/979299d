package com.xiaoxun.redpacket.mahjong

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.xiaoxun.redpacket.R
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 麻將牌辨識器 (基於 OpenCV multi-scale template matching)。
 *
 *  使用方式：
 *    val rec = TileRecognizer(ctx).also { it.load() }
 *    val tiles = rec.recognizeHand(screenshot, handRect)
 *
 *  目前範本來源：
 *    res/drawable-nodpi/mj_<code>.png  例如 mj_m1.png, mj_z3.png
 *    code 格式：m1-m9, s1-s9, p1-p9, z1-z7, f1-f8, s5_red, s6_red...
 *
 *  範本不夠精準時，可以加入更多版本：mj_m1_v2.png, mj_m1_v3.png
 *  辨識會取最高分的版本。
 */
class TileRecognizer(private val ctx: Context) {

    companion object {
        private const val TAG = "TileRecognizer"
    }

    /** Map<牌代碼, 範本 Bitmap list> (同一張牌可能有多個版本) */
    private val templates = mutableMapOf<String, MutableList<Bitmap>>()

    /** 從 resources 載入所有已準備的範本 */
    fun load() {
        if (templates.isNotEmpty()) return

        val res = ctx.resources
        val pkg = ctx.packageName
        // 嘗試載入所有可能的牌代碼
        val codes = buildList<String> {
            for (n in 1..9) { add("m$n"); add("s$n"); add("p$n") }
            for (n in 1..7) add("z$n")
            for (n in 1..8) add("f$n")
            // 紅 5/6 變體
            for (s in listOf("s", "m", "p")) for (n in listOf(5, 6)) add("${s}${n}_red")
        }
        for (code in codes) {
            // 先載 mj_<code> 再嘗試 mj_<code>_v2, _v3...
            loadVersion(res, pkg, "mj_$code")
            for (v in 2..5) loadVersion(res, pkg, "mj_${code}_v$v")
        }
        Log.i(TAG, "loaded ${templates.size} tile codes, total ${templates.values.sumOf { it.size }} templates")
    }

    private fun loadVersion(res: android.content.res.Resources, pkg: String, name: String) {
        val resId = res.getIdentifier(name, "drawable", pkg)
        if (resId == 0) return
        val bmp = BitmapFactory.decodeResource(res, resId) ?: return
        val code = name.removePrefix("mj_").substringBefore("_v")
        templates.getOrPut(code) { mutableListOf() } += bmp
    }

    /**
     * 在 [screenshot] 的指定 [handRect] (手牌區域 ROI) 中辨識所有牌。
     * 回傳依 X 座標排序的牌清單 (從左到右)。
     */
    fun recognizeHand(screenshot: Bitmap, handRect: Rect, isVertical: Boolean = true): List<Recognized> {
        if (templates.isEmpty()) {
            Log.w(TAG, "no templates loaded, call load() first")
            return emptyList()
        }

        // 截出 ROI
        val src = Mat()
        Utils.bitmapToMat(screenshot, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2BGR)

        val ry = handRect.y.coerceAtLeast(0)
        val rx = handRect.x.coerceAtLeast(0)
        val rw = handRect.width.coerceAtMost(src.width() - rx)
        val rh = handRect.height.coerceAtMost(src.height() - ry)
        val roi = src.submat(Rect(rx, ry, rw, rh))

        // 假設每張牌是固定大小的方塊 — 根據 ROI 與牌數推算
        // 16 張豎排：每張牌高度 = rh/16
        val tileLen = if (isVertical) rh / 16 else rw / 16
        val results = mutableListOf<Recognized>()

        for (i in 0 until 16) {
            val tileRoi = if (isVertical) {
                Rect(0, i * tileLen, rw, tileLen)
            } else {
                Rect(i * tileLen, 0, tileLen, rh)
            }
            if (tileRoi.y + tileRoi.height > roi.rows() ||
                tileRoi.x + tileRoi.width > roi.cols()) continue

            val cell = roi.submat(tileRoi)
            val (code, score) = matchBestTemplate(cell)
            if (code != null) {
                val tile = parseCode(code)
                if (tile != null) {
                    val cx = rx + tileRoi.x + tileRoi.width / 2f
                    val cy = ry + tileRoi.y + tileRoi.height / 2f
                    results += Recognized(tile, cx, cy, score)
                }
            }
            cell.release()
        }

        roi.release()
        src.release()
        return results
    }

    private fun matchBestTemplate(cell: Mat): Pair<String?, Float> {
        var bestCode: String? = null
        var bestScore = 0f

        for ((code, list) in templates) {
            for (tplBmp in list) {
                val tpl = Mat()
                Utils.bitmapToMat(tplBmp, tpl)
                Imgproc.cvtColor(tpl, tpl, Imgproc.COLOR_RGBA2BGR)

                // 範本縮放成與 cell 相同
                val scaled = Mat()
                Imgproc.resize(tpl, scaled, Size(cell.width().toDouble(), cell.height().toDouble()))
                tpl.release()

                // matchTemplate 需 cell size >= template size, 此處兩者相同所以 result 是 1x1
                val result = Mat(1, 1, CvType.CV_32F)
                Imgproc.matchTemplate(cell, scaled, result, Imgproc.TM_CCOEFF_NORMED)
                val mmr = Core.minMaxLoc(result)
                val score = mmr.maxVal.toFloat()
                if (score > bestScore) {
                    bestScore = score
                    bestCode = code
                }
                scaled.release(); result.release()
            }
        }
        return bestCode to bestScore
    }

    /** "m1" → Tile.m(1) */
    private fun parseCode(code: String): Tile? {
        val baseCode = code.removeSuffix("_red")
        if (baseCode.length < 2) return null
        val suit = baseCode[0]
        val rank = baseCode.substring(1).toIntOrNull() ?: return null
        return when (suit) {
            'm' -> Tile.m(rank)
            's' -> Tile.s(rank)
            'p' -> Tile.p(rank)
            'z' -> if (rank in 1..7) Tile.z(rank) else null
            'f' -> if (rank in 1..8) Tile.f(rank) else null
            else -> null
        }
    }

    data class Recognized(
        val tile: Tile,
        val screenX: Float,
        val screenY: Float,
        val score: Float
    )
}
