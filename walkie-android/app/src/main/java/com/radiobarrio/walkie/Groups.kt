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
import java.io.File
import kotlin.concurrent.thread

data class Miembro(val id: Int, val name: String)

/**
 * Grupo de confianza (p. ej. "Familia"): lo crea una persona con algunos de sus contactos.
 * Tiene su propia clave: solo los miembros leen y oyen lo que se envía al grupo.
 */
data class Grupo(
    val id: Int, val name: String, val key: ByteArray, val owner: Int,
    val members: List<Miembro>, val version: Int, val deleted: Boolean = false,
) {
    fun has(id: Int) = members.any { it.id == id }
}

object Grupos {
    val all = MutableStateFlow<Map<Int, Grupo>>(emptyMap())
    private lateinit var prefs: SharedPreferences

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("walkie", Context.MODE_PRIVATE)
        load()
    }

    /** Grupo activo en el que estoy (los eliminados quedan guardados solo para avisar a los miembros). */
    fun get(id: Int): Grupo? = all.value[id]?.takeIf { !it.deleted && it.has(Ident.myId) }
    fun activos(): List<Grupo> = all.value.values.filter { !it.deleted && it.has(Ident.myId) }.sortedBy { it.name.lowercase() }

    private fun yo() = Miembro(Ident.myId, Ident.name.ifBlank { Ident.alias })

    @Synchronized
    fun crear(name: String, ids: List<Int>): Grupo {
        val g = Grupo(
            Crypto.randomInt(), name.trim().take(30), Crypto.random(16), Ident.myId,
            listOf(yo()) + ids.distinct().map { Miembro(it, Ident.display(it)) }, 1,
        )
        all.value = all.value + (g.id to g)
        ids.forEach { Ident.queue(it, "g:${g.id}") }
        save()
        return g
    }

    /** Solo quien creó el grupo lo edita. Si saca a alguien, la clave cambia (deja de leer lo nuevo). */
    @Synchronized
    fun editar(gid: Int, name: String, ids: List<Int>) {
        val g = all.value[gid] ?: return
        if (g.owner != Ident.myId) return
        val antes = g.members.map { it.id }.filter { it != Ident.myId }
        val sacados = antes - ids.toSet()
        val g2 = g.copy(
            name = name.trim().take(30),
            key = if (sacados.isNotEmpty()) Crypto.random(16) else g.key,
            members = listOf(yo()) + ids.distinct().map { id -> Miembro(id, Ident.display(id)) },
            version = g.version + 1,
        )
        all.value = all.value + (gid to g2)
        (ids + sacados).distinct().forEach { Ident.queue(it, "g:$gid") }
        save()
    }

    /** El creador lo elimina para todos; un miembro solo se sale (lo borra de su teléfono). */
    @Synchronized
    fun eliminar(gid: Int) {
        val g = all.value[gid] ?: return
        if (g.owner == Ident.myId) {
            all.value = all.value + (gid to g.copy(deleted = true, version = g.version + 1))
            g.members.filter { it.id != Ident.myId }.forEach { Ident.queue(it.id, "g:$gid") }
        } else all.value = all.value - gid
        save()
    }

    /** Lo que se le envía a un miembro (cifrado con la clave del par): el grupo, o "ya no estás". */
    fun encodeFor(gid: Int, dest: Int): ByteArray? {
        val g = all.value[gid] ?: return null
        val fuera = g.deleted || !g.has(dest)
        return ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).run {
                writeInt(g.id); writeInt(g.version); writeByte(if (fuera) 1 else 0)
                writeUTF(g.name); write(if (fuera) ByteArray(16) else g.key); writeInt(g.owner)
                writeByte(g.members.size)
                g.members.forEach { writeInt(it.id); writeUTF(it.name) }
            }
        }.toByteArray()
    }

    /** Llega un grupo desde su creador. Devuelve (grupo, esNuevo, meSacaron) o null si no corresponde. */
    @Synchronized
    fun onGroup(from: Int, blob: ByteArray): Triple<Grupo, Boolean, Boolean>? = try {
        DataInputStream(ByteArrayInputStream(blob)).run {
            val gid = readInt(); val ver = readInt(); val fuera = readUnsignedByte() == 1
            val name = readUTF(); val key = ByteArray(16).also { readFully(it) }; val owner = readInt()
            val ms = List(readUnsignedByte()) { Miembro(readInt(), readUTF()) }
            val antes = all.value[gid]
            if (owner != from || (antes != null && antes.owner != from)) return null
            val g = Grupo(gid, name, key, owner, ms, ver)
            if (antes != null && ver <= antes.version) return Triple(antes, false, false)
            if (fuera) {
                all.value = all.value - gid
                save()
                Triple(g, false, antes != null)
            } else {
                all.value = all.value + (gid to g)
                save()
                Triple(g, antes == null, false)
            }
        }
    } catch (_: Exception) { null }

    /** Nombre de un miembro según la lista del grupo (para quien no es mi contacto). */
    fun memberName(gid: Int, id: Int): String? = all.value[gid]?.members?.firstOrNull { it.id == id }?.name

    // ------------------------------------------------------------ guardar
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun save() {
        val a = JSONArray()
        all.value.values.forEach { g ->
            val ms = JSONArray()
            g.members.forEach { ms.put(JSONObject().put("id", it.id).put("n", it.name)) }
            a.put(JSONObject().put("id", g.id).put("name", g.name).put("k", b64(g.key)).put("o", g.owner)
                .put("v", g.version).put("del", g.deleted).put("m", ms))
        }
        prefs.edit().putString("grupos", a.toString()).apply()
    }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("grupos", "[]"))
            all.value = (0 until a.length()).map { a.getJSONObject(it) }.associate { o ->
                val ms = o.getJSONArray("m")
                o.getInt("id") to Grupo(
                    o.getInt("id"), o.getString("name"), Base64.decode(o.getString("k"), Base64.NO_WRAP), o.getInt("o"),
                    (0 until ms.length()).map { ms.getJSONObject(it) }.map { Miembro(it.getInt("id"), it.getString("n")) },
                    o.getInt("v"), o.optBoolean("del"),
                )
            }
        } catch (_: Exception) {}
    }
}

/** Historial de mensajes guardado en el teléfono (sobrevive a reinicios). */
object Historial {
    private var file: File? = null
    private val lock = Any()

    fun init(ctx: Context) { if (file == null) file = File(ctx.filesDir, "mensajes.json") }

    fun load(): List<Mesh.Chat> = try {
        val a = JSONArray(file?.takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Mesh.Chat(
                o.getLong("k"), o.getInt("f"), o.getString("fn"), o.getInt("t"), o.optString("tn").ifEmpty { null },
                o.getString("x"), o.getLong("h"), o.getBoolean("m"), o.getString("c"),
            )
        }
    } catch (_: Exception) { emptyList() }

    fun save(list: List<Mesh.Chat>) {
        val f = file ?: return
        thread(name = "guardar-mensajes") {
            val a = JSONArray()
            list.forEach { c ->
                a.put(JSONObject().put("k", c.key).put("f", c.fromId).put("fn", c.fromName).put("t", c.toId)
                    .put("tn", c.toName ?: "").put("x", c.text).put("h", c.time).put("m", c.mine).put("c", c.conv))
            }
            synchronized(lock) { try { f.writeText(a.toString()) } catch (_: Exception) {} }
        }
    }
}
