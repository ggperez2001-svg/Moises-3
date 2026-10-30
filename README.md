# StemSplit

App Android que separa voz, batería, bajo y otros instrumentos de una canción **en el propio celular**, con mezclador, cambio de tono y velocidad, detección de acordes y exportación a WAV. Pensada para Samsung Galaxy A15 y A31 (Android 10+, arm64).

> Estado: el código **no fue compilado ni probado en un celular** (el entorno donde se escribió no tiene Android SDK ni internet). Sí se verificó en simulación (Python) que el procesamiento de audio por bloques reconstruye bien la señal y que la detección de acordes acierta en una progresión sintética. Es esperable que el primer build necesite algún ajuste.

## Cómo obtener el APK (sin instalar Android Studio)

1. Creá un repositorio en GitHub (puede ser privado) y subí todo el contenido de esta carpeta, incluida `.github/`.
2. Entrá a la pestaña **Actions** → **Build APK** → **Run workflow** (también corre solo al subir a `main`).
3. Cuando termine, bajá el artefacto **StemSplit-apk** (contiene `app-release.apk`).
4. Pasalo al celular, abrilo y permití "instalar apps de orígenes desconocidos" cuando Android lo pida.

Si el build falla, copiame el error del log y lo corrijo.

Alternativa: abrir la carpeta en Android Studio y usar *Build > Build APK(s)*.

## El modelo de separación (importante)

La app no trae el modelo dentro. Hay que cargarlo la primera vez (botón **Importar modelo**, o pegando una URL y **Descargar modelo**). Queda guardado en el celular.

Debe ser un `.tflite` estilo **Spleeter 4 stems** con este contrato:

- Entrada: 1 tensor `float32` de 512 × 1024 × 2 (frames × bins × canales). Es la magnitud del STFT con ventana Hann de 4096 y salto de 1024, a 44.1 kHz.
- Salida: 4 tensores `float32` del mismo tamaño, en orden **voz, batería, bajo, otros** (magnitud estimada de cada instrumento).

No pude verificar un modelo concreto ni su enlace de descarga. Si el modelo que consigas tiene otras formas de entrada o salida, la app muestra el error exacto y se adapta en `Separator.kt`.

Nota de calidad: como en Spleeter, el modelo solo ve hasta ~11 kHz, así que las pistas separadas quedan sin agudos por encima de eso.

## Qué esperar en el A15 y el A31

- Una canción de 3-4 minutos puede tardar varios minutos en separarse (más en el A31). Dejá la app abierta y la pantalla encendida mientras procesa.
- El audio se procesa por bloques de ~12 s y se guarda en el disco, así que el uso de memoria es constante. Hace falta espacio libre: unos 50 MB por minuto de canción.
- Los acordes se detectan sobre bajo + otros, y son una estimación (solo mayores y menores).
- El cambio de tono y velocidad es solo de reproducción. Al exportar no se aplica.

## Estructura

- `AudioDecoder.kt`: decodifica mp3/m4a/wav/flac/ogg a PCM 44.1 kHz estéreo.
- `Separator.kt`: STFT, red neuronal TFLite, máscara y reconstrucción por bloques.
- `ChordDetector.kt`: cromagrama + plantillas + Viterbi.
- `StemPlayer.kt`: reproductor con ganancia por pista, tono y velocidad.
- `WavExporter.kt`: exporta a Descargas/StemSplit.
- `ModelManager.kt`, `MainViewModel.kt`, `MainActivity.kt`: modelo e interfaz.
