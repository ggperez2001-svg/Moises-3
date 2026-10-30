package com.stemsplit.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Decodifica cualquier audio que soporte Android (mp3, m4a, wav, flac, ogg...)
 * a PCM 16-bit estéreo a 44.1 kHz guardado en un archivo temporal (así no se
 * carga la canción entera en RAM).
 */
object AudioDecoder {
    const val SR = 44100

    fun decodeToPcm(ctx: Context, uri: Uri, outFile: File, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)

        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("El archivo no contiene audio")
        }
        extractor.selectTrack(trackIdx)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs =
            if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var resampler = Resampler(srcRate)
        val info = MediaCodec.BufferInfo()
        val os = BufferedOutputStream(FileOutputStream(outFile), 1 shl 16)
        var frames = 0L
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val ob = codec.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val shorts = ShortArray(sb.remaining())
                        sb.get(shorts)
                        frames += resampler.process(shorts, channels, os)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val nr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nr != srcRate) {
                        srcRate = nr
                        resampler = Resampler(srcRate)
                    }
                }
            }
        } finally {
            os.close()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
        return frames
    }

    /** Remuestreo lineal en streaming a 44.1 kHz estéreo. Mono -> se duplica el canal. */
    private class Resampler(srcRate: Int) {
        private val step = srcRate.toDouble() / SR
        private var phase = 0.0
        private var prevL = 0f
        private var prevR = 0f
        private var tmp = ByteArray(0)

        fun process(data: ShortArray, ch: Int, os: OutputStream): Int {
            val n = data.size / ch
            if (n == 0) return 0
            val rOff = if (ch > 1) 1 else 0
            fun l(i: Int): Float = if (i < 0) prevL else data[i * ch].toFloat()
            fun r(i: Int): Float = if (i < 0) prevR else data[i * ch + rOff].toFloat()

            val maxOut = (n / step).toInt() + 4
            if (tmp.size < maxOut * 4) tmp = ByteArray(maxOut * 4)
            var count = 0
            var p = 0
            while (phase < n - 1) {
                val i0 = floor(phase).toInt()
                val fr = (phase - i0).toFloat()
                val a = l(i0)
                val b = r(i0)
                val lv = (a + (l(i0 + 1) - a) * fr).roundToInt().coerceIn(-32768, 32767)
                val rv = (b + (r(i0 + 1) - b) * fr).roundToInt().coerceIn(-32768, 32767)
                tmp[p++] = lv.toByte()
                tmp[p++] = (lv shr 8).toByte()
                tmp[p++] = rv.toByte()
                tmp[p++] = (rv shr 8).toByte()
                count++
                phase += step
            }
            phase -= n
            prevL = l(n - 1)
            prevR = r(n - 1)
            os.write(tmp, 0, count * 4)
            return count
        }
    }
}
