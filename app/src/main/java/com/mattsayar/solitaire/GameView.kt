package com.mattsayar.solitaire

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.mattsayar.solitaire.game.Cards
import com.mattsayar.solitaire.game.Hint
import com.mattsayar.solitaire.game.Klondike
import com.mattsayar.solitaire.game.Piles
import com.mattsayar.solitaire.game.Piles.STOCK
import com.mattsayar.solitaire.game.Piles.WASTE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The table. Everything is drawn on one hardware-accelerated canvas from pre-rendered card bitmaps.
 * Every card is a sprite that eases from wherever it is to wherever the game state says it should be,
 * so moves, undo, deals and auto-play all animate for free and input is never blocked by animation.
 */
class GameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Feedback { PICK, PLACE, REVEAL, FOUNDATION, DRAW, RECYCLE, INVALID, UNDO, DEAL }

    interface Listener {
        /** State changed. [byUser] is true for moves the player made (starts the clock). */
        fun onGameChanged(byUser: Boolean)
        fun onFeedback(kind: Feedback)
        fun onNoHints()
        fun onWon()
        fun onWinTapped()
    }

    var listener: Listener? = null

    lateinit var game: Klondike
        private set

    var leftHanded = false
        set(v) { if (field != v) { field = v; relayout() } }
    var fourColor = false
        set(v) { if (field != v) { field = v; relayout() } }
    var autoFoundation = true

    private val renderer = CardRenderer()
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ------------------------------------------------------------------ geometry
    /** Space covered by the floating status row / toolbar; cards are laid out between them. */
    private var insetTop = 0f
    private var insetBottom = 0f
    private var cw = 0f
    private var ch = 0f
    private var gap = 0f
    private var topY = 0f
    private var tabY = 0f
    private var bottomY = 0f
    private val colX = FloatArray(7)
    private var fdBase = 0f
    private var fuBase = 0f
    private var wasteFan = 0f
    private val pileX = FloatArray(Piles.COUNT)
    private val pileY = FloatArray(Piles.COUNT)
    private val colFu = FloatArray(7)
    private val colFd = FloatArray(7)

    // ------------------------------------------------------------------ layout targets (per card id)
    private val tx = FloatArray(Cards.COUNT)
    private val ty = FloatArray(Cards.COUNT)
    private val tFace = BooleanArray(Cards.COUNT)
    private val tz = IntArray(Cards.COUNT)
    private val tPile = IntArray(Cards.COUNT)
    private val tIndex = IntArray(Cards.COUNT)

    // ------------------------------------------------------------------ sprites
    private val fromX = FloatArray(Cards.COUNT)
    private val fromY = FloatArray(Cards.COUNT)
    private val toX = FloatArray(Cards.COUNT)
    private val toY = FloatArray(Cards.COUNT)
    private val moveStart = LongArray(Cards.COUNT)
    private val moveDur = IntArray(Cards.COUNT)
    private val faceFrom = BooleanArray(Cards.COUNT)
    private val faceTo = BooleanArray(Cards.COUNT)
    private val flipStart = LongArray(Cards.COUNT)
    private val shakeStart = LongArray(Cards.COUNT)
    private val dx = FloatArray(Cards.COUNT)
    private val dy = FloatArray(Cards.COUNT)
    private val dScale = FloatArray(Cards.COUNT)
    private val dFace = BooleanArray(Cards.COUNT)
    private val dAnimating = BooleanArray(Cards.COUNT)
    private val order = IntArray(Cards.COUNT) { it }
    private val sortKey = IntArray(Cards.COUNT)
    private var hasLayout = false
    private var pendingDeal = false

    // ------------------------------------------------------------------ drag state
    private var downX = 0f
    private var downY = 0f
    private var touchPile = -1
    private var touchIndex = -1
    private var dragging = false
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private val isDragged = BooleanArray(Cards.COUNT)
    private var trackingTouch = false

    // ------------------------------------------------------------------ hints / auto play
    private var hintList: List<Hint> = emptyList()
    private var hintCursor = 0
    private var hint: Hint? = null
    private var hintStart = 0L
    private var autoCompleting = false
    private var undoMarkForAuto = -1

    // ------------------------------------------------------------------ win cascade
    private var winMode = false
    private var trail: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private val launched = BooleanArray(Cards.COUNT)
    private val pX = FloatArray(Cards.COUNT)
    private val pY = FloatArray(Cards.COUNT)
    private val pVx = FloatArray(Cards.COUNT)
    private val pVy = FloatArray(Cards.COUNT)
    private val pAlive = BooleanArray(Cards.COUNT)
    private var launchCount = 0
    private var lastLaunch = 0L
    private var lastFrame = 0L

    // ------------------------------------------------------------------ paints
    private val bgPaint = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpRect = RectF()

    init {
        isHapticFeedbackEnabled = true
        contentDescription = "Solitaire table"
    }

    // ================================================================== public API

    fun setGame(g: Klondike, animateDeal: Boolean) {
        game = g
        endWin()
        cancelAuto()
        hint = null
        hintList = emptyList()
        if (!hasLayout) {
            pendingDeal = animateDeal; return
        }
        computeTargets()
        if (animateDeal) {
            val now = SystemClock.uptimeMillis()
            val sx = pileX[STOCK]; val sy = pileY[STOCK]
            for (c in 0 until Cards.COUNT) {
                val p = tPile[c]
                if (Piles.isTableau(p)) {
                    val col = p - Piles.TABLEAU_0
                    val i = tIndex[c]
                    // Classic dealing order: row by row across the columns.
                    var ordinal = 0
                    for (r in 0 until i) ordinal += 7 - r
                    ordinal += col - i
                    startMove(c, sx, sy, tx[c], ty[c], now + 60 + ordinal * 22L, 230)
                    faceFrom[c] = false; faceTo[c] = tFace[c]
                    flipStart[c] = moveStart[c] + 170
                } else snap(c)
            }
            listener?.onFeedback(Feedback.DEAL)
        } else {
            for (c in 0 until Cards.COUNT) snap(c)
        }
        invalidate()
    }

    fun drawFromStock() {
        if (inputLocked()) return
        val wasEmpty = game.pile(STOCK).isEmpty()
        if (game.draw()) {
            afterAction(if (wasEmpty) Feedback.RECYCLE else Feedback.DRAW, byUser = true)
        }
    }

    fun undo(): Boolean {
        if (winMode || autoCompleting) return false
        cancelAuto()
        undoMarkForAuto = -1
        if (!game.undo()) return false
        cancelDrag()
        hint = null
        sync()
        listener?.onFeedback(Feedback.UNDO)
        listener?.onGameChanged(byUser = false)
        return true
    }

    fun showHint() {
        if (inputLocked()) return
        if (hintList.isEmpty()) {
            hintList = game.hints(); hintCursor = 0
        }
        if (hintList.isEmpty()) {
            listener?.onNoHints(); return
        }
        hint = hintList[hintCursor % hintList.size]
        hintCursor++
        hintStart = SystemClock.uptimeMillis()
        invalidate()
    }

    fun canAutoComplete() = !autoCompleting && !winMode && game.canAutoComplete()

    fun startAutoComplete() {
        if (!canAutoComplete()) return
        cancelAuto()
        autoCompleting = true
        hint = null
        post(autoCompleteStep)
    }

    fun setContentInsets(top: Int, bottom: Int) {
        if (top.toFloat() == insetTop && bottom.toFloat() == insetBottom) return
        insetTop = top.toFloat(); insetBottom = bottom.toFloat()
        relayout()
    }

    fun relayout() {
        if (width == 0) return
        computeGeometry(width, height)
        if (::game.isInitialized) {
            computeTargets()
            for (c in 0 until Cards.COUNT) snap(c)
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(autoCompleteStep)
        removeCallbacks(autoFoundationStep)
    }

    fun release() {
        endWin()
        renderer.release()
    }

    // ================================================================== geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        computeGeometry(w, h)
        hasLayout = true
        if (winMode) endWin()
        if (::game.isInitialized) {
            if (pendingDeal) {
                pendingDeal = false
                setGame(game, animateDeal = true)
            } else {
                computeTargets()
                for (c in 0 until Cards.COUNT) snap(c)
            }
        }
    }

    private fun computeGeometry(w: Int, h: Int) {
        val pad = w * 0.02f
        gap = w * 0.014f
        cw = (w - 2 * pad - 6 * gap) / 7f
        ch = cw * 1.42f
        val maxCh = (h - insetTop - insetBottom) * 0.28f // landscape / tablets: keep room for long columns
        if (ch > maxCh) {
            ch = maxCh; cw = ch / 1.42f
        }
        cw = cw.toInt().toFloat(); ch = ch.toInt().toFloat()
        val left = (w - (7 * cw + 6 * gap)) / 2f
        for (i in 0 until 7) colX[i] = left + i * (cw + gap)
        fdBase = ch * 0.10f
        fuBase = ch * 0.28f
        val rowGap = max(gap * 2f, ch * 0.14f)
        bottomY = h - insetBottom - pad
        // One-handed reach: on tall screens, slide the whole board down into thumb range while still
        // leaving room for the tallest realistic column (6 face-down + a full king-to-ace run).
        val needed = ch + rowGap + 6 * fdBase + 12 * fuBase + ch
        val slack = bottomY - (insetTop + max(pad, 4 * density)) - needed
        topY = insetTop + max(pad, 4 * density) + max(0f, slack * 0.8f)
        tabY = topY + ch + rowGap
        wasteFan = cw * 0.36f

        if (leftHanded) {
            place(STOCK, 0); place(WASTE, 1)
            for (f in 0 until 4) place(Piles.foundation(f), 3 + f)
        } else {
            for (f in 0 until 4) place(Piles.foundation(f), f)
            place(WASTE, 4); place(STOCK, 6)
        }
        for (i in 0 until 7) {
            pileX[Piles.tableau(i)] = colX[i]; pileY[Piles.tableau(i)] = tabY
        }
        renderer.ensure(cw.toInt(), ch.toInt(), fourColor)
        bgPaint.shader = RadialGradient(
            w / 2f, h * 0.35f, max(w, h) * 0.85f,
            intArrayOf(0xFF1E8A57.toInt(), 0xFF116B41.toInt(), 0xFF09442A.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun place(p: Int, col: Int) {
        pileX[p] = colX[col]; pileY[p] = topY
    }

    /** Where every card should be for the current game state. */
    private fun computeTargets() {
        val g = game
        // Stock & foundations: stacked.
        for (p in intArrayOf(STOCK, Piles.foundation(0), Piles.foundation(1), Piles.foundation(2), Piles.foundation(3))) {
            val pile = g.pile(p)
            val base = if (p == STOCK) 0 else 200
            for (i in 0 until pile.size) {
                setTarget(pile[i], pileX[p], pileY[p], p != STOCK, base + i, p, i)
            }
        }
        // Waste: in draw-3 the top three are fanned.
        val waste = g.pile(WASTE)
        val fanned = if (g.drawCount == 3) min(3, waste.size) else min(1, waste.size)
        val firstFan = waste.size - fanned
        for (i in 0 until waste.size) {
            val slot = max(0, i - firstFan)
            setTarget(waste[i], pileX[WASTE] + slot * wasteFan, pileY[WASTE], true, 100 + i, WASTE, i)
        }
        // Tableau: fanned, compressed per column if it would run off the bottom.
        val avail = bottomY - tabY
        for (col in 0 until 7) {
            val p = Piles.tableau(col)
            val pile = g.pile(p)
            val hidden = g.hidden(p)
            val up = pile.size - hidden
            var fd = fdBase
            var fu = fuBase
            val needed = hidden * fd + max(0, up - 1) * fu + ch
            if (needed > avail) {
                val minFu = ch * 0.17f
                fu = max(minFu, (avail - ch - hidden * fd) / max(1, up - 1))
                val rest = avail - ch - max(0, up - 1) * fu
                if (hidden > 0 && hidden * fd > rest) fd = max(ch * 0.04f, rest / hidden)
            }
            colFd[col] = fd; colFu[col] = fu
            var y = tabY
            for (i in 0 until pile.size) {
                setTarget(pile[i], colX[col], y, i >= hidden, 300 + i, p, i)
                y += if (i < hidden) fd else fu
            }
        }
    }

    private fun setTarget(c: Int, x: Float, y: Float, face: Boolean, z: Int, p: Int, i: Int) {
        tx[c] = x; ty[c] = y; tFace[c] = face; tz[c] = z; tPile[c] = p; tIndex[c] = i
    }

    // ================================================================== sprite animation

    private fun snap(c: Int) {
        fromX[c] = tx[c]; fromY[c] = ty[c]; toX[c] = tx[c]; toY[c] = ty[c]
        moveStart[c] = 0; moveDur[c] = 1
        faceFrom[c] = tFace[c]; faceTo[c] = tFace[c]; flipStart[c] = 0
        dx[c] = tx[c]; dy[c] = ty[c]; dFace[c] = tFace[c]; dScale[c] = 1f
    }

    private fun startMove(c: Int, fx: Float, fy: Float, x: Float, y: Float, start: Long, dur: Int) {
        fromX[c] = fx; fromY[c] = fy; toX[c] = x; toY[c] = y; moveStart[c] = start; moveDur[c] = dur
        dx[c] = fx; dy[c] = fy
    }

    /** Animate every card from where it currently is to its new target. */
    private fun sync(fast: Boolean = false) {
        computeTargets()
        val now = SystemClock.uptimeMillis()
        for (c in 0 until Cards.COUNT) {
            if (isDragged[c]) continue
            if (toX[c] != tx[c] || toY[c] != ty[c]) {
                val dist = hypot(tx[c] - dx[c], ty[c] - dy[c])
                val dur = if (fast) 140 else (150 + (dist / ch) * 18).toInt().coerceAtMost(260)
                startMove(c, dx[c], dy[c], tx[c], ty[c], now, dur)
            }
            if (faceTo[c] != tFace[c]) {
                faceFrom[c] = dFace[c]; faceTo[c] = tFace[c]
                flipStart[c] = max(now, moveStart[c] + moveDur[c] / 3)
            }
        }
        invalidate()
    }

    private fun updateSprite(c: Int, now: Long): Boolean {
        var animating = false
        if (isDragged[c]) {
            val lead = game.pile(touchPile)[touchIndex]
            val i = tIndex[c] - tIndex[lead]
            dx[c] = dragX - dragOffX
            dy[c] = dragY - dragOffY + i * (if (Piles.isTableau(touchPile)) colFu[touchPile - Piles.TABLEAU_0] else 0f)
            dScale[c] = 1f; dFace[c] = true
            return true
        }
        val t = now - moveStart[c]
        if (t < moveDur[c]) {
            animating = true
            if (t <= 0) {
                dx[c] = fromX[c]; dy[c] = fromY[c]
            } else {
                val p = t.toFloat() / moveDur[c]
                val e = 1f - (1f - p) * (1f - p) * (1f - p) // ease-out cubic
                dx[c] = fromX[c] + (toX[c] - fromX[c]) * e
                dy[c] = fromY[c] + (toY[c] - fromY[c]) * e
            }
        } else {
            dx[c] = toX[c]; dy[c] = toY[c]
        }
        if (faceFrom[c] != faceTo[c]) {
            val ft = now - flipStart[c]
            when {
                ft < 0 -> { dFace[c] = faceFrom[c]; dScale[c] = 1f; animating = true }
                ft < FLIP_MS -> {
                    val q = ft.toFloat() / FLIP_MS
                    dScale[c] = abs(cos(q * PI)).toFloat().coerceAtLeast(0.02f)
                    dFace[c] = if (q < 0.5f) faceFrom[c] else faceTo[c]
                    animating = true
                }
                else -> { faceFrom[c] = faceTo[c]; dFace[c] = faceTo[c]; dScale[c] = 1f }
            }
        } else {
            dFace[c] = faceTo[c]; dScale[c] = 1f
        }
        val st = now - shakeStart[c]
        if (st in 0 until SHAKE_MS) {
            val q = st.toFloat() / SHAKE_MS
            dx[c] += (sin(q * PI * 6) * (1 - q) * cw * 0.08f).toFloat()
            animating = true
        }
        return animating
    }

    // ================================================================== drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        if (!hasLayout || !::game.isInitialized) return
        val now = SystemClock.uptimeMillis()
        var needsFrame = false

        if (winMode) {
            needsFrame = drawWin(canvas, now)
            if (needsFrame) postInvalidateOnAnimation()
            return
        }

        drawSlots(canvas)

        for (c in 0 until Cards.COUNT) {
            val anim = updateSprite(c, now)
            dAnimating[c] = anim
            if (anim) needsFrame = true
            sortKey[c] = tz[c] + when {
                isDragged[c] -> 2000
                anim && moveStart[c] + moveDur[c] > now -> 1000
                else -> 0
            }
        }
        // Insertion sort: the order barely changes between frames, so this is ~linear.
        for (i in 1 until order.size) {
            val v = order[i]; val k = sortKey[v]
            var j = i - 1
            while (j >= 0 && sortKey[order[j]] > k) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
        val m = renderer.margin.toFloat()
        for (k in order.indices) {
            val c = order[k]
            if (!dAnimating[c] && isBuried(c)) continue
            val bmp = if (dFace[c]) renderer.face(c) else renderer.back!!
            val x = dx[c].roundToInt() - m
            val y = dy[c].roundToInt() - m
            if (dScale[c] < 0.999f) {
                canvas.save()
                canvas.scale(dScale[c], 1f, dx[c] + cw / 2, 0f)
                canvas.drawBitmap(bmp, x, y, bmpPaint)
                canvas.restore()
            } else if (isDragged[c]) {
                // Lifted cards: slightly larger and drawn with a deeper shadow cue.
                canvas.save()
                canvas.scale(1.06f, 1.06f, dx[c] + cw / 2, dy[c] + ch / 2)
                canvas.drawBitmap(bmp, x, y, bmpPaint)
                canvas.restore()
            } else {
                canvas.drawBitmap(bmp, x, y, null)
            }
        }

        if (drawHint(canvas, now)) needsFrame = true
        if (needsFrame) postInvalidateOnAnimation()
    }

    /** Cards hidden under a neat stack are skipped: saves blits and avoids stacked shadows darkening. */
    private fun isBuried(c: Int): Boolean {
        val p = tPile[c]
        val i = tIndex[c]
        val size = game.pile(p).size
        return when {
            p == STOCK || Piles.isFoundation(p) -> i < size - 2
            p == WASTE -> {
                val fanned = if (game.drawCount == 3) min(3, size) else 1
                i < size - fanned - 1
            }
            else -> false
        }
    }

    private fun drawSlots(canvas: Canvas) {
        val m = renderer.margin.toFloat()
        val slot = renderer.slot!!
        for (f in 0 until 4) {
            val p = Piles.foundation(f)
            canvas.drawBitmap(renderer.foundationSlot!!, pileX[p] - m, pileY[p] - m, null)
        }
        val stockBmp = if (game.pile(WASTE).isNotEmpty()) renderer.stockRecycle!! else slot
        canvas.drawBitmap(stockBmp, pileX[STOCK] - m, pileY[STOCK] - m, null)
        for (i in 0 until 7) canvas.drawBitmap(slot, colX[i] - m, tabY - m, null)
    }

    private fun drawHint(canvas: Canvas, now: Long): Boolean {
        val h = hint ?: return false
        val t = now - hintStart
        if (t > HINT_MS) { hint = null; return false }
        val pulse = (0.5f + 0.5f * sin(t / 1000f * 2 * PI * 2.2f).toFloat())
        val fade = if (t > HINT_MS - 250) (HINT_MS - t) / 250f else 1f
        val alpha = ((0.55f + 0.45f * pulse) * fade * 255).toInt()
        hintPaint.color = 0xFFFFC857.toInt(); hintPaint.alpha = alpha
        hintPaint.strokeWidth = max(2f, cw * 0.06f)
        hintFill.color = 0xFFFFC857.toInt(); hintFill.alpha = (alpha * 0.22f).toInt()
        val r = renderer.corner
        if (h.to < 0) {
            pileRect(STOCK, tmpRect)
            canvas.drawRoundRect(tmpRect, r, r, hintFill); canvas.drawRoundRect(tmpRect, r, r, hintPaint)
        } else {
            val src = game.pile(h.from)
            if (h.index < src.size) {
                val first = src[h.index]; val last = src.top()
                tmpRect.set(tx[first], ty[first], tx[last] + cw, ty[last] + ch)
                canvas.drawRoundRect(tmpRect, r, r, hintFill); canvas.drawRoundRect(tmpRect, r, r, hintPaint)
            }
            topRect(h.to, tmpRect)
            canvas.drawRoundRect(tmpRect, r, r, hintFill); canvas.drawRoundRect(tmpRect, r, r, hintPaint)
        }
        return true
    }

    private fun pileRect(p: Int, out: RectF) = out.set(pileX[p], pileY[p], pileX[p] + cw, pileY[p] + ch)

    /** Rect of the top card of pile [p], or its empty slot. */
    private fun topRect(p: Int, out: RectF) {
        val pile = game.pile(p)
        if (pile.isEmpty()) pileRect(p, out) else {
            val c = pile.top(); out.set(tx[c], ty[c], tx[c] + cw, ty[c] + ch)
        }
    }

    // ================================================================== input

    private fun inputLocked() = winMode || autoCompleting

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::game.isInitialized || !hasLayout) return false
        if (winMode) {
            if (e.actionMasked == MotionEvent.ACTION_UP) listener?.onWinTapped()
            return true
        }
        if (autoCompleting) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackingTouch = true
                downX = e.x; downY = e.y
                dragging = false
                hitTest(e.x, e.y)
                if (touchIndex >= 0 && game.isMovable(touchPile, touchIndex)) {
                    val lead = game.pile(touchPile)[touchIndex]
                    updateSprite(lead, SystemClock.uptimeMillis())
                    dragOffX = e.x - dx[lead]; dragOffY = e.y - dy[lead]
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return true
                if (!dragging && touchIndex >= 0 && game.isMovable(touchPile, touchIndex) &&
                    hypot(e.x - downX, e.y - downY) > touchSlop
                ) {
                    dragging = true
                    hint = null
                    removeCallbacks(autoFoundationStep)
                    val pile = game.pile(touchPile)
                    for (i in touchIndex until pile.size) isDragged[pile[i]] = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    listener?.onFeedback(Feedback.PICK)
                }
                if (dragging) {
                    dragX = e.x; dragY = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return true
                trackingTouch = false
                if (dragging) drop() else tap()
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false
                cancelDrag()
                sync()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float) {
        touchPile = -1; touchIndex = -1
        val slopX = gap / 2
        fun inCol(px: Float) = x >= px - slopX && x <= px + cw + slopX
        // Tableau (checked first: largest area).
        for (col in 0 until 7) {
            if (!inCol(colX[col]) || y < tabY - gap) continue
            val p = Piles.tableau(col)
            val pile = game.pile(p)
            touchPile = p
            for (i in pile.size - 1 downTo 0) {
                val c = pile[i]
                val bottom = ty[c] + ch + (if (i == pile.size - 1) gap * 2 else 0f)
                if (y >= ty[c] && y <= bottom) { touchIndex = i; return }
            }
            if (pile.isEmpty() && y <= tabY + ch) return
            touchPile = -1
        }
        if (y > topY + ch + gap * 1.5f || y < topY - gap * 2) return
        // Waste: only the top card is live (its x shifts with the fan).
        val waste = game.pile(WASTE)
        if (waste.isNotEmpty()) {
            val c = waste.top()
            if (x >= tx[c] - slopX && x <= tx[c] + cw + slopX) { touchPile = WASTE; touchIndex = waste.size - 1; return }
        }
        if (inCol(pileX[STOCK])) { touchPile = STOCK; touchIndex = -1; return }
        for (f in 0 until 4) {
            val p = Piles.foundation(f)
            if (inCol(pileX[p])) {
                touchPile = p; touchIndex = game.pile(p).size - 1; return
            }
        }
    }

    private fun tap() {
        val p = touchPile
        if (p == STOCK) { drawFromStock(); return }
        if (p < 0 || touchIndex < 0) return
        if (!game.isMovable(p, touchIndex)) return
        val targets = game.targetsFor(p, touchIndex)
        if (targets.isEmpty()) {
            val pile = game.pile(p)
            val now = SystemClock.uptimeMillis()
            for (i in touchIndex until pile.size) shakeStart[pile[i]] = now
            listener?.onFeedback(Feedback.INVALID)
            invalidate()
            return
        }
        doMove(p, touchIndex, targets[0])
    }

    private fun drop() {
        val from = touchPile
        val index = touchIndex
        val lx = dragX - dragOffX
        val ly = dragY - dragOffY
        var best = -1
        var bestScore = 0f
        for (p in Piles.FOUNDATION_0 until Piles.COUNT) {
            if (p == from || !game.canMove(from, index, p)) continue
            val r = dropRect(p)
            val ox = min(lx + cw, r.right) - max(lx, r.left)
            val oy = min(ly + ch, r.bottom) - max(ly, r.top)
            var score = if (ox > 0 && oy > 0) ox * oy else 0f
            if (r.contains(dragX, dragY)) score += cw * ch // finger inside a pile is a strong signal
            if (score > bestScore) { bestScore = score; best = p }
        }
        val moved = hypot(dragX - downX, dragY - downY)
        cancelDrag()
        if (best >= 0) {
            doMove(from, index, best)
        } else {
            sync()
            if (moved > cw) listener?.onFeedback(Feedback.INVALID)
        }
    }

    private fun dropRect(p: Int): RectF {
        val r = RectF()
        if (Piles.isTableau(p)) {
            val pile = game.pile(p)
            val bottom = if (pile.isEmpty()) tabY + ch else ty[pile.top()] + ch
            r.set(pileX[p] - gap / 2, tabY - gap, pileX[p] + cw + gap / 2, max(bottom, tabY + ch) + ch * 0.4f)
        } else {
            r.set(pileX[p] - gap / 2, pileY[p] - gap, pileX[p] + cw + gap / 2, pileY[p] + ch + gap)
        }
        return r
    }

    private fun cancelDrag() {
        dragging = false
        for (c in 0 until Cards.COUNT) if (isDragged[c]) {
            isDragged[c] = false
            toX[c] = Float.NaN // force sync() to animate it from where the finger left it
        }
    }

    // ================================================================== game actions

    private fun doMove(from: Int, index: Int, to: Int) {
        val hiddenBefore = if (Piles.isTableau(from)) game.hidden(from) else 0
        if (!game.move(from, index, to)) return
        val revealed = Piles.isTableau(from) && game.hidden(from) < hiddenBefore
        afterAction(
            when {
                revealed -> Feedback.REVEAL
                Piles.isFoundation(to) -> Feedback.FOUNDATION
                else -> Feedback.PLACE
            },
            byUser = true,
        )
    }

    private fun afterAction(fb: Feedback, byUser: Boolean) {
        hint = null
        hintList = emptyList()
        sync()
        listener?.onFeedback(fb)
        listener?.onGameChanged(byUser)
        if (game.isWon) {
            scheduleWin(); return
        }
        if (byUser && autoFoundation) {
            undoMarkForAuto = game.undoDepth
            removeCallbacks(autoFoundationStep)
            postDelayed(autoFoundationStep, 190)
        }
    }

    private val autoFoundationStep = object : Runnable {
        override fun run() {
            if (dragging || winMode || autoCompleting) return
            val h = game.safeFoundationMove() ?: return
            val hiddenBefore = if (Piles.isTableau(h.from)) game.hidden(h.from) else 0
            if (!game.move(h.from, h.index, h.to)) return
            // Auto-plays belong to the player's move: one undo reverts both.
            if (undoMarkForAuto > 0) game.squashUndo(undoMarkForAuto)
            hint = null; hintList = emptyList()
            sync()
            listener?.onFeedback(if (Piles.isTableau(h.from) && game.hidden(h.from) < hiddenBefore) Feedback.REVEAL else Feedback.FOUNDATION)
            listener?.onGameChanged(false)
            if (game.isWon) scheduleWin() else postDelayed(this, 150)
        }
    }

    private val autoCompleteStep = object : Runnable {
        override fun run() {
            if (!autoCompleting) return
            val step = game.autoCompleteStep()
            if (step == null) { autoCompleting = false; return }
            sync(fast = true)
            listener?.onFeedback(if (step.to < 0) Feedback.DRAW else Feedback.FOUNDATION)
            listener?.onGameChanged(false)
            if (game.isWon) {
                autoCompleting = false
                scheduleWin()
            } else postDelayed(this, 70)
        }
    }

    private fun cancelAuto() {
        autoCompleting = false
        removeCallbacks(autoCompleteStep)
        removeCallbacks(autoFoundationStep)
        removeCallbacks(startWin)
    }

    // ================================================================== win cascade

    private fun scheduleWin() {
        removeCallbacks(autoFoundationStep)
        removeCallbacks(startWin)
        postDelayed(startWin, 380)
    }

    private val startWin = Runnable {
        if (!game.isWon || width == 0) return@Runnable
        winMode = true
        val bmp = trail ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { trail = it }
        bmp.eraseColor(Color.TRANSPARENT)
        trailCanvas = Canvas(bmp)
        java.util.Arrays.fill(launched, false)
        java.util.Arrays.fill(pAlive, false)
        launchCount = 0
        lastLaunch = 0
        lastFrame = SystemClock.uptimeMillis()
        listener?.onWon()
        invalidate()
    }

    private fun drawWin(canvas: Canvas, now: Long): Boolean {
        val m = renderer.margin.toFloat()
        val dt = ((now - lastFrame) / 16.67f).coerceIn(0.2f, 3f)
        lastFrame = now
        // Launch the next foundation top card on a cadence, round-robin K -> A.
        if (launchCount < Cards.COUNT && now - lastLaunch > 120) {
            lastLaunch = now
            val f = launchCount % 4
            val pile = game.pile(Piles.foundation(f))
            val depth = launchCount / 4
            val idx = pile.size - 1 - depth
            if (idx >= 0) {
                val c = pile[idx]
                launched[c] = true; pAlive[c] = true
                pX[c] = pileX[Piles.foundation(f)]; pY[c] = pileY[Piles.foundation(f)]
                // Cheap deterministic "randomness" per card for direction and speed.
                val r1 = ((c * 7919 + 13) % 101) / 100f
                val r2 = ((c * 104729 + 7) % 97) / 96f
                val dir = if (r1 < 0.5f) -1f else 1f
                pVx[c] = dir * cw * (0.05f + 0.09f * r2)
                pVy[c] = -ch * (0.01f + 0.07f * r1)
            }
            launchCount++
        }
        val tc = trailCanvas!!
        val g = ch * 0.012f
        var alive = false
        for (c in 0 until Cards.COUNT) {
            if (!pAlive[c]) continue
            pVy[c] += g * dt
            pX[c] += pVx[c] * dt
            pY[c] += pVy[c] * dt
            val floor = height - insetBottom
            if (pY[c] + ch > floor) {
                pY[c] = floor - ch; pVy[c] = -pVy[c] * 0.75f
            }
            if (pX[c] < -cw * 1.2f || pX[c] > width + cw * 0.2f) { pAlive[c] = false; continue }
            alive = true
            tc.drawBitmap(renderer.face(c), pX[c] - m, pY[c] - m, null)
        }
        // Foundations with the cards not launched yet, then the trails over them.
        drawSlots(canvas)
        for (f in 0 until 4) {
            val pile = game.pile(Piles.foundation(f))
            for (i in pile.size - 1 downTo 0) {
                val c = pile[i]
                if (!launched[c]) {
                    canvas.drawBitmap(renderer.face(c), pileX[Piles.foundation(f)] - m, pileY[Piles.foundation(f)] - m, null); break
                }
            }
        }
        canvas.drawBitmap(trail!!, 0f, 0f, null)
        return alive || launchCount < Cards.COUNT
    }

    private fun endWin() {
        removeCallbacks(startWin)
        winMode = false
        trailCanvas = null
        trail?.recycle()
        trail = null
    }

    companion object {
        private const val FLIP_MS = 170L
        private const val SHAKE_MS = 320L
        private const val HINT_MS = 1600L
    }
}
