package com.xiaoxun.redpacket.mahjong

import kotlin.math.max

/**
 * 本機麻將策略評分器（勝率/速度優先，非 AI）。
 *
 * 目前採「只看自己的牌」：
 * - 推薦最快聽/胡的打牌。
 * - 列出：如果有人打哪些牌可以碰。
 * - 列出：如果上家打哪些牌可以吃。
 *
 * 這樣不需要辨識別人剛打出的牌，速度快、錯誤來源少。
 */
object MahjongStrategy {

    data class DiscardAdvice(
        val discard: Tile,
        val score: Int,
        val ukeire: Int,
        val waits: List<Tile>,
        val reason: String
    )

    enum class CallAction { CHI, PONG }
    enum class CallRank { RECOMMEND, OK }

    data class CallAdvice(
        val action: CallAction,
        /** 別人打出這張時可以吃/碰 */
        val incoming: Tile,
        /** 自己用哪些牌去吃/碰 */
        val useTiles: List<Tile>,
        val rank: CallRank,
        val score: Int,
        val reason: String
    ) {
        val actionText: String get() = if (action == CallAction.PONG) "碰" else "吃"
        fun shortText(): String {
            val prefix = if (rank == CallRank.RECOMMEND) "★" else ""
            return prefix + incoming.displayName()
        }
    }

    data class Advice(
        val discardAdvices: List<DiscardAdvice>,
        val pongAdvices: List<CallAdvice>,
        val chiAdvices: List<CallAdvice>,
        val message: String
    )

    fun advise(
        hand: List<Tile>,
        tenpaiOptions: List<TenpaiSolver.TenpaiOption> = emptyList(),
        claimedMelds: Int = 0
    ): Advice {
        val tiles = hand.filter { !it.isFlower }
        val discards = rankDiscards(tiles, tenpaiOptions, claimedMelds)
        val pongs = listPongCandidates(tiles, claimedMelds)
        val chis = listChiCandidates(tiles, claimedMelds)
        val best = discards.firstOrNull()

        val discardText = if (best == null) {
            "牌數不足"
        } else if (best.waits.isNotEmpty()) {
            val waits = best.waits.joinToString("/") { it.displayName() }
            "打${best.discard.displayName()}→聽$waits"
        } else {
            "打${best.discard.displayName()}｜進${best.ukeire}"
        }
        val pongText = "碰:" + shortList(pongs)
        val chiText = "吃:" + shortList(chis)
        val msg = "$discardText\n$pongText\n$chiText"
        return Advice(discards, pongs, chis, msg)
    }

    private fun shortList(items: List<CallAdvice>, maxItems: Int = 5): String {
        if (items.isEmpty()) return "無"
        val shown = items.take(maxItems).joinToString("/") { it.shortText() }
        return if (items.size > maxItems) "$shown…" else shown
    }

