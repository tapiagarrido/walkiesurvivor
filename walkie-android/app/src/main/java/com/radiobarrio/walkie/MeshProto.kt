package com.radiobarrio.walkie

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Mensajes de la malla. Cada teléfono reenvía lo que recibe (con TTL) a sus
 * otros vecinos directos, así 3 teléfonos en línea hacen de puente.
 *
 * Cabecera (16 bytes, siempre visible para poder reenviar):
 *   0 'M'  1 versión  2 tipo (bit 0x80 = cuerpo cifrado)  3 TTL
 *   4-7 origen (id del nodo)  8-11 id del mensaje  12-15 destino (0 = todos)
 *
 * Privacidad (v2): ningún mensaje lleva el nombre real. Los desconocidos ven solo el apodo
 * (derivado del id). Nombre, ubicación y batería van en el beacon cifrados con la clave de
 * círculo del dueño; los mensajes y la voz privados, con la clave del par de contactos.
 */
object MeshProto {
    const val MAGIC = 'M'.code
    const val VERSION = 2
    const val MAX_TTL = 6
    private const val SEALED = 0x80

    const val T_BEACON = 1     // presencia: vecinos directos (público) + nombre/GPS/batería (cifrado)
    const val T_CHAT = 2       // mensaje de texto
    const val T_CALL = 3       // "quiero comunicarme contigo"
    const val T_VOICE = 4      // trozo de audio
    const val T_VOICE_END = 5
    const val T_PAIR_REQ = 6   // "usé tu invitación": mi nombre y clave, cifrados con el secreto de la invitación
    const val T_PAIR_ACK = 7   // "te agregué": mi nombre y clave actuales (cifrado)
    const val T_KEY = 8        // cambié mi clave de círculo (cifrado)
    const val T_KEY_ACK = 9
    const val T_SOS = 10       // botón de pánico: activo/cancelado + ubicación (cifrado, a cada contacto)
    const val T_SOS_ACK = 11   // "recibí tu alerta"
    const val T_FIND = 12      // Encuéntrame: 1 = haz sonar tu teléfono, 0 = basta (cifrado)
    const val T_FIND_ACK = 13  // respuesta del buscado: estado + batería (cifrado)
    const val T_GRANT = 14     // te doy / quito permiso de buscarme (cifrado)
    const val T_GRANT_ACK = 15
    const val T_GROUP = 16     // el creador te envía un grupo (nombre, clave, miembros) o "ya no estás" (cifrado)
    const val T_GROUP_ACK = 17 // "recibí el grupo" (cifrado)
    const val T_GCHAT = 18     // mensaje a un grupo: destino = id del grupo, cifrado con su clave

    // Audio: 8 kHz µ-law, 80 ms por paquete (64 kbps) para que aguante varios saltos.
    const val SAMPLE_RATE = 8000
    const val FRAME_SAMPLES = 640

    class Msg(
        val type: Int, var ttl: Int, val origin: Int, val msgId: Int, val dest: Int,
        // beacon
        val neighbors: IntArray = IntArray(0), val priv: ByteArray = ByteArray(0),
        // chat
        val text: String = "",
        // voz
        val seq: Int = 0, val audio: ByteArray = ByteArray(0),
        // emparejar / claves
        val inviteId: Int = 0, val blob: ByteArray = ByteArray(0),
        val sealed: Boolean = false,
        val sender: String = "",      // T_GCHAT: nombre de quien escribe (solo lo leen los miembros)
        val sosFlag: Boolean = false,   // beacon: "estoy en peligro" (público, sin nombre ni ubicación)
        val quiet: Boolean = false,     // beacon: en reposo (recibe mensajes y alertas, pero no voz)
    ) {
        val key: Long get() = (origin.toLong() shl 32) or (msgId.toLong() and 0xFFFFFFFFL)
    }

    /** Datos privados del beacon (solo los contactos los pueden leer). */
    class Priv(val name: String, val lat: Double, val lon: Double, val acc: Float, val battery: Int)

    fun encodePriv(p: Priv): ByteArray = ByteArrayOutputStream().also { bos ->
        DataOutputStream(bos).run {
            writeShortString(p.name); writeDouble(p.lat); writeDouble(p.lon); writeFloat(p.acc); writeByte(p.battery)
        }
    }.toByteArray()

    fun decodePriv(b: ByteArray): Priv? = try {
        DataInputStream(ByteArrayInputStream(b)).run {
            Priv(readShortString(), readDouble(), readDouble(), readFloat(), readByte().toInt())
        }
    } catch (_: Exception) { null }

    /** Lo que autentica el cifrado: la cabecera sin el TTL (que cambia en cada salto). */
    fun aad(raw: ByteArray): ByteArray = raw.copyOfRange(0, 16).also { it[3] = 0 }

    /** Cabecera de un mensaje que se va a armar (para cifrar con ella como AAD). */
    private fun header(m: Msg, sealed: Boolean): ByteArray = ByteArrayOutputStream(16).also { bos ->
        DataOutputStream(bos).run {
            writeByte(MAGIC); writeByte(VERSION); writeByte(m.type or if (sealed) SEALED else 0); writeByte(m.ttl)
            writeInt(m.origin); writeInt(m.msgId); writeInt(m.dest)
        }
    }.toByteArray()

    /** AAD de un mensaje sin cifrar (para sellar un blob interno, como en PAIR_REQ). */
    fun aadFor(m: Msg): ByteArray = header(m, false).also { it[3] = 0 }

