package com.mattsayar.solitaire

import android.content.Context

/** Settings and statistics, backed by SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("solitaire", Context.MODE_PRIVATE)

    var drawCount: Int
        get() = sp.getInt("draw", 1)
        set(v) = sp.edit().putInt("draw", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var leftHanded: Boolean
        get() = sp.getBoolean("left", false)
        set(v) = sp.edit().putBoolean("left", v).apply()

    var fourColor: Boolean
        get() = sp.getBoolean("fourColor", false)
        set(v) = sp.edit().putBoolean("fourColor", v).apply()

    var autoFoundation: Boolean
        get() = sp.getBoolean("autoFoundation", false)
        set(v) = sp.edit().putBoolean("autoFoundation", v).apply()

    var showTimer: Boolean
        get() = sp.getBoolean("showTimer", true)
        set(v) = sp.edit().putBoolean("showTimer", v).apply()

    var savedGame: String?
        get() = sp.getString("game", null)
        set(v) = sp.edit().putString("game", v).apply()

    var savedElapsedMs: Long
        get() = sp.getLong("elapsed", 0L)
        set(v) = sp.edit().putLong("elapsed", v).apply()

    /** Whether the saved game has already been counted as "played" in the stats. */
    var savedCounted: Boolean
        get() = sp.getBoolean("counted", false)
        set(v) = sp.edit().putBoolean("counted", v).apply()

    // ------------------------------------------------------------ statistics (per draw mode)

    class Stats(val played: Int, val won: Int, val streak: Int, val bestStreak: Int, val bestTimeSec: Int, val bestScore: Int) {
        val winRate get() = if (played == 0) 0 else (won * 100 / played)
    }

    fun stats(draw: Int) = Stats(
        sp.getInt("played$draw", 0), sp.getInt("won$draw", 0), sp.getInt("streak$draw", 0),
        sp.getInt("bestStreak$draw", 0), sp.getInt("bestTime$draw", 0), sp.getInt("bestScore$draw", 0),
    )

    fun recordStart(draw: Int) {
        sp.edit().putInt("played$draw", sp.getInt("played$draw", 0) + 1).apply()
    }

    /** Abandoning a started game breaks the streak. */
    fun recordLoss(draw: Int) {
        sp.edit().putInt("streak$draw", 0).apply()
    }

    fun recordWin(draw: Int, seconds: Int, score: Int) {
        val s = stats(draw)
        val streak = s.streak + 1
        sp.edit()
            .putInt("won$draw", s.won + 1)
            .putInt("streak$draw", streak)
            .putInt("bestStreak$draw", maxOf(streak, s.bestStreak))
            .putInt("bestTime$draw", if (s.bestTimeSec == 0) seconds else minOf(seconds, s.bestTimeSec))
            .putInt("bestScore$draw", maxOf(score, s.bestScore))
            .apply()
    }

    fun resetStats() {
        val e = sp.edit()
        for (d in intArrayOf(1, 3)) for (k in listOf("played", "won", "streak", "bestStreak", "bestTime", "bestScore")) e.remove("$k$d")
        e.apply()
    }
}
