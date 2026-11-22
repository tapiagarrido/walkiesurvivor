package com.radiobarrio.walkie

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Cifrado AES-GCM (clave de 128 bits). Resultado: nonce(12) + cifrado + etiqueta(16). */
object Crypto {
    private val rnd = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    fun randomInt(): Int { var x: Int; do { x = rnd.nextInt() } while (x == 0); return x }

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val nonce = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        aad?.let { c.updateAAD(it) }
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
            aad?.let { c.updateAAD(it) }
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (_: Exception) { null }
}

/** Apodo público y estable a partir del id: "🦝 Mapache feliz". Igual en todos los teléfonos. */
object Alias {
    private class A(val emoji: String, val nombre: String, val fem: Boolean)

    private val animales = listOf(
        A("🦝", "Mapache", false), A("🦌", "Ciervo", false), A("🐱", "Gato", false), A("🦊", "Zorro", false),
        A("🐻", "Oso", false), A("🦉", "Búho", false), A("🐢", "Tortuga", true), A("🐸", "Rana", true),
        A("🐧", "Pingüino", false), A("🦔", "Erizo", false), A("🐰", "Conejo", false), A("🦙", "Llama", true),
        A("🐿", "Ardilla", true), A("🦜", "Loro", false), A("🐼", "Panda", false), A("🦫", "Castor", false),
        A("🐌", "Caracol", false), A("🦆", "Pata", true), A("🐝", "Abeja", true), A("🐞", "Chinita", true),
        A("🦭", "Foca", true), A("🐙", "Pulpo", false), A("🦩", "Flamenco", false), A("🐨", "Koala", false),
        A("🦦", "Nutria", true), A("🐺", "Lobo", false), A("🦥", "Perezoso", false), A("🐭", "Ratón", false),
        A("🦒", "Jirafa", true), A("🐘", "Elefante", false), A("🦘", "Canguro", false), A("🐹", "Hámster", false),
        A("🦅", "Cóndor", false), A("🐑", "Oveja", true), A("🦎", "Lagartija", true), A("🐬", "Delfín", false),
    )

    // (masculino, femenino)
    private val adjetivos = listOf(
        "feliz" to "feliz", "nervioso" to "nerviosa", "curioso" to "curiosa", "dormilón" to "dormilona",
        "valiente" to "valiente", "tímido" to "tímida", "risueño" to "risueña", "despistado" to "despistada",
        "sabio" to "sabia", "veloz" to "veloz", "tranquilo" to "tranquila", "gruñón" to "gruñona",
        "amable" to "amable", "travieso" to "traviesa", "elegante" to "elegante", "glotón" to "glotona",
        "soñador" to "soñadora", "alegre" to "alegre", "pensativo" to "pensativa", "cantor" to "cantora",
        "bailarín" to "bailarina", "astuto" to "astuta", "paciente" to "paciente", "audaz" to "audaz",
        "enjaulado" to "enjaulada", "abrigado" to "abrigada", "madrugador" to "madrugadora", "callado" to "callada",
        "despeinado" to "despeinada", "aventurero" to "aventurera", "goloso" to "golosa", "orgulloso" to "orgullosa",
    )

    fun of(id: Int): String {
        var h = id * -0x61c88647           // mezcla de bits (razón áurea)
        h = h xor (h ushr 15)
        val a = animales[Math.floorMod(h, animales.size)]
        val adj = adjetivos[Math.floorMod(h ushr 8, adjetivos.size)]
        return "${a.emoji} ${a.nombre} ${if (a.fem) adj.second else adj.first}"
    }
}

/** Contacto de confianza: ve mi nombre y ubicación, y yo los suyos. */
data class Contact(
    val id: Int,
    val name: String,
    val circleKey: ByteArray,   // su clave de círculo: descifra su nombre y ubicación
    val pairKey: ByteArray,     // clave solo entre los dos: mensajes y voz privados
    val confirmed: Boolean,     // el otro ya nos agregó
    val canFindMe: Boolean = false,   // yo le doy permiso de hacer sonar mi teléfono (Encuéntrame)
    val added: Long = System.currentTimeMillis(),
    val iCanFind: Boolean = false,    // él me dio ese permiso a mí
)

/** Algo que hay que entregar a un contacto en cuanto esté al alcance de la malla. */
data class Pending(val dest: Int, val kind: String, val inviteId: Int = 0, val created: Long = System.currentTimeMillis())

data class Invitation(val inviteId: Int, val secret: ByteArray, val created: Long)

/** Datos de una invitación recibida (código o QR). */
class InviteCode(val fromId: Int, val inviteId: Int, val secret: ByteArray, val circleKey: ByteArray, val name: String)

/**
 * Identidad del teléfono: id de la malla, nombre real (solo para contactos), clave de círculo,
 * contactos, invitaciones y pendientes. Se guarda en el teléfono (sin respaldo en la nube).
 */
object Ident {
    private const val INVITE_DAYS = 7L
    private const val CODE_PREFIX = "WB1."

