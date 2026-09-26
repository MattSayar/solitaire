package com.mattsayar.solitaire

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny sound engine. Effects are synthesised into memory at startup (no binary assets in the repo)
 * and preloaded into static low-latency AudioTracks, so a cue plays the instant it's triggered.
 */
class Sounds {

    enum class Fx { PLACE, FLIP, DRAW, INVALID, WIN, SHUFFLE, UNDO }

    var enabled = true

    private val sr = 44100

    /** Two voices per effect so rapid repeats (auto-finish) can overlap instead of cutting each other off. */
    @Volatile private var voices: Array<Array<AudioTrack>>? = null
    private val nextVoice = IntArray(Fx.values().size)
    @Volatile private var released = false

    init {
        // Synthesis takes a few ms; do it off the main thread so startup stays instant.
        Thread {
            val built = Array(Fx.values().size) { i ->
                val pcm = synth(Fx.values()[i])
                Array(2) { track(pcm) }
            }
            synchronized(this) {
                if (released) built.forEach { v -> v.forEach { it.release() } } else voices = built
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return builder.build().also { it.write(pcm, 0, pcm.size) }
    }

    fun play(fx: Fx, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val all = voices ?: return
        val v = when (fx) {
            Fx.WIN -> 0.8f
            Fx.INVALID -> 0.6f
            else -> 0.75f
        } * volume
        val slot = nextVoice[fx.ordinal]
        nextVoice[fx.ordinal] = (slot + 1) % 2
        val t = all[fx.ordinal][slot]
        try {
            if (t.state != AudioTrack.STATE_INITIALIZED) return
            t.stop()
            t.reloadStaticData() // rewind to the start of the static buffer
            t.playbackRate = (sr * rate).toInt()
            t.setVolume(v)
            t.play()
        } catch (_: IllegalStateException) {
            // A track can be invalidated by the audio server (e.g. output device change); skip this cue.
        }
    }

    fun release() {
        synchronized(this) {
            released = true
            voices?.forEach { v -> v.forEach { it.release() } }
            voices = null
        }
    }

    // ------------------------------------------------------------------ synthesis

    private fun synth(fx: Fx): ShortArray = when (fx) {
        Fx.PLACE -> render(0.07) { t, rnd -> noise(rnd) * exp(-t * 90) * 0.55 + sin(2 * PI * 170 * t) * exp(-t * 60) * 0.6 }
        Fx.FLIP -> render(0.05) { t, rnd -> noise(rnd) * exp(-t * 140) * 0.7 + sin(2 * PI * 420 * t) * exp(-t * 110) * 0.25 }
        Fx.DRAW -> lowpass(render(0.11) { t, rnd -> noise(rnd) * env(t, 0.015, 45.0) * 0.8 }, 0.35)
        Fx.UNDO -> lowpass(render(0.12) { t, rnd -> noise(rnd) * env(t, 0.05, 40.0) * 0.6 }, 0.25)
        Fx.INVALID -> render(0.2) { t, _ ->
            val a = sin(2 * PI * 150 * t) * exp(-t * 40)
            val b = if (t > 0.08) sin(2 * PI * 120 * (t - 0.08)) * exp(-(t - 0.08) * 40) else 0.0
            (a + b) * 0.55
        }
        Fx.SHUFFLE -> lowpass(render(0.45) { t, rnd ->
            val tick = (t * 38) % 1.0
            noise(rnd) * exp(-tick * 9) * 0.6 * (1 - t / 0.5)
        }, 0.5)
        Fx.WIN -> render(1.3) { t, _ ->
            val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
            var v = 0.0
            for (i in notes.indices) {
                val start = i * 0.11
                if (t >= start) {
                    val u = t - start
                    v += (sin(2 * PI * notes[i] * u) + 0.3 * sin(4 * PI * notes[i] * u)) * exp(-u * 3.2) * 0.22
                }
            }
            v
        }
    }

    private fun env(t: Double, attack: Double, decay: Double) =
        if (t < attack) t / attack else exp(-(t - attack) * decay)

    private fun noise(rnd: java.util.Random) = rnd.nextDouble() * 2 - 1

    private inline fun render(seconds: Double, f: (Double, java.util.Random) -> Double): ShortArray {
        val n = (seconds * sr).toInt()
        val rnd = java.util.Random(7)
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            // 3ms fade-out at the end avoids clicks.
            val tail = ((n - i).toDouble() / (0.003 * sr)).coerceAtMost(1.0)
            (f(t, rnd).coerceIn(-1.0, 1.0) * tail * 32000).toInt().toShort()
        }
    }

    private fun lowpass(data: ShortArray, alpha: Double): ShortArray {
        var y = 0.0
        for (i in data.indices) {
            y += alpha * (data[i] - y)
            data[i] = y.toInt().toShort()
        }
        return data
    }
}
