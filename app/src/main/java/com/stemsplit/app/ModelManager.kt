package com.stemsplit.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Guarda el modelo .tflite en el almacenamiento interno de la app. */
object ModelManager {

    fun file(ctx: Context) = File(ctx.filesDir, "model.tflite")

    fun isReady(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 1_000_000 }

    fun importFrom(ctx: Context, uri: Uri) {
        val tmp = File(ctx.filesDir, "model.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        finish(ctx, tmp)
    }

    fun download(ctx: Context, url: String, onProgress: (Float) -> Unit) {
        val tmp = File(ctx.filesDir, "model.tmp")
        var current = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = conn.getHeaderField("Location") ?: break
                current = URL(current, loc)
                conn.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("El servidor respondió $code")
            }
            break
        }
        val len = conn.contentLengthLong
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    total += r
                    if (len > 0) onProgress((total.toFloat() / len).coerceIn(0f, 1f))
                }
            }
        }
        conn.disconnect()
        finish(ctx, tmp)
    }

    private fun finish(ctx: Context, tmp: File) {
        if (tmp.length() < 1_000_000) {
            tmp.delete()
            throw IllegalStateException("El archivo es demasiado chico para ser un modelo válido")
        }
        val dst = file(ctx)
        dst.delete()
        if (!tmp.renameTo(dst)) throw IllegalStateException("No se pudo guardar el modelo")
    }
}
