package com.stemsplit.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppScreen() }
            }
        }
    }
}

private fun fmt(sec: Float): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun AppScreen(vm: MainViewModel = viewModel()) {
    val activity = LocalContext.current as Activity
    val busy = vm.stage == MainViewModel.Stage.DECODING ||
        vm.stage == MainViewModel.Stage.SEPARATING ||
        vm.stage == MainViewModel.Stage.CHORDS

    DisposableEffect(busy) {
        if (busy) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(vm.stage) {
        while (vm.stage == MainViewModel.Stage.READY) {
            vm.refreshPosition()
            delay(200)
        }
    }

    val songPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadSong(uri)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    var modelUrl by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("StemSplit", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Separá voz, batería, bajo y otros instrumentos. Todo se procesa en tu celular.", fontSize = 14.sp)

        // ---------- modelo ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (vm.modelReady) "Modelo de separación: listo ✔" else "Modelo de separación: falta cargarlo",
                    fontWeight = FontWeight.SemiBold
                )
                if (!vm.modelReady || vm.modelBusy) {
                    Text("Importá el archivo .tflite o descargalo desde una URL (ver README).", fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = !vm.modelBusy && !busy) {
                        Text(if (vm.modelReady) "Cambiar modelo" else "Importar modelo")
                    }
                }
                OutlinedTextField(
                    value = modelUrl,
                    onValueChange = { modelUrl = it },
                    label = { Text("URL del modelo (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { vm.downloadModel(modelUrl) }, enabled = !vm.modelBusy && !busy) {
                    Text("Descargar modelo")
                }
                if (vm.modelBusy) {
                    LinearProgressIndicator(progress = { vm.modelProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // ---------- elegir canción ----------
        Button(
            onClick = { songPicker.launch(arrayOf("audio/*")) },
            enabled = vm.modelReady && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Elegir canción") }

        vm.error?.let {
            Text(it, color = Color(0xFFFF8A80))
        }

        if (busy) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.songName, fontWeight = FontWeight.SemiBold)
                    Text(vm.message)
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Dejá la app abierta y la pantalla encendida hasta que termine.", fontSize = 12.sp)
                }
            }
        }

        if (vm.stage == MainViewModel.Stage.READY) {
            PlayerSection(vm)
        }
    }
}

@Composable
private fun PlayerSection(vm: MainViewModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }

    Text(vm.songName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

    // transporte
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.togglePlay() }) {
                    Text(if (vm.isPlaying) "⏸  Pausa" else "▶  Reproducir")
                }
                Spacer(Modifier.width(12.dp))
                Text("${fmt(dragging ?: vm.positionSec)} / ${fmt(vm.durationSec)}")
            }
            Slider(
                value = (dragging ?: vm.positionSec).coerceIn(0f, vm.durationSec),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.seekTo(it) }
                    dragging = null
                },
                valueRange = 0f..vm.durationSec
            )
        }
    }

    // acordes
    ChordSection(vm)

    // mezclador
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Mezclador", fontWeight = FontWeight.SemiBold)
            for (i in 0 until 4) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Stems.LABELS[i], Modifier.weight(1f))
                    FilterChip(
                        selected = vm.muted[i],
                        onClick = { vm.muted[i] = !vm.muted[i]; vm.applyGains() },
                        label = { Text("M") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = vm.solo[i],
                        onClick = { vm.solo[i] = !vm.solo[i]; vm.applyGains() },
                        label = { Text("S") }
                    )
                }
                Slider(
                    value = vm.volumes[i],
                    onValueChange = { vm.volumes[i] = it; vm.applyGains() },
                    valueRange = 0f..1.5f
                )
            }
        }
    }

    // tono y velocidad
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Velocidad: ${"%.2f".format(vm.speed)}x")
            Slider(
                value = vm.speed,
                onValueChange = { vm.speed = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { vm.applyTempoPitch() },
                valueRange = 0.5f..1.5f
            )
            Text("Tono: ${if (vm.semitones > 0) "+" else ""}${vm.semitones} semitonos")
            Slider(
                value = vm.semitones.toFloat(),
                onValueChange = { vm.semitones = it.roundToInt() },
                onValueChangeFinished = { vm.applyTempoPitch() },
                valueRange = -6f..6f,
                steps = 11
            )
            TextButton(onClick = {
                vm.speed = 1f
                vm.semitones = 0
                vm.applyTempoPitch()
            }) { Text("Restablecer") }
        }
    }

    // exportar
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Exportar", fontWeight = FontWeight.SemiBold)
            Text(
                "Guarda cada pista y la mezcla actual (con tus volúmenes, mute y solo) como WAV en Descargas/StemSplit. " +
                    "No incluye el cambio de tono ni de velocidad.",
                fontSize = 13.sp
            )
            Button(onClick = { vm.exportAll() }, enabled = !vm.exporting) { Text("Exportar pistas (WAV)") }
            vm.exportMsg?.let { Text(it, fontSize = 13.sp) }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ChordSection(vm: MainViewModel) {
    val chords = vm.chords
    val currentIdx = chords.indexOfFirst { vm.positionSec >= it.startSec && vm.positionSec < it.endSec }
    val listState = rememberLazyListState()

    LaunchedEffect(currentIdx) {
        if (currentIdx >= 0) listState.animateScrollToItem(maxOf(0, currentIdx - 1))
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Acordes", fontWeight = FontWeight.SemiBold)
            Text(
                if (currentIdx >= 0) chords[currentIdx].name else "—",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
            if (chords.isEmpty()) {
                Text("No se detectaron acordes.", fontSize = 13.sp)
            } else {
                LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(chords) { idx, c ->
                        FilterChip(
                            selected = idx == currentIdx,
                            onClick = { vm.seekTo(c.startSec) },
                            label = { Text("${c.name}  ${fmt(c.startSec)}") }
                        )
                    }
                }
                Text("Tocá un acorde para saltar a ese momento. Es una estimación (solo mayores y menores).", fontSize = 12.sp)
            }
        }
    }
}
