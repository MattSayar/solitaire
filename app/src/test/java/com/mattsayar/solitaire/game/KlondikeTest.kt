package com.mattsayar.solitaire.game

import com.mattsayar.solitaire.game.Cards.CLUBS
import com.mattsayar.solitaire.game.Cards.DIAMONDS
import com.mattsayar.solitaire.game.Cards.HEARTS
import com.mattsayar.solitaire.game.Cards.SPADES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlondikeTest {

    private fun c(suit: Int, rank: Int) = Cards.of(suit, rank)

    /** Builds a position; any cards not listed are put on the foundations in order, so the state is complete. */
    private fun position(
        tableau: List<List<Int>> = List(7) { emptyList() },
        hidden: List<Int> = List(7) { 0 },
        stock: List<Int> = emptyList(),
        waste: List<Int> = emptyList(),
        draw: Int = 1,
    ): Klondike {
        val s = State.empty()
        val used = BooleanArray(52)
        tableau.forEachIndexed { i, col ->
            col.forEach { s.piles[Piles.tableau(i)].push(it); used[it] = true }
            s.hidden[Piles.tableau(i)] = hidden[i]
        }
        stock.forEach { s.piles[Piles.STOCK].push(it); used[it] = true }
        waste.forEach { s.piles[Piles.WASTE].push(it); used[it] = true }
        // Remaining cards go to foundations, but only as contiguous runs from the ace.
        for (suit in 0 until 4) {
            for (rank in 1..13) {
                val card = c(suit, rank)
                if (used[card]) break
                s.piles[Piles.foundation(suit)].push(card); used[card] = true
            }
        }
        // Anything still unplaced (above a gap) goes to the stock bottom.
        for (card in 0 until 52) if (!used[card]) {
            val stockPile = s.piles[Piles.STOCK]
            val existing = stockPile.toList()
            stockPile.clear(); stockPile.push(card); existing.forEach { stockPile.push(it) }
        }
        return Klondike.fromState(draw, s)
    }

    @Test
    fun dealIsCompleteAndDeterministic() {
        val a = Klondike.deal(1, 42L)
        val b = Klondike.deal(1, 42L)
        assertEquals(a.serialize(), b.serialize())
        val seen = BooleanArray(52)
        for (p in 0 until Piles.COUNT) for (i in 0 until a.pile(p).size) seen[a.pile(p)[i]] = true
        assertTrue(seen.all { it })
        for (col in 0 until 7) {
            assertEquals(col + 1, a.pile(Piles.tableau(col)).size)
            assertEquals(col, a.hidden(Piles.tableau(col)))
        }
        assertEquals(24, a.pile(Piles.STOCK).size)
    }

    @Test
    fun drawOneAndThreeAndRecycle() {
        val g1 = Klondike.deal(1, 7L)
        assertTrue(g1.draw())
        assertEquals(1, g1.pile(Piles.WASTE).size)
        val g3 = Klondike.deal(3, 7L)
        assertTrue(g3.draw())
        assertEquals(3, g3.pile(Piles.WASTE).size)
        repeat(7) { g3.draw() }
        assertEquals(0, g3.pile(Piles.STOCK).size)
        assertEquals(24, g3.pile(Piles.WASTE).size)
        val topOfStockBefore = Klondike.deal(3, 7L).pile(Piles.STOCK).top()
        assertTrue(g3.draw()) // recycle
        assertEquals(24, g3.pile(Piles.STOCK).size)
        assertEquals(topOfStockBefore, g3.pile(Piles.STOCK).top())
    }

    @Test
    fun tableauRules() {
        val g = position(
            tableau = listOf(
                listOf(c(SPADES, 9)), listOf(c(HEARTS, 8)), listOf(c(CLUBS, 8)),
                listOf(), listOf(c(DIAMONDS, 13)), listOf(), listOf()
            )
        )
        assertTrue(g.canMove(Piles.tableau(1), 0, Piles.tableau(0)))   // red 8 on black 9
        assertFalse(g.canMove(Piles.tableau(2), 0, Piles.tableau(0)))  // black on black
        assertTrue(g.canMove(Piles.tableau(4), 0, Piles.tableau(3)))   // king to empty
        assertFalse(g.canMove(Piles.tableau(0), 0, Piles.tableau(3)))  // non-king to empty
    }

    @Test
    fun movingRevealsAndScores() {
        val g = position(
            tableau = listOf(
                listOf(c(CLUBS, 5), c(HEARTS, 8)), listOf(c(SPADES, 9)),
                listOf(), listOf(), listOf(), listOf(), listOf()
            ),
            hidden = listOf(1, 0, 0, 0, 0, 0, 0)
        )
        assertTrue(g.move(Piles.tableau(0), 1, Piles.tableau(1)))
        assertEquals(0, g.hidden(Piles.tableau(0)))
        assertEquals(5, g.score)
        assertTrue(g.undo())
        assertEquals(1, g.hidden(Piles.tableau(0)))
        assertEquals(2, g.pile(Piles.tableau(0)).size)
    }

    @Test
    fun cannotPickUpFaceDown() {
        val g = Klondike.deal(1, 3L)
        assertFalse(g.isMovable(Piles.tableau(6), 0))
        assertTrue(g.isMovable(Piles.tableau(6), 6))
        assertFalse(g.isMovable(Piles.STOCK, 0))
    }

    @Test
    fun undoRestoresEverything() {
        val g = Klondike.deal(3, 99L)
        val start = g.state.serialize().substringAfter('|').substringAfter('|')
        repeat(5) { g.draw() }
        repeat(5) { assertTrue(g.undo()) }
        assertFalse(g.undo())
        assertEquals(start, g.state.serialize().substringAfter('|').substringAfter('|'))
    }

    @Test
    fun serializationRoundTrip() {
        val g = Klondike.deal(3, 1234L)
        repeat(4) { g.draw() }
        val restored = Klondike.deserialize(g.serialize())
        assertNotNull(restored)
        assertEquals(g.serialize(), restored!!.serialize())
        assertEquals(4, restored.undoDepth)
        assertNull(Klondike.deserialize("garbage"))
    }

    @Test
    fun tapTargetsPreferFoundationThenCycle() {
        val g = position(
            tableau = listOf(
                listOf(c(HEARTS, 1)), listOf(c(SPADES, 9)), listOf(c(CLUBS, 9)),
                listOf(c(HEARTS, 8)), listOf(), listOf(), listOf()
            )
        )
        assertEquals(g.foundationFor(c(HEARTS, 1)), g.targetsFor(Piles.tableau(0), 0).first())
        // Red 8 can go on either black 9: order starts after the source column and wraps.
        assertEquals(listOf(Piles.tableau(1), Piles.tableau(2)), g.targetsFor(Piles.tableau(3), 0))
    }

    @Test
    fun autoCompleteFinishesWhenAllFaceUp() {
        val g = position(
            tableau = listOf(
                listOf(c(SPADES, 13), c(HEARTS, 12)), listOf(c(HEARTS, 13), c(SPADES, 12)),
                listOf(c(CLUBS, 13)), listOf(c(DIAMONDS, 13)), listOf(), listOf(), listOf()
            ),
            waste = listOf(c(CLUBS, 12)), stock = listOf(c(DIAMONDS, 12))
        )
        assertTrue(g.canAutoComplete())
        var guard = 0
        while (!g.isWon && guard++ < 100) assertNotNull(g.autoCompleteStep())
        assertTrue(g.isWon)
    }

    @Test
    fun noAutoCompleteWithHiddenCards() {
        assertFalse(Klondike.deal(1, 5L).canAutoComplete())
    }

    @Test
    fun safeFoundationMoveRespectsOppositeColours() {
        val g = position(
            tableau = listOf(
                listOf(c(HEARTS, 3)), listOf(), listOf(), listOf(), listOf(), listOf(), listOf()
            ),
            stock = listOf(c(CLUBS, 2), c(SPADES, 2))
        )
        // Hearts 3 is not safe: black 2s are still out.
        assertNull(g.safeFoundationMove())
    }

    @Test
    fun hintsAlwaysOfferSomethingOnFreshDeal() {
        for (seed in 0L until 200L) {
            val g = Klondike.deal(if (seed % 2 == 0L) 1 else 3, seed)
            val hints = g.hints()
            assertTrue(hints.isNotEmpty())
            for (h in hints) {
                if (h.to >= 0) assertTrue("seed $seed $h", g.canMove(h.from, h.index, h.to))
            }
        }
    }

    @Test
    fun randomPlayNeverCorruptsState() {
        val rnd = java.util.Random(1)
        repeat(200) { game ->
            val g = Klondike.deal(if (game % 2 == 0) 1 else 3, game.toLong())
            repeat(300) {
                val hints = g.hints()
                val r = rnd.nextInt(10)
                when {
                    r == 0 && g.canUndo -> g.undo()
                    hints.isEmpty() -> return@repeat
                    else -> {
                        val h = hints[rnd.nextInt(hints.size)]
                        if (h.to < 0) g.draw() else assertTrue(g.move(h.from, h.index, h.to))
                    }
                }
                var total = 0
                for (p in 0 until Piles.COUNT) total += g.pile(p).size
                assertEquals(52, total)
            }
            assertNotNull(Klondike.deserialize(g.serialize()))
        }
    }
}
