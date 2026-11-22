# 📻 Walkie Survivor (Walkie Barrio)

**Comunicación de emergencia entre vecinos, sin internet, sin antenas y sin servidores.**

Cuando se cae la luz, la red celular o internet, los teléfonos del barrio se conectan **directamente entre sí** (Bluetooth y Wi‑Fi Direct) y forman una **malla**: cada teléfono reenvía los mensajes de los demás, así el alcance crece con cada vecino que tiene la app abierta.

> App Android · Kotlin + Jetpack Compose · Google Nearby Connections · cifrado AES‑GCM

---

## ¿Qué hace?

| | Función |
|---|---|
| 📡 **Radar** | Muestra a los vecinos cercanos: distancia y dirección (GPS + brújula) o, sin GPS, a cuántos "saltos" están. Se ve quién está conectado con quién (los "puentes"). |
| 💬 **Mensajes** | Lista fija de conversaciones: **📢 Barrio** (público), **👥 grupos** (familia, amigos…) y **🔒 contactos** (privado). Nunca se envía "a todos" por error. Los mensajes quedan guardados. |
| 🎙 **Walkie‑talkie** | Voz en vivo, presionar para hablar (o manos libres, o con el botón de volumen). Al barrio, a un grupo o a un contacto. |
| 🚨 **Botón de pánico** | Mantener SOS 2 s: el teléfono suena con una sirena (aunque esté en silencio), la linterna parpadea SOS en Morse, los contactos reciben "PIDE AYUDA" con tu ubicación y los desconocidos ven un 🚨 en su radar. |
| 📍 **Encuéntrame** | Si autorizas a un contacto, él puede hacer **sonar tu teléfono** a distancia (por ejemplo si quedaste atrapado) y acercarse guiado por GPS, intensidad de Bluetooth ("frío / caliente") y detección de los pitidos por micrófono, incluido un tono de ultrasonido. |
| 🕵️ **Privacidad** | Los desconocidos solo ven un **apodo divertido** ("🦝 Mapache feliz", "🐙 Pulpo sabio"), nunca tu nombre ni tu ubicación. Tu nombre real, posición y batería solo los ven tus **contactos de confianza**. |
| 💤 **Reposo** | "Apagar" la app no te hace desaparecer: sigues en el radar, recibes mensajes, alertas y Encuéntrame; solo se apaga la voz. Gasta muy poca batería. |
| 🔋 **Modo ahorro** | Con batería baja (o siempre, si quieres) busca vecinos por ratos y deja dormir al teléfono. Lo que llega por conexiones ya hechas sigue llegando al instante. |

---

## Instalar

1. Descarga **`WalkieBarrio.apk`** desde **[Releases](../../releases/latest)** y pásalo a los teléfonos (WhatsApp, Bluetooth, Quick Share, pendrive…).
2. Ábrelo y permite **"Instalar apps desconocidas"**. Si Play Protect avisa, toca **"Más detalles → Instalar de todas formas"** (es normal en apps que no vienen de la Play Store).
3. Al abrir la app, acepta los permisos: **ubicación, dispositivos cercanos, micrófono y notificaciones**.
4. **Xiaomi / Redmi / POCO:** en 👥 Contactos aparece un aviso amarillo; toca **🔋 Permitir** e **Inicio automático**. Si no, el sistema cierra la app y desapareces del radar.

**Requisitos**
- Android **10 o más nuevo**, con servicios de Google (Play Services).
  ❌ No funciona en Huawei recientes sin Google ni en iPhone.
- Bluetooth, Wi‑Fi y Ubicación **activados**. **No** hace falta internet ni conectarse a ninguna red Wi‑Fi.

> Prepárala **antes** de una emergencia: instálala, agrega a tus contactos y crea tus grupos mientras todavía hay internet para compartir el APK.

---

## Cómo se usa

### Primeros pasos
1. **👥 Contactos → ✏️ Cambiar:** escribe tu nombre (solo lo verán tus contactos).
2. **➕ Invitar a alguien:** muestra un **código QR**; la otra persona toca **📷 Escanear QR**. También se puede enviar el código por WhatsApp/SMS y pegarlo con **📋 Pegar código** (vale 7 días, sirve una sola vez).
   Desde ese momento se ven con su nombre real y pueden escribirse y hablarse en privado.
