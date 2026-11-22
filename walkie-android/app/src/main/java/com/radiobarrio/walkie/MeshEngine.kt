package com.radiobarrio.walkie

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Estado compartido entre el servicio y la pantalla. */
object Mesh {
    data class Node(
        val id: Int, val name: String, val lat: Double, val lon: Double, val acc: Float,
        val battery: Int, val hops: Int, val direct: Boolean, val neighbors: List<Int>, val lastSeen: Long,
        val contact: Boolean = false, val alias: String = "",
        val sos: Boolean = false,   // activó su alarma de pánico (lo ven todos, también desconocidos)
        val quiet: Boolean = false, // en reposo: sigue en el radar y recibe mensajes, pero no voz
    ) {
        val hasLoc get() = !lat.isNaN() && !lon.isNaN()
    }

    data class Chat(
        val key: Long, val fromId: Int, val fromName: String, val toId: Int, val toName: String?,
        val text: String, val time: Long, val mine: Boolean,
        val conv: String = "pub",   // conversación: "pub" (barrio), "c:<id>" (privado), "g:<id>" (grupo)
    )

    data class Call(val fromId: Int, val fromName: String, val time: Long)

    /** Búsqueda en curso (yo busco a un contacto con Encuéntrame). */
    data class Find(
        val id: Int, val name: String,
        val ringing: Boolean = false,   // su teléfono confirmó que está sonando
        val refused: Boolean = false,   // no me dio permiso
        val stopped: Boolean = false,   // él detuvo el sonido ("estoy bien")
        val battery: Int = -1, val lastAck: Long = 0,
        val rssi: Int = 0, val rssiAt: Long = 0,   // señal Bluetooth (dBm)
        val audible: Float = 0f, val ultra: Float = 0f,   // pitidos oídos por mi micrófono (0..1)
    )

    /** Alerta de pánico de un contacto. */
    data class Sos(
        val id: Int, val name: String, val lat: Double, val lon: Double, val acc: Float, val battery: Int,
        val time: Long, val active: Boolean,
    ) {
        val hasLoc get() = !lat.isNaN() && !lon.isNaN()
    }

    data class State(
        val running: Boolean = false,
        val myId: Int = 0,
        val name: String = "",
        val myAlias: String = "",
        val myLat: Double = Double.NaN,
        val myLon: Double = Double.NaN,
        val myAcc: Float = 0f,
        val nodes: List<Node> = emptyList(),
        val chat: List<Chat> = emptyList(),
        val unread: Map<String, Int> = emptyMap(),   // sin leer por conversación
        val transmitting: Boolean = false,
        val txTarget: Int = 0,
        val txSeconds: Int = 0,
        val level: Float = 0f,
        val speaker: String? = null,
        val speakerPrivate: Boolean = false,
        val speakerGroup: String? = null,
        val call: Call? = null,
        val error: String? = null,
        val demo: Boolean = false,
        val ahorro: Boolean = false,      // modo ahorro activo ahora
        val ahorroModo: Int = 0,          // 0 automático, 1 siempre, 2 nunca
        val bateria: Int = -1,
        val reposo: Boolean = false,      // "apagado liviano": sin voz ni llamados, todo lo demás sigue
        // pánico
        val panic: Boolean = false,           // mi alarma está activa
        val panicCountdown: Int = 0,          // segundos para activarse (desde la notificación)
        val panicAcks: List<String> = emptyList(),   // contactos que recibieron mi alerta
        val sos: List<Sos> = emptyList(),     // alertas recibidas
        val alertSounding: Boolean = false,
        // encuéntrame
        val find: Find? = null,               // yo estoy buscando a alguien
        val foundBy: String? = null,          // alguien me está buscando (mi teléfono suena)
    ) {
        val hasLoc get() = !myLat.isNaN() && !myLon.isNaN()
        val direct get() = nodes.count { it.direct }
    }

    val state = MutableStateFlow(State())

    @Volatile var engine: MeshEngine? = null
    @Volatile var uiVisible = false
    @Volatile var openConv: String? = null   // conversación abierta en pantalla
}

/**
 * Malla de teléfonos sin servidor: cada uno se conecta directo a los que tiene cerca
 * (Bluetooth / Wi-Fi Direct vía Nearby Connections) y reenvía los mensajes de los demás.
 */
class MeshEngine(private val ctx: Context, private val notifs: Notifs) {
    companion object {
        private const val SERVICE_ID = "com.radiobarrio.malla"
        private const val NAME_PREFIX = "RB2"
        private const val BEACON_MS = 4_000L
        private const val NODE_TTL_MS = 25_000L
        private const val BUSY_MS = 1_500L
        private const val MAX_TX_MS = 60_000L
        private const val CONNECT_FALLBACK_MS = 6_000L

        // Modo ahorro: en vez de buscar vecinos sin parar, busca por ratos
        const val SAVE_SCAN_MS = 15_000L        // ventana de búsqueda
        const val SAVE_CYCLE_MS = 90_000L       // cada cuánto, si ya hay vecinos
        const val SAVE_LONELY_MS = 45_000L      // cada cuánto, si estamos solos
        const val AUTO_BATTERY = 30             // automático: ahorrar bajo este %
    }

    private val prefs = ctx.getSharedPreferences("walkie", Context.MODE_PRIVATE)
    init { Ident.init(ctx); Grupos.init(ctx); Historial.init(ctx) }
    val myId: Int get() = Ident.myId
    private val name: String get() = Ident.name

    private val client = Nearby.getConnectionsClient(ctx)
    private val pm = ctx.getSystemService(PowerManager::class.java)
    private val alarm = ctx.getSystemService(AlarmManager::class.java)
    private val alarmIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, MeshAlarm::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    private fun lock(tag: String) = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply { setReferenceCounted(false) }
    private val wakeLock = lock("walkie:malla")   // modo normal: CPU siempre despierta
    private val cycleLock = lock("walkie:ciclo")  // modo ahorro: solo durante la búsqueda
    private val voiceLock = lock("walkie:voz")    // mientras suena o se transmite voz
    private val sirena = Sirena(ctx)
    private val ble = BleFinder(ctx)
    private val sonido = SoundFinder()
    @Volatile private var searching = 0       // id del contacto que busco
    @Volatile private var foundBy = 0         // id del contacto que me busca
    private var lastFindKeep = 0L
    @Volatile private var panic = false
    @Volatile private var countdown = 0
    @Volatile private var savingMode = prefs.getInt("ahorroModo", 0)
    @Volatile private var saving = false
    @Volatile private var reposo = prefs.getBoolean("reposo", false)
    val isSaving: Boolean get() = saving
    @Volatile private var discovering = false
    private var scanUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val msgCounter = AtomicInteger(Random.nextInt())
    @Volatile private var running = false

