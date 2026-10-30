package com.stemsplit.app

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Stems {
    val NAMES = listOf("vocals", "drums", "bass", "other")
    val LABELS = listOf("Voz", "Batería", "Bajo", "Otros")
}

/**
 * Separación de 4 pistas (voz, batería, bajo, otros) al estilo Spleeter.
 *
 * Contrato del modelo .tflite (ver README):
 *   entrada : float32, T=512 frames x F=1024 bins x 2 canales (magnitud del STFT)
 *   salida  : 4 tensores float32 del mismo tamaño, en orden vocals, drums, bass, other
 *             (magnitud estimada de cada instrumento)
 *
 * El audio se procesa por bloques (~12 s) con solapamiento, así el uso de RAM es
 * constante sin importar el largo de la canción.
 */
class Separator(private val modelFile: File) {

    companion object {
        const val N = 4096                 // tamaño de ventana FFT
        const val HOP = 1024
        const val T = 512                  // frames por bloque del modelo
        const val F = 1024                 // bins que ve el modelo (0 .. ~11 kHz)
        const val L = N + (T - 1) * HOP    // muestras por bloque
        const val PAD_L = N - HOP
        const val OVERLAP = 7 * HOP
        const val ADV = L - OVERLAP
        private const val EPS = 1e-10f
    }

    fun separate(mixPcm: File, totalFrames: Long, outDir: File, onProgress: (Float) -> Unit) {
        val fft = Fft(N)
        val win = FloatArray(N) { (0.5 * (1.0 - cos(2.0 * PI * it / N))).toFloat() }

        val mapped = RandomAccessFile(modelFile, "r").use {
            it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length())
        }
        val interp = Interpreter(mapped, Interpreter.Options().setNumThreads(4))
        val raf = RandomAccessFile(mixPcm, "r")
        val outs = ArrayList<BufferedOutputStream>()