    /** seal = clave para cifrar el cuerpo (mensajes privados), o null. */
    fun encode(m: Msg, seal: ByteArray? = null): ByteArray {
        val h = header(m, seal != null)
        val bos = ByteArrayOutputStream(64 + m.audio.size + m.text.length * 3 + m.priv.size + m.blob.size)
        DataOutputStream(bos).run {
            when (m.type) {
                T_BEACON -> {
                    writeByte(m.neighbors.size.coerceAtMost(32))
                    m.neighbors.take(32).forEach { writeInt(it) }
                    writeShort(m.priv.size); write(m.priv)
                    // bit 0 = pánico, bit 1 = reposo (nunca juntos: las versiones viejas leen "== 1")
                    writeByte(if (m.sosFlag) 1 else if (m.quiet) 2 else 0)
                }
                T_CHAT -> {
                    val b = m.text.toByteArray(Charsets.UTF_8)
                    writeShort(b.size); write(b)
                }
                T_VOICE -> {
                    writeShort(m.seq); writeShort(m.audio.size); write(m.audio)
                }
                T_PAIR_REQ -> {
                    writeInt(m.inviteId); writeShort(m.blob.size); write(m.blob)
                }
                T_PAIR_ACK, T_KEY, T_SOS, T_FIND, T_FIND_ACK, T_GRANT, T_GROUP, T_GROUP_ACK -> {
                    writeShort(m.blob.size); write(m.blob)
                }
                T_GCHAT -> {
                    writeShortString(m.sender)
                    val b = m.text.toByteArray(Charsets.UTF_8)
                    writeShort(b.size); write(b)
                }
            }
        }
        var body = bos.toByteArray()
        if (seal != null) body = Crypto.seal(seal, body, h.also { it[3] = 0 }.copyOf())
        return header(m, seal != null) + body
    }

    /**
     * keyFor(origen, destino) = clave del par (o del grupo) si el mensaje viene cifrado para mí.
     * Devuelve null si no es para mí o no lo puedo leer (igual se reenvía en crudo).
     */
    fun decode(b: ByteArray, keyFor: (Int, Int) -> ByteArray?): Msg? = try {
        DataInputStream(ByteArrayInputStream(b)).run {
            if (readUnsignedByte() != MAGIC || readUnsignedByte() != VERSION) return null
            val tb = readUnsignedByte(); val ttl = readUnsignedByte()
            val type = tb and 0x7F; val sealed = tb and SEALED != 0
            val origin = readInt(); val msgId = readInt(); val dest = readInt()
            var body = b.copyOfRange(16, b.size)
            if (sealed) {
                val k = keyFor(origin, dest) ?: return null
                body = Crypto.open(k, body, aad(b)) ?: return null
            }
            DataInputStream(ByteArrayInputStream(body)).run {
                when (type) {
                    T_BEACON -> {
                        val nb = IntArray(readUnsignedByte()) { readInt() }
                        val pv = ByteArray(readUnsignedShort()).also { readFully(it) }
                        val f = if (available() > 0) readUnsignedByte() else 0
                        Msg(type, ttl, origin, msgId, dest, neighbors = nb, priv = pv,
                            sosFlag = f and 1 != 0, quiet = f and 2 != 0)
                    }
                    T_CHAT -> {
                        val t = ByteArray(readUnsignedShort()).also { readFully(it) }
                        Msg(type, ttl, origin, msgId, dest, text = String(t, Charsets.UTF_8), sealed = sealed)
                    }
                    T_VOICE -> {
                        val seq = readUnsignedShort()
                        val a = ByteArray(readUnsignedShort()).also { readFully(it) }
                        Msg(type, ttl, origin, msgId, dest, seq = seq, audio = a, sealed = sealed)
                    }
                    T_PAIR_REQ -> {
                        val inv = readInt()
                        val bl = ByteArray(readUnsignedShort()).also { readFully(it) }
                        Msg(type, ttl, origin, msgId, dest, inviteId = inv, blob = bl)
                    }
                    T_GCHAT -> {
                        val who = readShortString()
                        val t = ByteArray(readUnsignedShort()).also { readFully(it) }
                        Msg(type, ttl, origin, msgId, dest, text = String(t, Charsets.UTF_8), sender = who, sealed = sealed)
                    }
                    T_PAIR_ACK, T_KEY, T_SOS, T_FIND, T_FIND_ACK, T_GRANT, T_GROUP, T_GROUP_ACK -> {
                        val bl = ByteArray(readUnsignedShort()).also { readFully(it) }
                        Msg(type, ttl, origin, msgId, dest, blob = bl, sealed = sealed)
                    }
                    T_CALL, T_VOICE_END, T_KEY_ACK, T_SOS_ACK, T_GRANT_ACK -> Msg(type, ttl, origin, msgId, dest, sealed = sealed)
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Copia del mensaje con el TTL ya descontado, lista para reenviar. */
    fun forRelay(raw: ByteArray): ByteArray = raw.copyOf().also { it[3] = (it[3] - 1).toByte() }

    private fun DataOutputStream.writeShortString(s: String) {
        var t = s
        var b = t.toByteArray(Charsets.UTF_8)
        while (b.size > 60) { t = t.dropLast(1); b = t.toByteArray(Charsets.UTF_8) }
        writeByte(b.size); write(b)
    }

    private fun DataInputStream.readShortString(): String {
        val n = readUnsignedByte(); val b = ByteArray(n); readFully(b)
        return String(b, Charsets.UTF_8)
    }

    // ---- G.711 µ-law ----
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun linearToUlaw(sample: Int): Byte {
        var s = sample
        var sign = 0
        if (s < 0) { s = -s; sign = 0x80 }
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    val ULAW_TO_LINEAR = ShortArray(256) { i ->
        val u = i.inv() and 0xFF
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val s = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        (if (u and 0x80 != 0) -s else s).toShort()
    }
}
