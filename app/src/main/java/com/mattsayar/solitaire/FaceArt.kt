package com.mattsayar.solitaire

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * Court-card portraits (jack, queen, king) drawn from vector paths, so they stay crisp at any card
 * size and ship no image assets. Art is authored in a 100 x 122 unit box and bottom-aligned in the
 * frame; the robe takes the suit colour and each suit gets its own hair colour, so all twelve
 * portraits look different.
 */
object FaceArt {

    private const val W = 100f
    private const val H = 122f
    /** Empty sky above the tallest element (jack's feather tip); allowed to fall outside the panel. */
    private const val TOP_SLACK = 7f

    private const val OUTLINE = 0xC02B1D14.toInt()
    private const val SKIN = 0xFFF3CBA5.toInt()
    private const val SKIN_SHADE = 0xFFC98F66.toInt()
    private const val GOLD = 0xFFE3B341.toInt()
    private const val GOLD_DARK = 0xFFB07F1A.toInt()
    private const val CREAM = 0xFFFFF8E6.toInt()
    private const val INK = 0xFF2B1D14.toInt()
    private const val CHEEK = 0x55E57373
    private const val LIPS = 0xFFC62840.toInt()
    private const val RUBY = 0xFFD32F2F.toInt()
    private const val SAPPHIRE = 0xFF1E5BB8.toInt()
    private const val ROSE = 0xFFE0405A.toInt()
    private const val LEAF = 0xFF3E8E41.toInt()
    private const val STEEL = 0xFFB9C6CE.toInt()
    private const val WOOD = 0xFF8D5A2B.toInt()

    // Hair per suit: clubs, diamonds, hearts, spades.
    private val kingHair = intArrayOf(0xFFB9B9B9.toInt(), 0xFF8A5A2B.toInt(), 0xFFF2F0EA.toInt(), 0xFF2E2622.toInt())
    private val queenHair = intArrayOf(0xFF3B2A20.toInt(), 0xFFE0B354.toInt(), 0xFF9C3B1E.toInt(), 0xFF1F1A18.toInt())
    private val jackHair = intArrayOf(0xFFB5652B.toInt(), 0xFF4A3222.toInt(), 0xFFE7C067.toInt(), 0xFF2A211C.toInt())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val p = Path()
    private val oval = RectF()