    // Conexiones directas
    private val connected = ConcurrentHashMap<String, Int>()     // endpointId -> nodo
    private val incoming = ConcurrentHashMap<String, Pair<Int, String>>()
    private class Found(val id: Int, val name: String, val at: Long)
    private val found = ConcurrentHashMap<String, Found>()
    private val requested = ConcurrentHashMap<String, Long>()

    // Nodos conocidos (directos o a varios saltos)
    private class NodeRec(
        var name: String, var lat: Double = Double.NaN, var lon: Double = Double.NaN, var acc: Float = 0f,
        var battery: Int = -1, var hops: Int = 1, var neighbors: IntArray = IntArray(0), var seen: Long = 0,
        var lastBeacon: Long = 0, var gap: Long = 0, var sos: Boolean = false, var quiet: Boolean = false,
    )
    private val nodes = ConcurrentHashMap<Int, NodeRec>()
    private val demoNodes = mutableListOf<Mesh.Node>()

    // Mensajes ya vistos (para no reenviar dos veces)
    private val seen = object : LinkedHashMap<Long, Boolean>(4096, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 4000
    }

    // Ubicación
    @Volatile private var myLoc: Location? = null
    private val locListener = LocationListener { onLocation(it) }

    // Voz
    @Volatile private var transmitting = false
    @Volatile private var txTarget = 0
    private var txThread: Thread? = null
    @Volatile private var talker = 0
    @Volatile private var lastAudioAt = 0L
    @Volatile private var endRequested = false
    private var lastSeq = 0
    private val queue = LinkedBlockingQueue<ShortArray>()
    private var playThread: Thread? = null

