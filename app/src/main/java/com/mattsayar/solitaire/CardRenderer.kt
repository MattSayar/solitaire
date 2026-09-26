package com.mattsayar.solitaire

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.mattsayar.solitaire.game.Cards

/**
 * Pre-renders every card face, the card back and the empty-slot markers into bitmaps once per size,
 * so drawing a frame is nothing but ~52 bitmap blits.
 */
class CardRenderer {

    var cardW = 0; private set
    var cardH = 0; private set
    /** Extra transparent margin around each bitmap that holds the drop shadow. */
    var margin = 0; private set
    var corner = 0f; private set

    private val faces = arrayOfNulls<Bitmap>(Cards.COUNT)
    var back: Bitmap? = null; private set
    var slot: Bitmap? = null; private set
    var foundationSlot: Bitmap? = null; private set
    var stockRecycle: Bitmap? = null; private set

    private var fourColor = false

    fun face(card: Int): Bitmap = faces[card]!!

    fun ensure(w: Int, h: Int, fourColor: Boolean) {
        if (w == cardW && h == cardH && fourColor == this.fourColor && back != null) return
        release()
        cardW = w; cardH = h; this.fourColor = fourColor
        margin = maxOf(2, (w * 0.06f).toInt())
        corner = w * 0.09f
        for (c in 0 until Cards.COUNT) faces[c] = renderFace(c)
        back = renderBack()
        slot = renderSlot(null)
        foundationSlot = renderSlot("A")
        stockRecycle = renderSlot("↻")
    }

    fun release() {
        for (i in faces.indices) { faces[i]?.recycle(); faces[i] = null }
        back?.recycle(); slot?.recycle(); foundationSlot?.recycle(); stockRecycle?.recycle()
        back = null; slot = null; foundationSlot = null; stockRecycle = null
    }

    private fun newBitmap(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(cardW + margin * 2, cardH + margin * 2, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    private fun cardRect() = RectF(margin.toFloat(), margin.toFloat(), (margin + cardW).toFloat(), (margin + cardH).toFloat())

    private fun drawShadowedBody(canvas: Canvas, rect: RectF, paint: Paint) {
        // The shadow-casting shape is fully covered by the body, leaving only the soft shadow visible.
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(margin * 0.7f, 0f, margin * 0.35f, 0x66000000)
        }
        canvas.drawRoundRect(rect, corner, corner, shadow)
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    fun suitColor(suit: Int): Int = if (fourColor) when (suit) {
        Cards.CLUBS -> 0xFF1B7F3A.toInt()
        Cards.DIAMONDS -> 0xFF1565C0.toInt()
        Cards.HEARTS -> 0xFFD32F2F.toInt()
        else -> 0xFF1C1C1E.toInt()
    } else if (Cards.isRedSuit(suit)) 0xFFD32F2F.toInt() else 0xFF1C1C1E.toInt()

    private fun renderFace(card: Int): Bitmap {
        val (bmp, canvas) = newBitmap()
        val rect = cardRect()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFFFFFFFF.toInt(), 0xFFF1EEE6.toInt(), Shader.TileMode.CLAMP)
        }
        drawShadowedBody(canvas, rect, body)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = maxOf(1f, cardW * 0.012f); color = 0x33000000
        }
        canvas.drawRoundRect(rect, corner, corner, border)

        val suit = Cards.suit(card)
        val rank = Cards.rank(card)
        val color = suitColor(suit)

