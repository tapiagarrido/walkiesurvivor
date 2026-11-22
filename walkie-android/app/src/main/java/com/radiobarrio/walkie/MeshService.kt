package com.radiobarrio.walkie

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Notificaciones: servicio activo, llamados y mensajes. */
class Notifs(private val ctx: Context) {
    companion object {
        const val CH_SERVICE = "malla"
        const val CH_CALL = "llamados"
        const val CH_CHAT = "mensajes"
        const val ID_SERVICE = 1
        const val ID_CALL = 2
        const val ID_CHAT = 3
        const val ID_INFO = 4
        const val ID_PANIC = 5
        const val ID_FOUND = 6
        const val CH_SOS = "panico"
    }

    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Malla activa", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Llamados de vecinos", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_CHAT, "Mensajes", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "Alertas de pánico", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)   // el sonido lo pone la app por el canal de alarma
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun openApp(tab: String?, conv: String? = null) = PendingIntent.getActivity(
        ctx, (tab + conv).hashCode(), Intent(ctx, MainActivity::class.java).putExtra("tab", tab).putExtra("conv", conv)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun service(saving: Boolean = false, reposo: Boolean = false): Notification {
        val b = NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_walkie)
            .setContentIntent(openApp(null))
            .addAction(0, "🚨 Pánico", svc(MeshService.ACTION_PANIC, 2))
            .setOngoing(true)
        if (reposo) b.setContentTitle("Walkie Barrio: en reposo 💤")
            .setContentText("Sigues en el radar y recibes mensajes y alertas · sin voz")
            .addAction(0, "Encender", svc(MeshService.ACTION_WAKE, 1))
        else b.setContentTitle(if (saving) "Walkie Barrio: modo ahorro 🔋" else "Walkie Barrio: malla activa")
            .setContentText(
                if (saving) "Busca vecinos cada 1–2 min · mensajes y llamados siguen llegando"
                else "Buscando y conectando vecinos cercanos"
            )
            .addAction(0, "💤 Reposo", svc(MeshService.ACTION_REPOSO, 1))
        return b.build()
    }

    private fun svc(action: String, code: Int) = PendingIntent.getService(
        ctx, code, Intent(ctx, MeshService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Mi alarma: cuenta regresiva (cancelable) o activa (con "Detener"). */
    fun panic(active: Boolean, countdown: Int) {
        if (!canNotify()) return
        val b = NotificationCompat.Builder(ctx, CH_SOS)
            .setSmallIcon(R.drawable.ic_walkie)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!active)
            .setContentIntent(openApp(null))
        if (active) b.setContentTitle("🚨 PÁNICO ACTIVO").setContentText("Sonando y avisando a tus contactos")
            .addAction(0, "Detener", svc(MeshService.ACTION_PANIC_STOP, 3))
        else b.setContentTitle("🚨 Pánico en $countdown s").setContentText("Toca Cancelar si fue sin querer")
            .addAction(0, "Cancelar", svc(MeshService.ACTION_PANIC_STOP, 3))
        nm.notify(ID_PANIC, b.build())
    }

    fun cancelPanic() = nm.cancel(ID_PANIC)

    /** Un contacto me está buscando: mi teléfono suena. */
    fun found(name: String) {
        if (!canNotify()) return
        nm.notify(
            ID_FOUND, NotificationCompat.Builder(ctx, CH_SOS)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle("📍 $name te está buscando")
                .setContentText("Tu teléfono suena para que te encuentre")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(openApp(null))
                .addAction(0, "Estoy bien, detener", svc(MeshService.ACTION_FOUND_STOP, 5))
                .build()
        )
    }

    fun cancelFound() = nm.cancel(ID_FOUND)

    /** Alerta de un contacto (una notificación por persona). */
    fun sos(fromId: Int, title: String, text: String) {
        if (!canNotify()) return
        nm.notify(
            1000 + (fromId and 0xFFFF), NotificationCompat.Builder(ctx, CH_SOS)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openApp("radar"))
                .addAction(0, "Silenciar", svc(MeshService.ACTION_SILENCE, 4))
                .build()
        )
    }

    fun cancelSos(fromId: Int) = nm.cancel(1000 + (fromId and 0xFFFF))

    fun updateService(saving: Boolean, reposo: Boolean) {
        if (canNotify()) nm.notify(ID_SERVICE, service(saving, reposo))
    }

    fun info(title: String, text: String) {
        if (!canNotify()) return
        nm.notify(
            ID_INFO, NotificationCompat.Builder(ctx, CH_CHAT)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp("contactos"))
                .setAutoCancel(true)
                .build()
        )
    }

    private fun canNotify() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun call(from: String) {
        if (!canNotify()) return
        nm.notify(
            ID_CALL, NotificationCompat.Builder(ctx, CH_CALL)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle("📣 $from quiere comunicarse contigo")
                .setContentText("Toca para responder")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(openApp("walkie"))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Un mensaje nuevo; al tocarlo abre esa conversación. Una notificación por conversación. */
    fun chat(title: String, text: String, conv: String) {
        if (!canNotify()) return
        nm.notify(
            ID_CHAT * 10_000 + (conv.hashCode() and 0x1FFF), NotificationCompat.Builder(ctx, CH_CHAT)
                .setSmallIcon(R.drawable.ic_walkie)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp("chat", conv))
                .setAutoCancel(true)
                .build()
        )
    }
}

