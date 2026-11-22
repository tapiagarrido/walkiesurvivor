package com.radiobarrio.walkie

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelUuid
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Radiobaliza Bluetooth para "Encuéntrame": el teléfono buscado emite una señal BLE con una
 * ficha que solo su contacto reconoce; el buscador mide su intensidad (RSSI) = frío / caliente.
 */
@SuppressLint("MissingPermission")
class BleFinder(ctx: Context) {
    companion object {
        val SERVICIO: ParcelUuid = ParcelUuid.fromString("00005742-0000-1000-8000-00805f9b34fb")

        /** Distancia aproximada (m) a partir del RSSI. Muy aproximada: paredes y escombros la alteran. */
        fun metros(rssi: Int): Double = 10.0.pow((-62.0 - rssi) / (10 * 2.5))
    }

    private val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var adv: AdvertiseCallback? = null
    private var scan: ScanCallback? = null

    fun startBeacon(token: ByteArray) {
        stopBeacon()
        val a = bt?.bluetoothLeAdvertiser ?: return
        val cb = object : AdvertiseCallback() {}
        try {
            a.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).build(),
                AdvertiseData.Builder().addServiceUuid(SERVICIO).addServiceData(SERVICIO, token)
                    .setIncludeDeviceName(false).build(),
                cb,
            )
            adv = cb
        } catch (_: Exception) {}
    }

    fun stopBeacon() {
        adv?.let { try { bt?.bluetoothLeAdvertiser?.stopAdvertising(it) } catch (_: Exception) {} }
        adv = null
    }

    fun startScan(token: ByteArray, onRssi: (Int) -> Unit) {
        stopScan()
        val s = bt?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val d = r.scanRecord?.getServiceData(SERVICIO) ?: return
                if (d.contentEquals(token)) onRssi(r.rssi)
            }
        }
        try {
            s.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(SERVICIO).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb,
            )
            scan = cb
        } catch (_: Exception) {}
    }

    fun stopScan() {
        scan?.let { try { bt?.bluetoothLeScanner?.stopScan(it) } catch (_: Exception) {} }
        scan = null
    }

    fun stopAll() { stopBeacon(); stopScan() }
}

/**
 * Escucha con el micrófono los pitidos de "Encuéntrame" (3 kHz audible y 18,5 kHz ultrasonido)
 * y entrega qué tan fuerte se oye cada uno (0..1), comparado con el ruido de alrededor.
 */
@SuppressLint("MissingPermission")
class SoundFinder {
    @Volatile private var on = false
    private var hilo: Thread? = null

    fun start(onLevel: (audible: Float, ultra: Float) -> Unit) {
        if (on) return
        on = true
        hilo = thread(name = "escucha-encuentrame") {
            val rate = Sirena.RATE_HI
            val n = 2048                                   // ~46 ms
            val buf = ShortArray(n)
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                // Sin filtros de voz (que borrarían los pitidos); si el teléfono no lo permite, micrófono normal
                fun abrir(src: Int) = try {
                    AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, n * 4))
                        .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } catch (_: Exception) { null }
                rec = abrir(MediaRecorder.AudioSource.UNPROCESSED) ?: abrir(MediaRecorder.AudioSource.MIC) ?: return@thread
                rec.startRecording()
                var picoA = 0f; var picoU = 0f
                while (on) {
                    var r = 0
                    while (r < n && on) { val k = rec.read(buf, r, n - r); if (k <= 0) break; r += k }
                    if (r < n) break
                    val a = snr(buf, rate, Sirena.TONO_AUDIBLE, 2400.0, 3600.0)
                    val u = snr(buf, rate, Sirena.TONO_ULTRA, 17300.0, 19700.0)
                    // Los pitidos van y vienen: se retiene el máximo y cae despacio
                    picoA = max(a, picoA * 0.93f); picoU = max(u, picoU * 0.93f)
                    onLevel(picoA, picoU)
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                onLevel(0f, 0f)
            }
        }
    }

    fun stop() { on = false; hilo = null }

    /** Potencia del tono comparada con frecuencias vecinas (dB), llevada a 0..1 (0 dB → 0, 30 dB → 1). */
    private fun snr(x: ShortArray, rate: Int, f: Double, ref1: Double, ref2: Double): Float {
        val p = goertzel(x, rate, f)
        val ruido = (goertzel(x, rate, ref1) + goertzel(x, rate, ref2)) / 2 + 1.0
        val db = 10 * log10(p / ruido + 1e-9)
        return (db / 30.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun goertzel(x: ShortArray, rate: Int, f: Double): Double {
        val w = 2 * PI * f / rate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s = v + c * s1 - s2; s2 = s1; s1 = s }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }
}
