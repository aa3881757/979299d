package com.xiaoxun.redpacket.mahjong

import kotlin.math.max

/**
 * 本機麻將策略評分器（勝率優先，非 AI）。
 *
 * 原則：
 * 1. 已聽牌：優先選「可胡剩餘張數」最多的打法。
 * 2. 未聽牌：用一向聽近似評分 + 有效進張數，推薦保留面子/搭子/對子，丟孤張與價值低的字牌。
 * 3. 吃碰：只在能降低向聽或明顯增加進張時建議；碰會扣分（少摸一次且暴露），吃扣分更高。
 */
object MahjongStrategy {

    data class DiscardAdvice(
        val discard: Tile,
        val score: Int,
        val ukeire: Int,
        val waits: List<Tile>,
        val reason: String
    )

    data class CallAdvice(
        val action: String,
        val tile: Tile,
        val score: Int,
        val reason: String
    )

    data class Advice(
        val discardAdvices: List<DiscardAdvice>,
        val callAdvices: List<CallAdvice>,
        val message: String
    )

    fun advise(hand: List<Tile>, tenpaiOptions: List<TenpaiSolver.TenpaiOption> = emptyList()): Advice {
        val tiles = hand.filter { !it.isFlower }
        val discards = rankDiscards(tiles, tenpaiOptions)
        val calls = adviseCalls(tiles)
        val best = discards.firstOrNull()
        val callText = calls.firstOrNull()?.let { "｜${it.action}：${it.reason}" } ?: "｜吃碰：先不要"
        val msg = if (best == null) {
            "牌數不足，請重新對準手牌"
        } else {
            "勝率優先：打 ${best.discard.displayName()}（${best.reason}）$callText"
        }
        return Advice(discards, calls, msg)
    }

    fun rankDiscards(
        hand: List<Tile>,
        tenpaiOptions: List<TenpaiSolver.TenpaiOption> = emptyList()
    ): List<DiscardAdvice> {
        val tiles = hand.filter { !it.isFlower }
        if (tiles.isEmpty()) return emptyList()

        if (tenpaiOptions.isNotEmpty()) {
            return tenpaiOptions.map { opt ->
                val remain = opt.waits.sumOf { remainingCount(tiles, it) }
                val waitsText = opt.waits.joinToString("/") { it.displayName() }
                DiscardAdvice(
                    discard = opt.discard,
                    score = 100_000 + remain * 100 + opt.waits.size,
                    ukeire = remain,
                    waits = opt.waits,
                    reason = "聽 $waitsText，剩約 ${remain} 張"
                )
            }.sortedWith(compareByDescending<DiscardAdvice> { it.score }.thenBy { it.discard.id })
        }

        val distinct = tiles.distinctBy { it.id }
        return distinct.map { discard ->
            val after = tiles.toMutableList().also { it.remove(discard) }
            val eval = evaluateShape(after)
            val ukeire = effectiveDrawCount(after)
            val isolatedPenalty = tileKeepValue(tiles, discard)
            val score = eval * 100 + ukeire * 8 - isolatedPenalty
            DiscardAdvice(
                discard = discard,
                score = score,
                ukeire = ukeire,
                waits = emptyList(),
                reason = "保留 ${shapeName(eval)}，有效進張約 ${ukeire} 張"
            )
        }.sortedWith(compareByDescending<DiscardAdvice> { it.score }.thenBy { tileKeepValue(tiles, it.discard) }.thenBy { it.discard.id })
    }

