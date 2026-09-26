package com.mattsayar.solitaire

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny sound engine. Effects are synthesised once into WAV files in the cache directory (no binary
 * assets in the repo) and played through a low-latency SoundPool.
 */
class Sounds(private val context: Context) {

    enum class Fx { PLACE, FLIP, DRAW, INVALID, WIN, SHUFFLE, UNDO }

    var enabled = true

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = IntArray(Fx.values().size)
    @Volatile private var ready = false

    init {
        Thread {
            val dir = File(context.cacheDir, "sfx-v1").apply { mkdirs() }
            for (fx in Fx.values()) {
                val f = File(dir, "${fx.name.lowercase()}.wav")
                if (!f.exists() || f.length() < 64) writeWav(f, synth(fx))
                ids[fx.ordinal] = pool.load(f.path, 1)
            }
            ready = true
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun play(fx: Fx, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled || !ready) return
        val v = when (fx) {
            Fx.WIN -> 0.8f
            Fx.INVALID -> 0.55f
            else -> 0.7f
        } * volume
        pool.play(ids[fx.ordinal], v, v, 1, 0, rate)
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private val sr = 44100

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

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        FileOutputStream(file).use { it.write(buf.array()) }
    }
}