    private val rogerBeep = ShortArray(MeshProto.SAMPLE_RATE / 7) { i ->
        val n = MeshProto.SAMPLE_RATE / 7
        val f = if (i < n / 2) 1300.0 else 900.0
        val env = minOf(1.0, minOf(i, n - i) / 60.0)
        (sin(2 * PI * f * i / MeshProto.SAMPLE_RATE) * 6000 * env).toInt().toShort()
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun displayName() = name.ifBlank { "Vecino ${Integer.toHexString(myId).takeLast(4)}" }
    // Lo que anuncia el teléfono por radio: solo el id (nunca el nombre real)
    private fun endpointName() = "$NAME_PREFIX|${Integer.toHexString(myId)}"

    private fun parseEndpoint(n: String): Pair<Int, String>? {
        val parts = n.split("|")
        if (parts.size < 2 || parts[0] != NAME_PREFIX) return null
        val id = parts[1].toLongOrNull(16)?.toInt() ?: return null
        return id to Alias.of(id)
    }

    // ================================================================ ciclo de vida
    fun start() {
        if (running) return
        running = true
        Mesh.state.update {
            it.copy(running = true, myId = myId, name = name, myAlias = Ident.alias, error = null,
                ahorroModo = savingMode, reposo = reposo, chat = Historial.load())
        }
        startAdvertising()
        lastEnsure = now()
        playThread = thread(name = "malla-play") { playLoop() }
        applySaving(shouldSave(), force = true)
    }

    fun stop() {
        running = false
        transmitting = false
        main.removeCallbacksAndMessages(null)
        alarm.cancel(alarmIntent)
        discovering = false
        try {
            client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        } catch (_: Exception) {}
        try { ctx.getSystemService(LocationManager::class.java).removeUpdates(locListener) } catch (_: Exception) {}
        playThread?.interrupt()
        sirena.stopAll()
        ble.stopAll(); sonido.stop()
        panic = false; countdown = 0; searching = 0; foundBy = 0
        for (l in listOf(wakeLock, cycleLock, voiceLock)) if (l.isHeld) l.release()
        connected.clear(); nodes.clear()
        Mesh.state.value = Mesh.State(myId = myId, name = name, myAlias = Ident.alias)
    }

    private val tick = object : Runnable {
        var n = 0L
        override fun run() {
            if (!running || saving) return
            if (n % 15 == 0L) {          // cada 30 s: ¿hay que pasar a ahorro?
                evaluate()
                if (saving) return
            }
            if (n % (BEACON_MS / 2000) == 0L) sendBeacon()
            if (panic && n % 15 == 0L) sendSos(true)     // repetir la alerta cada 30 s
            if (searching != 0 && n % 10 == 0L) sendFind(true)   // "sigue sonando" cada 20 s
            if (foundBy != 0 && now() - lastFindKeep > 10 * 60_000L) stopBeingFound()
            housekeeping()
            n++
            main.postDelayed(this, 2000)
        }
    }

    private fun housekeeping() {
        ensureRadio()
        deliverPending()
        val t = now()
        // Si el otro no nos conectó, lo intentamos nosotros
        for ((ep, f) in found) {
            if (connected.containsKey(ep)) continue
            val req = requested[ep]
            if (t - f.at > CONNECT_FALLBACK_MS && (req == null || t - req > 20_000)) requestConn(ep)
        }
        // Un vecino que se anuncia lento (modo ahorro) se espera más antes de borrarlo
        nodes.entries.removeIf { (id, r) ->
            val ttl = max(NODE_TTL_MS, minOf(r.gap * 3 + 10_000, 900_000L))
            t - r.seen > ttl && id !in connected.values
        }
        publishNodes()
    }

    /** Entrega invitaciones aceptadas y claves nuevas a contactos que estén al alcance. */
    private val lastPendingSent = HashMap<String, Long>()
    private fun deliverPending() {
        val t = now()
        for (p in Ident.pending.value) {
            if (!nodes.containsKey(p.dest)) continue
            val k = "${p.dest}:${p.kind}"
            if (t - (lastPendingSent[k] ?: 0L) < 20_000) continue
            lastPendingSent[k] = t
            val c = Ident.contact(p.dest) ?: run { Ident.done(p.dest, p.kind); null } ?: continue
            when (p.kind) {
                "pair" -> {
                    val (ty, id, d) = newMsg(MeshProto.T_PAIR_REQ, p.dest)
                    val base = MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, inviteId = p.inviteId)
                    val blob = Crypto.seal(c.pairKey, Ident.pairRequestPlain(), MeshProto.aadFor(base))
                    send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, inviteId = p.inviteId, blob = blob))
                }
                "key" -> {
                    val (ty, id, d) = newMsg(MeshProto.T_KEY, p.dest)
                    send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, blob = Ident.circleKey), c.pairKey)
                }
                "grant" -> {
                    val (ty, id, d) = newMsg(MeshProto.T_GRANT, p.dest)
                    send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, blob = byteArrayOf(if (c.canFindMe) 1 else 0)), c.pairKey)
                }
                else -> if (p.kind.startsWith("g:")) {
                    val blob = p.kind.substring(2).toIntOrNull()?.let { Grupos.encodeFor(it, p.dest) }
                    if (blob == null) Ident.done(p.dest, p.kind) else {
                        val (ty, id, d) = newMsg(MeshProto.T_GROUP, p.dest)
                        send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, blob = blob), c.pairKey)
                    }
                }
            }
        }
    }

    // ================================================================ modo ahorro
    /** (porcentaje, enchufado) según el aviso de batería del sistema. */
    private fun battery(): Pair<Int, Boolean> = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        (if (lvl < 0) -1 else lvl * 100 / scale) to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    } catch (_: Exception) { -1 to false }

    private fun shouldSave(): Boolean = if (panic || countdown > 0 || searching != 0 || foundBy != 0) false
    else if (reposo) true else when (savingMode) {
        1 -> true
        2 -> false
        else -> {
            val (lvl, charging) = battery()
            pm.isPowerSaveMode || (!charging && lvl in 0..AUTO_BATTERY)
        }
    }

    private fun evaluate() {
        Mesh.state.update { it.copy(bateria = battery().first) }
        applySaving(shouldSave())
    }

    /**
     * Reposo ("apagar" liviano): el teléfono sigue en la malla en modo ahorro —aparece en el radar,
     * reenvía, recibe mensajes, alertas de pánico y "Encuéntrame"— pero no recibe voz ni llamados.
     */
    fun setReposo(on: Boolean) {
        reposo = on
        prefs.edit().putBoolean("reposo", on).apply()
        if (on) {
            transmitting = false
            talker = 0; queue.clear()
            Mesh.state.update { it.copy(reposo = true, speaker = null, call = null) }
        } else Mesh.state.update { it.copy(reposo = false) }
        if (running) {
            evaluate()
            notifs.updateService(saving, reposo)
            sendBeacon()
        }
    }

    fun setSavingMode(m: Int) {
        savingMode = m
        prefs.edit().putInt("ahorroModo", m).apply()
        Mesh.state.update { it.copy(ahorroModo = m) }
        if (running) evaluate()
    }

    /**
     * Normal: busca vecinos sin parar, se anuncia cada 4 s, GPS preciso.
     * Ahorro: busca 15 s cada 45–90 s, se anuncia una vez por ciclo, ubicación aproximada,
     * y deja dormir al teléfono. Lo que llega por conexiones ya hechas (mensajes, llamados, voz)
     * sigue llegando al instante.
     */
    private fun applySaving(on: Boolean, force: Boolean = false) {
        if (!running || (!force && on == saving)) return
        saving = on
        main.removeCallbacks(tick)
        main.removeCallbacks(windowTick)
        if (on) {
            stopDiscovery()
            if (wakeLock.isHeld) wakeLock.release()
            startLocation()
            runCycle()
        } else {
            alarm.cancel(alarmIntent)
            if (cycleLock.isHeld) cycleLock.release()
            wakeLock.acquire()
            startDiscovery()
            startLocation()
            main.post(tick)
        }
        Mesh.state.update { it.copy(ahorro = on, bateria = battery().first) }
        notifs.updateService(on, reposo)
    }

    private fun runCycle() {
        if (!running || !saving) return
        cycleLock.acquire(SAVE_SCAN_MS + 15_000)
        evaluate()
        if (!saving) return
        sendBeacon()
        startDiscovery()
        scanUntil = now() + SAVE_SCAN_MS
        main.removeCallbacks(windowTick)
        main.post(windowTick)
    }

    private val windowTick = object : Runnable {
        override fun run() {
            if (!running || !saving) return
            housekeeping()
            if (now() < scanUntil) {
                main.postDelayed(this, 2000); return
            }
            stopDiscovery()
            val wait = if (connected.isEmpty()) SAVE_LONELY_MS else SAVE_CYCLE_MS
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + wait, alarmIntent,
            )
            if (cycleLock.isHeld) cycleLock.release()
        }
    }

    /** Lo llama la alarma del sistema (despierta al teléfono para el siguiente ciclo). */
    fun onAlarm() = runCycle()

    /** Al abrir la app en modo ahorro, buscar vecinos de inmediato. */
    fun onUiShown() {
        if (running && saving && now() >= scanUntil) {
            alarm.cancel(alarmIntent)
            runCycle()
        }
    }

    // ================================================================ Nearby
    private fun startAdvertising() {
        client.startAdvertising(
            endpointName(), SERVICE_ID, lifecycleCb, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnFailureListener { e ->
            if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) return@addOnFailureListener
            error("No se pudo anunciar este teléfono: ${e.message}")
        }
    }

    /**
     * Si Servicios de Google se reinicia (p. ej. al actualizarse), se pierden el anuncio y la búsqueda
     * sin aviso. Cada ~30 s se vuelven a pedir; si siguen activos, Nearby responde "ya activo".
     */
    private var lastEnsure = 0L
    private fun ensureRadio() {
        val t = now()
        if (t - lastEnsure < 28_000) return
        lastEnsure = t
        startAdvertising()
        if (!saving) startDiscovery(force = true)
    }

    private fun startDiscovery(force: Boolean = false) {
        if (discovering && !force) return
        discovering = true
        client.startDiscovery(
            SERVICE_ID, discoveryCb, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnSuccessListener {
            Mesh.state.update { st -> if (st.error?.startsWith("No se pudo buscar") == true) st.copy(error = null) else st }
        }.addOnFailureListener { e ->
            if ((e as? ApiException)?.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return@addOnFailureListener
            discovering = false
            // Con la app cerrada (p. ej. tras reiniciar el teléfono) Android no deja usar la ubicación:
            // se puede ser encontrado pero no buscar. Se reintenta solo al abrir la app; no es un error.
            if (Mesh.uiVisible) error("No se pudo buscar vecinos: ${e.message}")
        }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try { client.stopDiscovery() } catch (_: Exception) {}
    }

    private fun error(msg: String) {
        Mesh.state.update { it.copy(error = "$msg. Revisa que Bluetooth, Wi‑Fi y Ubicación estén activados.") }
    }

    private fun requestConn(ep: String) {
        if (!running || connected.containsKey(ep)) return
        requested[ep] = now()
        client.requestConnection(endpointName(), ep, lifecycleCb)
            .addOnFailureListener { requested.remove(ep) }
    }

    private val discoveryCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (id, n) = parseEndpoint(info.endpointName) ?: return
            if (id == myId || id in connected.values) return
            found[endpointId] = Found(id, n, now())
            // Solo uno de los dos pide la conexión, para no chocar
            if (myId < id) requestConn(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
        }
    }

    private val lifecycleCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val p = parseEndpoint(info.endpointName)
            if (p == null || p.first == myId) {
                client.rejectConnection(endpointId); return
            }
            incoming[endpointId] = p
            client.acceptConnection(endpointId, payloadCb)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val p = incoming.remove(endpointId)
            requested.remove(endpointId)
            if (result.status.isSuccess && p != null) {
                connected[endpointId] = p.first
                found.remove(endpointId)
                val r = nodes.getOrPut(p.first) { NodeRec(p.second) }
                r.hops = 1; r.seen = now()
                sendBeacon()
                publishNodes()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            publishNodes()
        }
    }

    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { onRaw(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    // ================================================================ enrutamiento
    private fun markSeen(key: Long): Boolean = synchronized(seen) {
        if (seen.containsKey(key)) false else { seen[key] = true; true }
    }

    private fun onRaw(fromEp: String, raw: ByteArray) {
        if (raw.size < 16 || raw[0].toInt() != MeshProto.MAGIC || raw[1].toInt() != MeshProto.VERSION) return
        val bb = java.nio.ByteBuffer.wrap(raw)
        val ttl = raw[3].toInt() and 0xFF
        val origin = bb.getInt(4); val msgId = bb.getInt(8); val dest = bb.getInt(12)
        val key = (origin.toLong() shl 32) or (msgId.toLong() and 0xFFFFFFFFL)
        if (origin == myId || !markSeen(key)) return
        // Lo que no es para mí (o no puedo descifrar) igual se reenvía: así funciona el puente
        if (dest == 0 || dest == myId || Grupos.get(dest) != null) {
            MeshProto.decode(raw) { o, d ->
                if (d != 0 && d != myId) Grupos.get(d)?.key else Ident.contact(o)?.pairKey
            }?.let { handle(it, raw) }
        }
        nodes[origin]?.let { it.seen = now() }
        if (ttl > 1 && dest != myId) {
            val targets = connected.keys.filter { it != fromEp }
            if (targets.isNotEmpty()) client.sendPayload(targets, Payload.fromBytes(MeshProto.forRelay(raw)))
        }
    }

    private fun send(m: MeshProto.Msg, seal: ByteArray? = null) {
        markSeen(m.key)
        val targets = connected.keys.toList()
        if (targets.isNotEmpty()) client.sendPayload(targets, Payload.fromBytes(MeshProto.encode(m, seal)))
    }

    private fun newMsg(type: Int, dest: Int = 0) = Triple(type, msgCounter.incrementAndGet(), dest)

    private fun handle(m: MeshProto.Msg, raw: ByteArray) {
        val forMe = m.dest == 0 || m.dest == myId
        val from = nodeName(m.origin)
        when (m.type) {
            MeshProto.T_BEACON -> {
                val r = nodes.getOrPut(m.origin) { NodeRec(Alias.of(m.origin)) }
                r.neighbors = m.neighbors; r.seen = now()
                if (m.sosFlag && !r.sos) vibrate(longArrayOf(0, 300, 150, 300))
                r.sos = m.sosFlag
                r.quiet = m.quiet
                if (r.lastBeacon > 0) r.gap = r.seen - r.lastBeacon
                r.lastBeacon = r.seen
                r.hops = if (m.origin in connected.values) 1 else MeshProto.MAX_TTL - m.ttl + 1
                // Solo un contacto puede leer nombre, ubicación y batería
                val c = Ident.contact(m.origin)
                val p = c?.let { Crypto.open(it.circleKey, m.priv, originAad(m.origin)) }?.let { MeshProto.decodePriv(it) }
                if (p != null) {
                    r.name = p.name; r.lat = p.lat; r.lon = p.lon; r.acc = p.acc; r.battery = p.battery
                } else {
                    r.name = Alias.of(m.origin); r.lat = Double.NaN; r.lon = Double.NaN; r.battery = -1
                }
            }
            MeshProto.T_CHAT -> if (forMe && (m.dest == 0 || m.sealed)) {
                val priv = m.dest != 0
                incoming(
                    Mesh.Chat(m.key, m.origin, from, m.dest, if (!priv) null else displayName(), m.text,
                        System.currentTimeMillis(), false, if (priv) "c:${m.origin}" else "pub"),
                    if (priv) "🔒 $from" else "📢 $from · Barrio (público)",
                )
            }
            MeshProto.T_GCHAT -> {
                val g = Grupos.get(m.dest)
                if (g != null && m.sealed) {
                    val quien = Ident.contact(m.origin)?.name ?: Grupos.memberName(g.id, m.origin) ?: m.sender.ifBlank { from }
                    incoming(
                        Mesh.Chat(m.key, m.origin, quien, g.id, g.name, m.text, System.currentTimeMillis(), false, "g:${g.id}"),
                        "👥 $quien · ${g.name}",
                    )
                }
            }
            MeshProto.T_GROUP -> if (m.dest == myId && m.sealed) {
                val c = Ident.contact(m.origin)
                val r = c?.let { Grupos.onGroup(m.origin, m.blob) }
                if (c != null && r != null) {
                    val (g, nuevo, sacado) = r
                    val (ty, id, d) = newMsg(MeshProto.T_GROUP_ACK, m.origin)
                    send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d,
                        blob = java.nio.ByteBuffer.allocate(4).putInt(g.id).array()), c.pairKey)
                    if (nuevo) notifs.info("👥 $from te agregó al grupo «${g.name}»", "Ya pueden escribirse y hablar en el grupo")
                    if (sacado) notifs.info("👥 Ya no estás en el grupo «${g.name}»", "$from te sacó o eliminó el grupo")
                }
            }
            MeshProto.T_GROUP_ACK -> if (m.dest == myId && m.sealed && m.blob.size == 4) {
                Ident.done(m.origin, "g:${java.nio.ByteBuffer.wrap(m.blob).int}")
            }
            MeshProto.T_CALL -> if (m.dest == myId && !reposo) {
                Mesh.state.update { it.copy(call = Mesh.Call(m.origin, from, System.currentTimeMillis())) }
                notifs.call(from)
                vibrate(longArrayOf(0, 400, 200, 400, 200, 400))
            }
            MeshProto.T_VOICE -> if (!reposo && (m.dest == 0 || m.sealed) && (forMe || Grupos.get(m.dest) != null)) {
                onVoice(m, Grupos.memberName(m.dest, m.origin)?.takeIf { Ident.contact(m.origin) == null } ?: from)
            }
            MeshProto.T_VOICE_END -> if (m.origin == talker) { endRequested = true; lastAudioAt = 0 }
            MeshProto.T_PAIR_REQ -> if (m.dest == myId) {
                val c = Ident.onPairRequest(m.origin, m.inviteId, m.blob, MeshProto.aad(raw)) ?: return
                val (ty, id, d) = newMsg(MeshProto.T_PAIR_ACK, c.id)
                send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, blob = Ident.pairRequestPlain()), c.pairKey)
                notifs.info("🤝 ${c.name} ahora es tu contacto", "Ya pueden verse con su nombre real")
                vibrate(longArrayOf(0, 80, 80, 80))
                nodes[c.id]?.name = c.name
                sendBeacon()
            }
            MeshProto.T_PAIR_ACK -> if (m.dest == myId && m.sealed) {
                val antes = Ident.contact(m.origin)?.confirmed ?: return
                Ident.onPairAck(m.origin, m.blob)
                if (!antes) {
                    notifs.info("🤝 ${Ident.display(m.origin)} confirmó el contacto", "Ya pueden verse con su nombre real")
                    vibrate(longArrayOf(0, 80, 80, 80))
                }
            }
            MeshProto.T_KEY -> if (m.dest == myId && m.sealed && m.blob.size == 16) {
                Ident.onNewKey(m.origin, m.blob)
                val c = Ident.contact(m.origin) ?: return
                val (ty, id, d) = newMsg(MeshProto.T_KEY_ACK, m.origin)
                send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d), c.pairKey)
            }
            MeshProto.T_KEY_ACK -> if (m.dest == myId && m.sealed) Ident.done(m.origin, "key")
            MeshProto.T_SOS -> if (m.dest == myId && m.sealed) onSos(m)
            MeshProto.T_FIND -> if (m.dest == myId && m.sealed) onFind(m)
            MeshProto.T_FIND_ACK -> if (m.dest == myId && m.sealed && m.origin == searching && m.blob.size >= 2) {
                val est = m.blob[0].toInt()
                Mesh.state.update { st ->
                    st.find?.let { f ->
                        st.copy(find = f.copy(ringing = est == 1, stopped = est == 0, refused = est == 2,
                            battery = m.blob[1].toInt(), lastAck = System.currentTimeMillis()))
                    } ?: st
                }
            }
            MeshProto.T_GRANT -> if (m.dest == myId && m.sealed && m.blob.isNotEmpty()) {
                val on = m.blob[0].toInt() == 1
                val antes = Ident.contact(m.origin)?.iCanFind
                Ident.onGrant(m.origin, on)
                Ident.contact(m.origin)?.let { c ->
                    val (ty, id, d) = newMsg(MeshProto.T_GRANT_ACK, m.origin)
                    send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d), c.pairKey)
                }
                if (on && antes != true) notifs.info("📍 $from te dio permiso para buscarlo",
                    "En una emergencia podrás hacer sonar su teléfono con «Encuéntrame»")
            }
            MeshProto.T_GRANT_ACK -> if (m.dest == myId && m.sealed) Ident.done(m.origin, "grant")
            MeshProto.T_SOS_ACK -> if (m.dest == myId && m.sealed && panic) {
                Mesh.state.update { st -> st.copy(panicAcks = (st.panicAcks + from).distinct()) }
            }
        }
        publishNodes()
    }

    /** El beacon cifrado queda atado a su dueño (no se puede reenviar haciéndose pasar por otro). */
    private fun originAad(id: Int) = java.nio.ByteBuffer.allocate(4).putInt(id).array()

    // ================================================================ acciones del usuario
    fun setName(n: String) {
        Ident.setName(n)
        Mesh.state.update { it.copy(name = name) }
        sendBeacon()
    }

    private fun sendBeacon() {
        val l = myLoc
        val (t, id, d) = newMsg(MeshProto.T_BEACON)
        val bat = battery().first
        val priv = MeshProto.encodePriv(
            MeshProto.Priv(displayName(), l?.latitude ?: Double.NaN, l?.longitude ?: Double.NaN, l?.accuracy ?: 0f, bat)
        )
        send(
            MeshProto.Msg(
                t, MeshProto.MAX_TTL, myId, id, d, neighbors = connected.values.distinct().toIntArray(),
                priv = Crypto.seal(Ident.circleKey, priv, originAad(myId)), sosFlag = panic, quiet = reposo,
            )
        )
    }

    /** conv: "pub" = barrio (público), "c:<id>" = privado a un contacto, "g:<id>" = grupo. Privados y grupos van cifrados. */
    fun sendChat(text: String, conv: String) {
        val t = text.trim().take(1000)
        if (t.isEmpty()) return
        val n = conv.substringAfter(':', "0").toIntOrNull() ?: return
        val (m, seal, toName) = when {
            conv == "pub" -> Triple(newMsg(MeshProto.T_CHAT, 0).let { (ty, id, d) ->
                MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, text = t) }, null, null)
            conv.startsWith("c:") -> {
                val c = Ident.contact(n) ?: return
                Triple(newMsg(MeshProto.T_CHAT, n).let { (ty, id, d) ->
                    MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, text = t) }, c.pairKey, nodeName(n))
            }
            conv.startsWith("g:") -> {
                val g = Grupos.get(n) ?: return
                Triple(newMsg(MeshProto.T_GCHAT, n).let { (ty, id, d) ->
                    MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, text = t, sender = displayName()) }, g.key, g.name)
            }
            else -> return
        }
        send(m, seal)
        Mesh.state.update {
            it.copy(chat = (it.chat + Mesh.Chat(m.key, myId, displayName(), m.dest, toName, t,
                System.currentTimeMillis(), true, conv)).takeLast(500))
        }
        Historial.save(Mesh.state.value.chat)
    }

    private fun incoming(c: Mesh.Chat, title: String) {
        val viendo = Mesh.uiVisible && Mesh.openConv == c.conv
        Mesh.state.update { st ->
            st.copy(
                chat = (st.chat + c).takeLast(500),
                unread = if (viendo) st.unread else st.unread + (c.conv to (st.unread[c.conv] ?: 0) + 1),
            )
        }
        Historial.save(Mesh.state.value.chat)
        if (!viendo) notifs.chat(title, c.text, c.conv)
        vibrate(longArrayOf(0, 60))
    }

    fun sendCall(dest: Int) {
        val (type, id, d) = newMsg(MeshProto.T_CALL, dest)
        send(MeshProto.Msg(type, MeshProto.MAX_TTL, myId, id, d))
        vibrate(longArrayOf(0, 40))
    }

    fun clearCall() = Mesh.state.update { it.copy(call = null) }
    fun markChatRead(conv: String) = Mesh.state.update { it.copy(unread = it.unread - conv) }
    fun nodeName(id: Int): String =
        demoNodes.firstOrNull { it.id == id }?.name ?: Ident.contact(id)?.let { c -> nodes[id]?.name?.takeIf { it != Alias.of(id) } ?: c.name } ?: Alias.of(id)

    /** Recalcular nombres en pantalla (p. ej. al agregar o quitar un contacto). */
    fun refresh() = publishNodes()

    // ================================================================ pánico
    /** Activa la alarma. Con segundos > 0 hay una cuenta regresiva que se puede cancelar. */
    fun startPanic(segundos: Int = 0) {
        if (!running || panic || countdown > 0) return
        if (segundos <= 0) { activatePanic(); return }
        countdown = segundos
        applySaving(false)
        main.post(cuenta)
    }

    private val cuenta = object : Runnable {
        override fun run() {
            if (countdown <= 0) return
            Mesh.state.update { it.copy(panicCountdown = countdown) }
            notifs.panic(false, countdown)
            vibrate(longArrayOf(0, 150))
            countdown--
            if (countdown == 0) activatePanic() else main.postDelayed(this, 1000)
        }
    }

    private fun activatePanic() {
        countdown = 0
        main.removeCallbacks(cuenta)
        panic = true
        applySaving(false)
        sirena.play(Sirena.Tipo.PANICO)
        sirena.linternaSos(true)
        vibrate(longArrayOf(0, 600, 200, 600))
        Mesh.state.update { it.copy(panic = true, panicCountdown = 0, panicAcks = emptyList()) }
        notifs.panic(true, 0)
        sendBeacon()
        sendSos(true)
    }

    /** Detiene la alarma (o cancela la cuenta regresiva) y avisa a los contactos. */
    fun stopPanic() {
        main.removeCallbacks(cuenta)
        val estaba = panic
        panic = false; countdown = 0
        sirena.stopAll()
        if (foundBy != 0) { sirena.play(Sirena.Tipo.BUSCAME); sirena.linternaSos(true) }
        Mesh.state.update { it.copy(panic = false, panicCountdown = 0) }
        notifs.cancelPanic()
        if (estaba) {
            sendBeacon()                                   // los desconocidos dejan de ver el 🚨
            sendSos(false)
            main.postDelayed({ sendSos(false) }, 5_000)   // por si alguno no lo recibió
        }
        evaluate()
    }

    /** Alerta cifrada, una copia para cada contacto (solo ellos la pueden leer). */
    private fun sendSos(active: Boolean) {
        val l = myLoc
        val plain = byteArrayOf(if (active) 1 else 0) + MeshProto.encodePriv(
            MeshProto.Priv(displayName(), l?.latitude ?: Double.NaN, l?.longitude ?: Double.NaN, l?.accuracy ?: 0f, battery().first)
        )
        for (c in Ident.contacts.value.values) {
            val (ty, id, d) = newMsg(MeshProto.T_SOS, c.id)
            send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d, blob = plain), c.pairKey)
        }
    }

    private fun onSos(m: MeshProto.Msg) {
        if (m.blob.isEmpty()) return
        val active = m.blob[0].toInt() == 1
        val p = MeshProto.decodePriv(m.blob.copyOfRange(1, m.blob.size)) ?: return
        val name = Ident.display(m.origin)
        val prev = Mesh.state.value.sos.firstOrNull { it.id == m.origin }
        val sos = Mesh.Sos(m.origin, name, p.lat, p.lon, p.acc, p.battery, System.currentTimeMillis(), active)
        Mesh.state.update { st -> st.copy(sos = st.sos.filterNot { it.id == m.origin } + sos) }
        if (active) {
            Ident.contact(m.origin)?.let { c ->
                val (ty, id, d) = newMsg(MeshProto.T_SOS_ACK, m.origin)
                send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, id, d), c.pairKey)
            }
            // Suena solo al comienzo de cada alerta (las repeticiones solo actualizan la ubicación)
            if (prev == null || !prev.active) {
                if (!panic) sirena.play(Sirena.Tipo.ALERTA, maxMs = 45_000)
                Mesh.state.update { it.copy(alertSounding = !panic) }
                main.postDelayed({ Mesh.state.update { it.copy(alertSounding = sirena.sonando && !panic) } }, 46_000)
                vibrate(longArrayOf(0, 700, 300, 700, 300, 700))
                notifs.sos(m.origin, "🚨 $name PIDE AYUDA", sosTexto(sos))
            }
        } else if (prev?.active == true) {
            if (Mesh.state.value.sos.none { it.active }) silenceAlerts()
            notifs.sos(m.origin, "✅ $name desactivó su alerta", "Ya no está sonando su alarma")
        }
    }

    private fun sosTexto(s: Mesh.Sos): String {
        val l = myLoc
        val dist = if (s.hasLoc && l != null) {
            val d = Geo.distance(l.latitude, l.longitude, s.lat, s.lon)
            "A ${Geo.fmt(d)} hacia el ${Geo.compass(Geo.bearing(l.latitude, l.longitude, s.lat, s.lon))}"
        } else if (s.hasLoc) "Ubicación recibida" else "Sin ubicación GPS"
        return dist + if (s.battery >= 0) " · batería ${s.battery}%" else ""
    }

    fun silenceAlerts() {
        if (!panic) sirena.stopSound()
        Mesh.state.update { it.copy(alertSounding = false) }
    }

    fun dismissSos(id: Int) {
        Mesh.state.update { st -> st.copy(sos = st.sos.filterNot { it.id == id }) }
        notifs.cancelSos(id)
        if (Mesh.state.value.sos.none { it.active }) silenceAlerts()
    }

    // ================================================================ encuéntrame
    /** Empiezo a buscar a un contacto que me dio permiso: su teléfono suena y yo mido la cercanía. */
    fun startFind(id: Int) {
        val c = Ident.contact(id) ?: return
        if (!running || !c.iCanFind) return
        if (searching != 0 && searching != id) stopFind()
        searching = id
        applySaving(false)
        Mesh.state.update { it.copy(find = Mesh.Find(id, c.name)) }
        ble.startScan(Ident.findToken(c)) { rssi ->
            Mesh.state.update { st ->
                st.find?.let { f ->
                    val suave = if (f.rssi == 0 || now() - f.rssiAt > 5000) rssi else (f.rssi * 0.6 + rssi * 0.4).toInt()
                    st.copy(find = f.copy(rssi = suave, rssiAt = now()))
                } ?: st
            }
        }
        var ultimo = 0L
        sonido.start { a, u ->
            val t = now()
            if (t - ultimo < 200 && a + u > 0f) return@start   // 5 veces por segundo basta para la pantalla
            ultimo = t
            Mesh.state.update { st -> st.find?.let { st.copy(find = it.copy(audible = a, ultra = u)) } ?: st }
        }
        sendFind(true)
    }

    fun stopFind() {
        val id = searching
        searching = 0
        ble.stopScan(); sonido.stop()
        Mesh.state.update { it.copy(find = null) }
        if (id != 0) {
            sendFind(false, id)
            main.postDelayed({ sendFind(false, id) }, 3_000)
        }
        evaluate()
    }

    private fun sendFind(on: Boolean, id: Int = searching) {
        val c = Ident.contact(id) ?: return
        val (ty, mid, d) = newMsg(MeshProto.T_FIND, id)
        send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, mid, d, blob = byteArrayOf(if (on) 1 else 0)), c.pairKey)
    }

    private fun onFind(m: MeshProto.Msg) {
        val c = Ident.contact(m.origin) ?: return
        val on = m.blob.firstOrNull()?.toInt() == 1
        if (!on) { if (foundBy == m.origin) stopBeingFound(tell = false); return }
        if (!c.canFindMe) { replyFind(m.origin, 2); return }
        lastFindKeep = now()
        if (foundBy != m.origin) {
            foundBy = m.origin
            applySaving(false)
            if (!panic) sirena.play(Sirena.Tipo.BUSCAME)
            sirena.linternaSos(true)
            ble.startBeacon(Ident.findToken(c))
            vibrate(longArrayOf(0, 500, 200, 500))
            Mesh.state.update { it.copy(foundBy = c.name) }
            notifs.found(c.name)
            sendBeacon()
        }
        replyFind(m.origin, 1)
    }

    /** El buscado detiene el sonido ("estoy bien") o se acaba el tiempo. */
    fun stopBeingFound(tell: Boolean = true) {
        val id = foundBy
        if (id == 0) return
        foundBy = 0
        if (!panic) sirena.stopAll()
        ble.stopBeacon()
        notifs.cancelFound()
        Mesh.state.update { it.copy(foundBy = null) }
        if (tell) replyFind(id, 0)
        evaluate()
    }

    private fun replyFind(id: Int, estado: Int) {
        val c = Ident.contact(id) ?: return
        val (ty, mid, d) = newMsg(MeshProto.T_FIND_ACK, id)
        send(MeshProto.Msg(ty, MeshProto.MAX_TTL, myId, mid, d,
            blob = byteArrayOf(estado.toByte(), battery().first.toByte())), c.pairKey)
    }

    fun setCanFindMe(id: Int, on: Boolean) {
        Ident.setCanFindMe(id, on)
        lastPendingSent.remove("$id:grant")
        deliverPending()
    }

    // ================================================================ voz
    /** Devuelve false si el canal está ocupado. dest = 0 habla a todos. */
    fun startTx(dest: Int): Boolean {
        if (!running || reposo) return false
        if (dest != 0 && Ident.contact(dest) == null && Grupos.get(dest) == null) return false
        if (transmitting) return true
        if (talker != 0 && now() - lastAudioAt < BUSY_MS) {
            vibrate(longArrayOf(0, 60, 60, 60)); return false
        }
        txTarget = dest
        transmitting = true
        queue.clear()
        vibrate(longArrayOf(0, 35))
        Mesh.state.update { it.copy(transmitting = true, txTarget = dest, txSeconds = 0, speaker = null, error = null) }
        val prev = txThread
        txThread = thread(name = "malla-tx") { txLoop(prev) }
        return true
    }

    fun stopTx() { transmitting = false }

    @SuppressLint("MissingPermission")
    private fun txLoop(prev: Thread?) {
        prev?.join(500)
        voiceLock.acquire(MAX_TX_MS + 5_000)
        val seal = if (txTarget == 0) null else Ident.contact(txTarget)?.pairKey ?: Grupos.get(txTarget)?.key
        var rec: AudioRecord? = null
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        var seq = Random.nextInt(0xFFFF)
        try {
            val rate = MeshProto.SAMPLE_RATE * 2 // grabamos a 16 kHz y bajamos a 8 kHz
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf, MeshProto.FRAME_SAMPLES * 2 * 2 * 4),
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED)
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true }
            rec.startRecording()
            val pcm16k = ShortArray(MeshProto.FRAME_SAMPLES * 2)
            val ulaw = ByteArray(MeshProto.FRAME_SAMPLES)
            val t0 = now()
            while (running && transmitting) {
                val elapsed = now() - t0
                if (elapsed > MAX_TX_MS) break
                var read = 0
                while (read < pcm16k.size) {
                    val r = rec.read(pcm16k, read, pcm16k.size - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < pcm16k.size) break
                var peak = 0
                for (i in 0 until MeshProto.FRAME_SAMPLES) {
                    val s = (pcm16k[2 * i] + pcm16k[2 * i + 1]) / 2
                    peak = max(peak, abs(s))
                    ulaw[i] = MeshProto.linearToUlaw(s)
                }
                seq = (seq + 1) and 0xFFFF
                val (type, id, d) = newMsg(MeshProto.T_VOICE, txTarget)
                send(MeshProto.Msg(type, MeshProto.MAX_TTL, myId, id, d, seq = seq, audio = ulaw.copyOf()), seal)
                val secs = (elapsed / 1000).toInt()
                Mesh.state.update { it.copy(level = peak / 32768f, txSeconds = secs) }
            }
        } catch (_: Exception) {
            Mesh.state.update { it.copy(error = "No se pudo usar el micrófono") }
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            ns?.release(); agc?.release(); rec?.release()
        }
        val (type, id, d) = newMsg(MeshProto.T_VOICE_END, txTarget)
        send(MeshProto.Msg(type, MeshProto.MAX_TTL, myId, id, d), seal)
        transmitting = false
        if (voiceLock.isHeld) voiceLock.release()
        Mesh.state.update { it.copy(transmitting = false, level = 0f, txSeconds = 0) }
    }

    private fun onVoice(m: MeshProto.Msg, from: String) {
        if (transmitting) return
        voiceLock.acquire(5_000)   // que el teléfono no se duerma a media frase
        val t = now()
        val busy = talker != 0 && t - lastAudioAt < BUSY_MS
        if (busy && m.origin != talker) return
        if (!busy) {
            talker = m.origin
            lastSeq = (m.seq - 1) and 0xFFFF
            endRequested = false
            Mesh.state.update { it.copy(speaker = from, speakerPrivate = m.dest == myId, speakerGroup = Grupos.get(m.dest)?.name) }
        }
        val d = (m.seq - lastSeq) and 0xFFFF
        if (d == 0 || d >= 0x8000) return
        lastSeq = m.seq
        lastAudioAt = t
        queue.offer(ShortArray(m.audio.size) { MeshProto.ULAW_TO_LINEAR[m.audio[it].toInt() and 0xFF] })
        while (queue.size > 10) queue.poll()
    }

    private fun playLoop() {
        val rate = MeshProto.SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(max(minBuf, MeshProto.FRAME_SAMPLES * 2 * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        var playing = false
        try {
            while (running) {
                if (!playing) {
                    val f = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    val t0 = now()
                    while (running && queue.size < 2 && now() - t0 < 250) Thread.sleep(10)
                    playing = true
                    track.write(f, 0, f.size)
                } else {
                    val f = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (f != null) {
                        track.write(f, 0, f.size)
                    } else if (endRequested || now() - lastAudioAt > BUSY_MS) {
                        playing = false
                        endRequested = false
                        talker = 0
                        track.write(rogerBeep, 0, rogerBeep.size)
                        Mesh.state.update { it.copy(speaker = null, speakerPrivate = false, speakerGroup = null) }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    // ================================================================ ubicación
    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val lm = ctx.getSystemService(LocationManager::class.java)
        try { lm.removeUpdates(locListener) } catch (_: Exception) {}
        // Ahorro: sin GPS propio; ubicación por red cada 2 min y la que pidan otras apps (gratis)
        val plan = if (saving) listOf(
            Triple(LocationManager.NETWORK_PROVIDER, 120_000L, 25f),
            Triple(LocationManager.PASSIVE_PROVIDER, 30_000L, 10f),
        ) else listOf(
            Triple(LocationManager.GPS_PROVIDER, 3_000L, 1f),
            Triple(LocationManager.NETWORK_PROVIDER, 3_000L, 1f),
        )
        for ((p, ms, m) in plan) {
            if (p !in lm.allProviders) continue
            try {
                lm.getLastKnownLocation(p)?.let { onLocation(it) }
                lm.requestLocationUpdates(p, ms, m, locListener, Looper.getMainLooper())
            } catch (_: Exception) {}
        }
    }

    private fun onLocation(l: Location) {
        val cur = myLoc
        val better = cur == null || l.provider == LocationManager.GPS_PROVIDER ||
            l.accuracy <= cur.accuracy || l.time - cur.time > 20_000
        if (!better) return
        myLoc = l
        if (!Mesh.state.value.demo || Mesh.state.value.myLat.isNaN()) {
            Mesh.state.update { it.copy(myLat = l.latitude, myLon = l.longitude, myAcc = l.accuracy) }
        }
    }

    // ================================================================ publicar a la pantalla
    private fun publishNodes() {
        val direct = connected.values.toSet()
        val list = nodes.map { (id, r) ->
            val c = Ident.contact(id)
            Mesh.Node(
                id, if (c != null) (r.name.takeIf { n -> !n.startsWith(Alias.of(id)) } ?: c.name) else Alias.of(id),
                r.lat, r.lon, r.acc, r.battery,
                if (id in direct) 1 else r.hops, id in direct, r.neighbors.toList(), r.seen,
                contact = c != null, alias = Alias.of(id), sos = r.sos, quiet = r.quiet,
            )
        } + demoNodes
        Mesh.state.update { it.copy(nodes = list.sortedWith(compareBy({ it.hops }, { it.name }))) }
    }

    /** Modo demostración: 3 vecinos simulados en línea (tú — Ana — Pedro — Sede). */
    fun toggleDemo() {
        val on = !Mesh.state.value.demo
        demoNodes.clear()
        if (on) {
            val st = Mesh.state.value
            val baseLat = if (st.hasLoc) st.myLat else -36.8270
            val baseLon = if (st.hasLoc) st.myLon else -73.0498
            fun at(m: Double, bearing: Double): Pair<Double, Double> {
                val b = bearing * PI / 180
                val dLat = m * cos(b) / 111_320.0
                val dLon = m * sin(b) / (111_320.0 * cos(baseLat * PI / 180))
                return (baseLat + dLat) to (baseLon + dLon)
            }
            val t = now()
            val (aLat, aLon) = at(70.0, 40.0)
            val (pLat, pLon) = at(150.0, 70.0)
            val (sLat, sLon) = at(240.0, 95.0)
            demoNodes += Mesh.Node(-11, "Ana", aLat, aLon, 8f, 64, 1, true, listOf(myId, -12), t)
            demoNodes += Mesh.Node(-12, "Pedro", pLat, pLon, 12f, 31, 2, false, listOf(-11, -13), t)
            demoNodes += Mesh.Node(-13, "Sede vecinal", sLat, sLon, 5f, 90, 3, false, listOf(-12), t)
            demoNodes += Mesh.Node(-14, "🦝 Mapache feliz", Double.NaN, Double.NaN, 0f, -1, 2, false, listOf(-11), t, false, "🦝 Mapache feliz")
            Mesh.state.update { it.copy(demo = true, myLat = baseLat, myLon = baseLon) }
        } else {
            val l = myLoc
            Mesh.state.update {
                it.copy(demo = false, myLat = l?.latitude ?: Double.NaN, myLon = l?.longitude ?: Double.NaN)
            }
        }
        publishNodes()
    }

    private fun vibrate(pattern: LongArray) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }
}
