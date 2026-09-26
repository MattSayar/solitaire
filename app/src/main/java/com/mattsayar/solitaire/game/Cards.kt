package com.mattsayar.solitaire.game

/**
 * Cards are plain ints 0..51 to keep the engine allocation-free.
 * suit = id / 13 (0 clubs, 1 diamonds, 2 hearts, 3 spades), rank = id % 13 + 1 (1 = ace, 13 = king).
 */
object Cards {
    const val COUNT = 52

    const val CLUBS = 0
    const val DIAMONDS = 1
    const val HEARTS = 2
    const val SPADES = 3

    fun of(suit: Int, rank: Int) = suit * 13 + rank - 1
    fun suit(card: Int) = card / 13
    fun rank(card: Int) = card % 13 + 1
    fun isRed(card: Int) = isRedSuit(suit(card))
    fun isRedSuit(suit: Int) = suit == DIAMONDS || suit == HEARTS

    fun rankLabel(rank: Int) = when (rank) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> rank.toString()
    }

    fun name(card: Int) = rankLabel(rank(card)) + "CDHS"[suit(card)]
}

/** Pile ids. Stock, waste, 4 foundations, 7 tableau columns. */
object Piles {
    const val STOCK = 0
    const val WASTE = 1
    const val FOUNDATION_0 = 2
    const val TABLEAU_0 = 6
    const val COUNT = 13

    fun foundation(i: Int) = FOUNDATION_0 + i
    fun tableau(i: Int) = TABLEAU_0 + i
    fun isFoundation(p: Int) = p in FOUNDATION_0 until TABLEAU_0
    fun isTableau(p: Int) = p in TABLEAU_0 until COUNT
}

/** A small growable-free int stack; a pile never holds more than 52 cards. */
class Pile(private val cards: IntArray = IntArray(Cards.COUNT), size: Int = 0) {
    var size: Int = size
        private set

    operator fun get(i: Int) = cards[i]
    fun top() = cards[size - 1]
    fun isEmpty() = size == 0
    fun isNotEmpty() = size > 0

    fun push(card: Int) {
        cards[size++] = card
    }

    fun pop(): Int = cards[--size]

    fun clear() {
        size = 0
    }

    /** Moves cards [from, size) onto [dest], preserving order. */
    fun moveTailTo(from: Int, dest: Pile) {
        for (i in from until size) dest.push(cards[i])
        size = from
    }

    fun copy() = Pile(cards.copyOf(), size)

    fun toList(): List<Int> = List(size) { cards[it] }
}
