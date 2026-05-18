package com.xiaoxun.redpacket.mahjong

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect as AndroidRect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 麻將牌辨識器。
 *
 * v1.13 改進：
 * - 不再假設手牌一定在左側直排，可辨識底部橫排與左側直排。
 * - 範本載入時先轉成灰階 + CLAHE Mat，避免每格、每張範本重複 bitmapToMat。
 * - ROI 內先找白色牌面區塊；找不到時才用 16 等分 fallback。
 * - 回傳未知牌與信心分數，讓上層能知道是「沒抓到牌」還是「範本不足」。
 */
class TileRecognizer(private val ctx: Context) {

    enum class Orientation { HORIZONTAL, VERTICAL }

    data class HandCandidate(
        val rect: Rect,
        val orientation: Orientation,
        val name: String
    )

    data class Recognized(
        val tile: Tile?,
        val screenX: Float,
        val screenY: Float,
        val score: Float,
        val bounds: AndroidRect
    )

    data class RecognitionResult(
        val candidate: HandCandidate,
        val tiles: List<Recognized>
    ) {
        val knownCount: Int get() = tiles.count { it.tile != null }
        val averageScore: Float get() {
            val known = tiles.filter { it.tile != null }
            return if (known.isEmpty()) 0f else known.map { it.score }.average().toFloat()
        }
    }

    private data class TemplateData(
        val code: String,
        val tile: Tile,
        val mat: Mat
    )

    companion object {
        private const val TAG = "TileRecognizer"
        private const val HAND_COUNT = 16
        private const val CELL_WIDTH = 64
        private const val CELL_HEIGHT = 96
        private const val MIN_SCORE_KNOWN = 0.46f

        private val CODES = buildList<String> {
            for (n in 1..9) { add("m$n"); add("s$n"); add("p$n") }
            for (n in 1..7) add("z$n")
            for (n in 1..8) add("f$n")
            for (s in listOf("s", "m", "p")) for (n in listOf(5, 6)) add("${s}${n}_red")
        }
    }

    private val templates = mutableListOf<TemplateData>()

    fun load() {
        if (templates.isNotEmpty()) return

        val res = ctx.resources
        val pkg = ctx.packageName
        var total = 0

        for (code in CODES) {
            loadVersion(res, pkg, "mj_$code")?.let {
                templates += it
                total++
            }
            for (v in 2..6) {
                loadVersion(res, pkg, "mj_${code}_v$v")?.let {
                    templates += it
                    total++
                }
            }
        }

        Log.i(
            TAG,
            "loaded ${templates.map { it.code }.toSet().size} tile codes, total $total templates"
        )
    }

    fun release() {
        templates.forEach { it.mat.release() }
        templates.clear()
    }

    private fun loadVersion(
        res: android.content.res.Resources,
        pkg: String,
        name: String
    ): TemplateData? {
        val resId = res.getIdentifier(name, "drawable", pkg)
        if (resId == 0) return null

        val bmp = BitmapFactory.decodeResource(res, resId) ?: return null
        val code = name.removePrefix("mj_").substringBefore("_v")
        val tile = parseCode(code) ?: run {
            bmp.recycle()
            return null
        }

        val src = Mat()
        val gray = Mat()
        val normalized = Mat()
        return try {
            Utils.bitmapToMat(bmp, src)
            safeCvtToGray(src, gray) ?: return null
            normalizeTile(gray, normalized)
            if (normalized.empty()) return null
            TemplateData(code, tile, normalized.clone())
        } catch (t: Throwable) {
            Log.w(TAG, "skip bad template $name: ${t.message}")
            null
        } finally {
            src.release()
            gray.release()
            normalized.release()
            bmp.recycle()
        }
    }

    fun defaultCandidates(screenWidth: Int, screenHeight: Int): List<HandCandidate> {
        val w = screenWidth
        val h = screenHeight
        return listOf(
            // WePlay landscape screenshots put the player's hand around 77%-94% height.
            // Older ROI started at 84%, cutting off tile faces and causing 0-tile recognition.
            HandCandidate(
                rect = Rect((w * 0.03f).toInt(), (h * 0.74f).toInt(), (w * 0.94f).toInt(), (h * 0.24f).toInt()),
                orientation = Orientation.HORIZONTAL,
                name = "bottom-full-high"
            ),
            HandCandidate(
                rect = Rect((w * 0.05f).toInt(), (h * 0.76f).toInt(), (w * 0.86f).toInt(), (h * 0.21f).toInt()),
                orientation = Orientation.HORIZONTAL,
                name = "bottom-wide-high"
            ),
            HandCandidate(
                rect = Rect((w * 0.18f).toInt(), (h * 0.76f).toInt(), (w * 0.72f).toInt(), (h * 0.21f).toInt()),
                orientation = Orientation.HORIZONTAL,
                name = "bottom-center-high"
            ),
            HandCandidate(
                rect = Rect((w * 0.04f).toInt(), (h * 0.82f).toInt(), (w * 0.90f).toInt(), (h * 0.16f).toInt()),
                orientation = Orientation.HORIZONTAL,
                name = "bottom-wide-low"
            ),
            HandCandidate(
                rect = Rect((w * 0.00f).toInt(), (h * 0.10f).toInt(), (w * 0.18f).toInt(), (h * 0.78f).toInt()),
                orientation = Orientation.VERTICAL,
                name = "left-vertical"
            )
        )
    }