        try {
            // ---- validación del modelo ----
            val inT = interp.getInputTensor(0)
            val inCount = inT.shape().fold(1) { a, b -> a * b }
            require(inT.dataType() == DataType.FLOAT32 && inCount == T * F * 2) {
                "Modelo no compatible: entrada ${inT.shape().toList()} ${inT.dataType()}; " +
                    "se esperaba float32 de ${T}x${F}x2"
            }
            require(interp.outputTensorCount == 4) {
                "Modelo no compatible: tiene ${interp.outputTensorCount} salidas, se esperaban 4"
            }
            for (i in 0 until 4) {
                val c = interp.getOutputTensor(i).shape().fold(1) { a, b -> a * b }
                require(c == T * F * 2) { "Modelo no compatible: la salida $i tiene forma ${interp.getOutputTensor(i).shape().toList()}" }
            }

            val inBuf = ByteBuffer.allocateDirect(T * F * 2 * 4).order(ByteOrder.nativeOrder())
            val outBufs = Array(4) { ByteBuffer.allocateDirect(T * F * 2 * 4).order(ByteOrder.nativeOrder()) }
            val outMap = HashMap<Int, Any>()
            for (i in 0 until 4) outMap[i] = outBufs[i]

            val xr = Array(2) { FloatArray(T * F) }
            val xi = Array(2) { FloatArray(T * F) }
            val inFloat = FloatArray(T * F * 2)
            val est = Array(4) { FloatArray(T * F * 2) }
            val sumSq = FloatArray(T * F * 2)
            val acc = Array(4) { Array(2) { FloatArray(L) } }
            val den = FloatArray(L)
            val samples = Array(2) { FloatArray(L) }
            val raw = ByteArray(L * 4)
            val re = FloatArray(N)
            val im = FloatArray(N)

            for (name in Stems.NAMES) {
                outs.add(BufferedOutputStream(FileOutputStream(File(outDir, "$name.pcm")), 1 shl 16))
            }

            val lastK = if (totalFrames - L + 2L * PAD_L <= 0) 0L
            else ceil((totalFrames - L + 2L * PAD_L).toDouble() / ADV).toLong()
            val chunks = lastK + 1

            var k = 0L
            while (true) {
                val s = -PAD_L.toLong() + k * ADV
                readChunk(raf, s, totalFrames, raw, samples)

                // ---- STFT: los dos canales juntos en una sola FFT compleja (L=real, R=imag) ----
                for (t in 0 until T) {
                    val off = t * HOP
                    for (n in 0 until N) {
                        re[n] = samples[0][off + n] * win[n]
                        im[n] = samples[1][off + n] * win[n]
                    }
                    fft.forward(re, im)
                    val base = t * F
                    for (f in 0 until F) {
                        val j = (N - f) and (N - 1)
                        val zr = re[f]; val zi = im[f]
                        val cr = re[j]; val ci = -im[j]
                        val lr = (zr + cr) * 0.5f
                        val li = (zi + ci) * 0.5f
                        val rr = (zi - ci) * 0.5f
                        val ri = -(zr - cr) * 0.5f
                        xr[0][base + f] = lr; xi[0][base + f] = li
                        xr[1][base + f] = rr; xi[1][base + f] = ri
                        val idx = (base + f) * 2
                        inFloat[idx] = sqrt(lr * lr + li * li)
                        inFloat[idx + 1] = sqrt(rr * rr + ri * ri)
                    }
                }

                // ---- red neuronal ----
                inBuf.rewind()
                inBuf.asFloatBuffer().put(inFloat)
                for (b in outBufs) b.rewind()
                interp.runForMultipleInputsOutputs(arrayOf<Any>(inBuf), outMap)
                java.util.Arrays.fill(sumSq, 0f)
                for (i in 0 until 4) {
                    outBufs[i].rewind()
                    outBufs[i].asFloatBuffer().get(est[i])
                    val e = est[i]
                    for (j in e.indices) sumSq[j] += e[j] * e[j]
                }

                // ---- máscara de razón + ISTFT por stem ----
                for (t in 0 until T) {
                    val off = t * HOP
                    for (n in 0 until N) den[off + n] += win[n] * win[n]
                }
                for (i in 0 until 4) {
                    val e = est[i]
                    val aL = acc[i][0]
                    val aR = acc[i][1]
                    for (t in 0 until T) {
                        java.util.Arrays.fill(re, 0f)
                        java.util.Arrays.fill(im, 0f)
                        val base = t * F
                        for (f in 0 until F) {
                            val idx = (base + f) * 2
                            val ml = e[idx] * e[idx] / (sumSq[idx] + EPS)
                            val mr = e[idx + 1] * e[idx + 1] / (sumSq[idx + 1] + EPS)
                            val slr = xr[0][base + f] * ml
                            val sli = xi[0][base + f] * ml
                            val srr = xr[1][base + f] * mr
                            val sri = xi[1][base + f] * mr
                            re[f] = slr - sri
                            im[f] = sli + srr
                            if (f > 0) {
                                re[N - f] = slr + sri
                                im[N - f] = srr - sli
                            }
                        }
                        fft.inverse(re, im)
                        val off = t * HOP
                        for (n in 0 until N) {
                            val w = win[n]
                            aL[off + n] += re[n] * w
                            aR[off + n] += im[n] * w
                        }
                    }
                }

                // ---- emitir muestras ya completas ----
                val isLast = s + L - PAD_L >= totalFrames
                val emitEnd = if (isLast) min(L.toLong(), totalFrames - s).toInt() else ADV
                val o0 = if (s < 0) (-s).toInt() else 0
                if (emitEnd > o0) {
                    val buf = ByteArray((emitEnd - o0) * 4)
                    for (i in 0 until 4) {
                        var p = 0
                        for (o in o0 until emitEnd) {
                            val d = den[o]
                            val inv = if (d > 1e-4f) 1f / d else 0f
                            val lv = (acc[i][0][o] * inv * 32767f).roundToInt().coerceIn(-32768, 32767)
                            val rv = (acc[i][1][o] * inv * 32767f).roundToInt().coerceIn(-32768, 32767)
                            buf[p++] = lv.toByte()
                            buf[p++] = (lv shr 8).toByte()
                            buf[p++] = rv.toByte()
                            buf[p++] = (rv shr 8).toByte()
                        }
                        outs[i].write(buf)
                    }
                }

                k++
                onProgress((k.toFloat() / chunks).coerceIn(0f, 1f))
                if (isLast) break

                // ---- arrastrar la cola solapada al próximo bloque ----
                for (i in 0 until 4) for (c in 0 until 2) {
                    val a = acc[i][c]
                    System.arraycopy(a, ADV, a, 0, OVERLAP)
                    java.util.Arrays.fill(a, OVERLAP, L, 0f)
                }
                System.arraycopy(den, ADV, den, 0, OVERLAP)
                java.util.Arrays.fill(den, OVERLAP, L, 0f)
            }
        } finally {
            for (o in outs) try { o.close() } catch (_: Exception) {}
            raf.close()
            interp.close()
        }
    }

    private fun readChunk(raf: RandomAccessFile, s: Long, total: Long, raw: ByteArray, out: Array<FloatArray>) {
        java.util.Arrays.fill(out[0], 0f)
        java.util.Arrays.fill(out[1], 0f)
        val from = max(s, 0L)
        val to = min(s + L, total)
        if (to <= from) return
        val n = (to - from).toInt()
        raf.seek(from * 4)
        raf.readFully(raw, 0, n * 4)
        val dst = (from - s).toInt()
        for (i in 0 until n) {
            val b = i * 4
            val l = (raw[b].toInt() and 0xFF) or (raw[b + 1].toInt() shl 8)
            val r = (raw[b + 2].toInt() and 0xFF) or (raw[b + 3].toInt() shl 8)
            out[0][dst + i] = l / 32768f
            out[1][dst + i] = r / 32768f
        }
    }
}
