package com.mattsayar.solitaire

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.mattsayar.solitaire.GameView.Feedback
import com.mattsayar.solitaire.game.Klondike
import kotlin.random.Random

class MainActivity : Activity(), GameView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var sounds: Sounds
    private lateinit var gameView: GameView
    private lateinit var root: FrameLayout
    private lateinit var status: LinearLayout
    private lateinit var modeView: TextView
    private lateinit var statsView: TextView
    private lateinit var soundButton: ImageButton
    private lateinit var toolbar: LinearLayout
    private lateinit var autoFinish: TextView
    private lateinit var toast: TextView
    private lateinit var scrim: View
    private lateinit var sheet: FrameLayout
    private lateinit var winLayer: FrameLayout
    private lateinit var undoButton: View
    private lateinit var hintButton: View

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var game: Klondike
    private var pendingDeal = false
    private var initialGameShown = false

    // Timer: elapsed time is accumulated while the app is in the foreground and a game is in progress.
    private var elapsedMs = 0L
    private var runningSince = 0L
    private var started = false
    private var resumed = false

    private var pendingWin: Runnable? = null
    private var sheetOpen = false
    private var winShown = false

    private val dp get() = resources.displayMetrics.density

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        sounds = Sounds(applicationContext).apply { enabled = prefs.sound }

        root = view<FrameLayout>(R.id.root)
        gameView = view<GameView>(R.id.game)
        status = view<LinearLayout>(R.id.status)
        modeView = view<TextView>(R.id.mode)
        statsView = view<TextView>(R.id.stats)
        soundButton = view<ImageButton>(R.id.sound)
        toolbar = view<LinearLayout>(R.id.toolbar)
        autoFinish = view<TextView>(R.id.autoFinish)
        toast = view<TextView>(R.id.toast)
        scrim = view<View>(R.id.scrim)
        sheet = view<FrameLayout>(R.id.sheet)
        winLayer = view<FrameLayout>(R.id.win)

        gameView.listener = this
        applySettings()
        buildToolbar()
        styleChrome()

        modeView.setOnClickListener { showNewGameSheet() }
        soundButton.setOnClickListener {
            prefs.sound = !prefs.sound
            sounds.enabled = prefs.sound
            updateSoundIcon()
            if (prefs.sound) sounds.play(Sounds.Fx.PLACE)
            showToast(if (prefs.sound) "Sound on" else "Sound off")
        }
        autoFinish.setOnClickListener {
            haptic(HapticFeedbackConstants.VIRTUAL_KEY)
            hideAutoFinish()
            gameView.startAutoComplete()
        }
        scrim.setOnClickListener { hideSheet() }

        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pushContentInsets() }
        status.addOnLayoutChangeListener(layoutListener)
        toolbar.addOnLayoutChangeListener(layoutListener)
        gameView.addOnLayoutChangeListener(layoutListener)

        restoreOrDeal()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startClockIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        stopClock()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sounds.release()
        gameView.release()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheetOpen -> hideSheet()
            winShown -> hideWin()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ================================================================== window / insets

    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        val w = window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(insets: WindowInsets) {
        val l: Int; val t: Int; val r: Int; val b: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = i.left; t = i.top; r = i.right; b = i.bottom
        } else {
            l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
            r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
        }
        status.setPadding((12 * dp).toInt() + l, t, (4 * dp).toInt() + r, 0)
        toolbar.setPadding((8 * dp).toInt() + l, (6 * dp).toInt(), (8 * dp).toInt() + r, (6 * dp).toInt() + b)
        sheet.setPadding(l, 0, r, 0)
        winLayer.setPadding(l, t, r, b)
    }

    private fun pushContentInsets() {
        if (gameView.height == 0 || toolbar.height == 0) return
        gameView.setContentInsets(status.bottom, gameView.height - toolbar.top)
        (autoFinish.layoutParams as FrameLayout.LayoutParams).bottomMargin = gameView.height - toolbar.top + (12 * dp).toInt()
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = gameView.height - toolbar.top + (72 * dp).toInt()
        if (!initialGameShown) {
            initialGameShown = true
            gameView.post { gameView.setGame(game, pendingDeal); updateChrome() }
        }
    }

    // ================================================================== game lifecycle

    private fun restoreOrDeal() {
        val restored = prefs.savedGame?.let { Klondike.deserialize(it) }
        if (restored != null && !restored.isWon) {
            game = restored
            elapsedMs = prefs.savedElapsedMs
            started = prefs.savedCounted
            pendingDeal = false
        } else {
            game = Klondike.deal(prefs.drawCount, Random.nextLong())
            elapsedMs = 0; started = false
            pendingDeal = true
        }
    }

    private fun newGame(drawCount: Int, seed: Long = Random.nextLong()) {
        if (started && !game.isWon) prefs.recordLoss(game.drawCount)
        stopClock()
        pendingWin?.let { handler.removeCallbacks(it) }
        pendingWin = null
        prefs.drawCount = drawCount
        game = Klondike.deal(drawCount, seed)
        elapsedMs = 0; started = false
        hideWin(); hideSheet()
        gameView.setGame(game, animateDeal = true)
        updateChrome()
        save()
    }

    private fun save() {
        prefs.savedGame = game.serialize()
        prefs.savedElapsedMs = currentElapsed()
        prefs.savedCounted = started
    }

    // ================================================================== clock

    private fun currentElapsed() = elapsedMs + if (runningSince > 0) SystemClock.elapsedRealtime() - runningSince else 0

    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000 - currentElapsed() % 1000)
        }
    }

    private fun startClockIfNeeded() {
        if (!started || game.isWon || !resumed || runningSince > 0) return
        runningSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopClock() {
        if (runningSince > 0) elapsedMs += SystemClock.elapsedRealtime() - runningSince
        runningSince = 0
        handler.removeCallbacks(tick)
    }

    // ================================================================== GameView.Listener

    override fun onGameChanged(byUser: Boolean) {
        if (byUser && !started) {
            started = true
            prefs.recordStart(game.drawCount)
            startClockIfNeeded()
        }
        updateChrome()
    }

    override fun onFeedback(kind: Feedback) {
        when (kind) {
            Feedback.PICK -> { sounds.play(Sounds.Fx.FLIP, 0.45f, 1.2f); haptic(HapticFeedbackConstants.CLOCK_TICK) }
            Feedback.PLACE -> { sounds.play(Sounds.Fx.PLACE); haptic(HapticFeedbackConstants.CLOCK_TICK) }
            Feedback.REVEAL -> {
                sounds.play(Sounds.Fx.PLACE)
                handler.postDelayed({ sounds.play(Sounds.Fx.FLIP) }, 90)
                haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            Feedback.FOUNDATION -> { sounds.play(Sounds.Fx.PLACE, 1f, 1.25f); haptic(HapticFeedbackConstants.CLOCK_TICK) }
            Feedback.DRAW -> { sounds.play(Sounds.Fx.DRAW); haptic(HapticFeedbackConstants.CLOCK_TICK) }
            Feedback.RECYCLE -> { sounds.play(Sounds.Fx.SHUFFLE, 0.8f, 1.3f); haptic(HapticFeedbackConstants.VIRTUAL_KEY) }
            Feedback.INVALID -> {
                sounds.play(Sounds.Fx.INVALID)
                haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
            }
            Feedback.UNDO -> { sounds.play(Sounds.Fx.UNDO); haptic(HapticFeedbackConstants.CLOCK_TICK) }
            Feedback.DEAL -> sounds.play(Sounds.Fx.SHUFFLE)
        }
    }

    override fun onNoHints() = showToast("No moves available. Try Undo or start a new game.")

    override fun onWon() {
        stopClock()
        hideAutoFinish()
        sounds.play(Sounds.Fx.WIN)
        haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        val seconds = (currentElapsed() / 1000).toInt()
        val bonus = if (seconds >= 30) 700_000 / seconds else 0
        val finalScore = game.score + bonus
        val prior = prefs.stats(game.drawCount)
        prefs.recordWin(game.drawCount, seconds, finalScore)
        save()
        updateChrome()
        pendingWin = Runnable { showWin(seconds, bonus, finalScore, prior) }
        handler.postDelayed(pendingWin!!, 2600)
    }

    override fun onWinTapped() {
        pendingWin?.let { handler.removeCallbacks(it); it.run() }
    }

    // ================================================================== chrome

    private fun applySettings() {
        gameView.leftHanded = prefs.leftHanded
        gameView.fourColor = prefs.fourColor
        gameView.autoFoundation = prefs.autoFoundation
        sounds.enabled = prefs.sound
        if (::toolbar.isInitialized) {
            toolbar.layoutDirection = if (prefs.leftHanded) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        if (::soundButton.isInitialized) updateSoundIcon()
    }

    private fun updateChrome() {
        updateStats()
        modeView.text = if (game.drawCount == 3) "DRAW 3" else "DRAW 1"
        val canUndo = game.canUndo && !game.isWon
        undoButton.isEnabled = canUndo
        undoButton.animate().alpha(if (canUndo) 1f else 0.4f).setDuration(120).start()
        hintButton.isEnabled = !game.isWon
        hintButton.alpha = if (game.isWon) 0.4f else 1f
        if (gameView.canAutoComplete()) showAutoFinish() else hideAutoFinish()
    }

    private fun updateStats() {
        if (!::game.isInitialized) return
        if (!prefs.showTimer) {
            statsView.text = ""
            return
        }
        val secs = currentElapsed() / 1000
        statsView.text = "%d:%02d   ·   %d moves   ·   %d pts".format(secs / 60, secs % 60, game.moves, game.score)
    }

    private fun updateSoundIcon() {
        soundButton.setImageResource(if (prefs.sound) R.drawable.ic_sound_on else R.drawable.ic_sound_off)
        soundButton.imageTintList = ColorStateList.valueOf(color(R.color.text))
        soundButton.contentDescription = if (prefs.sound) "Sound on" else "Sound off"
    }

    private fun styleChrome() {
        modeView.background = pill(0x33000000, 14f)
        toast.background = pill(0xEE1B2A22.toInt(), 22f)
        toast.elevation = 6 * dp
        autoFinish.background = RippleDrawable(ColorStateList.valueOf(0x33000000), pill(color(R.color.accent), 24f), null)
        autoFinish.elevation = 8 * dp
        autoFinish.setCompoundDrawablesRelativeWithIntrinsicBounds(tinted(R.drawable.ic_auto, color(R.color.on_accent)), null, null, null)
        updateSoundIcon()
    }

    private fun buildToolbar() {
        toolbar.removeAllViews()
        toolbar.addView(toolButton(R.drawable.ic_settings, "Settings", 1f) { showSettingsSheet() })
        toolbar.addView(toolButton(R.drawable.ic_new, "New", 1f) { showNewGameSheet() }.also {
            it.setOnLongClickListener { haptic(HapticFeedbackConstants.LONG_PRESS); newGame(game.drawCount); true }
        })
        hintButton = toolButton(R.drawable.ic_hint, "Hint", 1f) { gameView.showHint() }
        toolbar.addView(hintButton)
        undoButton = toolButton(R.drawable.ic_undo, "Undo", 1.35f, primary = true) { gameView.undo() }
        toolbar.addView(undoButton)
        installRepeatingUndo(undoButton)
        toolbar.layoutDirection = if (prefs.leftHanded) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    /** Hold Undo to rewind quickly. */
    private fun installRepeatingUndo(v: View) {
        var repeating = false
        val repeat = object : Runnable {
            override fun run() {
                if (gameView.undo()) handler.postDelayed(this, 110) else repeating = false
            }
        }
        v.setOnLongClickListener {
            repeating = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
            handler.post(repeat)
            true
        }
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (repeating) { repeating = false; handler.removeCallbacks(repeat) }
            }
            false
        }
    }

    private fun toolButton(icon: Int, label: String, weight: Float, primary: Boolean = false, onClick: () -> Unit): View {
        val fg = if (primary) color(R.color.on_accent) else color(R.color.text)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (60 * dp).toInt()
            contentDescription = label
            isClickable = true; isFocusable = true
            val shape = if (primary) pill(color(R.color.accent), 18f) else null
            val mask = pill(Color.WHITE, 18f)
            background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33000000 else 0x33FFFFFF), shape, mask)
            setOnClickListener { haptic(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        }
        box.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(fg)
        }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()))
        box.addView(TextView(this).apply {
            text = label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        box.layoutParams = LinearLayout.LayoutParams(0, (60 * dp).toInt(), weight).apply {
            marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
        }
        return box
    }

    private fun showAutoFinish() {
        if (autoFinish.visibility == View.VISIBLE) return
        autoFinish.visibility = View.VISIBLE
        autoFinish.scaleX = 0.6f; autoFinish.scaleY = 0.6f; autoFinish.alpha = 0f
        autoFinish.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(2f)).setListener(null).start()
    }

    private fun hideAutoFinish() {
        if (autoFinish.visibility != View.VISIBLE) return
        autoFinish.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { autoFinish.visibility = View.GONE }
        }).start()
    }

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(200).withEndAction { toast.visibility = View.GONE }.start()
    }

    private fun showToast(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        toast.animate().cancel()
        toast.alpha = 0f; toast.translationY = 12 * dp
        toast.animate().alpha(1f).translationY(0f).setDuration(180).start()
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2200)
    }

    private fun haptic(constant: Int) {
        if (prefs.haptics) gameView.performHapticFeedback(constant)
    }

    // ================================================================== bottom sheets

    private fun showSheet(content: View) {
        sheet.removeAllViews()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(color(R.color.surface))
                val r = 24 * dp
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            val navPad = toolbar.paddingBottom - (6 * dp).toInt()
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt() + navPad)
        }
        // Grab handle.
        card.addView(View(this).apply { background = pill(0x55FFFFFF, 3f) },
            LinearLayout.LayoutParams((36 * dp).toInt(), (5 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (10 * dp).toInt()
            })
        card.addView(content)
        sheet.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        sheet.translationY = root.height.toFloat()
        sheet.animate().translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(2.2f)).start()
        sheetOpen = true
    }

    private fun hideSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        scrim.animate().alpha(0f).setDuration(180).withEndAction { if (!sheetOpen) scrim.visibility = View.GONE }.start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(200)
            .withEndAction { if (!sheetOpen) sheet.visibility = View.GONE }.start()
    }

    private fun showNewGameSheet() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title("New game"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in intArrayOf(1, 3)) {
            val selected = game.drawCount == d
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                val bg = pill(if (selected) color(R.color.accent) else color(R.color.surface_high), 18f)
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
                setPadding(0, (18 * dp).toInt(), 0, (18 * dp).toInt())
                setOnClickListener { haptic(HapticFeedbackConstants.VIRTUAL_KEY); newGame(d) }
            }
            val fg = if (selected) color(R.color.on_accent) else color(R.color.text)
            tile.addView(TextView(this).apply {
                text = "Draw $d"; setTextColor(fg); textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER
            })
            tile.addView(TextView(this).apply {
                text = if (d == 1) "Relaxed" else "Classic challenge"
                setTextColor(fg); alpha = 0.8f; textSize = 13f; gravity = Gravity.CENTER
            })
            row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt()
            })
        }
        col.addView(row)
        col.addView(actionRow("Replay this deal", R.drawable.ic_undo) { newGame(game.drawCount, game.seed) })
        if (started && !game.isWon) col.addView(caption("Starting over counts the current game as a loss. Tip: long-press New to deal instantly."))
        showSheet(col)
    }

    private fun showSettingsSheet() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title("Settings"))
        col.addView(switchRow("Sound effects", prefs.sound) {
            prefs.sound = it; sounds.enabled = it; updateSoundIcon(); if (it) sounds.play(Sounds.Fx.PLACE)
        })
        col.addView(switchRow("Vibration", prefs.haptics) { prefs.haptics = it; if (it) haptic(HapticFeedbackConstants.VIRTUAL_KEY) })
        col.addView(switchRow("Left-handed layout", prefs.leftHanded) { prefs.leftHanded = it; applySettings() })
        col.addView(switchRow("Auto-play safe cards to foundations", prefs.autoFoundation) { prefs.autoFoundation = it; applySettings() })
        col.addView(switchRow("Four-colour suits", prefs.fourColor) { prefs.fourColor = it; applySettings() })
        col.addView(switchRow("Show timer, moves & score", prefs.showTimer) { prefs.showTimer = it; updateStats() })
        col.addView(title("Statistics").apply { setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt()) })
        col.addView(statsTable())
        var armed = false
        lateinit var reset: View
        reset = actionRow("Reset statistics", R.drawable.ic_new) {
            if (!armed) {
                armed = true
                ((reset as LinearLayout).getChildAt(1) as TextView).text = "Tap again to confirm reset"
            } else {
                prefs.resetStats(); hideSheet(); showToast("Statistics reset")
            }
        }
        col.addView(reset)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        val maxH = (root.height * 0.8f).toInt()
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val wrapper = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }
        wrapper.addView(scroll)
        showSheet(wrapper)
    }

    private fun statsTable(): View {
        val s1 = prefs.stats(1)
        val s3 = prefs.stats(3)
        fun time(sec: Int) = if (sec == 0) "–" else "%d:%02d".format(sec / 60, sec % 60)
        val rows = listOf(
            Triple("", "Draw 1", "Draw 3"),
            Triple("Played", "${s1.played}", "${s3.played}"),
            Triple("Won", "${s1.won}", "${s3.won}"),
            Triple("Win rate", "${s1.winRate}%", "${s3.winRate}%"),
            Triple("Current streak", "${s1.streak}", "${s3.streak}"),
            Triple("Best streak", "${s1.bestStreak}", "${s3.bestStreak}"),
            Triple("Best time", time(s1.bestTimeSec), time(s3.bestTimeSec)),
            Triple("High score", "${s1.bestScore}", "${s3.bestScore}"),
        )
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(color(R.color.surface_high), 16f)
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        }
        rows.forEachIndexed { i, (a, b, c) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (5 * dp).toInt(), 0, (5 * dp).toInt()) }
            for ((k, v) in listOf(a, b, c).withIndex()) {
                row.addView(TextView(this).apply {
                    text = v
                    textSize = 14f
                    setTextColor(if (i == 0 || k == 0) color(R.color.text_dim) else color(R.color.text))
                    if (i == 0) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    gravity = if (k == 0) Gravity.START else Gravity.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (k == 0) 1.6f else 1f))
            }
            table.addView(row)
        }
        return table
    }

    // ================================================================== win panel

    private fun showWin(seconds: Int, bonus: Int, finalScore: Int, prior: Prefs.Stats) {
        pendingWin = null
        if (winShown) return
        winShown = true
        hideSheet()
        winLayer.removeAllViews()
        val stats = prefs.stats(game.drawCount)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = pill(0xF2172A21.toInt(), 28f)
            elevation = 12 * dp
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            isClickable = true
        }
        card.addView(TextView(this).apply {
            text = "You won!"
            textSize = 30f
            setTextColor(color(R.color.accent))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        val newBest = prior.won > 0 && seconds < prior.bestTimeSec
        card.addView(TextView(this).apply {
            text = buildString {
                append("%d:%02d  ·  %d moves".format(seconds / 60, seconds % 60, game.moves))
                append("\nScore $finalScore")
                if (bonus > 0) append("  (incl. $bonus time bonus)")
                if (newBest) append("\n★ New best time!")
                append("\nWin streak ${stats.streak}  ·  ${stats.won} wins in Draw ${game.drawCount}")
            }
            textSize = 15f
            setLineSpacing(0f, 1.25f)
            gravity = Gravity.CENTER
            setTextColor(color(R.color.text))
            setPadding(0, (10 * dp).toInt(), 0, (18 * dp).toInt())
        })
        val play = TextView(this).apply {
            text = "Play again"
            textSize = 17f
            setTextColor(color(R.color.on_accent))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), pill(color(R.color.accent), 26f), null)
            setOnClickListener { newGame(game.drawCount) }
        }
        card.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (54 * dp).toInt()))
        val other = if (game.drawCount == 1) 3 else 1
        card.addView(TextView(this).apply {
            text = "Switch to Draw $other"
            textSize = 15f
            setTextColor(color(R.color.text))
            gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, pill(Color.WHITE, 26f))
            setOnClickListener { newGame(other) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { topMargin = (6 * dp).toInt() })
        winLayer.addView(card, FrameLayout.LayoutParams((320 * dp).toInt().coerceAtMost(root.width - (32 * dp).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        winLayer.visibility = View.VISIBLE
        card.alpha = 0f; card.scaleX = 0.85f; card.scaleY = 0.85f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun hideWin() {
        if (!winShown) return
        winShown = false
        winLayer.visibility = View.GONE
        winLayer.removeAllViews()
    }

    // ================================================================== small view factories

    private fun <T : View> view(id: Int): T = findViewById<T>(id)!!

    private fun color(id: Int) = getColor(id)

    private fun pill(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * dp
    }

    private fun tinted(id: Int, color: Int) = getDrawable(id)!!.mutate().apply {
        setTint(color)
        val s = (22 * dp).toInt()
        setBounds(0, 0, s, s)
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(color(R.color.text))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(0, (4 * dp).toInt(), 0, (12 * dp).toInt())
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.text_dim))
        setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), 0)
    }

    private fun actionRow(label: String, icon: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (56 * dp).toInt()
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        isClickable = true
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, pill(Color.WHITE, 14f))
        setOnClickListener { haptic(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon); imageTintList = ColorStateList.valueOf(color(R.color.text_dim))
        }, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(color(R.color.text)) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this).apply {
            isChecked = checked
            text = label
            textSize = 16f
            setTextColor(color(R.color.text))
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(R.color.accent), 0xFFBFC9C3.toInt())
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x88FFC857.toInt(), 0x55FFFFFF)
            )
            minHeight = (52 * dp).toInt()
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        return sw
    }
}