    fun recognizeBest(screenshot: Bitmap): RecognitionResult? {
        if (templates.isEmpty()) {
            Log.w(TAG, "no templates loaded, call load() first")
            return null
        }

        val src = Mat()
        return try {
            Utils.bitmapToMat(screenshot, src)
            if (src.empty() || src.cols() <= 0 || src.rows() <= 0) return null
            val candidates = defaultCandidates(screenshot.width, screenshot.height)
            candidates
                .mapNotNull { candidate -> recognizeCandidate(src, candidate) }
                .maxWithOrNull(
                    compareBy<RecognitionResult> { it.knownCount }
                        .thenBy { it.averageScore }
                )
        } catch (t: Throwable) {
            Log.w(TAG, "recognizeBest failed safely: ${t.message}")
            null
        } finally {
            src.release()
        }
    }

    fun recognizeHand(
        screenshot: Bitmap,
        handRect: Rect,
        isVertical: Boolean = true
    ): List<Recognized> {
        if (templates.isEmpty()) {
            Log.w(TAG, "no templates loaded, call load() first")
            return emptyList()
        }

        val src = Mat()
        return try {
            Utils.bitmapToMat(screenshot, src)
            if (src.empty() || src.cols() <= 0 || src.rows() <= 0) return emptyList()
            recognizeCandidate(
                src,
                HandCandidate(
                    rect = handRect,
                    orientation = if (isVertical) Orientation.VERTICAL else Orientation.HORIZONTAL,
                    name = "manual"
                )
            )?.tiles.orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "recognizeHand failed safely: ${t.message}")
            emptyList()
        } finally {
            src.release()
        }
    }

    private fun recognizeCandidate(src: Mat, candidate: HandCandidate): RecognitionResult? {
        val safe = safeRect(candidate.rect, src.width(), src.height()) ?: return null
        if (src.empty() || safe.width <= 0 || safe.height <= 0) return null
        val roi = src.submat(safe)
        if (roi.empty() || roi.cols() <= 0 || roi.rows() <= 0) {
            roi.release()
            return null
        }
        val gray = Mat()
        val enhanced = Mat()
        val mask = Mat()

        return try {
            safeCvtToGray(roi, gray) ?: return null
            if (gray.empty()) return null
            if (safeEnhanceGray(gray, enhanced) == null) {
                gray.copyTo(enhanced)
            }
            if (enhanced.empty()) return null

            Core.inRange(enhanced, Scalar(145.0), Scalar(255.0), mask)
            val rects = findTileRects(mask, safe, candidate.orientation)
            val cells = if (rects.size >= HAND_COUNT - 2) {
                rects.sortedWith(compareBy<Rect> { it.x }.thenBy { it.y }).take(HAND_COUNT)
            } else {
                fallbackRects(safe, candidate.orientation, mask)
            }

            val recognized = cells.mapNotNull { screenRect ->
                matchCell(src, screenRect)
            }
            RecognitionResult(candidate, recognized)
        } catch (t: Throwable) {
            Log.w(TAG, "candidate ${candidate.name} failed safely: ${t.message}")
            null
        } finally {
            roi.release()
            gray.release()
            enhanced.release()
            mask.release()
        }
    }

    private fun findTileRects(mask: Mat, safeRoi: Rect, orientation: Orientation): List<Rect> {
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        return try {
            if (mask.empty()) return emptyList()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val roiArea = safeRoi.width.toDouble() * safeRoi.height.toDouble()
            val raw = contours.mapNotNull { contour ->
                val r = Imgproc.boundingRect(contour)
                val area = r.width * r.height
                val ratio = r.height.toDouble() / max(1.0, r.width.toDouble())
                val valid = when (orientation) {
                    Orientation.HORIZONTAL ->
                        area > roiArea * 0.003 &&
                            r.width > safeRoi.width * 0.025 &&
                            r.height > safeRoi.height * 0.35 &&
                            ratio in 0.9..3.4
                    Orientation.VERTICAL ->
                        area > roiArea * 0.003 &&
                            r.width > safeRoi.width * 0.30 &&
                            r.height > safeRoi.height * 0.025 &&
                            ratio in 0.6..4.2
                }
                if (valid) Rect(safeRoi.x + r.x, safeRoi.y + r.y, r.width, r.height) else null
            }

            mergeCloseRects(raw, orientation)
        } catch (t: Throwable) {
            Log.w(TAG, "findTileRects failed safely: ${t.message}")
            emptyList()
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    private fun mergeCloseRects(rects: List<Rect>, orientation: Orientation): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val sorted = when (orientation) {
            Orientation.HORIZONTAL -> rects.sortedBy { it.x }
            Orientation.VERTICAL -> rects.sortedBy { it.y }
        }

        val out = mutableListOf<Rect>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last == null) {
                out += r
                continue
            }
            val close = when (orientation) {
                Orientation.HORIZONTAL -> abs(r.x - last.x) < max(r.width, last.width) * 0.45
                Orientation.VERTICAL -> abs(r.y - last.y) < max(r.height, last.height) * 0.45
            }
            if (close) {
                val x1 = min(last.x, r.x)
                val y1 = min(last.y, r.y)
                val x2 = max(last.x + last.width, r.x + r.width)
                val y2 = max(last.y + last.height, r.y + r.height)
                out[out.lastIndex] = Rect(x1, y1, x2 - x1, y2 - y1)
            } else {
                out += r
            }
        }
        return out
    }

    private fun fallbackRects(roi: Rect, orientation: Orientation, mask: Mat? = null): List<Rect> {
        return when (orientation) {
            Orientation.HORIZONTAL -> {
                val active = mask?.let { activeSpan(it, Orientation.HORIZONTAL) }
                val activeLeft = active?.first ?: 0
                val activeRight = active?.second ?: roi.width
                val activeWidth = (activeRight - activeLeft).coerceAtLeast(roi.width / 2)
                val step = activeWidth / HAND_COUNT.toFloat()
                val insetY = (roi.height * 0.08f).toInt()
                val h = (roi.height * 0.84f).toInt().coerceAtLeast(1)
                (0 until HAND_COUNT).map { i ->
                    Rect(
                        (roi.x + activeLeft + i * step).toInt(),
                        roi.y + insetY,
                        step.toInt().coerceAtLeast(1),
                        h
                    )
                }
            }
            Orientation.VERTICAL -> {
                val active = mask?.let { activeSpan(it, Orientation.VERTICAL) }
                val activeTop = active?.first ?: 0
                val activeBottom = active?.second ?: roi.height
                val activeHeight = (activeBottom - activeTop).coerceAtLeast(roi.height / 2)
                val step = activeHeight / HAND_COUNT.toFloat()
                val insetX = (roi.width * 0.08f).toInt()
                val w = (roi.width * 0.84f).toInt().coerceAtLeast(1)
                (0 until HAND_COUNT).map { i ->
                    Rect(
                        roi.x + insetX,
                        (roi.y + activeTop + i * step).toInt(),
                        w,
                        step.toInt().coerceAtLeast(1)
                    )
                }
            }
        }
    }

    private fun activeSpan(mask: Mat, orientation: Orientation): Pair<Int, Int>? {
        val cols = mask.cols()
        val rows = mask.rows()
        if (cols <= 0 || rows <= 0) return null

        val projectionSize = if (orientation == Orientation.HORIZONTAL) cols else rows
        val projection = IntArray(projectionSize)
        val pixel = DoubleArray(1)

        if (orientation == Orientation.HORIZONTAL) {
            for (x in 0 until cols) {
                var count = 0
                for (y in 0 until rows) {
                    mask.get(y, x, pixel)
                    if (pixel[0] > 0.0) count++
                }
                projection[x] = count
            }
        } else {
            for (y in 0 until rows) {
                var count = 0
                for (x in 0 until cols) {
                    mask.get(y, x, pixel)
                    if (pixel[0] > 0.0) count++
                }
                projection[y] = count
            }
        }

        val threshold = if (orientation == Orientation.HORIZONTAL) {
            (rows * 0.10f).toInt().coerceAtLeast(3)
        } else {
            (cols * 0.10f).toInt().coerceAtLeast(3)
        }

        var start = -1
        var end = -1
        for (i in projection.indices) {
            if (projection[i] >= threshold) {
                if (start < 0) start = i
                end = i
            }
        }
        if (start < 0 || end <= start) return null

        val padding = ((end - start) * 0.06f).toInt().coerceAtLeast(4)
        return max(0, start - padding) to min(projectionSize, end + padding)
    }

    private fun matchCell(src: Mat, screenRect: Rect): Recognized? {
        val safe = safeRect(screenRect, src.width(), src.height()) ?: return null
        if (safe.width < 8 || safe.height < 8) return null

        val cell = src.submat(safe)
        val gray = Mat()
        val normalized = Mat()
        return try {
            if (cell.empty() || cell.cols() <= 0 || cell.rows() <= 0) return null
            safeCvtToGray(cell, gray) ?: return null
            if (gray.empty()) return null
            normalizeTile(gray, normalized)
            if (normalized.empty()) return null

            var bestCode: String? = null
            var bestTile: Tile? = null
            var bestScore = -1f

            for (template in templates) {
                if (template.mat.empty()) continue
                if (normalized.cols() < template.mat.cols() || normalized.rows() < template.mat.rows()) continue
                val result = Mat(1, 1, CvType.CV_32F)
                try {
                    Imgproc.matchTemplate(normalized, template.mat, result, Imgproc.TM_CCOEFF_NORMED)
                    val score = Core.minMaxLoc(result).maxVal.toFloat()
                    if (score > bestScore) {
                        bestScore = score
                        bestCode = template.code
                        bestTile = template.tile
                    }
                } finally {
                    result.release()
                }
            }

            val knownTile = if (bestScore >= MIN_SCORE_KNOWN) bestTile else null
            if (knownTile == null) {
                Log.d(TAG, "unknown tile at $safe best=$bestCode score=$bestScore")
            }

            Recognized(
                tile = knownTile,
                screenX = safe.x + safe.width / 2f,
                screenY = safe.y + safe.height / 2f,
                score = bestScore,
                bounds = AndroidRect(safe.x, safe.y, safe.x + safe.width, safe.y + safe.height)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "matchCell failed safely: ${t.message}")
            null
        } finally {
            cell.release()
            gray.release()
            normalized.release()
        }
    }


    private fun safeCvtToGray(src: Mat, gray: Mat): Mat? {
        if (src.empty() || src.cols() <= 0 || src.rows() <= 0) return null
        return try {
            when (src.channels()) {
                1 -> src.copyTo(gray)
                3 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
                4 -> Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> return null
            }
            if (gray.empty()) null else gray
        } catch (t: Throwable) {
            Log.w(TAG, "cvtColor failed: ${t.message}")
            null
        }
    }

    private fun normalizeTile(gray: Mat, out: Mat) {
        if (gray.empty() || gray.cols() <= 0 || gray.rows() <= 0) return
        val resized = Mat()
        try {
            Imgproc.resize(gray, resized, Size(CELL_WIDTH.toDouble(), CELL_HEIGHT.toDouble()))
            if (resized.empty()) return
            if (safeEnhanceGray(resized, out) == null) {
                resized.copyTo(out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "normalizeTile failed safely: ${t.message}")
        } finally {
            resized.release()
        }
    }

    private fun safeEnhanceGray(gray: Mat, out: Mat): Mat? {
        if (gray.empty() || gray.cols() <= 0 || gray.rows() <= 0) return null
        return try {
            val gray8 = Mat()
            try {
                if (gray.type() == CvType.CV_8UC1) {
                    gray.copyTo(gray8)
                } else {
                    gray.convertTo(gray8, CvType.CV_8UC1)
                }
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                clahe.apply(gray8, out)
                clahe.collectGarbage()
            } finally {
                gray8.release()
            }
            if (out.empty()) null else out
        } catch (t: Throwable) {
            Log.w(TAG, "enhance failed safely: ${t.message}")
            null
        }
    }

    private fun safeRect(rect: Rect, width: Int, height: Int): Rect? {
        val x = rect.x.coerceIn(0, max(0, width - 1))
        val y = rect.y.coerceIn(0, max(0, height - 1))
        val right = (rect.x + rect.width).coerceIn(x + 1, width)
        val bottom = (rect.y + rect.height).coerceIn(y + 1, height)
        val w = right - x
        val h = bottom - y
        return if (w <= 0 || h <= 0) null else Rect(x, y, w, h)
    }

    private fun parseCode(code: String): Tile? {
        val baseCode = code.removeSuffix("_red")
        if (baseCode.length < 2) return null
        val suit = baseCode[0]
        val rank = baseCode.substring(1).toIntOrNull() ?: return null
        return when (suit) {
            'm' -> if (rank in 1..9) Tile.m(rank) else null
            's' -> if (rank in 1..9) Tile.s(rank) else null
            'p' -> if (rank in 1..9) Tile.p(rank) else null
            'z' -> if (rank in 1..7) Tile.z(rank) else null
            'f' -> if (rank in 1..8) Tile.f(rank) else null
            else -> null
        }
    }
}
