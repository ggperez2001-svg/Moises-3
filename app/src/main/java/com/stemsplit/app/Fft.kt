package com.stemsplit.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** FFT compleja radix-2 en el lugar. Tamaño n potencia de 2. */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "n debe ser potencia de 2" }
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: FloatArray, im: FloatArray) = run(re, im, false)

    fun inverse(re: FloatArray, im: FloatArray) {
        run(re, im, true)
        val s = 1f / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun run(re: FloatArray, im: FloatArray, inv: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = if (inv) -sinT[k] else sinT[k]
                    val xr = re[j + half]
                    val xi = im[j + half]
                    val tr = xr * wr - xi * wi
                    val ti = xr * wi + xi * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}
