package com.radiobarrio.walkie

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.annotation.SuppressLint
import android.net.Uri
import android.provider.Settings
import android.os.PowerManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Verde = Color(0xFF3FB27F)
private val Rojo = Color(0xFFD6333A)
private val Ambar = Color(0xFFE0A526)
private val Fondo = Color(0xFF111418)
private val Panel = Color(0xFF1B2027)
private val Panel2 = Color(0xFF242B34)
private val Suave = Color(0xFF9AA5B1)

class MainActivity : ComponentActivity(), SensorEventListener {
    private val prefs by lazy { getSharedPreferences("walkie", MODE_PRIVATE) }
    private var tab by mutableStateOf("radar")
    private var chatConv by mutableStateOf<String?>(null)     // conversación abierta (null = lista)
    private var walkieConv by mutableStateOf<String?>(null)   // canal de voz elegido (null = elegir; nunca "todos" por defecto)
    private var editGroup by mutableStateOf<Int?>(null)       // 0 = grupo nuevo
    private var infoGroup by mutableStateOf<Int?>(null)
    private var selected by mutableStateOf<Int?>(null)
    private var heading by mutableFloatStateOf(0f)
    private var northUp by mutableStateOf(false)
    private var toggleMode by mutableStateOf(false)
    private var volumePtt by mutableStateOf(false)
    private var askName by mutableStateOf(false)
    private var askAhorro by mutableStateOf(false)
    private var askApagar by mutableStateOf(false)
    private var segundoPlanoOk by mutableStateOf(true)
    private var inviteCode by mutableStateOf<String?>(null)       // invitación que muestro
    private var askPaste by mutableStateOf(false)
    private var incoming by mutableStateOf<InviteCode?>(null)       // invitación por aceptar
    private var removing by mutableStateOf<Contact?>(null)