    fun rankDiscards(
        hand: List<Tile>,
        tenpaiOptions: List<TenpaiSolver.TenpaiOption> = emptyList(),
        claimedMelds: Int = 0
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
            val ukeire = effectiveDrawCount(after, claimedMelds)
            val isolatedPenalty = tileKeepValue(tiles, discard)
            val score = eval * 100 + ukeire * 8 - isolatedPenalty
            DiscardAdvice(
                discard = discard,
                score = score,
                ukeire = ukeire,
                waits = emptyList(),
                reason = "有效進張約 ${ukeire} 張"
            )
        }.sortedWith(compareByDescending<DiscardAdvice> { it.score }.thenBy { tileKeepValue(tiles, it.discard) }.thenBy { it.discard.id })
    }

    /** 列出所有「有人打這張，可碰」的牌。 */
    fun listPongCandidates(hand: List<Tile>, claimedMelds: Int = 0): List<CallAdvice> {
        val tiles = hand.filter { !it.isFlower }
        val baseEval = evaluateShape(tiles)
        return tiles
            .groupBy { it.id }
            .filter { (_, same) -> same.size >= 2 }
            .map { (id, same) ->
                val incoming = Tile(id)
                val after = tiles.toMutableList().apply {
                    remove(incoming)
                    remove(incoming)
                }
                val evalGain = evaluateShape(after) - baseEval
                val afterUkeire = effectiveDrawCount(after, claimedMelds + 1)
                val value = callTileValue(incoming, same.size)
                val score = afterUkeire * 8 + value + evalGain * 2 - 20
                val rank = if (incoming.isHonor || score >= 65 || same.size >= 3) CallRank.RECOMMEND else CallRank.OK
                CallAdvice(
                    action = CallAction.PONG,
                    incoming = incoming,
                    useTiles = listOf(incoming, incoming),
                    rank = rank,
                    score = score,
                    reason = if (rank == CallRank.RECOMMEND) "碰後速度較快" else "可碰，視情況"
                )
            }
            .sortedWith(compareByDescending<CallAdvice> { it.rank == CallRank.RECOMMEND }.thenByDescending { it.score }.thenBy { it.incoming.id })
    }

    /** 列出所有「上家打這張，可吃」的牌。 */
    fun listChiCandidates(hand: List<Tile>, claimedMelds: Int = 0): List<CallAdvice> {
        val tiles = hand.filter { !it.isFlower }
        val counts = tiles.groupingBy { it.id }.eachCount()
        val baseEval = evaluateShape(tiles)
        val out = mutableListOf<CallAdvice>()

        for (incoming in Tile.ALL_PLAYABLE) {
            if (!incoming.isNumbered) continue
            val patterns = listOf(
                listOf(incoming.id - 2, incoming.id - 1),
                listOf(incoming.id - 1, incoming.id + 1),
                listOf(incoming.id + 1, incoming.id + 2)
            )
            for (p in patterns) {
                if (!p.all { sameSuitNumber(incoming, it) }) continue
                if (!hasTiles(counts, p)) continue

                val after = tiles.toMutableList()
                after.remove(Tile(p[0]))
                after.remove(Tile(p[1]))
                val evalGain = evaluateShape(after) - baseEval
                val afterUkeire = effectiveDrawCount(after, claimedMelds + 1)
                val openWaitBonus = chiShapeBonus(incoming, p)
                val score = afterUkeire * 8 + openWaitBonus + evalGain * 2 - 35
                val rank = if (score >= 70) CallRank.RECOMMEND else CallRank.OK
                out += CallAdvice(
                    action = CallAction.CHI,
                    incoming = incoming,
                    useTiles = p.map { Tile(it) },
                    rank = rank,
                    score = score,
                    reason = "用 ${p.joinToString("/") { Tile(it).displayName() }} 吃"
                )
            }
        }

        // 同一張 incoming 可能有多種吃法，保留分數最高那一種，避免浮窗太長。
        return out.groupBy { it.incoming.id }
            .map { (_, options) -> options.maxBy { it.score } }
            .sortedWith(compareByDescending<CallAdvice> { it.rank == CallRank.RECOMMEND }.thenByDescending { it.score }.thenBy { it.incoming.id })
    }

    private fun hasTiles(counts: Map<Int, Int>, ids: List<Int>): Boolean {
        val need = ids.groupingBy { it }.eachCount()
        return need.all { (id, n) -> (counts[id] ?: 0) >= n }
    }

    private fun callTileValue(tile: Tile, countInHand: Int): Int {
        var v = if (tile.isHonor) 42 else 18
        if (countInHand >= 3) v += 20
        if (tile.isNumbered && tile.rank in 3..7) v += 6
        return v
    }

    private fun chiShapeBonus(incoming: Tile, pair: List<Int>): Int {
        val ranks = (pair + incoming.id).map { Tile(it).rank }.sorted()
        // 兩面形比邊張/嵌張好。
        return when (ranks) {
            listOf(1, 2, 3), listOf(7, 8, 9) -> 8
            else -> 18
        }
    }

    private fun evaluateShape(hand: List<Tile>): Int {
        val counts = IntArray(60)
        for (t in hand) counts[t.id]++
        var melds = 0
        var pairs = 0
        var taatsu = 0
        var isolated = 0

        for (id in counts.indices) {
            while (counts[id] >= 3) { counts[id] -= 3; melds++ }
        }
        for (s in 1..3) {
            for (r in 1..7) {
                val a = s * 10 + r
                while (counts[a] > 0 && counts[a + 1] > 0 && counts[a + 2] > 0) {
                    counts[a]--; counts[a + 1]--; counts[a + 2]--; melds++
                }
            }
        }
        for (id in counts.indices) {
            if (counts[id] >= 2) { counts[id] -= 2; pairs++ }
        }
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

    private fun effectiveDrawCount(handAfterDiscard: List<Tile>, claimedMelds: Int = 0): Int {
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
}
