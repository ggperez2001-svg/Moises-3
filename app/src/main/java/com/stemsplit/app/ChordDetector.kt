package com.stemsplit.app

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Chord(val startSec: Float, val endSec: Float, val name: String)

/**
 * Detección de acordes (mayores y menores) con cromagrama + plantillas + Viterbi
 * para evitar que el acorde "parpadee". Es una estimación: no distingue séptimas,
 * suspendidos, etc.
 */
object ChordDetector {
    private const val SR = 44100
    private const val N = 8192
    private const val HOP = 4096
    private const val NONE = 24          // estado "sin acorde"
    private const val SWITCH_PENALTY = 0.5f
    private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun chordName(state: Int): String =
        if (state == NONE) "N.C." else NOTES[state % 12] + if (state >= 12) "m" else ""

    /** Suma los archivos PCM indicados (estéreo 16-bit) y detecta los acordes de la mezcla. */
    fun detect(files: List<File>, totalFrames: Long, onProgress: (Float) -> Unit): List<Chord> {
        if (totalFrames < N) return emptyList()
        val nFrames = ((totalFrames - N) / HOP).toInt() + 1
        val fft = Fft(N)
        val win = FloatArray(N) { (0.5 * (1.0 - cos(2.0 * PI * it / N))).toFloat() }

        // bin -> clase de altura (0=C ... 11=B), solo entre 80 Hz y 3.5 kHz
        val binPc = IntArray(N / 2) { -1 }
        val binW = FloatArray(N / 2)
        for (k in 1 until N / 2) {
            val f = k * SR.toDouble() / N
            if (f in 80.0..3500.0) {
                val midi = 12.0 * log2(f / 440.0) + 69.0
                binPc[k] = ((midi.roundToInt() % 12) + 12) % 12
                binW[k] = (1.0 / (1.0 + f / 1500.0)).toFloat()
            }
        }

        val rafs = files.map { RandomAccessFile(it, "r") }
        val raw = ByteArray(N * 4)
        val mono = FloatArray(N)
        val re = FloatArray(N)
        val im = FloatArray(N)
        val chroma = Array(nFrames) { FloatArray(12) }
        val energy = FloatArray(nFrames)

        try {
            for (t in 0 until nFrames) {
                java.util.Arrays.fill(mono, 0f)
                val pos = t.toLong() * HOP
                for (r in rafs) {
                    r.seek(pos * 4)
                    r.readFully(raw, 0, N * 4)
                    for (i in 0 until N) {
                        val b = i * 4
                        val l = (raw[b].toInt() and 0xFF) or (raw[b + 1].toInt() shl 8)
                        val rr = (raw[b + 2].toInt() and 0xFF) or (raw[b + 3].toInt() shl 8)
                        mono[i] += (l + rr) / 65536f
                    }
                }
                for (i in 0 until N) {
                    re[i] = mono[i] * win[i]
                    im[i] = 0f
                }
                fft.forward(re, im)
                val c = chroma[t]
                var e = 0f
                for (k in 1 until N / 2) {
                    val pc = binPc[k]
                    if (pc < 0) continue
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    e += mag
                    c[pc] += ln(1f + mag) * binW[k]
                }
                energy[t] = e
                if (t % 50 == 0) onProgress(t.toFloat() / nFrames)
            }
        } finally {
            rafs.forEach { it.close() }
        }

        // suavizado temporal (±1 frame) y normalización L2
        val smooth = Array(nFrames) { FloatArray(12) }
        for (t in 0 until nFrames) {
            for (d in -1..1) {
                val u = (t + d).coerceIn(0, nFrames - 1)
                for (p in 0 until 12) smooth[t][p] += chroma[u][p]
            }
            var norm = 0f
            for (p in 0 until 12) norm += smooth[t][p] * smooth[t][p]
            norm = sqrt(norm)
            if (norm > 0f) for (p in 0 until 12) smooth[t][p] /= norm
        }

        // plantillas: 0..11 mayores, 12..23 menores
        val templates = Array(24) { FloatArray(12) }
        for (r in 0 until 12) {
            templates[r][r] = 1f
            templates[r][(r + 4) % 12] = 1f
            templates[r][(r + 7) % 12] = 1f
            templates[12 + r][r] = 1f
            templates[12 + r][(r + 3) % 12] = 1f
            templates[12 + r][(r + 7) % 12] = 1f
        }
        val inv = 1f / sqrt(3f)
        for (tpl in templates) for (p in 0 until 12) tpl[p] *= inv

        val sorted = energy.sortedArray()
        val silenceThr = 0.15f * sorted[nFrames / 2]

        // Viterbi sobre 25 estados
        val S = 25
        val dp = FloatArray(S)
        val prev = FloatArray(S)
        val back = ByteArray(nFrames * S)
        for (t in 0 until nFrames) {
            val silent = energy[t] < silenceThr
            val emit = FloatArray(S)
            for (s in 0 until 24) {
                var dot = 0f
                for (p in 0 until 12) dot += smooth[t][p] * templates[s][p]
                emit[s] = dot
            }
            emit[NONE] = if (silent) 1f else 0f
            if (t == 0) {
                for (s in 0 until S) dp[s] = emit[s]
                continue
            }
            System.arraycopy(dp, 0, prev, 0, S)
            var best = 0
            for (s in 1 until S) if (prev[s] > prev[best]) best = s
            for (s in 0 until S) {
                if (prev[s] >= prev[best] - SWITCH_PENALTY) {
                    dp[s] = prev[s] + emit[s]
                    back[t * S + s] = s.toByte()
                } else {
                    dp[s] = prev[best] - SWITCH_PENALTY + emit[s]
                    back[t * S + s] = best.toByte()
                }
            }
        }
        var state = 0
        for (s in 1 until S) if (dp[s] > dp[state]) state = s
        val path = IntArray(nFrames)
        for (t in nFrames - 1 downTo 0) {
            path[t] = state
            if (t > 0) state = back[t * S + state].toInt()
        }

        // segmentos
        val duration = totalFrames.toFloat() / SR
        val result = ArrayList<Chord>()
        var t0 = 0
        for (t in 1..nFrames) {
            if (t == nFrames || path[t] != path[t0]) {
                val start = if (t0 == 0) 0f else max(0f, (t0 * HOP + N / 2 - HOP / 2).toFloat() / SR)
                val end = if (t == nFrames) duration else min(duration, (t * HOP + N / 2 - HOP / 2).toFloat() / SR)
                result.add(Chord(start, end, chordName(path[t0])))
                t0 = t
            }
        }
        onProgress(1f)
        return result
    }
}