3. **💬 Mensajes → ➕ Nuevo grupo:** por ejemplo "Familia", con los contactos que elijas.

### Mensajes y voz
- En **💬 Mensajes** eliges una conversación de la lista. La barra de arriba dice siempre a quién le escribes: **ámbar = público** (lo leen todos los vecinos, también desconocidos), **verde = privado y cifrado**.
- En **🎙 Walkie** primero eliges con quién hablar (no hay canal por defecto). Mantén presionado el botón verde para hablar.
- **📣 Llamar** (desde el radar) hace sonar un aviso en el teléfono del contacto para que abra el walkie.

### Emergencias
- **🚨 SOS:** mantener presionado 2 segundos (o desde la notificación, con 5 s para cancelar).
- **📍 Encuéntrame:** en 👥 Contactos activa *"📍 Puede hacer sonar mi teléfono"* para las personas que podrán buscarte. Para buscar a alguien que te dio permiso: *"📍 Encuéntrame: buscar a …"*. Su teléfono suena hasta que lo encuentres o él toque *"Estoy bien"*.

### Batería y apagado
- **⚡ Normal / 🔋 Ahorro:** toca el indicador de batería para elegir *Automático* (ahorra bajo 30 %), *Siempre* o *Nunca*.
- **Apagar → 💤 Reposo** (recomendado): sigues en el radar y recibes mensajes, alertas y Encuéntrame; solo se apaga la voz. Los demás te ven con 💤.
- **Desconectar del todo:** desapareces de la malla. No recomendado en una emergencia.
- La app **vuelve sola** si el sistema la cierra, si se reinicia el teléfono o si se actualiza.

---

## Cómo funciona

