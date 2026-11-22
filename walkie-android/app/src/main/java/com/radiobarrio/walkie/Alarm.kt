package com.radiobarrio.walkie

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sonidos de emergencia por el canal de ALARMA (suena aunque el teléfono esté en silencio)
 * y la linterna parpadeando "SOS" en Morse.
 */
class Sirena(private val ctx: Context) {
    enum class Tipo { PANICO, ALERTA, BUSCAME }

    companion object {
        const val RATE = 22050
        const val RATE_HI = 44100        // para el tono de ultrasonido
        const val TONO_AUDIBLE = 3000.0  // Hz: lo que mejor emite un parlante de teléfono y mejor oye una persona
        const val TONO_ULTRA = 18500.0   // Hz: casi inaudible; sirve de pista extra en espacios abiertos
        const val CONTINUO_MS = 120_000L   // después de 2 min de sirena continua, por ratos (ahorra batería)
        const val ON_MS = 4_000L
        const val OFF_MS = 8_000L
    }

    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var volAntes = -1
    @Volatile private var linterna = false
    private var hiloLinterna: Thread? = null

    /** Sirena característica: sube y baja (650 ↔ 1500 Hz), con armónicos para que se oiga fuerte. */
    private val panico: ShortArray by lazy {
        val n = (RATE * 2.4).toInt()
        var fase = 0.0
        ShortArray(n) { i ->
            val t = i.toDouble() / n
            val f = 650 + 850 * (if (t < 0.5) t * 2 else (1 - t) * 2)
            fase += 2 * PI * f / RATE
            val s = sin(fase) + 0.35 * sin(3 * fase) + 0.15 * sin(5 * fase)
            (s / 1.5 * 30000).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Aviso para quien recibe: tres pitidos agudos y una pausa. */
    private val alerta: ShortArray by lazy {
        val n = (RATE * 1.4).toInt()
        ShortArray(n) { i ->
            val ms = i * 1000 / RATE
            val enPitido = ms < 750 && ms % 250 < 160
            if (!enPitido) 0.toShort() else {
                val env = minOf(1.0, (ms % 250) / 8.0, (160 - ms % 250) / 8.0)
                val s = sin(2 * PI * 1900 * i / RATE) + 0.3 * sin(2 * PI * 3800 * i / RATE)
                (s * env * 24000).toInt().toShort()
            }
        }
    }

    /**
     * "Encuéntrame": 3 pitidos de 3 kHz + un tono de 18,5 kHz, y una pausa (ciclo de 3 s).
     * El buscador los detecta con el micrófono; las personas oyen los pitidos.
     */
    private val buscame: ShortArray by lazy {
        val n = RATE_HI * 3
        ShortArray(n) { i ->
            val ms = i * 1000 / RATE_HI
            val (f, ini, fin) = when {
                ms < 900 && ms % 300 < 220 -> Triple(TONO_AUDIBLE, ms - ms % 300, ms - ms % 300 + 220)
                ms in 1100..1700 -> Triple(TONO_ULTRA, 1100, 1700)
                else -> Triple(0.0, 0, 0)
            }
            if (f == 0.0) 0.toShort() else {
                val env = minOf(1.0, (ms - ini) / 10.0, (fin - ms) / 10.0)
                (sin(2 * PI * f * i / RATE_HI) * env * 30000).toInt().toShort()
            }
        }
    }

    @Synchronized
    fun play(tipo: Tipo, maxMs: Long = 0) {
        stopSound()
        volumenAlMaximo()
        val pcm = when (tipo) { Tipo.PANICO -> panico; Tipo.ALERTA -> alerta; Tipo.BUSCAME -> buscame }
        val rate = if (tipo == Tipo.BUSCAME) RATE_HI else RATE
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
        if (tipo == Tipo.PANICO) main.postDelayed(porRatos, CONTINUO_MS)
        if (maxMs > 0) main.postDelayed({ stopSound() }, maxMs)
    }

    // Sirena por ratos: 4 s sonando, 8 s en silencio
    private val porRatos = object : Runnable {
        var sonando = true
        override fun run() {
            val t = track ?: return
            try { if (sonando) t.pause() else t.play() } catch (_: Exception) {}
            sonando = !sonando
            main.postDelayed(this, if (sonando) ON_MS else OFF_MS)
        }
    }

    val sonando: Boolean get() = track != null

    @Synchronized
    fun stopSound() {
        main.removeCallbacksAndMessages(null)
        porRatos.sonando = true
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        track = null
        if (volAntes >= 0) {
            try { am.setStreamVolume(AudioManager.STREAM_ALARM, volAntes, 0) } catch (_: Exception) {}
            volAntes = -1
        }
    }

    private fun volumenAlMaximo() {
        try {
            if (volAntes < 0) volAntes = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ linterna SOS (· · · — — — · · ·)
    fun linternaSos(on: Boolean) {
        if (on == linterna) return
        linterna = on
        if (!on) { hiloLinterna?.interrupt(); return }
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null } ?: return
        hiloLinterna = thread(name = "linterna-sos") {
            val p = 200L
            val patron = listOf(1, 1, 1, 3, 3, 3, 1, 1, 1)   // unidades de luz
            try {
                while (linterna) {
                    patron.forEachIndexed { i, u ->
                        cm.setTorchMode(id, true); Thread.sleep(p * u)
                        cm.setTorchMode(id, false); Thread.sleep(if (i == 2 || i == 5) p * 3 else p)
                    }
                    Thread.sleep(p * 7)
                }
            } catch (_: Exception) {
            } finally {
                try { cm.setTorchMode(id, false) } catch (_: Exception) {}
            }
        }
    }

    fun stopAll() {
        stopSound()
        linternaSos(false)
    }
}