/** Despierta al teléfono para el siguiente ciclo de búsqueda del modo ahorro. */
class MeshAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Mesh.engine?.onAlarm()
    }
}

/**
 * Al encender el teléfono o actualizar la app, la malla vuelve sola (salvo que la persona la haya
 * desconectado del todo): así nadie desaparece del radar por un reinicio.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("walkie", Context.MODE_PRIVATE)
        if (prefs.getBoolean("desconectado", false) || !prefs.getBoolean("iniciada", false)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        } catch (_: Exception) {}
    }
}

/** Mantiene la malla funcionando con la pantalla apagada (y en reposo, con la app cerrada). */
class MeshService : Service() {
    companion object {
        const val ACTION_OFF = "com.radiobarrio.walkie.OFF"          // desconectar del todo
        const val ACTION_REPOSO = "com.radiobarrio.walkie.REPOSO"    // apagado liviano
        const val ACTION_WAKE = "com.radiobarrio.walkie.WAKE"        // salir del reposo
        const val ACTION_PANIC = "com.radiobarrio.walkie.PANIC"
        const val ACTION_PANIC_STOP = "com.radiobarrio.walkie.PANIC_STOP"
        const val ACTION_SILENCE = "com.radiobarrio.walkie.SILENCE"
        const val ACTION_FOUND_STOP = "com.radiobarrio.walkie.FOUND_STOP"
        const val EXTRA_UI = "ui"    // lo inicia la pantalla (app visible)
    }

    private var engine: MeshEngine? = null
    private var notifs: Notifs? = null
    private var conMicrofono = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Micrófono y GPS solo se pueden pedir con la app a la vista; si arrancó sola (reinicio del
     * teléfono, el sistema la revivió) va sin ellos y se agregan cuando se abre la pantalla.
     */
    private fun promote(ui: Boolean): Boolean {
        val n = notifs ?: Notifs(this).also { notifs = it }
        val prefs = getSharedPreferences("walkie", MODE_PRIVATE)
        val notif = n.service(engine?.isSaving ?: false, prefs.getBoolean("reposo", false))
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        var full = base
        if (granted(Manifest.permission.RECORD_AUDIO)) full = full or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) full = full or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (ui || conMicrofono) try {
            ServiceCompat.startForeground(this, Notifs.ID_SERVICE, notif, full)
            conMicrofono = true
            return true
        } catch (_: Exception) {}
        return try {
            ServiceCompat.startForeground(this, Notifs.ID_SERVICE, notif, base); true
        } catch (e: Exception) {
            Mesh.state.value = Mesh.state.value.copy(error = "No se pudo iniciar: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("walkie", MODE_PRIVATE)
        if (intent?.action == ACTION_OFF) {
            prefs.edit().putBoolean("desconectado", true).apply()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean("desconectado", false).putBoolean("iniciada", true).apply()
        if (!promote(intent?.getBooleanExtra(EXTRA_UI, false) == true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine == null) {
            engine = MeshEngine(this, notifs!!).also {
                Mesh.engine = it
                it.start()
            }
        }
        when (intent?.action) {
            ACTION_REPOSO -> engine?.setReposo(true)
            ACTION_WAKE -> engine?.setReposo(false)
            ACTION_PANIC -> engine?.startPanic(5)          // desde la notificación: 5 s para cancelar
            ACTION_PANIC_STOP -> engine?.stopPanic()
            ACTION_SILENCE -> engine?.silenceAlerts()
            ACTION_FOUND_STOP -> engine?.stopBeingFound()
        }
        // Si el sistema la cierra por falta de memoria, que la vuelva a abrir
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        Mesh.engine = null
        super.onDestroy()
    }
}
