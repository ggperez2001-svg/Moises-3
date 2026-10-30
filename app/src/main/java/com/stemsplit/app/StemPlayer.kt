package com.stemsplit.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Reproduce la suma de las pistas (leídas del disco) con ganancia por pista.
 * Velocidad y tono se aplican con PlaybackParams del AudioTrack (cambian de forma independiente).
 */
class StemPlayer(private val files: List<File>, val totalFrames: Long) {

    companion object {
        const val SR = 44100
        private const val BLOCK = 4096
    }

    @Volatile var gains = FloatArray(4) { 1f }
    @Volatile var isPlaying = false
        private set
    @Volatile private var running = true
    @Volatile private var pendingSeek = -1L
    @Volatile private var written = 0L
    @Volatile var onEnded: (() -> Unit)? = null

    private val track: AudioTrack
    private val thread: Thread

    init {
        val minBytes = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBytes, 8192 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        thread = Thread({ loop() }, "StemPlayer").also { it.start() }
    }

    fun positionFrames(): Long = max(0L, written - if (isPlaying) track.bufferSizeInFrames else 0)

    fun play() {
        if (written >= totalFrames) seek(0)
        isPlaying = true
        track.play()
    }

    fun pause() {
        isPlaying = false
        track.pause()
    }

    fun seek(frame: Long) {
        val f = frame.coerceIn(0, totalFrames)
        written = f
        pendingSeek = f
        track.pause()
        track.flush()
        if (isPlaying) track.play()
    }

    /** speed: 0.5..1.5 ; semitones: -6..+6 */
    fun setSpeedPitch(speed: Float, semitones: Int) {
        try {
            val params = PlaybackParams()
                .setSpeed(speed)
                .setPitch(2.0.pow(semitones / 12.0).toFloat())
            track.playbackParams = params
            // algunas versiones de Android reanudan la reproducción al cambiar los parámetros
            if (!isPlaying) track.pause()
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }

    fun release() {
        running = false
        try { thread.join(500) } catch (_: InterruptedException) {}
        try { track.release() } catch (_: Exception) {}
    }

    private fun loop() {
        val rafs = files.map { RandomAccessFile(it, "r") }
        val raw = ByteArray(BLOCK * 4)
        val acc = FloatArray(BLOCK * 2)
        val out = ShortArray(BLOCK * 2)
        var readPos = 0L
        try {
            while (running) {
                val ps = pendingSeek
                if (ps >= 0) {
                    readPos = ps
                    pendingSeek = -1
                }
                if (!isPlaying) {
                    Thread.sleep(15)
                    continue
                }
                if (readPos >= totalFrames) {
                    isPlaying = false
                    track.pause()
                    written = totalFrames
                    onEnded?.invoke()
                    continue
                }
                val n = min(BLOCK.toLong(), totalFrames - readPos).toInt()
                java.util.Arrays.fill(acc, 0, n * 2, 0f)
                val g = gains
                for (si in rafs.indices) {
                    val gain = g[si]
                    if (gain <= 0f) continue
                    val r = rafs[si]
                    r.seek(readPos * 4)
                    r.readFully(raw, 0, n * 4)
                    for (i in 0 until n * 2) {
                        val v = (raw[i * 2].toInt() and 0xFF) or (raw[i * 2 + 1].toInt() shl 8)
                        acc[i] += v * gain
                    }
                }
                for (i in 0 until n * 2) {
                    out[i] = acc[i].coerceIn(-32768f, 32767f).toInt().toShort()
                }
                track.write(out, 0, n * 2)
                if (pendingSeek < 0) {
                    readPos += n
                    written = readPos
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            rafs.forEach { try { it.close() } catch (_: Exception) {} }
        }
    }
}