    private fun adviseCalls(hand: List<Tile>): List<CallAdvice> {
        val tiles = hand.filter { !it.isFlower }
        if (tiles.size < 10) return emptyList()
        val baseEval = evaluateShape(tiles)
        val out = mutableListOf<CallAdvice>()

        for (tile in Tile.ALL_PLAYABLE) {
            val count = tiles.count { it.id == tile.id }
            if (count >= 2) {
                val after = tiles.toMutableList().apply {
                    remove(tile); remove(tile)
                }
                val gain = evaluateShape(after) - baseEval
                val keep = tileKeepValue(tiles, tile)
                val score = gain * 100 + keep - 35
                val reason = when {
                    gain > 0 -> "可碰 ${tile.displayName()}，向聽可能變好；碰後打孤張"
                    keep >= 40 -> "有對子價值，可碰 ${tile.displayName()}；若不缺速度可不碰"
                    else -> "碰 ${tile.displayName()} 速度提升有限"
                }
                if (score >= 15) out += CallAdvice("碰", tile, score, reason)
            }

            if (tile.isNumbered) {
                val patterns = listOf(
                    listOf(tile.id - 2, tile.id - 1),
                    listOf(tile.id - 1, tile.id + 1),
                    listOf(tile.id + 1, tile.id + 2)
                )
                for (p in patterns) {
                    if (p.all { sameSuitNumber(tile, it) && tiles.any { t -> t.id == it } }) {
                        val after = tiles.toMutableList()
                        after.remove(Tile(p[0])); after.remove(Tile(p[1]))
                        val gain = evaluateShape(after) - baseEval
                        val score = gain * 100 + 10 - 55
                        if (score >= 20) {
                            out += CallAdvice("吃", tile, score, "可吃 ${tile.displayName()} 組順；只在趕聽或明顯變好時吃")
                        }
                    }
                }
            }
        }
        return out.sortedByDescending { it.score }.take(3)
    }

    private fun evaluateShape(hand: List<Tile>): Int {
        val counts = IntArray(60)
        for (t in hand) counts[t.id]++
        var melds = 0
        var pairs = 0
        var taatsu = 0
        var isolated = 0

        // 刻子
        for (id in counts.indices) {
            while (counts[id] >= 3) { counts[id] -= 3; melds++ }
        }
        // 順子
        for (s in 1..3) {
            for (r in 1..7) {
                val a = s * 10 + r
                while (counts[a] > 0 && counts[a + 1] > 0 && counts[a + 2] > 0) {
                    counts[a]--; counts[a + 1]--; counts[a + 2]--; melds++
                }
            }
        }
        // 對子
        for (id in counts.indices) {
            if (counts[id] >= 2) { counts[id] -= 2; pairs++ }
        }
        // 兩面/嵌張/邊張搭子
        for (s in 1..3) {
            for (r in 1..8) {
                val a = s * 10 + r
                if (counts[a] > 0 && counts[a + 1] > 0) { counts[a]--; counts[a + 1]--; taatsu++ }
            }
            for (r in 1..7) {
                val a = s * 10 + r
                if (counts[a] > 0 && counts[a + 2] > 0) { counts[a]--; counts[a + 2]--; taatsu++ }
            }
        }
        for (id in counts.indices) isolated += counts[id]

        val pairScore = if (pairs > 0) 18 + (pairs - 1) * 4 else 0
        return melds * 100 + taatsu.coerceAtMost(5 - melds) * 35 + pairScore - isolated * 6
    }

    private fun effectiveDrawCount(handAfterDiscard: List<Tile>): Int {
        var total = 0
        val base = evaluateShape(handAfterDiscard)
        for (draw in Tile.ALL_PLAYABLE) {
            val remain = remainingCount(handAfterDiscard, draw)
            if (remain <= 0) continue
            val improved = evaluateShape(handAfterDiscard + draw)
            if (improved > base) total += remain
        }
        return total
    }

    private fun remainingCount(visibleHand: List<Tile>, tile: Tile): Int {
        val used = visibleHand.count { it.id == tile.id }
        return max(0, 4 - used)
    }

    private fun tileKeepValue(hand: List<Tile>, tile: Tile): Int {
        val count = hand.count { it.id == tile.id }
        var v = 0
        if (count >= 2) v += 36
        if (count >= 3) v += 42
        if (tile.isHonor) return v + if (count == 1) 4 else 20
        for (d in -2..2) {
            if (d == 0) continue
            val other = Tile(tile.id + d)
            if (sameSuitNumber(tile, other.id) && hand.any { it.id == other.id }) {
                v += when (kotlin.math.abs(d)) { 1 -> 18; 2 -> 10; else -> 0 }
            }
        }
        if (tile.rank in 3..7) v += 8 else v -= 3
        return v
    }

    private fun sameSuitNumber(base: Tile, id: Int): Boolean {
        val t = Tile(id)
        return base.isNumbered && t.isNumbered && base.suit == t.suit && t.rank in 1..9
    }

    private fun shapeName(score: Int): String = when {
        score >= 430 -> "好形/接近聽牌"
        score >= 330 -> "多面子搭子"
        score >= 240 -> "基本牌型"
        else -> "整理孤張"
    }
}