    private val scanLauncher = registerForActivityResult(ScanContract()) { r -> r.contents?.let { onCodeText(it) } }
    private var sensorManager: SensorManager? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!granted(Manifest.permission.RECORD_AUDIO))
            Toast.makeText(this, "Sin micrófono podrás escuchar y escribir, pero no hablar", Toast.LENGTH_LONG).show()
        startMesh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        toggleMode = prefs.getBoolean("toggleMode", false)
        volumePtt = prefs.getBoolean("volumePtt", false)
        northUp = prefs.getBoolean("northUp", false)
        askName = prefs.getString("name", "").isNullOrBlank()
        Ident.init(this)
        if (Mesh.engine == null) Mesh.state.value = Mesh.State(name = Ident.name, myAlias = Ident.alias)
        intent?.getStringExtra("tab")?.let { tab = it }
        intent?.getStringExtra("conv")?.let { chatConv = it }
        handleShare(intent)
        sensorManager = getSystemService(SensorManager::class.java)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Verde, onPrimary = Color.White, background = Fondo, surface = Panel)) {
                val st by Mesh.state.collectAsStateWithLifecycle()
                Pantalla(st)
            }
        }
        pedirPermisosYEncender()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("tab")?.let { tab = it }
        intent.getStringExtra("conv")?.let { chatConv = it }
        handleShare(intent)
    }

    // ---------------------------------------------------------------- invitaciones
    /** Un mensaje compartido a la app (p. ej. desde WhatsApp: mantener presionado → Compartir). */
    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { onCodeText(it) }
    }

    private fun onCodeText(text: String) {
        val c = Ident.parseCode(text)
        if (c == null) {
            Toast.makeText(this, "No encontré un código de invitación válido", Toast.LENGTH_LONG).show(); return
        }
        tab = "contactos"
        incoming = c
    }

    private fun escanear() = scanLauncher.launch(
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Apunta al código QR de tu vecino")
            .setBeepEnabled(false).setOrientationLocked(false)
    )

    private fun compartir(code: String) {
        val texto = "📻 Te invito a mi círculo de confianza en Walkie Barrio.\n" +
            "Abre la app → 👥 Contactos → 📋 Pegar código, y pega este mensaje completo " +
            "(o compártelo directo a Walkie Barrio):\n\n$code\n\nVale 7 días y sirve una sola vez."
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Enviar invitación",
        ))
    }

    override fun onResume() {
        super.onResume()
        Mesh.uiVisible = true
        Mesh.engine?.onUiShown()
        revisarSegundoPlano()
        // Si la malla arrancó sola (reinicio del teléfono), al abrir la app se le suman micrófono y GPS
        if (Mesh.engine != null && granted(Manifest.permission.RECORD_AUDIO)) startMesh()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        Mesh.uiVisible = false
        sensorManager?.unregisterListener(this)
    }

    // ---------------------------------------------------------------- brújula
    private val rot = FloatArray(9)
    private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.getOrientation(rot, ori)
        val deg = ((Math.toDegrees(ori[0].toDouble()) + 360) % 360).toFloat()
        var diff = deg - heading
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        if (kotlin.math.abs(diff) > 1.5f) heading = (heading + diff * 0.3f + 360) % 360
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------------------------------------------------------------- permisos
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun pedirPermisosYEncender() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) need += listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT >= 33) need += listOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val faltan = need.filterNot { granted(it) }
        if (faltan.isEmpty()) startMesh() else permLauncher.launch(faltan.toTypedArray())
    }

    private fun startMesh() {
        ContextCompat.startForegroundService(
            this, Intent(this, MeshService::class.java).putExtra(MeshService.EXTRA_UI, true),
        )
    }

    // ---------------------------------------------------------------- segundo plano
    /** ¿Android (y Xiaomi) dejarán a la malla seguir viva con la app cerrada? */
    private fun revisarSegundoPlano() {
        segundoPlanoOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private val esXiaomi get() = Build.MANUFACTURER.lowercase().let { "xiaomi" in it || "redmi" in it || "poco" in it }

    @SuppressLint("BatteryLife")
    private fun pedirSinRestriccion() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** Xiaomi tiene su propio permiso de "Inicio automático" (si no, cierra la app al limpiar la memoria). */
    private fun abrirInicioAutomatico() {
        val intentos = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in intentos) try { startActivity(i); return } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- hablar
    private fun pttDown(): Boolean {
        val e = Mesh.engine ?: run {
            Toast.makeText(this, "La malla está apagada", Toast.LENGTH_SHORT).show(); return false
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Falta permiso de micrófono", Toast.LENGTH_SHORT).show(); return false
        }
        if (Mesh.state.value.reposo) {
            Toast.makeText(this, "Estás en reposo: enciende la voz primero", Toast.LENGTH_SHORT).show(); return false
        }
        val dest = walkieConv?.let { convDest(it) } ?: run {
            Toast.makeText(this, "Primero elige con quién hablar", Toast.LENGTH_SHORT).show(); return false
        }
        val ok = e.startTx(dest)
        if (!ok) Toast.makeText(this, "Canal ocupado: espera que termine", Toast.LENGTH_SHORT).show()
        return ok
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (volumePtt && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> pttDown()
                event.action == KeyEvent.ACTION_UP -> Mesh.engine?.stopTx()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun irA(t: String) {
        tab = t
    }

    // ================================================================ UI
    @Composable
    private fun Pantalla(st: Mesh.State) {
        Surface(Modifier.fillMaxSize(), color = Fondo) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Cabecera(st)
                st.error?.let {
                    Text(it, color = Color.Black, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().background(Ambar).padding(8.dp))
                }
                st.call?.let { BannerLlamado(it) }
                if (!st.panic && st.panicCountdown == 0) st.sos.forEach { BannerSos(st, it) }
                Box(Modifier.weight(1f)) {
                    when {
                        st.panic || st.panicCountdown > 0 -> PanicoActivo(st)
                        st.foundBy != null -> TeBuscan(st.foundBy)
                        st.find != null -> PantallaBuscar(st, st.find)
                        !st.running -> Apagado()
                        tab == "radar" -> TabRadar(st)
                        tab == "chat" -> TabChat(st)
                        tab == "contactos" -> TabContactos(st)
                        else -> TabWalkie(st)
                    }
                }
                NavigationBar(containerColor = Panel) {
                    NavigationBarItem(selected = tab == "radar", onClick = { irA("radar") },
                        icon = { Text("📡", fontSize = 20.sp) }, label = { Text("Radar") })
                    NavigationBarItem(selected = tab == "chat", onClick = { irA("chat") },
                        icon = {
                            BadgedBox(badge = { val n = st.unread.values.sum(); if (n > 0) Badge { Text("$n") } }) {
                                Text("💬", fontSize = 20.sp)
                            }
                        }, label = { Text("Mensajes") })
                    NavigationBarItem(selected = tab == "walkie", onClick = { irA("walkie") },
                        icon = { Text("🎙", fontSize = 20.sp) }, label = { Text("Walkie") })
                    NavigationBarItem(selected = tab == "contactos", onClick = { irA("contactos") },
                        icon = { Text("👥", fontSize = 20.sp) }, label = { Text("Contactos") })
                }
            }
        }
        if (askName) DialogoNombre(st.name)
        if (askAhorro) DialogoAhorro(st)
        if (askApagar) DialogoApagar(st)
        inviteCode?.let { DialogoInvitar(it) }
        if (askPaste) DialogoPegar()
        incoming?.let { DialogoAceptar(it) }
        removing?.let { DialogoQuitar(it) }
        editGroup?.let { DialogoGrupo(it) }
        infoGroup?.let { DialogoInfoGrupo(it, st) }
    }

    @Composable
    private fun Cabecera(st: Mesh.State) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📻 Walkie Barrio", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                val enMalla = st.nodes.size
                Text(
                    if (!st.running) "Apagado"
                    else if (st.reposo) "💤 En reposo · $enMalla en la malla"
                    else if (enMalla == 0) "Buscando vecinos…"
                    else "${st.direct} directo${if (st.direct != 1) "s" else ""} · $enMalla en la malla",
                    color = if (st.reposo) Ambar else if (enMalla > 0) Verde else Suave, fontSize = 13.sp, maxLines = 1,
                )
            }
            if (st.running) BotonSos()
            if (st.running) TextButton(onClick = { askAhorro = true }) {
                Text(
                    (if (st.ahorro) "🔋 Ahorro" else "⚡ Normal") + if (st.bateria >= 0) " ${st.bateria}%" else "",
                    color = if (st.ahorro) Ambar else Suave, fontSize = 13.sp,
                )
            }
            if (st.running) TextButton(onClick = { askApagar = true }) {
                Text(if (st.reposo) "💤" else "Apagar", color = if (st.reposo) Ambar else Suave)
            }
        }
    }

    // ---------------------------------------------------------------- pánico
    /** Botón SOS: hay que mantenerlo 2 segundos (para no activarlo sin querer). */
    @Composable
    private fun BotonSos() {
        val scope = rememberCoroutineScope()
        var prog by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier.padding(horizontal = 2.dp).size(64.dp, 40.dp).clip(RoundedCornerShape(20.dp)).background(Rojo)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val t0 = System.currentTimeMillis()
                        val job = scope.launch {
                            while (prog < 1f) {
                                prog = ((System.currentTimeMillis() - t0) / 2000f).coerceAtMost(1f)
                                delay(30)
                            }
                            Mesh.engine?.startPanic(0) ?: Toast.makeText(this@MainActivity,
                                "La malla está apagada", Toast.LENGTH_SHORT).show()
                        }
                        tryAwaitRelease()
                        job.cancel()
                        if (prog < 1f) Toast.makeText(this@MainActivity,
                            "Mantén presionado SOS 2 segundos para activar el pánico", Toast.LENGTH_SHORT).show()
                        prog = 0f
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(prog).background(Color(0x66FFFFFF)))
            Text("SOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }

    @Composable
    private fun PanicoActivo(st: Mesh.State) {
        val n = Ident.contacts.collectAsStateWithLifecycle().value.size
        Column(
            Modifier.fillMaxSize().background(Rojo).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            if (st.panicCountdown > 0) {
                Text("🚨", fontSize = 64.sp)
                Text("Pánico en ${st.panicCountdown}…", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { Mesh.engine?.stopPanic() }, modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rojo)) {
                    Text("CANCELAR", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                return@Column
            }
            Text("🚨", fontSize = 72.sp)
            Text("PÁNICO ACTIVO", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                if (n == 0) "⚠️ No tienes contactos de confianza: solo suenan la sirena y la linterna. " +
                    "Agrega contactos en 👥 para que reciban tu alerta."
                else "Sirena y linterna encendidas. Avisando a tus $n contacto${if (n != 1) "s" else ""} " +
                    "con tu ubicación cada 30 segundos.",
                color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (n > 0) Text(
                if (st.panicAcks.isEmpty()) "Esperando que tus contactos la reciban…"
                else "✓ Recibida por: ${st.panicAcks.joinToString(", ")}",
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = { Mesh.engine?.stopPanic() }, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rojo)) {
                Text("DETENER", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // ---------------------------------------------------------------- encuéntrame
    @Composable
    private fun Medidor(titulo: String, nivel: Float, texto: String, color: Color) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row {
                Text(titulo, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(texto, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { nivel.coerceIn(0f, 1f) }, color = color,
                modifier = Modifier.fillMaxWidth().height(14.dp).padding(top = 4.dp).clip(RoundedCornerShape(7.dp)))
        }
    }

    @Composable
    private fun PantallaBuscar(st: Mesh.State, f: Mesh.Find) {
        var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { delay(1000); ahora = System.currentTimeMillis() } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📍 Buscando a ${f.name}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Button(onClick = { Mesh.engine?.stopFind() }, colors = ButtonDefaults.buttonColors(containerColor = Rojo)) {
                    Text("Terminar")
                }
            }
            val (estado, col) = when {
                f.refused -> "⛔ ${f.name} no te ha dado permiso para buscarlo" to Rojo
                f.stopped -> "✅ ${f.name} detuvo el sonido: dice que está bien" to Verde
                f.ringing -> "🔊 Su teléfono está sonando" + (if (f.battery >= 0) " · 🔋 ${f.battery}%" else "") +
                    " · respondió hace ${((ahora - f.lastAck) / 1000).coerceAtLeast(0)} s" to Verde
                else -> "📡 Enviando el aviso por la malla… esperando respuesta" to Ambar
            }
            Text(estado, color = Color.White, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.35f)).padding(10.dp))

            // 1) GPS: flecha y distancia (sirve de lejos)
            val n = st.nodes.firstOrNull { it.id == f.id }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("1 · GPS (de lejos)", color = Suave, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
                if (n != null && n.hasLoc && st.hasLoc) {
                    val d = Geo.distance(st.myLat, st.myLon, n.lat, n.lon)
                    val b = Geo.bearing(st.myLat, st.myLon, n.lat, n.lon)
                    Text("⬆", color = Ambar, fontSize = 80.sp, modifier = Modifier.rotate((b - heading).toFloat()))
                    Text("${Geo.fmt(d)} hacia el ${Geo.compass(b)}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Precisión ±${(n.acc + st.myAcc).toInt()} m · la flecha usa la brújula: sostén el teléfono plano",
                        color = Suave, fontSize = 12.sp, textAlign = TextAlign.Center)
                } else Text("Sin ubicación GPS por ahora (bajo techo o escombros el GPS falla). Usa las otras pistas.",
                    color = Suave, fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            // 2) Bluetooth: frío / caliente (unos 30 m)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                Text("2 · Señal Bluetooth (menos de ~30 m)", color = Suave, fontSize = 13.sp)
                if (f.rssiAt > 0 && android.os.SystemClock.elapsedRealtime() - f.rssiAt < 8000) {
                    val m = BleFinder.metros(f.rssi)
                    val (txt, c) = when {
                        m < 2 -> "🔥🔥 ¡Muy cerca!" to Rojo
                        m < 5 -> "🔥 Caliente" to Color(0xFFFF7A1A)
                        m < 15 -> "🌤 Tibio" to Ambar
                        else -> "❄️ Frío" to Color(0xFF5DA9E9)
                    }
                    Medidor("≈ ${m.toInt().coerceAtLeast(1)} m", ((f.rssi + 100) / 60f), txt, c)
                } else Text("Sin señal todavía. Acércate a donde crees que está.", color = Color.White, fontSize = 15.sp)
            }

            // 3) Sonido: los pitidos de su teléfono (últimos metros)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(12.dp)) {
                Text("3 · Sonido (últimos metros)", color = Suave, fontSize = 13.sp)
                Medidor("🔊 Pitidos", f.audible, if (f.audible > 0.66f) "fuerte" else if (f.audible > 0.25f) "se oye" else "—", Verde)
                Medidor("〰 Ultrasonido", f.ultra, if (f.ultra > 0.66f) "fuerte" else if (f.ultra > 0.25f) "se oye" else "—", Color(0xFF9B7BEA))
                Text("Pide silencio, quédate quieto unos segundos y gira despacio: donde la barra sube más, está más cerca. " +
                    "Escucha también con tus oídos.", color = Suave, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun TeBuscan(nombre: String) {
        Column(Modifier.fillMaxSize().background(Color(0xFFB8740F)).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("📍", fontSize = 72.sp)
            Text("$nombre te está buscando", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Tu teléfono suena y emite una señal para guiarlo hasta ti.\n\n" +
                "Si estás atrapado: deja el teléfono a la vista, quédate quieto y ahorra energía. " +
                "Si puedes, golpea algo con un ritmo de 3 golpes para que te oigan.",
                color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Button(onClick = { Mesh.engine?.stopBeingFound() }, modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB8740F))) {
                Text("Estoy bien, detener sonido", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun BannerSos(st: Mesh.State, s: Mesh.Sos) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(if (s.active) Rojo else Color(0xFF1F4D3A)).padding(12.dp)
        ) {
            Text(if (s.active) "🚨 ${s.name} PIDE AYUDA" else "✅ ${s.name} desactivó su alerta",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (s.active) {
                val donde = when {
                    s.hasLoc && st.hasLoc -> {
                        val d = Geo.distance(st.myLat, st.myLon, s.lat, s.lon)
                        "📍 A ${Geo.fmt(d)} hacia el ${Geo.compass(Geo.bearing(st.myLat, st.myLon, s.lat, s.lon))} (±${s.acc.toInt()} m)"
                    }
                    s.hasLoc -> "📍 Envió su ubicación (activa tu GPS para ver la distancia)"
                    else -> "📍 Sin ubicación GPS"
                }
                Text(donde + if (s.battery >= 0) " · 🔋 ${s.battery}%" else "", color = Color.White, fontSize = 14.sp)
                Text("Última señal: ${hora.format(Date(s.time))}", color = Color.White, fontSize = 13.sp)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.active) Button(onClick = { selected = s.id; irA("radar") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rojo)) { Text("📡 Ver en radar") }
                if (s.active && st.alertSounding) OutlinedButton(onClick = { Mesh.engine?.silenceAlerts() }) {
                    Text("🔇 Silenciar", color = Color.White)
                }
                TextButton(onClick = { Mesh.engine?.dismissSos(s.id) }) { Text("Cerrar", color = Color.White) }
            }
        }
    }

    @Composable
    private fun BannerLlamado(c: Mesh.Call) {
        Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Rojo).padding(12.dp)) {
            Text("📣 ${c.fromName} quiere comunicarse contigo", color = Color.White, fontSize = 17.sp,
                fontWeight = FontWeight.Bold)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    walkieConv = "c:${c.fromId}"; Mesh.engine?.clearCall(); irA("walkie")
                }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Rojo)) {
                    Text("🎙 Responder")
                }
                OutlinedButton(onClick = { chatConv = "c:${c.fromId}"; Mesh.engine?.clearCall(); irA("chat") }) {
                    Text("💬 Escribir", color = Color.White)
                }
                TextButton(onClick = { Mesh.engine?.clearCall() }) { Text("Ignorar", color = Color.White) }
            }
        }
    }

    @Composable
    private fun Apagado() {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("La malla está apagada", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = ::pedirPermisosYEncender, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Encender", fontSize = 18.sp)
            }
        }
    }

    @Composable
    private fun DialogoApagar(st: Mesh.State) {
        AlertDialog(
            onDismissRequest = { askApagar = false },
            title = { Text(if (st.reposo) "💤 Estás en reposo" else "¿Apagar Walkie Barrio?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "En reposo sigues en el radar de tus vecinos, te llegan mensajes y alertas de pánico, " +
                            "y tus contactos pueden hacer sonar tu teléfono si te pasa algo. " +
                            "Solo se apagan la voz (walkie) y los llamados. Gasta muy poca batería.",
                        color = Color.White, fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (st.reposo) Button(
                        onClick = { Mesh.engine?.setReposo(false); askApagar = false },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("🎙 Encender todo (con voz)", fontSize = 16.sp) }
                    else Button(
                        onClick = { Mesh.engine?.setReposo(true); askApagar = false; moveTaskToBack(true) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ambar),
                    ) { Text("💤 Pasar a reposo (recomendado)", fontSize = 16.sp, color = Color.Black) }
                    Spacer(Modifier.height(14.dp))
                    Text("Desconectar del todo", color = Rojo, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Desapareces del radar, no te llegan mensajes ni alertas y nadie podrá hacer " +
                            "sonar tu teléfono en una emergencia.",
                        color = Suave, fontSize = 13.sp,
                    )
                    TextButton(onClick = {
                        askApagar = false
                        startService(Intent(this@MainActivity, MeshService::class.java).setAction(MeshService.ACTION_OFF))
                        finish()
                    }) { Text("Desconectar igual", color = Rojo) }
                }
            },
            confirmButton = { TextButton(onClick = { askApagar = false }) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun DialogoAhorro(st: Mesh.State) {
        val opciones = listOf(
            Triple(0, "Automático (recomendado)", "Ahorra solo si la batería baja de ${MeshEngine.AUTO_BATTERY} % o el teléfono está en ahorro de energía."),
            Triple(1, "Siempre ahorrar", "Para emergencias largas: la batería dura varias veces más."),
            Triple(2, "Nunca ahorrar", "Encuentra vecinos más rápido, pero gasta más batería."),
        )
        AlertDialog(
            onDismissRequest = { askAhorro = false },
            title = { Text("🔋 Batería") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        if (st.ahorro) "Ahora: modo ahorro activo" else "Ahora: modo normal",
                        color = if (st.ahorro) Ambar else Verde, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    opciones.forEach { (m, titulo, desc) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (st.ahorroModo == m) Panel2 else Color.Transparent)
                                .clickable { Mesh.engine?.setSavingMode(m) ?: prefs.edit().putInt("ahorroModo", m).apply() }
                                .padding(10.dp),
                        ) {
                            Text(if (st.ahorroModo == m) "◉ " else "○ ", color = Verde, fontSize = 16.sp)
                            Column {
                                Text(titulo, color = Color.White, fontSize = 15.sp)
                                Text(desc, color = Suave, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "En ahorro el teléfono busca vecinos nuevos cada 1–2 minutos, se anuncia menos " +
                            "y usa ubicación aproximada. Los mensajes, llamados y la voz de los vecinos " +
                            "ya conectados siguen llegando al instante.",
                        color = Suave, fontSize = 13.sp,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { askAhorro = false }) { Text("Listo") } },
        )
    }

    // ---------------------------------------------------------------- contactos
    @Composable
    private fun TabContactos(st: Mesh.State) {
        val contactos by Ident.contacts.collectAsStateWithLifecycle()
        val pend by Ident.pending.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (!segundoPlanoOk) {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF4A3A12)).padding(14.dp)) {
                    Text("⚠️ El teléfono puede cerrar la app", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "Para seguir en el radar y recibir alertas con la app cerrada, permite que funcione " +
                            "en segundo plano." + if (esXiaomi) " En Xiaomi activa también el \"Inicio automático\"." else "",
                        color = Color.White, fontSize = 13.sp,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::pedirSinRestriccion) { Text("🔋 Permitir") }
                        if (esXiaomi) OutlinedButton(onClick = ::abrirInicioAutomatico) { Text("Inicio automático", color = Color.White) }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)) {
                Text("Tu identidad", color = Suave, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.name.ifBlank { "Sin nombre" }, color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { askName = true }) { Text("✏️ Cambiar") }
                }
                Text("👆 solo lo ven tus contactos de confianza", color = Verde, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(Ident.alias, color = Color.White, fontSize = 18.sp)
                Text("👆 lo que ven los demás: sin nombre real ni ubicación", color = Suave, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { inviteCode = Ident.newInvite() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("➕ Invitar a alguien", fontSize = 16.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = ::escanear, modifier = Modifier.weight(1f)) { Text("📷 Escanear QR", color = Color.White) }
                OutlinedButton(onClick = { askPaste = true }, modifier = Modifier.weight(1f)) { Text("📋 Pegar código", color = Color.White) }
            }

            Spacer(Modifier.height(16.dp))
            Text("Contactos de confianza (${contactos.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (contactos.isEmpty()) Text(
                "Aún no tienes contactos. Invita a tu familia y vecinos de confianza: verán tu nombre y " +
                    "tu ubicación en el radar, y podrán hablarte en privado (cifrado).",
                color = Suave, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp),
            )
            contactos.values.sortedBy { it.name.lowercase() }.forEach { c ->
                val enMalla = st.nodes.firstOrNull { it.id == c.id }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Panel2)
                    .padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(
                        when { enMalla == null -> Color(0xFF4A535E); enMalla.direct -> ColDirecto; else -> ColRelevo }
                    ))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Color.White, fontSize = 16.sp)
                        Text(
                            when {
                                !c.confirmed -> "⏳ Falta que se conecten una vez por la malla para confirmar"
                                enMalla == null -> "✓ Confirmado · fuera de alcance"
                                else -> "✓ Confirmado · en la malla"
                            },
                            color = if (c.confirmed) Suave else Ambar, fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = { removing = c }) { Text("Quitar", color = Suave) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📍 Puede hacer sonar mi teléfono si me pierdo", color = Suave, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Switch(checked = c.canFindMe, onCheckedChange = { on ->
                        Mesh.engine?.setCanFindMe(c.id, on) ?: Ident.setCanFindMe(c.id, on)
                    })
                }
                if (c.iCanFind) Button(
                    onClick = { Mesh.engine?.startFind(c.id) }, enabled = st.running,
                    colors = ButtonDefaults.buttonColors(containerColor = Ambar, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📍 Encuéntrame: buscar a ${c.name}") }
                }
            }
            if (pend.any { it.kind == "key" }) Text(
                "🔑 Enviando tu clave nueva a ${pend.count { it.kind == "key" }} contacto(s) cuando estén al alcance.",
                color = Suave, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "🔒 Cómo funciona: lo que envías a tus contactos va cifrado; los teléfonos de desconocidos " +
                    "solo lo reenvían, sin poder leerlo. Invita en persona con el QR (lo más seguro) o envía " +
                    "el código por WhatsApp/SMS a alguien de confianza.",
                color = Suave, fontSize = 13.sp,
            )
        }
    }

    @Composable
    private fun DialogoInvitar(code: String) {
        val qr: Bitmap? = remember(code) {
            try { BarcodeEncoder().encodeBitmap(code, BarcodeFormat.QR_CODE, 720, 720) } catch (_: Exception) { null }
        }
        AlertDialog(
            onDismissRequest = { inviteCode = null },
            title = { Text("Invitar a tu círculo") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Muestra este QR a tu vecino o familiar para que lo escanee con Walkie Barrio.",
                        color = Suave, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    qr?.let {
                        Image(it.asImageBitmap(), "Código QR de invitación",
                            Modifier.size(240.dp).clip(RoundedCornerShape(8.dp)).background(Color.White))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Sirve una sola vez y vale 7 días. Solo dáselo a gente de confianza: " +
                        "verá tu nombre y tu ubicación.", color = Ambar, fontSize = 13.sp)
                }
            },
            confirmButton = { Button(onClick = { compartir(code) }) { Text("Enviar por WhatsApp/SMS") } },
            dismissButton = { TextButton(onClick = { inviteCode = null }) { Text("Cerrar") } },
        )
    }

    @Composable
    private fun DialogoPegar() {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPaste = false },
            title = { Text("Pegar código de invitación") },
            text = {
                Column {
                    Text("Copia el mensaje de invitación que te enviaron y pégalo aquí.", color = Suave, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(t, { t = it }, maxLines = 4, placeholder = { Text("WB1.…") })
                    TextButton(onClick = {
                        val cm = getSystemService(ClipboardManager::class.java)
                        t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: t
                    }) { Text("📋 Pegar del portapapeles") }
                }
            },
            confirmButton = {
                Button(onClick = { askPaste = false; onCodeText(t) }, enabled = t.isNotBlank()) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { askPaste = false }) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun DialogoAceptar(c: InviteCode) {
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("¿Agregar a ${c.name}?") },
            text = {
                Text("Quedarán como contactos de confianza: se verán con su nombre real y su ubicación, " +
                    "y podrán hablar en privado. Se confirma solo la primera vez que estén conectados por la malla.",
                    color = Suave, fontSize = 14.sp)
            },
            confirmButton = {
                Button(onClick = {
                    val err = Ident.accept(c)
                    incoming = null
                    Mesh.engine?.refresh()
                    Toast.makeText(this@MainActivity, err ?: "${c.name} agregado a tus contactos", Toast.LENGTH_LONG).show()
                }) { Text("Agregar") }
            },
            dismissButton = { TextButton(onClick = { incoming = null }) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun DialogoQuitar(c: Contact) {
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("¿Quitar a ${c.name}?") },
            text = {
                Text("Dejará de ver tu nombre y tu ubicación: tu clave cambia y se envía nueva solo a tus " +
                    "otros contactos. Para volver a agregarlo necesitarás una invitación nueva.",
                    color = Suave, fontSize = 14.sp)
            },
            confirmButton = {
                Button(onClick = {
                    Ident.remove(c.id); removing = null; Mesh.engine?.refresh()
                }, colors = ButtonDefaults.buttonColors(containerColor = Rojo)) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun DialogoNombre(actual: String) {
        var n by remember { mutableStateOf(actual) }
        AlertDialog(
            onDismissRequest = { if (actual.isNotBlank()) askName = false },
            title = { Text("¿Cómo te llamas?") },
            text = {
                Column {
                    Text("Solo tus contactos de confianza verán este nombre. Los demás te ven como " +
                        "«${Ident.alias}».", color = Suave, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(n, { n = it.take(30) }, singleLine = true,
                        placeholder = { Text("Ej: Ana, pasaje 3") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (n.isNotBlank()) {
                        Mesh.engine?.setName(n.trim()) ?: run {
                            Ident.setName(n.trim())
                            Mesh.state.value = Mesh.state.value.copy(name = n.trim())
                        }
                        askName = false
                    }
                }) { Text("Guardar") }
            },
        )
    }

    // ---------------------------------------------------------------- radar
    @Composable
    private fun TabRadar(st: Mesh.State) {
        val rotation = if (northUp) 0f else heading
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Radar(
                st, rotation, selected,
                onSelect = { selected = it },
                onLongPress = { Mesh.engine?.toggleDemo() },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("● directo  ", color = ColDirecto, fontSize = 13.sp)
                Text("● vía otros  ", color = ColRelevo, fontSize = 13.sp)
                Text("○ sin GPS", color = Suave, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { northUp = !northUp; prefs.edit().putBoolean("northUp", northUp).apply() }) {
                    Text(if (northUp) "🧭 Norte arriba" else "🧭 Brújula", fontSize = 13.sp)
                }
            }
            // Vecinos desconocidos en peligro: solo apodo y saltos (los contactos ya ven su alerta completa)
            st.nodes.filter { it.sos && !it.contact }.forEach { n ->
                Text(
                    "🚨 ${n.name} activó su alarma de pánico · " +
                        (if (n.direct) "muy cerca (conexión directa)" else "a ${n.hops} saltos") +
                        ". No es tu contacto: no ves su ubicación, pero quizás puedas oír su sirena.",
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(Rojo).clickable { selected = n.id }.padding(10.dp),
                )
            }
            if (!st.hasLoc) Text("📍 Esperando GPS (sal al exterior). Mientras, los vecinos se ubican por saltos.",
                color = Ambar, fontSize = 13.sp)
            if (st.demo) OutlinedButton(onClick = { Mesh.engine?.toggleDemo() }, modifier = Modifier.padding(top = 4.dp)) {
                Text("🧪 Demostración: salir", color = Color.White)
            }

            val sel = st.nodes.firstOrNull { it.id == selected }
            if (sel != null) FichaNodo(st, sel)

            Spacer(Modifier.height(8.dp))
            if (st.nodes.isEmpty()) {
                Text(
                    "Buscando vecinos… Acerca otro teléfono con Walkie Barrio abierto. " +
                        "Bluetooth, Wi‑Fi y Ubicación deben estar activados (no hace falta internet).",
                    color = Suave, fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                if (!st.demo) TextButton(onClick = { Mesh.engine?.toggleDemo() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Ver demostración")
                }
            } else {
                st.nodes.forEach { n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (n.id == selected) Panel2 else Color.Transparent)
                            .clickable { selected = n.id }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (n.direct) ColDirecto else ColRelevo))
                        Spacer(Modifier.width(10.dp))
                        Text((if (n.sos) "🚨 " else "") + n.name, color = if (n.sos) Rojo else Color.White,
                            fontWeight = if (n.sos) FontWeight.Bold else null, modifier = Modifier.weight(1f))
                        Text(resumenNodo(st, n), color = Suave, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    private fun resumenNodo(st: Mesh.State, n: Mesh.Node): String {
        val dist = if (st.hasLoc && n.hasLoc) Geo.fmt(Geo.distance(st.myLat, st.myLon, n.lat, n.lon)) + " · " else ""
        return dist + if (n.direct) "directo" else "${n.hops} saltos"
    }

    /** Primer vecino directo en el camino hacia un nodo (búsqueda en anchura). */
    private fun via(st: Mesh.State, dest: Int): String? {
        val adj = HashMap<Int, MutableSet<Int>>()
        fun link(a: Int, b: Int) { adj.getOrPut(a) { mutableSetOf() }.add(b); adj.getOrPut(b) { mutableSetOf() }.add(a) }
        st.nodes.forEach { n -> n.neighbors.forEach { link(n.id, it) }; if (n.direct) link(st.myId, n.id) }
        val prev = HashMap<Int, Int>()
        val cola = ArrayDeque(listOf(st.myId))
        val vistos = mutableSetOf(st.myId)
        while (cola.isNotEmpty()) {
            val x = cola.removeFirst()
            if (x == dest) break
            for (y in adj[x].orEmpty()) if (vistos.add(y)) { prev[y] = x; cola.addLast(y) }
        }
        var cur = dest
        while (prev[cur] != null && prev[cur] != st.myId) cur = prev[cur]!!
        return if (prev[cur] == st.myId && cur != dest) st.nodes.firstOrNull { it.id == cur }?.name else null
    }

    @Composable
    private fun FichaNodo(st: Mesh.State, n: Mesh.Node) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(14.dp)) {
            Text(n.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (n.sos) Text("🚨 Activó su alarma de pánico", color = Rojo, fontWeight = FontWeight.Bold)
            if (n.quiet) Text("💤 En reposo: le llegan mensajes y alertas, pero no voz ni llamados", color = Ambar, fontSize = 13.sp)
            if (!n.contact) Text(
                "🔒 No está en tus contactos: no ves su nombre ni dónde está, y él tampoco los tuyos.",
                color = Ambar, fontSize = 13.sp,
            ) else if (n.alias.isNotEmpty()) Text("Los demás lo ven como ${n.alias}", color = Suave, fontSize = 12.sp)
            if (st.hasLoc && n.hasLoc) {
                val d = Geo.distance(st.myLat, st.myLon, n.lat, n.lon)
                val b = Geo.bearing(st.myLat, st.myLon, n.lat, n.lon)
                Text("📏 ${Geo.fmt(d)} hacia el ${Geo.compass(b)} (±${n.acc.toInt()} m)", color = Suave)
            } else Text("📏 Distancia desconocida (sin GPS)", color = Suave)
            val v = via(st, n.id)
            Text(
                if (n.direct) "🔗 Conexión directa"
                else "🔗 A ${n.hops} saltos" + (v?.let { ", vía $it" } ?: ""),
                color = if (n.direct) ColDirecto else ColRelevo,
            )
            if (n.battery >= 0) Text("🔋 ${n.battery}%", color = Suave)
            val seg = ((SystemClock.elapsedRealtime() - n.lastSeen) / 1000).coerceAtLeast(0)
            Text("Visto hace $seg s", color = Suave, fontSize = 13.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!n.quiet && !st.reposo) Button(onClick = {
                    Mesh.engine?.sendCall(n.id)
                    Toast.makeText(this@MainActivity, "Llamado enviado a ${n.name}", Toast.LENGTH_SHORT).show()
                }) { Text("📣 Llamar") }
                if (n.contact) {
                    if (Ident.contact(n.id)?.iCanFind == true) OutlinedButton(onClick = { Mesh.engine?.startFind(n.id) }) {
                        Text("📍 Buscar", color = Color.White)
                    }
                    OutlinedButton(onClick = { chatConv = "c:${n.id}"; irA("chat") }) { Text("💬", color = Color.White) }
                    if (!n.quiet && !st.reposo) OutlinedButton(onClick = { walkieConv = "c:${n.id}"; irA("walkie") }) { Text("🎙", color = Color.White) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- conversaciones
    /** Un canal para escribir o hablar: el barrio (público), un grupo o un contacto. */
    private data class Canal(val conv: String, val nombre: String, val icono: String, val id: Int) {
        val publico get() = conv == "pub"
        val grupo get() = conv.startsWith("g:")
    }

    private fun canal(conv: String): Canal? = when {
        conv == "pub" -> Canal("pub", "Barrio", "📢", 0)
        conv.startsWith("g:") -> conv.substring(2).toIntOrNull()?.let { Grupos.get(it) }?.let { Canal(conv, it.name, "👥", it.id) }
        conv.startsWith("c:") -> conv.substring(2).toIntOrNull()?.let { Ident.contact(it) }?.let { Canal(conv, it.name, "🔒", it.id) }
        else -> null
    }

    private fun convDest(conv: String): Int = if (conv == "pub") 0 else conv.substringAfter(':').toIntOrNull() ?: 0

    /** Quién está al alcance en ese canal. */
    private fun alcance(c: Canal, st: Mesh.State): Pair<String, Color> = when {
        c.publico -> "${st.nodes.size} vecino${if (st.nodes.size != 1) "s" else ""} en la malla" to Suave
        c.grupo -> {
            val g = Grupos.get(c.id)
            val n = g?.members?.count { m -> st.nodes.any { it.id == m.id } } ?: 0
            "${g?.members?.size ?: 0} personas · $n al alcance" to if (n > 0) Verde else Suave
        }
        else -> st.nodes.firstOrNull { it.id == c.id }?.let { if (it.quiet) "💤 en reposo" to Ambar else "al alcance" to Verde }
            ?: ("fuera de alcance" to Suave)
    }

    /** Lista fija (no se reordena): Barrio arriba, luego grupos y contactos por nombre. */
    @Composable
    private fun ListaCanales(st: Mesh.State, voz: Boolean, onPick: (Canal) -> Unit) {
        val contactos by Ident.contacts.collectAsStateWithLifecycle()
        val grupos by Grupos.all.collectAsStateWithLifecycle()
        val lista = remember(contactos, grupos) {
            listOf(Canal("pub", "Barrio", "📢", 0)) +
                Grupos.activos().map { Canal("g:${it.id}", it.name, "👥", it.id) } +
                contactos.values.sortedBy { it.name.lowercase() }.map { Canal("c:${it.id}", it.name, "🔒", it.id) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Text(if (voz) "¿Con quién quieres hablar?" else "Conversaciones", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            }
            items(lista, key = { it.conv }) { c ->
                val (est, colEst) = alcance(c, st)
                val ultimo = if (voz) null else st.chat.lastOrNull { it.conv == c.conv }
                val sinLeer = if (voz) 0 else st.unread[c.conv] ?: 0
                // Por voz solo se le habla a un contacto conectado y despierto
                val bloqueado = voz && !c.publico && !c.grupo && st.nodes.none { it.id == c.id && !it.quiet }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (c.publico) Color(0xFF3A2F12) else Panel)
                        .clickable(enabled = !bloqueado) { onPick(c) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.icono, fontSize = 26.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.nombre + if (c.publico) " · público" else "", color = if (bloqueado) Suave else Color.White,
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            ultimo != null -> (if (ultimo.mine) "Tú: " else if (c.publico || c.grupo) "${ultimo.fromName}: " else "") + ultimo.text
                            c.publico -> if (voz) "Te oyen todos los vecinos, también desconocidos" else "Lo leen todos los vecinos, también desconocidos"
                            else -> est
                        }
                        Text(sub, color = if (ultimo == null && !c.publico) colEst else Suave, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sinLeer > 0) Badge { Text("$sinLeer") }
                    else if (ultimo != null) Text(hora.format(Date(ultimo.time)), color = Suave, fontSize = 12.sp)
                }
            }
            if (!voz) item {
                OutlinedButton(onClick = { editGroup = 0 }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text("➕ Nuevo grupo (familia, amigos…)", color = Color.White)
                }
            }
            if (contactos.isEmpty()) item {
                Text("Agrega contactos en 👥 Contactos para tener conversaciones privadas y grupos.",
                    color = Suave, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }

    /** Barra que siempre dice a quién le escribes o hablas (ámbar = público). */
    @Composable
    private fun CabeceraCanal(c: Canal, st: Mesh.State, voz: Boolean, accion: String, onAccion: () -> Unit) {
        val (est, _) = alcance(c, st)
        Row(
            Modifier.fillMaxWidth().background(if (c.publico) Color(0xFF6B4E0F) else Color(0xFF1F4D3A))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAccion) { Text(accion, color = Color.White, fontSize = if (accion == "←") 22.sp else 14.sp) }
            Column(Modifier.weight(1f)) {
                Text("${c.icono} ${c.nombre}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        c.publico -> if (voz) "PÚBLICO: te oyen todos los vecinos" else "PÚBLICO: lo leen todos los vecinos"
                        c.grupo -> "Grupo privado y cifrado · $est"
                        else -> "Privado y cifrado · $est"
                    },
                    color = Color(0xFFE6E6E6), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (c.grupo) TextButton(onClick = { infoGroup = c.id }) { Text("⚙️", fontSize = 18.sp) }
        }
    }

    // ---------------------------------------------------------------- chat
    @Composable
    private fun TabChat(st: Mesh.State) {
        Ident.contacts.collectAsStateWithLifecycle().value
        Grupos.all.collectAsStateWithLifecycle().value
        val c = chatConv?.let { canal(it) }
        if (c == null) {
            if (chatConv != null) chatConv = null
            ListaCanales(st, voz = false) { chatConv = it.conv }
            return
        }
        BackHandler { chatConv = null }
        DisposableEffect(c.conv) {
            Mesh.openConv = c.conv
            onDispose { if (Mesh.openConv == c.conv) Mesh.openConv = null }
        }
        val mensajes = remember(st.chat, c.conv) { st.chat.filter { it.conv == c.conv } }
        LaunchedEffect(c.conv, mensajes.size) { Mesh.engine?.markChatRead(c.conv) }
        Column(Modifier.fillMaxSize()) {
            CabeceraCanal(c, st, voz = false, accion = "←") { chatConv = null }
            val lista = rememberLazyListState()
            LaunchedEffect(c.conv, mensajes.size) { if (mensajes.isNotEmpty()) lista.scrollToItem(mensajes.size - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = lista) {
                if (mensajes.isEmpty()) item {
                    Text(
                        when {
                            c.publico -> "Mensajes para todo el barrio: los leen todos los vecinos conectados, también los que no conoces."
                            c.grupo -> "Mensajes del grupo: solo los leen sus miembros (van cifrados)."
                            else -> "Conversación privada y cifrada con ${c.nombre}. Llega si está conectado a la malla."
                        },
                        color = Suave, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                items(mensajes, key = { it.key }) { m -> Burbuja(m) }
            }
            var texto by remember(c.conv) { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().background(Panel).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    texto, { texto = it.take(1000) }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (c.publico) "Mensaje PÚBLICO al barrio…" else "Mensaje a ${c.nombre}…") },
                    maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { Mesh.engine?.sendChat(texto, c.conv); texto = "" }, enabled = texto.isNotBlank(),
                    colors = if (c.publico) ButtonDefaults.buttonColors(containerColor = Ambar) else ButtonDefaults.buttonColors(),
                ) { Text("Enviar", color = if (c.publico) Color.Black else Color.White) }
            }
        }
    }

    // ---------------------------------------------------------------- grupos
    @Composable
    private fun DialogoGrupo(gid: Int) {
        val g = if (gid == 0) null else Grupos.get(gid)
        val contactos by Ident.contacts.collectAsStateWithLifecycle()
        var nombre by remember { mutableStateOf(g?.name ?: "") }
        val elegidos = remember {
            mutableStateListOf<Int>().apply { g?.members?.map { it.id }?.filter { it != Ident.myId }?.let { addAll(it) } }
        }
        fun alternar(id: Int) { if (id in elegidos) elegidos.remove(id) else elegidos.add(id) }
        AlertDialog(
            onDismissRequest = { editGroup = null },
            title = { Text(if (g == null) "👥 Nuevo grupo" else "✏️ Editar grupo") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(nombre, { nombre = it.take(30) }, label = { Text("Nombre (ej: Familia)") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    Text("¿Quiénes están? (de tus contactos)", color = Suave)
                    if (contactos.isEmpty()) Text("Primero agrega contactos en 👥 Contactos.", color = Ambar)
                    contactos.values.sortedBy { it.name.lowercase() }.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { alternar(c.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.id in elegidos, onCheckedChange = { alternar(c.id) })
                            Text(c.name, color = Color.White, fontSize = 16.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("🔒 El grupo tiene su propia clave: solo sus miembros leen y oyen lo que se envía. " +
                        "Cada uno lo recibe cuando esté al alcance de la malla.", color = Suave, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = nombre.isNotBlank() && elegidos.isNotEmpty(), onClick = {
                    if (g == null) {
                        val n = Grupos.crear(nombre, elegidos.toList())
                        chatConv = "g:${n.id}"; irA("chat")
                    } else Grupos.editar(g.id, nombre, elegidos.toList())
                    editGroup = null
                }) { Text(if (g == null) "Crear" else "Guardar") }
            },
            dismissButton = { TextButton(onClick = { editGroup = null }) { Text("Cancelar") } },
        )
    }

    @Composable
    private fun DialogoInfoGrupo(gid: Int, st: Mesh.State) {
        val g = Grupos.get(gid)
        if (g == null) { infoGroup = null; return }
        val soyDueno = g.owner == Ident.myId
        var confirmar by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { infoGroup = null },
            title = { Text("👥 ${g.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Creado por " + if (soyDueno) "ti" else (Ident.contact(g.owner)?.name ?: Grupos.memberName(g.id, g.owner) ?: "otro"),
                        color = Suave)
                    Spacer(Modifier.height(8.dp))
                    g.members.forEach { m ->
                        val yo = m.id == Ident.myId
                        val n = st.nodes.firstOrNull { it.id == m.id }
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(if (yo) "• Tú" else "• " + (Ident.contact(m.id)?.name ?: m.name), color = Color.White,
                                modifier = Modifier.weight(1f))
                            if (!yo) Text(
                                if (n == null) "fuera de alcance" else if (n.quiet) "💤 reposo" else "al alcance",
                                color = if (n == null) Suave else if (n.quiet) Ambar else Verde, fontSize = 13.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("🔒 Lo que se escribe o se habla en el grupo va cifrado: solo lo leen y oyen sus miembros.",
                        color = Suave, fontSize = 13.sp)
                    if (confirmar) Text(
                        if (soyDueno) "¿Eliminar el grupo para todos? Toca otra vez para confirmar."
                        else "¿Salir del grupo? Dejarás de recibir sus mensajes. Toca otra vez para confirmar.",
                        color = Rojo, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Row {
                    if (soyDueno) TextButton(onClick = { infoGroup = null; editGroup = g.id }) { Text("✏️ Editar") }
                    TextButton(onClick = {
                        if (!confirmar) confirmar = true else {
                            Grupos.eliminar(g.id)
                            infoGroup = null
                            if (chatConv == "g:${g.id}") chatConv = null
                            if (walkieConv == "g:${g.id}") walkieConv = null
                        }
                    }) { Text(if (soyDueno) "🗑 Eliminar" else "Salir", color = Rojo) }
                }
            },
            dismissButton = { TextButton(onClick = { infoGroup = null }) { Text("Cerrar") } },
        )
    }

    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())

    @Composable
    private fun Burbuja(m: Mesh.Chat) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
            Column(
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (m.mine) Color(0xFF1F4D3A) else Panel2).padding(10.dp)
            ) {
                Text("${if (m.mine) "Tú" else m.fromName} · ${hora.format(Date(m.time))}",
                    color = Suave, fontSize = 12.sp)
                Text(m.text, color = Color.White, fontSize = 16.sp)
            }
        }
    }

    // ---------------------------------------------------------------- walkie
    @Composable
    private fun TabWalkie(st: Mesh.State) {
        Ident.contacts.collectAsStateWithLifecycle().value
        Grupos.all.collectAsStateWithLifecycle().value
        val canalVoz = walkieConv?.let { canal(it) }
        if (canalVoz == null) {
            if (walkieConv != null) walkieConv = null
            Column(Modifier.fillMaxSize()) {
                if (st.reposo) Text("💤 Estás en reposo: la voz está apagada. Enciéndela con el botón 💤 de arriba.",
                    color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF4A3A12)).padding(12.dp))
                ListaCanales(st, voz = true) { walkieConv = it.conv }
            }
            return
        }
        BackHandler(enabled = !st.transmitting) { walkieConv = null }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(4.dp))
            if (st.reposo) {
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF4A3A12))
                    .padding(14.dp)) {
                    Text("💤 Estás en reposo: la voz está apagada", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Sigues recibiendo mensajes y alertas.", color = Color.White, fontSize = 13.sp)
                    Button(onClick = { Mesh.engine?.setReposo(false) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("🎙 Encender la voz")
                    }
                }
            }
            CabeceraCanal(canalVoz, st, voz = true, accion = "Cambiar") { if (!st.transmitting) walkieConv = null }
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val (msg, color) = when {
                    st.transmitting -> "🎙 TRANSMITIENDO a ${if (st.txTarget == 0) "todos" else Mesh.engine?.nodeName(st.txTarget)} · ${st.txSeconds} s" to Rojo
                    st.speaker != null -> "🔊 ${st.speaker}" + when {
                        st.speakerGroup != null -> " (grupo ${st.speakerGroup})"
                        st.speakerPrivate -> " (te habla a ti)"
                        else -> " (a todo el barrio)"
                    } to Verde
                    st.nodes.isEmpty() -> "Nadie conectado todavía" to Panel
                    else -> "Canal libre · escuchando" to Panel
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color).padding(14.dp),
                    contentAlignment = Alignment.Center) {
                    Text(msg, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                if (st.transmitting) LinearProgressIndicator(
                    progress = { (st.level * 2f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), color = Rojo,
                )
                Spacer(Modifier.height(20.dp))
                val busy = st.speaker != null && !st.transmitting
                Box(
                    Modifier.size(220.dp).clip(CircleShape)
                        .background(if (st.transmitting) Rojo else if (busy || st.reposo) Color(0xFF3A424D) else Verde)
                        .pointerInput(toggleMode, walkieConv) {
                            detectTapGestures(
                                onPress = {
                                    if (!toggleMode) {
                                        val ok = pttDown()
                                        tryAwaitRelease()
                                        if (ok) Mesh.engine?.stopTx()
                                    }
                                },
                                onTap = {
                                    if (toggleMode) {
                                        if (Mesh.state.value.transmitting) Mesh.engine?.stopTx() else pttDown()
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            st.transmitting && toggleMode -> "TOCA PARA\nTERMINAR"
                            st.transmitting -> "HABLANDO…\nsuelta para\nterminar"
                            busy -> "ESPERA\ncanal ocupado"
                            toggleMode -> "TOCA PARA\nHABLAR"
                            else -> "MANTÉN\nPRESIONADO\nPARA HABLAR"
                        },
                        color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Opcion("Tocar para hablar y tocar para terminar (manos libres)", toggleMode) {
                    toggleMode = it; prefs.edit().putBoolean("toggleMode", it).apply()
                }
                Opcion("Usar el botón «bajar volumen» para hablar", volumePtt) {
                    volumePtt = it; prefs.edit().putBoolean("volumePtt", it).apply()
                }
            }
        }
    }

    @Composable
    private fun Opcion(texto: String, valor: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Switch(checked = valor, onCheckedChange = onChange)
        }
    }
}
