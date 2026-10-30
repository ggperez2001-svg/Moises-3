package com.stemsplit.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Guarda las pistas como WAV en Descargas/StemSplit (sin pedir permisos de almacenamiento). */
object WavExporter {

    fun exportAll(
        ctx: Context,
        songName: String,
        stemFiles: List<File>,
        gains: FloatArray,
        totalFrames: Long,
        onProgress: (Float) -> Unit
    ): Int {
        val base = songName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "cancion" }
        val dataBytes = totalFrames * 4
        var done = 0
        val total = stemFiles.size + 1

        for (i in stemFiles.indices) {
            writeWav(ctx, "${base}_${Stems.NAMES[i]}.wav", dataBytes) { os ->
                stemFiles[i].inputStream().buffered().use { it.copyTo(os) }
            }
            done++
            onProgress(done.toFloat() / total)
        }

        // mezcla con los volúmenes / mute / solo actuales
        writeWav(ctx, "${base}_mezcla.wav", dataBytes) { os ->
            val rafs = stemFiles.map { RandomAccessFile(it, "r") }
            try {
                val block = 8192
                val raw = ByteArray(block * 4)
                val acc = FloatArray(block * 2)
                val out = ByteArray(block * 4)
                var pos = 0L
                while (pos < totalFrames) {
                    val n = minOf(block.toLong(), totalFrames - pos).toInt()
                    java.util.Arrays.fill(acc, 0, n * 2, 0f)
                    for (si in rafs.indices) {
                        if (gains[si] <= 0f) continue
                        rafs[si].seek(pos * 4)
                        rafs[si].readFully(raw, 0, n * 4)
                        for (i in 0 until n * 2) {
                            val v = (raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)
                            acc[i] += v * gains[si]
                        }
                    }
                    for (i in 0 until n * 2) {
                        val v = acc[i].coerceIn(-32768f, 32767f).toInt()
                        out[i * 2] = v.toByte()
                        out[i * 2 + 1] = (v shr 8).toByte()
                    }
                    os.write(out, 0, n * 4)
                    pos += n
                }
            } finally {
                rafs.forEach { it.close() }
            }
        }
        onProgress(1f)
        return total
    }

    private fun writeWav(ctx: Context, fileName: String, dataBytes: Long, body: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/StemSplit")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear $fileName")
        val os = ctx.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("No se pudo escribir $fileName")
        os.buffered().use {
            it.write(header(dataBytes))
            body(it)
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(2)
        b.putInt(44100); b.putInt(44100 * 4)
        b.putShort(4); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }
}
