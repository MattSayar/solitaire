package com.mattsayar.solitaire.game

import com.mattsayar.solitaire.game.Piles.STOCK
import com.mattsayar.solitaire.game.Piles.WASTE
import com.mattsayar.solitaire.game.Piles.isFoundation
import com.mattsayar.solitaire.game.Piles.isTableau
import java.util.Random

/** Full, copyable game position. Cheap to snapshot (13 small int arrays), which is how undo works. */
class State(
    val piles: Array<Pile>,
    /** Number of face-down cards at the bottom of each pile (only meaningful for tableau piles). */
    val hidden: IntArray,
    var score: Int,
    var moves: Int,
    var recycles: Int,
) {
    fun copy() = State(Array(piles.size) { piles[it].copy() }, hidden.copyOf(), score, moves, recycles)

    fun serialize(): String = buildString {
        append(score).append('|').append(moves).append('|').append(recycles).append('|')
        hidden.joinTo(this, ",")
        append('|')
        piles.joinTo(this, ";") { it.toList().joinToString(",") }
    }

    companion object {
        fun empty() = State(Array(Piles.COUNT) { Pile() }, IntArray(Piles.COUNT), 0, 0, 0)

        fun deserialize(s: String): State? = runCatching {
            val parts = s.split('|')
            val hidden = parts[3].split(',').map { it.toInt() }.toIntArray()
            val pileStrs = parts[4].split(';')
            require(hidden.size == Piles.COUNT && pileStrs.size == Piles.COUNT)
            val seen = BooleanArray(Cards.COUNT)
            val piles = Array(Piles.COUNT) { p ->
                val pile = Pile()
                if (pileStrs[p].isNotEmpty()) pileStrs[p].split(',').forEach {
                    val c = it.toInt()
                    require(c in 0 until Cards.COUNT && !seen[c])
                    seen[c] = true
                    pile.push(c)
                }
                require(hidden[p] in 0..pile.size)
                pile
            }
            require(seen.all { it })
            State(piles, hidden, parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }.getOrNull()
    }
}

/** A suggested move. [to] == -1 with [from] == STOCK means "draw from the stock". */
data class Hint(val from: Int, val index: Int, val to: Int)

/**
 * Klondike rules engine: draw-1 or draw-3, unlimited passes through the stock,
 * standard (Windows-style) scoring, unlimited undo.
 */
class Klondike private constructor(val drawCount: Int, val seed: Long, state: State) {

    var state: State = state
        private set

    private val undoStack = ArrayList<State>()
    private var recordUndo = true

    val canUndo get() = undoStack.isNotEmpty()
    val undoDepth get() = undoStack.size
    val score get() = state.score
    val moves get() = state.moves
    val isWon get() = (0 until 4).sumOf { pile(Piles.foundation(it)).size } == Cards.COUNT

    fun pile(p: Int): Pile = state.piles[p]
    fun hidden(p: Int): Int = state.hidden[p]

    fun isFaceUp(p: Int, i: Int): Boolean = when {
        p == STOCK -> false
        isTableau(p) -> i >= state.hidden[p]
        else -> true
    }

    // ---------------------------------------------------------------- rules

    /** Can [card] (possibly heading a run) be placed on pile [to]? */
    fun accepts(to: Int, card: Int): Boolean {
        val dest = pile(to)
        return when {
            isFoundation(to) ->
                if (dest.isEmpty()) Cards.rank(card) == 1
                else dest.top() == card - 1 && Cards.suit(dest.top()) == Cards.suit(card)
            isTableau(to) ->
                if (dest.isEmpty()) Cards.rank(card) == 13
                else Cards.rank(dest.top()) == Cards.rank(card) + 1 && Cards.isRed(dest.top()) != Cards.isRed(card)
            else -> false
        }
    }

    /** Can the run starting at [index] of pile [p] be picked up? */
    fun isMovable(p: Int, index: Int): Boolean {
        val src = pile(p)
        if (index < 0 || index >= src.size) return false
        return when {
            p == STOCK -> false
            isTableau(p) -> index >= state.hidden[p]
            else -> index == src.size - 1
        }
    }

    fun canMove(from: Int, index: Int, to: Int): Boolean {
        if (from == to || !isMovable(from, index)) return false
        if (isFoundation(to) && index != pile(from).size - 1) return false
        return accepts(to, pile(from)[index])
    }

    // ---------------------------------------------------------------- actions

    fun move(from: Int, index: Int, to: Int): Boolean {
        if (!canMove(from, index, to)) return false
        pushUndo()
        val s = state
        s.piles[from].moveTailTo(index, s.piles[to])
        s.score += when {
            from == WASTE && isTableau(to) -> 5
            isFoundation(to) && !isFoundation(from) -> 10
            isFoundation(from) && isTableau(to) -> -15
            else -> 0
        }
        revealIfNeeded(from)
        s.moves++
        clampScore()
        return true
    }

    /** Draws from the stock, or recycles the waste when the stock is empty. */
    fun draw(): Boolean {
        val s = state
        val stock = s.piles[STOCK]
        val waste = s.piles[WASTE]
        if (stock.isEmpty() && waste.isEmpty()) return false
        pushUndo()
        if (stock.isNotEmpty()) {
            repeat(minOf(drawCount, stock.size)) { waste.push(stock.pop()) }
        } else {
            while (waste.isNotEmpty()) stock.push(waste.pop())
            s.recycles++
            s.score -= if (drawCount == 1) 100 else 20
            clampScore()
        }
        s.moves++
        return true
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val movesSoFar = state.moves
        state = undoStack.removeAt(undoStack.size - 1)
        state.moves = movesSoFar + 1 // undo counts as a move, like the classics
        return true
    }

    /** Drops undo entries above [depth], merging the moves made since then into a single undo step. */
    fun squashUndo(depth: Int) {
        while (undoStack.size > depth) undoStack.removeAt(undoStack.size - 1)
    }

    private fun revealIfNeeded(p: Int) {
        if (isTableau(p) && state.hidden[p] > 0 && state.hidden[p] == state.piles[p].size) {
            state.hidden[p]--
            state.score += 5
        }
    }

    private fun clampScore() {
        if (state.score < 0) state.score = 0
    }

    private fun pushUndo() {
        if (recordUndo) undoStack.add(state.copy())
    }

    // ---------------------------------------------------------------- helpers for the UI

    /** Foundation that accepts [card], or -1. */
    fun foundationFor(card: Int): Int {
        for (f in 0 until 4) if (accepts(Piles.foundation(f), card)) return Piles.foundation(f)
        return -1
    }

    /**
     * Destinations for tap-to-move, best first. Tapping the same card repeatedly cycles through them
     * because tableau columns are scanned starting just after the source column.
     */
    fun targetsFor(from: Int, index: Int): List<Int> {
        if (!isMovable(from, index)) return emptyList()
        val src = pile(from)
        val card = src[index]
        val out = ArrayList<Int>(4)
        if (index == src.size - 1 && !isFoundation(from)) {
            val f = foundationFor(card)
            if (f >= 0) out.add(f)
        }
        val start = if (isTableau(from)) from - Piles.TABLEAU_0 + 1 else 0
        var firstEmpty = -1
        for (k in 0 until 7) {
            val t = Piles.tableau((start + k) % 7)
            if (t == from || !accepts(t, card)) continue
            if (pile(t).isEmpty()) {
                if (firstEmpty < 0) firstEmpty = t
            } else out.add(t)
        }
        // Includes kings already at the base of a column: the player asked for it, so honour the tap.
        // (hints() never suggests that shuffle.)
        if (firstEmpty >= 0) out.add(firstEmpty)
        return out
    }

    /** Useful moves, most valuable first. Falls back to drawing from the stock. */
    fun hints(): List<Hint> {
        val out = ArrayList<Hint>()
        val waste = pile(WASTE)
        // 1. Anything to the foundations.
        if (waste.isNotEmpty()) {
            val f = foundationFor(waste.top())
            if (f >= 0) out.add(Hint(WASTE, waste.size - 1, f))
        }
        for (c in 0 until 7) {
            val p = Piles.tableau(c)
            val src = pile(p)
            if (src.isEmpty()) continue
            val f = foundationFor(src.top())
            if (f >= 0) out.add(Hint(p, src.size - 1, f))
        }
        // 2. Tableau runs that uncover a face-down card (or empty a column for a king).
        for (c in 0 until 7) {
            val p = Piles.tableau(c)
            val src = pile(p)
            val h = state.hidden[p]
            if (src.isEmpty()) continue
            if (h == 0) continue // moving the whole face-up column only shuffles it around
            for (t in targetsFor(p, h)) if (isTableau(t)) {
                out.add(Hint(p, h, t)); break
            }
        }
        // 3. Waste to tableau.
        if (waste.isNotEmpty()) {
            for (t in targetsFor(WASTE, waste.size - 1)) if (isTableau(t)) {
                out.add(Hint(WASTE, waste.size - 1, t)); break
            }
        }
        // 4. Partial runs that let a buried card reach its foundation.
        for (c in 0 until 7) {
            val p = Piles.tableau(c)
            val src = pile(p)
            for (i in state.hidden[p] + 1 until src.size) {
                if (foundationFor(src[i - 1]) < 0) continue
                val t = targetsFor(p, i).firstOrNull { isTableau(it) && pile(it).isNotEmpty() } ?: continue
                out.add(Hint(p, i, t))
            }
        }
        // 5. Draw.
        if (pile(STOCK).isNotEmpty() || pile(WASTE).isNotEmpty()) out.add(Hint(STOCK, -1, -1))
        return out
    }

    /** Next foundation move that can never hurt (no card could ever need to be placed on it). */
    fun safeFoundationMove(): Hint? {
        val candidates = IntArray(8)
        var n = 0
        if (pile(WASTE).isNotEmpty()) candidates[n++] = WASTE
        for (c in 0 until 7) if (pile(Piles.tableau(c)).isNotEmpty()) candidates[n++] = Piles.tableau(c)
        for (k in 0 until n) {
            val p = candidates[k]
            val card = pile(p).top()
            val f = foundationFor(card)
            if (f >= 0 && isSafe(card)) return Hint(p, pile(p).size - 1, f)
        }
        return null
    }

    private fun foundationRank(suit: Int): Int {
        for (f in 0 until 4) {
            val fp = pile(Piles.foundation(f))
            if (fp.isNotEmpty() && Cards.suit(fp.top()) == suit) return Cards.rank(fp.top())
        }
        return 0
    }

    private fun isSafe(card: Int): Boolean {
        val r = Cards.rank(card)
        if (r <= 2) return true
        val red = Cards.isRed(card)
        for (s in 0 until 4) {
            if (Cards.isRedSuit(s) != red && foundationRank(s) < r - 1) return false
        }
        return true
    }

    // ---------------------------------------------------------------- auto-complete

    /**
     * One step of auto-complete: the lowest card that can go to a foundation, otherwise a draw.
     * Returns the move performed (to == -1 for a draw) or null if nothing could be done.
     */
    fun autoCompleteStep(): Hint? {
        var best: Hint? = null
        var bestRank = 99
        val waste = pile(WASTE)
        if (waste.isNotEmpty()) {
            val f = foundationFor(waste.top())
            if (f >= 0) {
                best = Hint(WASTE, waste.size - 1, f); bestRank = Cards.rank(waste.top())
            }
        }
        for (c in 0 until 7) {
            val p = Piles.tableau(c)
            val src = pile(p)
            if (src.isEmpty()) continue
            val r = Cards.rank(src.top())
            if (r >= bestRank) continue
            val f = foundationFor(src.top())
            if (f >= 0) {
                best = Hint(p, src.size - 1, f); bestRank = r
            }
        }
        if (best != null) {
            move(best.from, best.index, best.to)
            return best
        }
        return if (draw()) Hint(STOCK, -1, -1) else null
    }

    /** True when every card is face up and a simulated auto-finish reaches a win. */
    fun canAutoComplete(): Boolean {
        if (isWon) return false
        for (c in 0 until 7) if (state.hidden[Piles.tableau(c)] > 0) return false
        val sim = Klondike(drawCount, seed, state.copy())
        sim.recordUndo = false
        var idleDraws = 0
        var guard = 0
        while (!sim.isWon && guard++ < 2000) {
            val step = sim.autoCompleteStep() ?: return false
            if (step.to == -1) {
                if (++idleDraws > 2 * (sim.pile(STOCK).size + sim.pile(WASTE).size) + 4) return false
            } else idleDraws = 0
        }
        return sim.isWon
    }

    // ---------------------------------------------------------------- persistence

    fun serialize(maxUndo: Int = 300): String = buildString {
        append("v1\n").append(drawCount).append('\n').append(seed).append('\n')
        append(state.serialize())
        for (i in maxOf(0, undoStack.size - maxUndo) until undoStack.size) {
            append('\n').append(undoStack[i].serialize())
        }
    }

    companion object {
        fun deal(drawCount: Int, seed: Long): Klondike {
            val deck = IntArray(Cards.COUNT) { it }
            val rnd = Random(seed)
            for (i in deck.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = deck[i]; deck[i] = deck[j]; deck[j] = t
            }
            val s = State.empty()
            var k = 0
            for (col in 0 until 7) {
                val p = Piles.tableau(col)
                repeat(col + 1) { s.piles[p].push(deck[k++]) }
                s.hidden[p] = col
            }
            while (k < deck.size) s.piles[STOCK].push(deck[k++])
            return Klondike(if (drawCount == 3) 3 else 1, seed, s)
        }

        fun deserialize(text: String): Klondike? {
            val lines = text.split('\n')
            if (lines.size < 4 || lines[0] != "v1") return null
            val draw = lines[1].toIntOrNull() ?: return null
            val seed = lines[2].toLongOrNull() ?: return null
            val current = State.deserialize(lines[3]) ?: return null
            val game = Klondike(if (draw == 3) 3 else 1, seed, current)
            for (i in 4 until lines.size) State.deserialize(lines[i])?.let { game.undoStack.add(it) }
            return game
        }

        /** Test hook: build a game from an explicit position. */
        internal fun fromState(drawCount: Int, state: State) = Klondike(drawCount, 0L, state)
    }
}
