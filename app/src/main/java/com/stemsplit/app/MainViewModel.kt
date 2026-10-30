package com.stemsplit.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Stage { IDLE, DECODING, SEPARATING, CHORDS, READY }

    var stage by mutableStateOf(Stage.IDLE)
        private set
    var progress by mutableStateOf(0f)
        private set
    var message by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
    var songName by mutableStateOf("")
        private set
    var modelReady by mutableStateOf(ModelManager.isReady(app))
        private set
    var modelBusy by mutableStateOf(false)
        private set
    var modelProgress by mutableStateOf(0f)
        private set
    var chords by mutableStateOf<List<Chord>>(emptyList())
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var positionSec by mutableStateOf(0f)
        private set
    var durationSec by mutableStateOf(0f)
        private set
    var speed by mutableStateOf(1f)
    var semitones by mutableStateOf(0)
    var exportMsg by mutableStateOf<String?>(null)
    var exporting by mutableStateOf(false)
        private set

    val volumes = mutableStateListOf(1f, 1f, 1f, 1f)
    val muted = mutableStateListOf(false, false, false, false)
    val solo = mutableStateListOf(false, false, false, false)

    private var player: StemPlayer? = null
    private var totalFrames = 0L
    private val sessionDir: File get() = File(getApplication<Application>().filesDir, "session")

    // ---------------- modelo ----------------

    fun importModel(uri: Uri) {
        modelBusy = true
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelManager.importFrom(getApplication(), uri)
                modelReady = true
            } catch (e: Throwable) {
                error = "No se pudo importar el modelo: ${e.message}"
            }
            modelBusy = false
        }
    }

    fun downloadModel(url: String) {
        if (url.isBlank()) {
            error = "Pegá la URL del modelo .tflite"
            return
        }
        modelBusy = true
        modelProgress = 0f
        error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelManager.download(getApplication(), url.trim()) { modelProgress = it }
                modelReady = true
            } catch (e: Throwable) {
                error = "No se pudo descargar el modelo: ${e.message}"
            }
            modelBusy = false
        }
    }

    // ---------------- canción ----------------

    fun loadSong(uri: Uri) {
        if (!modelReady) {
            error = "Primero cargá el modelo de separación"
            return
        }
        releasePlayer()
        error = null
        chords = emptyList()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val ctx = getApplication<Application>()
                songName = queryName(uri)
                sessionDir.deleteRecursively()
                sessionDir.mkdirs()

                stage = Stage.DECODING
                message = "Decodificando audio…"
                progress = 0f
                val mix = File(sessionDir, "mix.pcm")
                totalFrames = AudioDecoder.decodeToPcm(ctx, uri, mix) { progress = it }
                require(totalFrames > 44100L) { "El audio es demasiado corto" }

                stage = Stage.SEPARATING
                progress = 0f
                message = "Separando pistas… 0%"
                Separator(ModelManager.file(ctx)).separate(mix, totalFrames, sessionDir) {
                    progress = it
                    message = "Separando pistas… ${(it * 100).toInt()}%"
                }

                stage = Stage.CHORDS
                progress = 0f
                message = "Detectando acordes…"
                val chordFiles = listOf("bass", "other").map { File(sessionDir, "$it.pcm") }
                chords = ChordDetector.detect(chordFiles, totalFrames) { progress = it }

                val p = StemPlayer(Stems.NAMES.map { File(sessionDir, "$it.pcm") }, totalFrames)
                p.onEnded = { isPlaying = false }
                player = p
                durationSec = totalFrames.toFloat() / StemPlayer.SR
                positionSec = 0f
                speed = 1f
                semitones = 0
                for (i in 0 until 4) {
                    volumes[i] = 1f
                    muted[i] = false
                    solo[i] = false
                }
                applyGains()
                stage = Stage.READY
            } catch (e: OutOfMemoryError) {
                error = "Se quedó sin memoria. Cerrá otras apps e intentá con una canción más corta."
                stage = Stage.IDLE
            } catch (e: Throwable) {
                error = "Error: ${e.message}"
                stage = Stage.IDLE
            }
        }
    }

    private fun queryName(uri: Uri): String {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "cancion"
            }
        return "cancion"
    }

    // ---------------- reproducción ----------------

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        isPlaying = p.isPlaying
    }

    fun seekTo(sec: Float) {
        player?.seek((sec * StemPlayer.SR).toLong())
        positionSec = sec
    }

    fun refreshPosition() {
        val p = player ?: return
        isPlaying = p.isPlaying
        positionSec = p.positionFrames().toFloat() / StemPlayer.SR
    }

    fun applyGains() {
        val anySolo = solo.any { it }
        val g = FloatArray(4) { i ->
            when {
                anySolo -> if (solo[i]) volumes[i] else 0f
                muted[i] -> 0f
                else -> volumes[i]
            }
        }
        player?.gains = g
    }

    fun applyTempoPitch() {
        player?.setSpeedPitch(speed, semitones)
    }

    // ---------------- exportar ----------------

    fun exportAll() {
        val p = player ?: return
        val g = player?.gains ?: return
        exporting = true
        exportMsg = "Exportando…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = WavExporter.exportAll(
                    getApplication(), songName,
                    Stems.NAMES.map { File(sessionDir, "$it.pcm") }, g, p.totalFrames
                ) { exportMsg = "Exportando… ${(it * 100).toInt()}%" }
                exportMsg = "Listo: $n archivos WAV en Descargas/StemSplit"
            } catch (e: Throwable) {
                exportMsg = "No se pudo exportar: ${e.message}"
            }
            exporting = false
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        isPlaying = false
    }

    override fun onCleared() {
        releasePlayer()
    }
}