    private lateinit var prefs: SharedPreferences
    var myId: Int = 0; private set
    lateinit var circleKey: ByteArray; private set

    val contacts = MutableStateFlow<Map<Int, Contact>>(emptyMap())
    val pending = MutableStateFlow<List<Pending>>(emptyList())
    private val invites = mutableListOf<Invitation>()

    val name: String get() = prefs.getString("name", "") ?: ""
    val alias: String get() = Alias.of(myId)

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("walkie", Context.MODE_PRIVATE)
        myId = prefs.getInt("meshId", 0).takeIf { it != 0 }
            ?: Crypto.randomInt().also { prefs.edit().putInt("meshId", it).apply() }
        circleKey = prefs.getString("circleKey", null)?.let { b64d(it) }
            ?: Crypto.random(16).also { prefs.edit().putString("circleKey", b64(it)).apply() }
        load()
    }

    fun setName(n: String) = prefs.edit().putString("name", n.trim().take(30)).apply()

    // ------------------------------------------------------------ invitaciones
    /** Crea una invitación de un solo uso (vale 7 días) y devuelve el código para QR o mensaje. */
    @Synchronized
    fun newInvite(): String {
        val inv = Invitation(Crypto.randomInt(), Crypto.random(16), System.currentTimeMillis())
        invites += inv
        while (invites.size > 30) invites.removeAt(0)
        save()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).run {
            writeByte(1); writeInt(myId); writeInt(inv.inviteId); write(inv.secret); write(circleKey)
            val nb = name.ifBlank { "Vecino" }.toByteArray(Charsets.UTF_8).take(60).toByteArray()
            writeByte(nb.size); write(nb)
        }
        return CODE_PREFIX + Base64.encodeToString(bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Busca un código de invitación dentro de un texto (por ejemplo, un mensaje de WhatsApp). */
    fun parseCode(text: String): InviteCode? {
        val m = Regex("""WB1\.([A-Za-z0-9_-]{40,})""").find(text) ?: return null
        return try {
            val b = Base64.decode(m.groupValues[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            DataInputStream(ByteArrayInputStream(b)).run {
                if (readUnsignedByte() != 1) return null
                val id = readInt(); val inv = readInt()
                val s = ByteArray(16).also { readFully(it) }
                val k = ByteArray(16).also { readFully(it) }
                val n = ByteArray(readUnsignedByte()).also { readFully(it) }
                InviteCode(id, inv, s, k, String(n, Charsets.UTF_8))
            }
        } catch (_: Exception) { null }
    }

    /** Acepta una invitación: ya vemos al otro; le avisamos por la malla en cuanto esté al alcance. */
    @Synchronized
    fun accept(c: InviteCode): String? {
        if (c.fromId == myId) return "Ese es tu propio código"
        val prev = contacts.value[c.fromId]
        if (prev != null && prev.confirmed) return "${prev.name} ya está en tus contactos"
        contacts.value = contacts.value + (c.fromId to Contact(c.fromId, c.name, c.circleKey, c.secret, false))
        pending.value = pending.value.filterNot { it.dest == c.fromId && it.kind == "pair" } +
            Pending(c.fromId, "pair", c.inviteId)
        save()
        return null
    }

    /** Llega la respuesta de alguien que usó mi invitación. Devuelve el contacto nuevo o null. */
    @Synchronized
    fun onPairRequest(fromId: Int, inviteId: Int, blob: ByteArray, aad: ByteArray): Contact? {
        val existing = contacts.value[fromId]
        val inv = invites.firstOrNull { it.inviteId == inviteId }
        val key = inv?.secret ?: existing?.pairKey ?: return null
        val plain = Crypto.open(key, blob, aad) ?: return null
        val (n, k) = DataInputStream(ByteArrayInputStream(plain)).run {
            val nb = ByteArray(readUnsignedByte()).also { readFully(it) }
            String(nb, Charsets.UTF_8) to ByteArray(16).also { readFully(it) }
        }
        val c = Contact(fromId, n.ifBlank { "Vecino" }, k, key, true, existing?.canFindMe ?: false, existing?.added ?: System.currentTimeMillis(), existing?.iCanFind ?: false)
        contacts.value = contacts.value + (fromId to c)
        if (inv != null) invites.remove(inv)
        save()
        return c
    }

    fun pairRequestPlain(): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).run {
            val nb = name.ifBlank { "Vecino" }.toByteArray(Charsets.UTF_8).take(60).toByteArray()
            writeByte(nb.size); write(nb); write(circleKey)
        }
    }.toByteArray()

    /** El otro confirmó: guardamos su nombre y clave actuales. */
    @Synchronized
    fun onPairAck(fromId: Int, plain: ByteArray) {
        val c = contacts.value[fromId] ?: return
        val (n, k) = DataInputStream(ByteArrayInputStream(plain)).run {
            val nb = ByteArray(readUnsignedByte()).also { readFully(it) }
            String(nb, Charsets.UTF_8) to ByteArray(16).also { readFully(it) }
        }
        contacts.value = contacts.value + (fromId to c.copy(name = n.ifBlank { c.name }, circleKey = k, confirmed = true))
        pending.value = pending.value.filterNot { it.dest == fromId && it.kind == "pair" }
        save()
    }

    // ------------------------------------------------------------ contactos
    /**
     * Elimina un contacto. Como él conoce mi clave de círculo, la cambio y se la envío
     * (cifrada) a los demás contactos: así deja de ver mi nombre y ubicación.
     */
    @Synchronized
    fun remove(id: Int) {
        contacts.value = contacts.value - id
        circleKey = Crypto.random(16)
        prefs.edit().putString("circleKey", b64(circleKey)).apply()
        pending.value = pending.value.filterNot { it.dest == id || it.kind == "key" } +
            contacts.value.values.map { Pending(it.id, "key") }
        save()
    }

    @Synchronized
    fun onNewKey(fromId: Int, key: ByteArray) {
        val c = contacts.value[fromId] ?: return
        contacts.value = contacts.value + (fromId to c.copy(circleKey = key))
        save()
    }

    /** Deja algo pendiente de entregar a un contacto (se reintenta cuando esté al alcance). */
    @Synchronized
    fun queue(dest: Int, kind: String) {
        pending.value = pending.value.filterNot { it.dest == dest && it.kind == kind } + Pending(dest, kind)
        save()
    }

    @Synchronized
    fun done(dest: Int, kind: String) {
        pending.value = pending.value.filterNot { it.dest == dest && it.kind == kind }
        save()
    }

    /** Doy (o quito) permiso a un contacto para hacer sonar mi teléfono; se le avisa por la malla. */
    @Synchronized
    fun setCanFindMe(id: Int, on: Boolean) {
        val c = contacts.value[id] ?: return
        contacts.value = contacts.value + (id to c.copy(canFindMe = on))
        pending.value = pending.value.filterNot { it.dest == id && it.kind == "grant" } + Pending(id, "grant")
        save()
    }

    /** Un contacto me dio (o quitó) permiso para buscarlo. */
    @Synchronized
    fun onGrant(fromId: Int, on: Boolean) {
        val c = contacts.value[fromId] ?: return
        contacts.value = contacts.value + (fromId to c.copy(iCanFind = on))
        save()
    }

    /** Ficha corta que identifica la radiobaliza Bluetooth de un contacto (los extraños no la reconocen). */
    fun findToken(c: Contact): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(c.pairKey, "HmacSHA256"))
        return mac.doFinal("encuentrame".toByteArray()).copyOf(4)
    }

    fun contact(id: Int): Contact? = contacts.value[id]

    /** Nombre a mostrar: el real si es contacto; si no, su apodo. */
    fun display(id: Int): String = contacts.value[id]?.name ?: Alias.of(id)

    // ------------------------------------------------------------ guardar / cargar
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun save() {
        val now = System.currentTimeMillis()
        val ttl = INVITE_DAYS * 86_400_000L
        invites.removeAll { now - it.created > ttl }
        pending.value = pending.value.filter { now - it.created < ttl * 4 }
        val cs = JSONArray()
        contacts.value.values.forEach { c ->
            cs.put(JSONObject().put("id", c.id).put("name", c.name).put("ck", b64(c.circleKey))
                .put("pk", b64(c.pairKey)).put("ok", c.confirmed).put("find", c.canFindMe).put("added", c.added)
                .put("ican", c.iCanFind))
        }
        val inv = JSONArray()
        invites.forEach { inv.put(JSONObject().put("id", it.inviteId).put("s", b64(it.secret)).put("t", it.created)) }
        val pe = JSONArray()
        pending.value.forEach { pe.put(JSONObject().put("d", it.dest).put("k", it.kind).put("i", it.inviteId).put("t", it.created)) }
        prefs.edit().putString("contacts", cs.toString()).putString("invites", inv.toString())
            .putString("pending", pe.toString()).apply()
    }

    private fun load() {
        try {
            val cs = JSONArray(prefs.getString("contacts", "[]"))
            contacts.value = (0 until cs.length()).map { cs.getJSONObject(it) }.associate { o ->
                o.getInt("id") to Contact(
                    o.getInt("id"), o.getString("name"), b64d(o.getString("ck")), b64d(o.getString("pk")),
                    o.getBoolean("ok"), o.optBoolean("find"), o.optLong("added"), o.optBoolean("ican"),
                )
            }
            val inv = JSONArray(prefs.getString("invites", "[]"))
            invites.clear()
            for (i in 0 until inv.length()) inv.getJSONObject(i).let {
                invites += Invitation(it.getInt("id"), b64d(it.getString("s")), it.getLong("t"))
            }
            val pe = JSONArray(prefs.getString("pending", "[]"))
            pending.value = (0 until pe.length()).map { pe.getJSONObject(it) }.map {
                Pending(it.getInt("d"), it.getString("k"), it.optInt("i"), it.getLong("t"))
            }
        } catch (_: Exception) {}
    }
}
