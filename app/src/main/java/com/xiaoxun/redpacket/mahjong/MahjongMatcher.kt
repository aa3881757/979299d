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
        return try {
            analyzeInternal(screenshot)
        } catch (t: Throwable) {
            Log.e(TAG, "mahjong analyze failed safely", t)
            Analysis(
                emptyList(),
                emptyList(),
                false,
                "辨識暫停：截圖格式不支援，已安全略過（請用 build-20+）"
            )
        }
    }

    private fun analyzeInternal(screenshot: Bitmap): Analysis {
        val recognition = recognizer.recognizeBest(screenshot)
        if (recognition == null) {
            return Analysis(emptyList(), emptyList(), false, "麻將辨識失敗：請重按 🀄 或稍微移開浮窗")
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
            val knownSummary = if (tileObjs.isEmpty()) "" else "：${summary(tileObjs)}"
            return Analysis(tiles, emptyList(), false,
                "先列辨識 ${tileObjs.size}/${tiles.size} 張$knownSummary（未知 ${unknownCount}，信心不足）")
        }

        // 已胡？
        val canWin = TenpaiSolver.canWin(playable)
        if (canWin) {
            return Analysis(tiles, emptyList(), true, "✓ 已胡牌！（識別 ${tileObjs.size} 張）")
        }

        // 勝率優先：已聽牌時看剩餘可胡張數；未聽牌時看牌型 + 有效進張。
        val options = TenpaiSolver.findTenpai(playable)
        val strategy = MahjongStrategy.advise(playable, options)
        val quality = buildQualitySuffix(unknownCount, lowCount, recognition)
        return Analysis(tiles, options, false, strategy.message + quality)
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
