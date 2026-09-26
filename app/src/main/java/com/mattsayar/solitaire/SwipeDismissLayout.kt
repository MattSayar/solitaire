package com.mattsayar.solitaire

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * Bottom-sheet container that can be dragged down and flung away. A downward drag is only taken
 * over when the content under the finger can't scroll up any further, so scrollable sheets
 * (Settings) still scroll normally and only dismiss once they're back at the top.
 */
class SwipeDismissLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    /** Called when a swipe should close the sheet; the owner animates it the rest of the way. */
    var onDismiss: (() -> Unit)? = null

    /** 0 = fully open, 1 = fully off-screen. Lets the owner fade the scrim along with the drag. */
    var onDrag: ((Float) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private val hit = Rect()

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.rawY
                dragging = false
                track(e, reset = true)
            }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dy = e.y - downY
                val dx = e.x - downX
                if (!dragging && dy > touchSlop && dy > abs(dx) && !canScrollUpAt(this, e.x, e.y)) {
                    dragging = true
                    lastY = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycleTracker()
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        track(e, reset = e.actionMasked == MotionEvent.ACTION_DOWN)
        when (e.actionMasked) {
            // Touches that no child consumed (titles, padding) land here: keep the stream so a drag can start.
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.rawY
                animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && e.y - downY > touchSlop) {
                    dragging = true; lastY = e.rawY
                }
                if (dragging) {
                    translationY = max(0f, translationY + (e.rawY - lastY))
                    lastY = e.rawY
                    report()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(e.actionMasked == MotionEvent.ACTION_UP)
                dragging = false
                recycleTracker()
            }
        }
        return true
    }

    private fun settle(released: Boolean) {
        val v = velocity?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
        val farEnough = translationY > height * 0.3f
        val flungDown = v > max(minFling * 4f, 800f * resources.displayMetrics.density)
        val flungUp = v < -minFling * 4f
        if (released && !flungUp && (flungDown || farEnough)) {
            onDismiss?.invoke()
        } else {
            animate().translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
                .setUpdateListener { report() }
                .withEndAction { report(); animate().setUpdateListener(null) }.start()
        }
    }

    private fun report() {
        if (height > 0) onDrag?.invoke((translationY / height).coerceIn(0f, 1f))
    }

    private fun track(e: MotionEvent, reset: Boolean = false) {
        if (reset) { velocity?.recycle(); velocity = null }
        val t = velocity ?: VelocityTracker.obtain().also { velocity = it }
        // Track in screen space: our own translation moves the local coordinate system while dragging.
        val ev = MotionEvent.obtain(e)
        ev.setLocation(e.rawX, e.rawY)
        t.addMovement(ev)
        ev.recycle()
    }

    private fun recycleTracker() {
        velocity?.recycle(); velocity = null
    }

    /** Does any view under (x, y) still have content scrolled above its viewport? */
    private fun canScrollUpAt(group: ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            child.getHitRect(hit)
            if (!hit.contains(x.toInt(), y.toInt())) continue
            if (child.canScrollVertically(-1)) return true
            if (child is ViewGroup && canScrollUpAt(child, x - child.left + child.scrollX, y - child.top + child.scrollY)) return true
        }
        return false
    }
}