    /** Draws the portrait for [rank] (11..13) into [frame], which the caller has already filled/bordered. */
    fun draw(canvas: Canvas, rank: Int, suit: Int, suitColor: Int, frame: RectF, radius: Float) {
        canvas.save()
        val clip = Path().apply { addRoundRect(frame, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        // Fit the whole portrait by height (nothing cropped), centred; the backdrop fills the whole panel.
        fill.shader = LinearGradient(0f, frame.top, 0f, frame.bottom, 0xFFFDF4DC.toInt(), 0xFFF1DDA6.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(frame, fill)
        fill.shader = null
        val s = minOf(frame.width() / W, frame.height() / (H - TOP_SLACK))
        canvas.translate(frame.centerX() - W * s / 2, frame.bottom - H * s)
        canvas.scale(s, s)
        halo(canvas, suitColor)
        // Near-black robes swallow the detail, so black suits get a deep slate instead.
        val robe = if (Color.luminance(suitColor) < 0.2f) 0xFF34414E.toInt() else suitColor
        when (rank) {
            13 -> king(canvas, suit, robe)
            12 -> queen(canvas, suit, robe)
            else -> jack(canvas, suit, robe)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ figures

    private fun king(c: Canvas, suit: Int, robe: Int) {
        val hair = kingHair[suit]
        // Scepter behind the shoulder.
        line(c, 85f, 122f, 83f, 34f, GOLD_DARK, 4.2f)
        line(c, 85f, 122f, 83f, 34f, GOLD, 2.4f)
        circle(c, 83f, 30f, 5f, GOLD)
        line(c, 83f, 21f, 83f, 26f, GOLD_DARK, 1.6f)
        line(c, 80.5f, 23f, 85.5f, 23f, GOLD_DARK, 1.6f)

        robe(c, robe)
        // Ermine cape.
        p.reset()
        p.moveTo(13f, 97f); p.cubicTo(20f, 86f, 34f, 81f, 50f, 81f); p.cubicTo(66f, 81f, 80f, 86f, 87f, 97f)
        p.lineTo(88f, 105f); p.cubicTo(74f, 96f, 26f, 96f, 12f, 105f); p.close()
        shape(c, p, CREAM)
        for (x in floatArrayOf(22f, 34f, 50f, 66f, 78f)) ermineSpot(c, x, if (x == 50f) 90f else 93f)
        neckAndHead(c, backHair = { oval(c, 50f, 49f, 18f, 20f, hair) })
        // Beard and moustache.
        p.reset()
        p.moveTo(34.5f, 54f); p.cubicTo(34f, 72f, 42f, 81f, 50f, 83f); p.cubicTo(58f, 81f, 66f, 72f, 65.5f, 54f)
        p.cubicTo(62f, 62f, 57f, 63.5f, 50f, 63.5f); p.cubicTo(43f, 63.5f, 38f, 62f, 34.5f, 54f); p.close()
        shape(c, p, hair)
        p.reset()
        p.moveTo(50f, 60f); p.cubicTo(46f, 57.5f, 41f, 58.5f, 39.5f, 63f); p.cubicTo(43.5f, 61.5f, 47f, 62f, 50f, 62.5f)
        p.cubicTo(53f, 62f, 56.5f, 61.5f, 60.5f, 63f); p.cubicTo(59f, 58.5f, 54f, 57.5f, 50f, 60f); p.close()
        shape(c, p, hair)
        eyes(c, lashes = false)
        brows(c, hair, 1.8f)
        // Crown.
        p.reset()
        p.moveTo(32.5f, 38f); p.lineTo(30f, 21f); p.lineTo(38.5f, 29f); p.lineTo(44f, 16f); p.lineTo(50f, 27f)
        p.lineTo(56f, 16f); p.lineTo(61.5f, 29f); p.lineTo(70f, 21f); p.lineTo(67.5f, 38f); p.close()
        shape(c, p, GOLD)
        rect(c, 32f, 34f, 68f, 40f, GOLD_DARK)
        circle(c, 40f, 37f, 1.9f, RUBY); circle(c, 50f, 37f, 2.1f, SAPPHIRE); circle(c, 60f, 37f, 1.9f, RUBY)
        for ((x, y) in listOf(30f to 21f, 44f to 16f, 56f to 16f, 70f to 21f)) circle(c, x, y, 2f, GOLD)
        emblem(c, suit, robe, 50f, 111f)
    }

    private fun queen(c: Canvas, suit: Int, robe: Int) {
        val hair = queenHair[suit]
        // Long hair falls behind the shoulders.
        p.reset()
        p.moveTo(31f, 50f); p.cubicTo(29f, 27f, 71f, 27f, 69f, 50f); p.cubicTo(70f, 66f, 74f, 80f, 76f, 94f)
        p.cubicTo(68f, 98f, 60f, 94f, 58f, 86f); p.lineTo(42f, 86f); p.cubicTo(40f, 94f, 32f, 98f, 24f, 94f)
        p.cubicTo(26f, 80f, 30f, 66f, 31f, 50f); p.close()
        shape(c, p, hair)
        robe(c, robe)
        // Square neckline with gold trim.
        p.reset()
        p.moveTo(37f, 83f); p.lineTo(40f, 94f); p.lineTo(60f, 94f); p.lineTo(63f, 83f); p.close()
        shape(c, p, SKIN)
        line(c, 38.5f, 95f, 61.5f, 95f, GOLD, 2.4f)
        neckAndHead(c, backHair = null)
        // Pearl necklace.
        for (i in 0..6) {
            val t = i / 6f
            circle(c, 42f + 16f * t, 86f + 3.2f * (1 - (2 * t - 1) * (2 * t - 1)), 1.25f, CREAM)
        }
        // Fringe framing the face, with locks down the cheeks.
        p.reset()
        p.moveTo(33f, 60f); p.cubicTo(30f, 38f, 42f, 32f, 50f, 33f); p.cubicTo(58f, 32f, 70f, 38f, 67f, 60f)
        p.cubicTo(65f, 50f, 63f, 44f, 58f, 41f); p.cubicTo(54f, 44f, 44f, 45f, 40f, 42f)
        p.cubicTo(36f, 46f, 35f, 52f, 33f, 60f); p.close()
        shape(c, p, hair)
        eyes(c, lashes = true)
        archedBrows(c, hair)
        p.reset()
        p.moveTo(46f, 64f); p.quadTo(48f, 62.6f, 50f, 63.4f); p.quadTo(52f, 62.6f, 54f, 64f)
        p.quadTo(50f, 67f, 46f, 64f); p.close()
        fill.color = LIPS; c.drawPath(p, fill)
        // Tiara.
        p.reset()
        p.moveTo(37f, 37f); p.lineTo(39f, 27f); p.lineTo(44.5f, 32f); p.lineTo(50f, 22f); p.lineTo(55.5f, 32f)
        p.lineTo(61f, 27f); p.lineTo(63f, 37f); p.cubicTo(55f, 34.5f, 45f, 34.5f, 37f, 37f); p.close()
        shape(c, p, GOLD)
        circle(c, 50f, 31f, 2f, RUBY)
        for ((x, y) in listOf(39f to 27f, 50f to 22f, 61f to 27f)) circle(c, x, y, 1.7f, CREAM)
        // Rose held up on the left.
        line(c, 19f, 122f, 21f, 70f, LEAF, 2f)
        p.reset()
        p.moveTo(20.5f, 88f); p.cubicTo(13f, 86f, 11f, 80f, 12f, 78f); p.cubicTo(16f, 79f, 20f, 82f, 20.5f, 88f); p.close()
        shape(c, p, LEAF)
        circle(c, 21f, 66f, 6f, ROSE)
        stroke.color = 0x99801020.toInt(); stroke.strokeWidth = 1f
        oval.set(17.5f, 62.5f, 24.5f, 69.5f); c.drawArc(oval, 200f, 250f, false, stroke)
        emblem(c, suit, robe, 50f, 111f)
    }

    private fun jack(c: Canvas, suit: Int, robe: Int) {
        val hair = jackHair[suit]
        // Halberd behind the shoulder.
        line(c, 85f, 122f, 85f, 27f, WOOD, 3f)
        p.reset()
        p.moveTo(85f, 12f); p.lineTo(88.5f, 25f); p.lineTo(85f, 29f); p.lineTo(81.5f, 25f); p.close()
        shape(c, p, STEEL)
        p.reset()
        p.moveTo(85f, 30f); p.cubicTo(92f, 30f, 96f, 35f, 96f, 42f); p.cubicTo(92f, 39f, 89f, 38f, 85f, 38f); p.close()
        shape(c, p, STEEL)

        robe(c, robe)
        // Doublet: V collar and buttons.
        p.reset()
        p.moveTo(38f, 82f); p.lineTo(50f, 98f); p.lineTo(62f, 82f); p.close()
        shape(c, p, CREAM)
        line(c, 38f, 82f, 50f, 98f, GOLD, 2.6f); line(c, 62f, 82f, 50f, 98f, GOLD, 2.6f)
        neckAndHead(c, backHair = { oval(c, 50f, 49f, 17.5f, 18f, hair) })
        // Bob haircut fringe.
        p.reset()
        p.moveTo(33f, 58f); p.cubicTo(31f, 40f, 40f, 34f, 50f, 34f); p.cubicTo(60f, 34f, 69f, 40f, 67f, 58f)
        p.cubicTo(65f, 50f, 62f, 45f, 58f, 44f); p.lineTo(55f, 47f); p.lineTo(52f, 43.5f); p.lineTo(47f, 47f)
        p.lineTo(44f, 43.5f); p.cubicTo(38f, 46f, 35f, 50f, 33f, 58f); p.close()
        shape(c, p, hair)
        eyes(c, lashes = false)
        brows(c, hair, 1.4f)
        // Smile.
        stroke.color = INK; stroke.strokeWidth = 1.3f
        oval.set(45.5f, 59.5f, 54.5f, 65.5f); c.drawArc(oval, 25f, 130f, false, stroke)
        // Feathered cap in the suit colour.
        p.reset()
        p.moveTo(60f, 33f); p.cubicTo(70f, 27f, 76f, 19f, 81f, 8f); p.cubicTo(81f, 21f, 75f, 30f, 63f, 37f); p.close()
        shape(c, p, CREAM)
        line(c, 62f, 35f, 79f, 12f, 0x66B07F1A, 0.9f)
        p.reset()
        p.moveTo(29f, 37f); p.cubicTo(28f, 25f, 44f, 21f, 55f, 23f); p.cubicTo(66f, 25f, 72f, 31f, 70f, 37f); p.close()
        shape(c, p, robe)
        rect(c, 31f, 35f, 69f, 40f, GOLD)
        circle(c, 50f, 37.5f, 1.8f, RUBY)
        emblem(c, suit, robe, 50f, 111f)
    }

    // ------------------------------------------------------------------ shared parts

    private fun halo(c: Canvas, suitColor: Int) {
        // Soft halo behind the head.
        fill.color = (suitColor and 0x00FFFFFF) or 0x14000000
        c.drawCircle(50f, 48f, 34f, fill)
        fill.color = 0x30FFFFFF
        c.drawCircle(50f, 48f, 27f, fill)
    }

    private fun robe(c: Canvas, color: Int) {
        p.reset()
        p.moveTo(6f, 124f); p.lineTo(11f, 100f); p.cubicTo(13f, 90f, 24f, 83.5f, 38f, 82f); p.lineTo(62f, 82f)
        p.cubicTo(76f, 83.5f, 87f, 90f, 89f, 100f); p.lineTo(94f, 124f); p.close()
        shape(c, p, color)
        // Gold hem stripes give the robe some structure.
        line(c, 30f, 97f, 27f, 124f, 0x80E3B341.toInt(), 1.6f)
        line(c, 70f, 97f, 73f, 124f, 0x80E3B341.toInt(), 1.6f)
    }

    private fun neckAndHead(c: Canvas, backHair: (() -> Unit)?) {
        backHair?.invoke()
        rect(c, 43.5f, 62f, 56.5f, 84f, SKIN)
        oval(c, 34.5f, 54f, 3f, 4.5f, SKIN)
        oval(c, 65.5f, 54f, 3f, 4.5f, SKIN)
        oval(c, 50f, 52f, 15f, 18f, SKIN)
        fill.color = CHEEK
        c.drawCircle(41.5f, 58.5f, 3.2f, fill); c.drawCircle(58.5f, 58.5f, 3.2f, fill)
        // Nose.
        stroke.color = SKIN_SHADE; stroke.strokeWidth = 1.2f
        p.reset(); p.moveTo(50.5f, 51f); p.lineTo(48.5f, 58.5f); p.lineTo(51f, 59f)
        c.drawPath(p, stroke)
    }

    private fun eyes(c: Canvas, lashes: Boolean) {
        for (x in floatArrayOf(44f, 56f)) {
            fill.color = CREAM; oval.set(x - 2.8f, 50.6f - 1.8f, x + 2.8f, 50.6f + 1.8f); c.drawOval(oval, fill)
            fill.color = INK; c.drawCircle(x, 50.7f, 1.4f, fill)
            if (lashes) {
                stroke.color = INK; stroke.strokeWidth = 1f
                oval.set(x - 3f, 48.6f, x + 3f, 52.6f); c.drawArc(oval, 190f, 160f, false, stroke)
            }
        }
    }

    private fun brows(c: Canvas, color: Int, width: Float) {
        val dark = if (Color.luminance(color) > 0.6f) 0xFF7A7064.toInt() else color
        line(c, 41f, 46.5f, 46.5f, 45.8f, dark, width)
        line(c, 53.5f, 45.8f, 59f, 46.5f, dark, width)
    }

    private fun archedBrows(c: Canvas, color: Int) {
        val dark = if (Color.luminance(color) > 0.6f) 0xFF7A7064.toInt() else color
        stroke.color = dark; stroke.strokeWidth = 1.1f
        for (x in floatArrayOf(43.8f, 56.2f)) {
            oval.set(x - 3.4f, 45f, x + 3.4f, 50f); c.drawArc(oval, 205f, 130f, false, stroke)
        }
    }

    /** Suit pip on a cream roundel on the chest. */
    private fun emblem(c: Canvas, suit: Int, color: Int, x: Float, y: Float) {
        circle(c, x, y, 7.5f, CREAM)
        stroke.color = GOLD; stroke.strokeWidth = 1.6f
        c.drawCircle(x, y, 7.5f, stroke)
        CardRenderer.drawSuit(c, suit, x - 4.8f, y - 4.8f, 9.6f, color)
    }

    private fun ermineSpot(c: Canvas, x: Float, y: Float) {
        p.reset(); p.moveTo(x, y - 2.2f); p.lineTo(x + 1.3f, y + 1.8f); p.lineTo(x - 1.3f, y + 1.8f); p.close()
        fill.color = INK; c.drawPath(p, fill)
    }

    // ------------------------------------------------------------------ primitives

    private fun shape(c: Canvas, path: Path, color: Int) {
        fill.color = color
        c.drawPath(path, fill)
        stroke.color = OUTLINE; stroke.strokeWidth = 1.1f
        c.drawPath(path, stroke)
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        p.reset(); oval.set(cx - rx, cy - ry, cx + rx, cy + ry); p.addOval(oval, Path.Direction.CW)
        shape(c, p, color)
    }

    private fun circle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) = oval(c, cx, cy, r, r, color)

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.reset(); p.addRect(l, t, r, b, Path.Direction.CW)
        shape(c, p, color)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        stroke.color = color; stroke.strokeWidth = width
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    /** Minimal colour maths without pulling in android.graphics.Color's API-26 helpers. */
    private object Color {
        fun luminance(c: Int): Float {
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            return 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
    }
}