### La malla
```
  📱 Ana ───── 📱 Pedro ───── 📱 Sede vecinal ───── 📱 Luis
     (Ana no alcanza a Luis, pero sus mensajes llegan saltando por Pedro y la Sede)
```
- Cada teléfono **anuncia** su presencia y **busca** a otros con [Nearby Connections](https://developers.google.com/nearby/connections/overview) (estrategia `P2P_CLUSTER`: Bluetooth, BLE y Wi‑Fi Direct, sin router).
- Se conecta automáticamente con los que tiene cerca y **reenvía** todo lo que recibe a sus otros vecinos (*flooding*), con un **TTL de 6 saltos** y una memoria de mensajes ya vistos para no repetirlos.
- Cada 4 s (o una vez por ciclo en ahorro) envía un **beacon**: sus vecinos directos (público, para dibujar los puentes) + nombre, ubicación y batería **cifrados** para sus contactos.
- Un teléfono **reenvía también lo que no puede leer** (mensajes privados de otros): así cualquier vecino sirve de puente sin poder espiar.

### Identidad y cifrado
- Cada teléfono genera un **id aleatorio**; el **apodo** (animal + adjetivo) se deriva de ese id. El nombre real nunca viaja en claro.
- Cada persona tiene una **clave de círculo** (AES‑128) con la que cifra su nombre/ubicación en el beacon; se la entrega a sus contactos al emparejarse.
- Cada **par de contactos** comparte una **clave del par** (el secreto de la invitación) para mensajes, voz, pánico y Encuéntrame privados.
- Cada **grupo** tiene su propia clave, que el creador entrega cifrada a cada miembro. Si saca a alguien, la clave cambia.
- Todo va con **AES‑GCM**, autenticando la cabecera del mensaje. Al quitar un contacto se rota la clave de círculo.
- Lo que se entrega a alguien que no está al alcance (invitaciones, claves, grupos, permisos) queda en una **cola** y se reintenta cuando aparezca en la malla.

### Voz
- Audio de 8 kHz en **µ‑law** (64 kbps), paquetes de 80 ms, para que aguante varios saltos.
- Un solo hablante a la vez por canal ("canal ocupado"), con pitido de fin de transmisión.

### Encuéntrame
- El teléfono buscado emite una **radiobaliza BLE** con una ficha que solo su contacto reconoce (HMAC de la clave del par) → el buscador mide la señal (RSSI) y estima la distancia.
- Además suena por el canal de alarma: 3 pitidos de **3 kHz** + un tono de **18,5 kHz** cada 3 s. El buscador los detecta con el micrófono (algoritmo de Goertzel) y muestra qué tan fuerte los oye.

### Protocolo
Cabecera fija de 16 bytes (siempre visible para poder reenviar): `'M'`, versión (2), tipo (bit 0x80 = cuerpo cifrado), TTL, origen, id del mensaje, destino (0 = todos, id de un nodo o id de un grupo).

| Tipo | Uso |
|---|---|
| 1 `BEACON` | presencia + vecinos + datos privados cifrados + banderas (🚨 pánico, 💤 reposo) |
| 2 `CHAT` / 18 `GCHAT` | texto al barrio o privado / texto a un grupo |
| 3 `CALL` · 4 `VOICE` · 5 `VOICE_END` | llamado y voz |
| 6–9 `PAIR_*`, `KEY_*` | emparejar contactos y rotar claves |
| 10–11 `SOS` | alarma de pánico y confirmación |
| 12–15 `FIND`, `GRANT` | Encuéntrame y su permiso |
| 16–17 `GROUP` | entrega de grupos |

### Segundo plano y batería
- Un **servicio en primer plano** mantiene la malla con la pantalla apagada.
- En **ahorro/reposo** busca vecinos 15 s cada 45–90 s usando `AlarmManager` y deja dormir la CPU entre ciclos.
- `START_STICKY` + receptor de `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`: vuelve sola tras un cierre, reinicio o actualización.
  Si arrancó sin que nadie abriera la app, Android no le permite usar la ubicación: se deja **encontrar** pero no **busca** hasta que se abre una vez.

---

## Compilar desde el código

Requisitos: Android SDK (compileSdk 36), JDK 17.

```bash
cd walkie-android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug          # APK de prueba: app/build/outputs/apk/debug/
```

El APK oficial se firma con `walkie-android/firmar.sh`, que necesita la carpeta privada `firma/` (clave de la app). **Esa carpeta no está en el repositorio a propósito**: con ella cualquiera podría publicar una actualización falsa. Si compilas tu propia versión, quedará firmada con tu clave y no podrá instalarse *encima* de la oficial (hay que desinstalar primero).

### Estructura
```
walkie-android/app/src/main/java/com/radiobarrio/walkie/
  MeshEngine.kt   motor de la malla: conexiones, reenvío, beacon, chat, voz, pánico, Encuéntrame, ahorro
  MeshProto.kt    formato binario de los mensajes y códec de audio
  MeshService.kt  servicio en primer plano, notificaciones, arranque automático
  Identity.kt     identidad, apodos, contactos, invitaciones, cifrado
  Groups.kt       grupos e historial de mensajes
  Alarm.kt        sirena, pitidos de Encuéntrame y linterna SOS
  Proximity.kt    radiobaliza BLE y detector de pitidos por micrófono
  Radar.kt        dibujo del radar
  MainActivity.kt pantallas (Compose)
servidor.py + index.html   versión web anterior (ver abajo)
LEEME.md                   notas detalladas de cada versión
```

---

## Versión web (alternativa sin instalar nada)

`servidor.py` + `index.html` es la primera versión del proyecto: **un teléfono hace de central** (comparte su Wi‑Fi y corre el servidor en [Termux](https://f-droid.org/packages/com.termux/)) y los vecinos abren una página en el navegador. Tiene chat con canales, SOS, notas de voz, fotos y un tablero de estado de vecinos. Solo necesita Python 3, sin librerías.

```bash
python servidor.py      # luego abrir http://<ip-del-hotspot>:8080
```

Instrucciones completas en [LEEME.md](LEEME.md).

---

## Limitaciones

- El alcance entre dos teléfonos es de **~10–100 m** según muros y modelo; la malla lo extiende con cada vecino que tenga la app.
- La ubicación en interiores puede ser imprecisa; el radar muestra el margen de error.
- Los mensajes privados llegan si el destinatario está conectado a la malla en ese momento (no hay almacenamiento diferido, salvo invitaciones, claves y grupos).
- Requiere Google Play Services.

## Próximos pasos
- Nodos fijos de muy bajo consumo (nRF52840 + LoRa, alimentados con un panel solar pequeño) para extender la malla a kilómetros sin depender de que haya vecinos de puente.
