package com.xiaoxun.redpacket.mahjong

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Rect

/**
 * 麻將分析的對外介面。
 *  1. 用 TileRecognizer 從螢幕擷取辨識手牌
 *  2. 用 TenpaiSolver 算聽牌建議
 *  3. 回傳給上層 (FloatingOverlay) 顯示
 */
class MahjongMatcher(ctx: Context) {

    companion object {
        private const val TAG = "MahjongMatcher"
        /**
         * WePlay 麻將手牌區域 ROI (1125x2436 螢幕):
         *   X: 8-158
         *   Y: 270-2070
         * 之後可以做成可調設定。
         */
        val DEFAULT_HAND_ROI = Rect(8, 270, 150, 1800)
    }

    private val recognizer = TileRecognizer(ctx).also { it.load() }

    data class Analysis(
        val recognizedTiles: List<TileRecognizer.Recognized>,
        val tenpaiOptions: List<TenpaiSolver.TenpaiOption>,
        val isWinning: Boolean,
        val message: String  // 給 overlay 顯示的文字
    )

    fun analyze(screenshot: Bitmap, handRoi: Rect = DEFAULT_HAND_ROI): Analysis {
        val tiles = recognizer.recognizeHand(screenshot, handRoi)
        val tileObjs = tiles.map { it.tile }

        if (tileObjs.size < 14) {
            return Analysis(tiles, emptyList(), false,
                "識別到 ${tileObjs.size} 張 (需 16 張，請確認手牌區位置或補上缺失範本)")
        }

        // 已胡？
        val canWin = TenpaiSolver.canWin(tileObjs)
        if (canWin) {
            return Analysis(tiles, emptyList(), true, "✓ 已胡牌！")
        }

        // 找聽牌
        val options = TenpaiSolver.findTenpai(tileObjs)
        if (options.isEmpty()) {
            return Analysis(tiles, options, false,
                "未聽牌 (識別 ${tileObjs.size} 張: ${summary(tileObjs)})")
        }

        // 取最佳建議 (聽最多張的)
        val best = options.maxByOrNull { it.waits.size }!!
        val waitsText = best.waits.joinToString("/") { it.displayName() }
        return Analysis(tiles, options, false,
            "建議打 ${best.discard.displayName()} → 聽 $waitsText")
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
