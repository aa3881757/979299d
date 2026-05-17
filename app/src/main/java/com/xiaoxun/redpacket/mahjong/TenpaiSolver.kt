package com.xiaoxun.redpacket.mahjong

/**
 * 台灣 16 張麻將聽牌計算器。
 *
 * 胡牌條件：5*AAA/ABC + 1*AA = 16 張 (花牌不算)
 *           已副露 (吃/碰/槓) 的牌組視為已完成的 meld，
 *           手中需要再湊 (5 - 副露數) 個 meld + 1 對。
 *
 * 用法：
 *   val hand = listOf(Tile.m(1), Tile.m(2), ...)   // 16 張
 *   val result = TenpaiSolver.findTenpai(hand)
 *   result 內每個項目 = (要打的牌, 可胡的牌清單)
 */
object TenpaiSolver {

    data class TenpaiOption(
        val discard: Tile,
        val waits: List<Tile>
    )

    /**
     * 判斷一組牌 (含應有 16 張，扣掉副露已固定的) 是否能胡牌。
     * @param tiles 純手牌 (應為 1 對 + 4*meld = 14 張，或全 16 = 5*meld+pair)
     * @param meldsNeeded 還需湊的 meld 數量 (5 - 副露數)
     */
    fun canWin(tiles: List<Tile>, meldsNeeded: Int = 5): Boolean {
        val counts = IntArray(60)
        for (t in tiles) if (!t.isFlower) counts[t.id]++
        val total = counts.sum()
        if (total != meldsNeeded * 3 + 2) return false

        // 嘗試每個 pair 候選
        for (id in counts.indices) {
            if (counts[id] >= 2) {
                counts[id] -= 2
                if (countMelds(counts, meldsNeeded)) {
                    counts[id] += 2
                    return true
                }
                counts[id] += 2
            }
        }
        return false
    }

    /**
     * 對 [hand]（16 張、不含花、加上已副露牌組對應數量的牌）
     * 找出所有可丟掉哪張會聽哪些牌。
     */
    fun findTenpai(hand: List<Tile>, claimedMelds: Int = 0): List<TenpaiOption> {
        val meldsNeeded = 5 - claimedMelds
        // 過濾花牌
        val tiles = hand.filter { !it.isFlower }
        if (tiles.size != meldsNeeded * 3 + 1) return emptyList()   // 應為 5*3+1=16 (無副露)

        val out = mutableListOf<TenpaiOption>()
        val distinctTiles = tiles.distinctBy { it.id }
        for (discard in distinctTiles) {
            val after = tiles.toMutableList()
            after.remove(discard)
            val waits = mutableListOf<Tile>()
            for (w in Tile.ALL_PLAYABLE) {
                val test = after + w
                if (canWin(test, meldsNeeded)) waits += w
            }
            if (waits.isNotEmpty()) out += TenpaiOption(discard, waits)
        }
        return out
    }

    /** 用 counts[id]++ 形式表達手牌，遞迴湊 meldsNeeded 個 meld */
    private fun countMelds(counts: IntArray, meldsNeeded: Int): Boolean {
        if (meldsNeeded == 0) {
            // 所有 count 應為 0
            return counts.all { it == 0 }
        }
        // 找第一個非零的牌
        var firstId = -1
        for (id in counts.indices) {
            if (counts[id] > 0) { firstId = id; break }
        }
        if (firstId == -1) return false

        // 嘗試 AAA (刻子)
        if (counts[firstId] >= 3) {
            counts[firstId] -= 3
            if (countMelds(counts, meldsNeeded - 1)) {
                counts[firstId] += 3
                return true
            }
            counts[firstId] += 3
        }

        // 嘗試 ABC (順子) — 只有數牌可以
        val tile = Tile(firstId)
        if (tile.isNumbered && tile.rank <= 7) {
            val b = firstId + 1
            val c = firstId + 2
            if (counts[b] > 0 && counts[c] > 0) {
                counts[firstId]--; counts[b]--; counts[c]--
                if (countMelds(counts, meldsNeeded - 1)) {
                    counts[firstId]++; counts[b]++; counts[c]++
                    return true
                }
                counts[firstId]++; counts[b]++; counts[c]++
            }
        }
        return false
    }
}
