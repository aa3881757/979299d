package com.xiaoxun.redpacket.mahjong

/**
 * 麻將牌的內部表示。
 *
 * 編碼：suit*10 + rank
 *   萬子 m: 1-9     → 11-19
 *   索子 s: 1-9     → 21-29
 *   筒子 p: 1-9     → 31-39
 *   字牌 z: 1-7     → 41-47   (東南西北中發白)
 *   花牌 f: 1-8     → 51-58   (春夏秋冬梅蘭竹菊)  花牌不參與胡牌計算
 *
 * 用 Int 不用 enum 是為了效率 (聽牌計算會跑數千次)
 */
@JvmInline
value class Tile(val id: Int) {

    val suit: Int get() = id / 10      // 1=m 2=s 3=p 4=z 5=f
    val rank: Int get() = id % 10      // 1-9 / 1-7 / 1-8

    val isMan: Boolean   get() = suit == 1
    val isSou: Boolean   get() = suit == 2
    val isPin: Boolean   get() = suit == 3
    val isHonor: Boolean get() = suit == 4
    val isFlower: Boolean get() = suit == 5
    /** 是否為數牌 (萬索筒) — 可組順子 */
    val isNumbered: Boolean get() = suit in 1..3

    fun next(): Tile? {
        if (!isNumbered) return null
        return if (rank < 9) Tile(id + 1) else null
    }

    fun displayName(): String = when (suit) {
        1 -> "${num(rank)}萬"
        2 -> "${num(rank)}索"
        3 -> "${num(rank)}筒"
        4 -> arrayOf("東","南","西","北","中","發","白")[rank - 1]
        5 -> arrayOf("春","夏","秋","冬","梅","蘭","竹","菊")[rank - 1]
        else -> "?"
    }

    private fun num(r: Int) = arrayOf("一","二","三","四","五","六","七","八","九")[r - 1]

    companion object {
        /** 所有可參與胡牌計算的 34 種牌 (不含花牌) */
        val ALL_PLAYABLE: List<Tile> by lazy {
            val out = mutableListOf<Tile>()
            for (s in 1..3) for (r in 1..9) out += Tile(s * 10 + r)
            for (r in 1..7) out += Tile(40 + r)
            out
        }

        fun m(r: Int) = Tile(10 + r)
        fun s(r: Int) = Tile(20 + r)
        fun p(r: Int) = Tile(30 + r)
        fun z(r: Int) = Tile(40 + r)
        fun f(r: Int) = Tile(50 + r)
    }
}
