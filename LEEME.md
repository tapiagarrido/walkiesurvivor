# 📻 Radio Barrio — chat de vecinos sin internet

Un teléfono hace de **central**: comparte su Wi‑Fi (zona Wi‑Fi / hotspot) y corre un
pequeño servidor. Los vecinos se conectan a ese Wi‑Fi y abren una dirección en el
navegador. **No necesitan instalar nada** y no se usan datos móviles ni internet.

Qué incluye:
- **Chat** con 3 canales: General, 🚨 Urgente y 📦 Recursos
- **Botón SOS**: alerta a todos los conectados (vibra y suena)
- **Notas de voz** 🎤 y **fotos** 📷 (las fotos se reducen automáticamente)
- **Tablero de vecinos**: cada persona marca ✅ Bien / 🆘 Ayuda / 🩹 Heridos, dónde está y qué puede ofrecer
- El historial queda guardado en la carpeta `datos/` del teléfono central

---

## 1. Preparar el teléfono central (una sola vez, CON internet)

1. Instala **Termux** desde **F-Droid** (https://f-droid.org). La versión de Play Store está desactualizada.
2. Abre Termux y escribe:
   ```
   pkg update && pkg install python
   termux-setup-storage
   ```
3. Copia la carpeta `radio-barrio` (con `servidor.py` e `index.html`) al teléfono,
   por ejemplo a `Descargas`, y luego en Termux:
   ```
   cp -r ~/storage/downloads/radio-barrio ~/
   ```

Hazlo **ahora**, no durante la emergencia. Lo ideal es dejarlo preparado en 2 o 3 teléfonos del barrio.

## 2. Encender la central (sin internet)

1. Activa la **Zona Wi‑Fi / Hotspot** del teléfono. Ponle un nombre fácil
   (ej. `RADIO-BARRIO`) y una clave simple que todos sepan (o déjala abierta).
2. En Termux:
   ```
   cd ~/radio-barrio
   python servidor.py
   ```
3. La pantalla mostrará algo como `http://192.168.43.1:8080`. **Esa es la dirección para los vecinos.**
   Escríbela en un papel o cartel.
4. En la barra de notificaciones de Termux toca **"Acquire wakelock"** para que Android no lo duerma.

## 3. Conectarse como vecino

1. Ajustes → Wi‑Fi → conectarse a `RADIO-BARRIO`.
   Si el teléfono avisa "sin internet", elige **"Mantener conexión"**.
2. Abrir Chrome y escribir la dirección (ej. `192.168.43.1:8080`).
3. Poner su nombre. Listo.
4. Consejo: menú ⋮ → **"Agregar a pantalla principal"** para tener un ícono.

**Mantén la página abierta** para recibir alertas. Si el navegador queda en segundo plano,
Android puede pausarla; al volver se actualiza sola.

---

## Alcance: cómo llegar a ~150 metros

Un hotspot de teléfono alcanza normalmente **50–100 m** en exterior, menos con muros.
Para acercarse a 150 m:

| Medida | Efecto |
|---|---|
| Poner la central **en alto** (2º piso, ventana, techo) | La mejora más grande y gratis |
| Línea de vista hacia la calle, sin muros de concreto | Mucho mejor alcance |
| Usar un **router Wi‑Fi** viejo con power bank o batería 12 V en vez del hotspot | 100–200 m o más; el teléfono/PC se conecta al router y corre el servidor |
| Router o antena **exterior** (tipo CPE, ~US$30–50) | Fácilmente 300 m o más |
| **2 centrales** en distintos puntos | Cubren más zona (pero cada una tiene su propio chat) |

Ojo: el límite también es el teléfono del vecino, que transmite más débil que un router.
Prueben **antes**: caminen con un teléfono y anoten hasta dónde llega el punto verde.

## Batería

- Central: conectada a power bank o panel solar, pantalla apagada (el servidor sigue con el wakelock).
- Un hotspot activo gasta bastante: cuenta con unas 4–8 horas con la batería del teléfono sola.

## También sirve en un PC o notebook

Con Python 3 instalado: `python3 servidor.py`. El PC puede crear la red (hotspot) o
conectarse a un router. No requiere nada más.

## Notas técnicas

- Puerto distinto: `PUERTO=9000 python servidor.py`
- Borrar historial: detener el servidor y eliminar la carpeta `datos/`
- Seguridad: cualquiera conectado al Wi‑Fi puede leer y escribir. Usen clave en el hotspot
  si no quieren desconocidos.
- Las notas de voz usan la grabadora del teléfono. Si un teléfono no tiene grabadora,
  se abre el selector de archivos.

---

# 🎙 Walkie Barrio — malla de teléfonos (sin router, sin internet, sin servidor)

Cada teléfono se conecta **directo** con los que tiene cerca (Bluetooth y Wi‑Fi Direct) y
**reenvía** los mensajes de los demás. Si 3 teléfonos quedan en línea, el del medio hace de
**puente**: A habla con C aunque estén demasiado lejos entre sí.

```
  Tú ))) Ana ))) Pedro ))) Sede vecinal
  ~50–100 m por salto · hasta 6 saltos
```

## Qué hace
- **📡 Radar**: muestra a los vecinos conectados con su nombre.
  - Con GPS (funciona sin internet, mejor al aire libre): posición real, distancia y dirección.
    El radar gira con la **brújula**, o se fija con "Norte arriba".
  - Sin GPS: se ubican en anillos según los **saltos** (1 salto = conexión directa).
  - Líneas punteadas: quién está conectado con quién (se ve el puente).
  - Verde = conexión directa, ámbar = llega a través de otros.
  - Toca un vecino para ver distancia, batería, por quién pasa la conexión y:
    **📣 Llamar** (le suena y vibra un aviso "X quiere comunicarse contigo"), **💬 Mensaje** o **🎙 Hablarle**.
- **💬 Mensajes**: a todos o privados a una persona. Viajan de teléfono en teléfono.
- **🎙 Walkie**: mantén presionado para hablar, a **todos** o a **una persona**.
  Opciones: manos libres y botón de bajar volumen para hablar.
- Sigue funcionando con la pantalla apagada (notificación fija "malla activa").
- **Demostración**: si no hay nadie cerca, toca "Ver demostración" (o mantén presionado el radar)
  para ver 4 vecinos simulados en línea.

## Instalar
1. Pasa `WalkieBarrio.apk` a cada teléfono (cable, Bluetooth, Quick Share…) e instálalo.
2. Acepta todos los permisos: micrófono, ubicación, dispositivos cercanos y notificaciones.
3. Escribe tu nombre.
4. Deja activados **Bluetooth, Wi‑Fi y Ubicación** (no hace falta estar conectado a ninguna red).
5. Xiaomi/Redmi/POCO: Ajustes → Apps → Walkie Barrio → Ahorro de batería → **Sin restricciones**.

Requiere Android 10+ con servicios de Google Play (no funciona en iPhone ni en Huawei sin Google).

## 🔋 Modo ahorro (v0.3)
Toca **⚡ Normal / 🔋 Ahorro** arriba a la derecha:
- **Automático** (recomendado): ahorra cuando la batería baja de 30 % o el teléfono está en ahorro de energía; vuelve a normal al enchufarlo.
- **Siempre ahorrar**: para emergencias largas.
- **Nunca**: encuentra vecinos más rápido, gasta más.

En ahorro el teléfono busca vecinos nuevos 15 s cada 45–90 s (en vez de sin parar), se anuncia una vez por ciclo, usa ubicación aproximada sin GPS propio y deja dormir al procesador. Mensajes, llamados y voz de los vecinos ya conectados siguen llegando al instante. Encontrar a un vecino *nuevo* puede tardar 1–2 minutos.

## 👥 Contactos y privacidad (v0.4)
- Los desconocidos solo ven tu **apodo** (ej. "🐙 Pulpo sabio"): ni tu nombre ni tu ubicación.
- Tus **contactos de confianza** ven tu nombre, ubicación y batería, y pueden hablarte en privado (cifrado).
- Agregar: 👥 Contactos → **➕ Invitar** (QR en persona, o código por WhatsApp/SMS). El otro usa **📷 Escanear QR** o **📋 Pegar código**. Se confirma la primera vez que estén conectados por la malla.
- **Quitar** un contacto cambia tu clave: deja de verte.

## 🚨 Botón de pánico (v0.5)
- Mantén **SOS** (arriba) 2 segundos, o usa **🚨 Pánico** en la notificación (5 s para cancelar).
- Suena una sirena a todo volumen aunque el teléfono esté en silencio, y la linterna parpadea SOS.
- **Contactos:** reciben "🚨 … PIDE AYUDA" con distancia, dirección y batería, y les suena una alerta.
- **Desconocidos:** ven 🚨 junto a tu apodo en el radar, sin nombre ni ubicación.
- **DETENER** apaga todo y avisa que estás bien.

## 📍 Encuéntrame (v0.6)
- En 👥 Contactos activa **"📍 Puede hacer sonar mi teléfono si me pierdo"** para quien quieras (familia).
- Esa persona verá **"📍 Encuéntrame: buscar a …"**: tu teléfono suena solo (aunque esté en silencio), parpadea la linterna y emite una señal Bluetooth que solo ella reconoce.
- Su pantalla de búsqueda guía con 3 pistas: **GPS** (flecha y distancia, de lejos), **Bluetooth** (frío/caliente, < 30 m) y **sonido** (micrófono que escucha los pitidos, últimos metros).
- Quien es buscado puede tocar **"Estoy bien, detener sonido"**; si nadie lo detiene, para solo a los 10 minutos sin noticias del buscador.

## Alcance real (honesto)
- Por salto: ~**30–100 m** según obstáculos (Bluetooth); más si se activa Wi‑Fi Direct.
- 150 m o más se logra **encadenando teléfonos**: uno en una ventana alta a mitad de camino sirve de puente.
- La voz pasa por todos los saltos, con algo de retraso (~0,3–1 s). El texto llega aunque la señal sea mala.
- Cada teléfono puede mantener unas pocas conexiones directas a la vez (~4–7).

## Detalles técnicos
- Google Nearby Connections, estrategia P2P_CLUSTER (Bluetooth, BLE, Wi‑Fi Direct, Wi‑Fi LAN).
- Inundación con TTL 6 y eliminación de duplicados. Presencia cada 4 s.
- Voz: 8 kHz, G.711 µ-law, paquetes de 80 ms (64 kbps).
- Sin autenticación: cualquier teléfono con la app puede unirse (pensado para emergencias).
- Código en `walkie-android/`. Compilar: `cd walkie-android && ./gradlew assembleDebug`

## Reposo: "apagar" sin desaparecer (v0.7)

En una emergencia nadie debería desaparecer del radar por apagar la app. Por eso **"Apagar" ahora ofrece 💤 Reposo** (recomendado):

| | Encendida | 💤 Reposo | Desconectada del todo |
|---|---|---|---|
| Aparece en el radar | ✅ | ✅ (con 💤) | ❌ |
| Recibe mensajes | ✅ | ✅ | ❌ |
| Recibe alertas de pánico | ✅ | ✅ | ❌ |
| Sus contactos pueden hacerla sonar (Encuéntrame) | ✅ | ✅ | ❌ |
| Reenvía mensajes de otros (puente) | ✅ | ✅ | ❌ |
| Voz (walkie) y llamados | ✅ | ❌ | ❌ |
| Batería | normal / ahorro | ahorro siempre | nada |

- En reposo el teléfono busca vecinos por ratos (modo ahorro) y deja dormir al teléfono.
- Los demás ven **💤** junto a su nombre: no pueden elegirlo para hablar por voz, pero sí escribirle.
- Si activa su pánico o alguien la busca, sale sola del ahorro mientras dure.
- **Vuelve sola** si el sistema la cierra, si se reinicia el teléfono o si se actualiza la app (salvo que la persona la haya desconectado del todo).
- **Xiaomi / Redmi / POCO:** traen el permiso *Inicio automático* apagado y cierran la app al limpiar la memoria. En 👥 Contactos aparece un aviso con los botones **🔋 Permitir** e **Inicio automático**: hay que activar ambos en cada teléfono.

## Conversaciones y grupos (v0.8)

Antes, Mensajes y Walkie empezaban en "Todos", y era fácil enviar algo privado a todo el barrio por error. Ahora:

- **💬 Mensajes es una lista fija de conversaciones** (no se reordena): arriba **📢 Barrio · público** (en ámbar), luego tus **👥 grupos** y tus **🔒 contactos**. Se toca una y se entra a esa conversación; una barra arriba dice siempre a quién le escribes (ámbar = público, verde = privado). ← o el botón atrás vuelven a la lista.
- **🎙 Walkie empieza sin canal**: pregunta "¿Con quién quieres hablar?". La barra de arriba muestra el canal; "Cambiar" vuelve a la lista. Los contactos fuera de alcance o en 💤 reposo aparecen desactivados.
- **Grupos** ("➕ Nuevo grupo"): nombre + contactos. Cada grupo tiene su propia clave; los mensajes y la voz del grupo solo los leen y oyen sus miembros (los demás teléfonos solo los reenvían). Cada miembro recibe el grupo cuando esté al alcance de la malla. ⚙️ muestra los miembros; quien lo creó puede editarlo o eliminarlo para todos, los demás pueden salirse. Si se saca a alguien, la clave cambia.
- Los mensajes **quedan guardados** en el teléfono (los últimos 500).
- Las notificaciones abren directo la conversación correspondiente.

## Firma de la app y cómo generar el APK (v0.8.2)

La app tiene **su propia clave de firma** (`walkie-android/firma/`). Android solo instala una actualización si viene firmada con la misma clave, así que:

- **Para generar el APK:** `walkie-android/firmar.sh` → deja `WalkieBarrio.apk` listo para repartir.
- **⚠️ Respalda la carpeta `walkie-android/firma/` completa** (pendrive, disco externo). Contiene la clave, su contraseña (`firma.properties`), la clave antigua de desarrollo y la prueba de rotación. **Si se pierde, no se pueden publicar actualizaciones**: cada vecino tendría que desinstalar y perder contactos, grupos y mensajes. No la compartas: con ella cualquiera podría publicar una "actualización" falsa.
- Los teléfonos que tenían versiones anteriores (firmadas con la clave de desarrollo) se actualizan sin desinstalar gracias a la rotación de firma (Android 10+).

### Para instalar en otros teléfonos
- Android 10 o más nuevo, con servicios de Google (no funciona en Huawei recientes sin Google, ni en iPhone).
- Al abrir el APK, permitir "instalar apps desconocidas". Si Play Protect avisa, "Más detalles → Instalar de todas formas".
- Xiaomi/Redmi/POCO: en 👥 Contactos tocar **🔋 Permitir** e **Inicio automático**.
- Tras reiniciar el teléfono la app vuelve sola y se deja encontrar; busca vecinos nuevos en cuanto se abre una vez.