        // Big, bold index in the top-left: this is what you read when cards are fanned.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = cardW * 0.40f
            if (rank == 10) textScaleX = 0.82f
        }
        val label = Cards.rankLabel(rank)
        val left = rect.left + cardW * 0.07f
        val baseline = rect.top + cardW * 0.38f
        canvas.drawText(label, left, baseline, text)

        // Small suit pip next to the index (top-right) so suit is visible even when heavily overlapped.
        val pipSize = cardW * 0.30f
        drawSuit(canvas, suit, rect.right - cardW * 0.07f - pipSize, rect.top + cardW * 0.09f, pipSize, color)

        // Large centre suit (face cards get a framed letter over the suit for a touch of flair).
        val big = cardW * 0.58f
        val cx = rect.centerX() - big / 2
        val cy = rect.top + cardH * 0.58f - big / 2
        if (rank >= 11) {
            // Illustrated portrait in a gold-bordered panel below the index.
            val inset = cardW * 0.09f
            val frame = RectF(rect.left + inset, rect.top + cardW * 0.44f, rect.right - inset, rect.bottom - inset)
            val r = corner * 0.6f
            FaceArt.draw(canvas, rank, suit, color, frame, r)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, cardW * 0.022f); this.color = 0xFFC9A13B.toInt()
            }
            canvas.drawRoundRect(frame, r, r, border)
        } else {
            drawSuit(canvas, suit, cx, cy, big, color)
        }
        return bmp
    }

    private fun renderBack(): Bitmap {
        val (bmp, canvas) = newBitmap()
        val rect = cardRect()
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xFF2B59C3.toInt(), 0xFF16307A.toInt(), Shader.TileMode.CLAMP)
        }
        drawShadowedBody(canvas, rect, body)

        val inset = cardW * 0.07f
        val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.save()
        val clip = Path().apply { addRoundRect(inner, corner * 0.6f, corner * 0.6f, Path.Direction.CW) }
        canvas.clipPath(clip)
        // Diagonal lattice pattern.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF; strokeWidth = cardW * 0.025f }
        val step = cardW * 0.14f
        var d = -cardH.toFloat()
        while (d < cardW + cardH) {
            canvas.drawLine(inner.left + d, inner.top, inner.left + d + cardH, inner.top + cardH, line)
            canvas.drawLine(inner.left + d, inner.top, inner.left + d - cardH, inner.top + cardH, line)
            d += step
        }
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = cardW * 0.025f; color = 0xCCFFFFFF.toInt()
        }
        canvas.drawRoundRect(inner, corner * 0.6f, corner * 0.6f, border)
        // Gold emblem.
        val emblem = cardW * 0.34f
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(rect.centerX(), rect.centerY(), emblem * 0.8f, 0xFFFFE08A.toInt(), 0xFFE0A526.toInt(), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), emblem * 0.62f, gold)
        drawSuit(canvas, Cards.SPADES, rect.centerX() - emblem / 2 * 0.8f, rect.centerY() - emblem / 2 * 0.8f, emblem * 0.8f, 0xFF16307A.toInt())
        return bmp
    }

    private fun renderSlot(label: String?): Bitmap {
        val (bmp, canvas) = newBitmap()
        val rect = cardRect()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
        canvas.drawRoundRect(rect, corner, corner, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, cardW * 0.025f); color = 0x55FFFFFF
        }
        val half = stroke.strokeWidth / 2
        canvas.drawRoundRect(RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half), corner, corner, stroke)
        if (label != null) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF; textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = cardW * 0.5f
            }
            canvas.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        }
        return bmp
    }

    companion object {
        private val paths = arrayOfNulls<Path>(4)

        /** Suit shapes drawn as vector paths in a unit square: consistent on every device (no emoji fonts). */
        private fun unitPath(suit: Int): Path = paths[suit] ?: Path().apply {
            when (suit) {
                Cards.HEARTS -> {
                    moveTo(0.5f, 0.95f)
                    cubicTo(0.2f, 0.72f, 0.0f, 0.5f, 0.02f, 0.3f)
                    cubicTo(0.04f, 0.1f, 0.2f, 0.02f, 0.32f, 0.03f)
                    cubicTo(0.42f, 0.04f, 0.48f, 0.12f, 0.5f, 0.2f)
                    cubicTo(0.52f, 0.12f, 0.58f, 0.04f, 0.68f, 0.03f)
                    cubicTo(0.8f, 0.02f, 0.96f, 0.1f, 0.98f, 0.3f)
                    cubicTo(1.0f, 0.5f, 0.8f, 0.72f, 0.5f, 0.95f)
                    close()
                }
                Cards.DIAMONDS -> {
                    moveTo(0.5f, 0.0f)
                    quadTo(0.7f, 0.28f, 0.9f, 0.5f)
                    quadTo(0.7f, 0.72f, 0.5f, 1.0f)
                    quadTo(0.3f, 0.72f, 0.1f, 0.5f)
                    quadTo(0.3f, 0.28f, 0.5f, 0.0f)
                    close()
                }
                Cards.SPADES -> {
                    moveTo(0.5f, 0.02f)
                    cubicTo(0.8f, 0.28f, 1.0f, 0.45f, 0.98f, 0.62f)
                    cubicTo(0.96f, 0.8f, 0.8f, 0.86f, 0.68f, 0.84f)
                    cubicTo(0.6f, 0.83f, 0.54f, 0.78f, 0.52f, 0.72f)
                    quadTo(0.55f, 0.9f, 0.68f, 0.98f)
                    lineTo(0.32f, 0.98f)
                    quadTo(0.45f, 0.9f, 0.48f, 0.72f)
                    cubicTo(0.46f, 0.78f, 0.4f, 0.83f, 0.32f, 0.84f)
                    cubicTo(0.2f, 0.86f, 0.04f, 0.8f, 0.02f, 0.62f)
                    cubicTo(0.0f, 0.45f, 0.2f, 0.28f, 0.5f, 0.02f)
                    close()
                }
                else -> { // clubs: three lobes + stem, unioned so overlaps never cancel out
                    addCircle(0.5f, 0.27f, 0.215f, Path.Direction.CW)
                    addCircle(0.26f, 0.6f, 0.215f, Path.Direction.CW)
                    addCircle(0.74f, 0.6f, 0.215f, Path.Direction.CW)
                    addCircle(0.5f, 0.52f, 0.12f, Path.Direction.CW)
                    val stem = Path().apply {
                        moveTo(0.46f, 0.55f)
                        quadTo(0.45f, 0.88f, 0.3f, 0.98f)
                        lineTo(0.7f, 0.98f)
                        quadTo(0.55f, 0.88f, 0.54f, 0.55f)
                        close()
                    }
                    op(stem, Path.Op.UNION)
                }
            }
        }.also { paths[suit] = it }

        private val suitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tmpPath = Path()
        private val matrix = android.graphics.Matrix()

        fun drawSuit(canvas: Canvas, suit: Int, x: Float, y: Float, size: Float, color: Int) {
            matrix.reset()
            matrix.setScale(size, size)
            matrix.postTranslate(x, y)
            unitPath(suit).transform(matrix, tmpPath)
            suitPaint.color = color
            canvas.drawPath(tmpPath, suitPaint)
        }
    }
}
