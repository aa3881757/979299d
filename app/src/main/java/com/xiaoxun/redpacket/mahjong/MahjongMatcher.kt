package com.xiaoxun.redpacket.mahjong

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 麻將分析的對外介面。
 *  1. 用 TileRecognizer 從螢幕擷取辨識手牌
 *  2. 用 TenpaiSolver 算聽牌建議
 *  3. 回傳給上層 (FloatingOverlay) 顯示
 */
class MahjongMatcher(ctx: Context) {

    companion object {
        private const val TAG = "MahjongMatcher"
        private const val MIN_TILES_FOR_USEFUL_RESULT = 14
    }

    private val recognizer = TileRecognizer(ctx).also { it.load() }

    data class Analysis(
        val recognizedTiles: List<TileRecognizer.Recognized>,
        val tenpaiOptions: List<TenpaiSolver.TenpaiOption>,
        val isWinning: Boolean,
        val message: String  // 給 overlay 顯示的文字
    )

    fun analyze(screenshot: Bitmap): Analysis {
        val recognition = recognizer.recognizeBest(screenshot)
        if (recognition == null) {
            return Analysis(emptyList(), emptyList(), false, "麻將辨識失敗：沒有載入範本")
        }

        val tiles = recognition.tiles
        val tileObjs = tiles.mapNotNull { it.tile }
        val unknownCount = tiles.count { it.tile == null }
        val lowCount = tiles.count { it.tile != null && it.score < 0.58f }
        val playable = tileObjs.filter { !it.isFlower }

        Log.i(
            TAG,
            "candidate=${recognition.candidate.name}, known=${recognition.knownCount}/${tiles.size}, avg=${recognition.averageScore}"
        )

        if (playable.size < MIN_TILES_FOR_USEFUL_RESULT) {
            return Analysis(tiles, emptyList(), false,
                "識別到 ${tileObjs.size}/${tiles.size} 張（未知 ${unknownCount}，請對準底部手牌或補範本）")
        }

        // 已胡？
        val canWin = TenpaiSolver.canWin(playable)
        if (canWin) {
            return Analysis(tiles, emptyList(), true, "✓ 已胡牌！（識別 ${tileObjs.size} 張）")
        }

        // 找聽牌
        val options = TenpaiSolver.findTenpai(playable)
        if (options.isEmpty()) {
            val quality = buildQualitySuffix(unknownCount, lowCount, recognition)
            return Analysis(tiles, options, false,
                "未聽牌（${summary(playable)}）$quality")
        }

        // 取最佳建議 (聽最多張的)
        val best = options.maxByOrNull { it.waits.size }!!
        val waitsText = best.waits.joinToString("/") { it.displayName() }
        val quality = buildQualitySuffix(unknownCount, lowCount, recognition)
        return Analysis(tiles, options, false,
            "建議打 ${best.discard.displayName()} → 聽 $waitsText$quality")
    }

    @Deprecated("Use analyze(screenshot); ROI is now auto-detected.")
    fun analyze(screenshot: Bitmap, handRoi: org.opencv.core.Rect): Analysis {
        val tiles = recognizer.recognizeHand(screenshot, handRoi)
        val tileObjs = tiles.mapNotNull { it.tile }
        return Analysis(tiles, emptyList(), false, "手動 ROI 識別 ${tileObjs.size} 張：${summary(tileObjs)}")
    }

    private fun buildQualitySuffix(
        unknownCount: Int,
        lowCount: Int,
        recognition: TileRecognizer.RecognitionResult
    ): String {
        val notes = mutableListOf<String>()
        if (unknownCount > 0) notes += "未知 $unknownCount"
        if (lowCount > 0) notes += "低信心 $lowCount"
        notes += recognition.candidate.name
        return "（${notes.joinToString("，")}）"
    }

    private fun summary(tiles: List<Tile>): String {
        return tiles.groupBy { it.suit }.entries.joinToString(" ") { (s, list) ->
            val sorted = list.sortedBy { it.rank }
            val cName = when (s) { 1 -> "萬"; 2 -> "索"; 3 -> "筒"; 4 -> ""; 5 -> "花"; else -> "?" }
            if (s == 4) sorted.joinToString("") { it.displayName() }
            else sorted.joinToString("", postfix = cName) { it.rank.toString() }
        }
    }
}
