#!/usr/bin/env python3
"""
Radio Barrio - chat comunitario de emergencia SIN internet.

Un teléfono (o PC) corre este servidor y comparte su Wi-Fi (hotspot o router).
Los vecinos se conectan a esa red y abren http://<ip>:8080 en el navegador.
No necesita librerías externas: solo Python 3 (funciona en Termux/Android).

Uso:  python servidor.py          (puerto 8080)
      PUERTO=9000 python servidor.py
"""
import json
import os
import re
import socket
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

PUERTO = int(os.environ.get("PUERTO", "8080"))
BASE = os.path.dirname(os.path.abspath(__file__))
DATOS = os.path.join(BASE, "datos")
ARCHIVOS = os.path.join(DATOS, "archivos")
F_MENSAJES = os.path.join(DATOS, "mensajes.jsonl")
F_ESTADOS = os.path.join(DATOS, "estados.json")
F_INDEX = os.path.join(BASE, "index.html")

CANALES = ("general", "emergencias", "recursos")
ESTADOS = ("bien", "ayuda", "herido")
MAX_ARCHIVO = 8 * 1024 * 1024  # 8 MB
MAX_TEXTO = 1000
MAX_EN_RESPUESTA = 300

EXT_MIME = {
    "webm": "audio/webm", "ogg": "audio/ogg", "mp3": "audio/mpeg",
    "m4a": "audio/mp4", "aac": "audio/aac", "amr": "audio/amr",
    "3gp": "audio/3gpp", "wav": "audio/wav", "opus": "audio/ogg",
    "jpg": "image/jpeg", "png": "image/png", "webp": "image/webp",
}
MIME_EXT = {
    "audio/webm": "webm", "audio/ogg": "ogg", "audio/mpeg": "mp3",
    "audio/mp3": "mp3", "audio/mp4": "m4a", "audio/x-m4a": "m4a",
    "audio/aac": "aac", "audio/amr": "amr", "audio/3gpp": "3gp",
    "audio/wav": "wav", "audio/x-wav": "wav", "audio/opus": "opus",
    "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp",
}
RE_ARCHIVO = re.compile(r"^[0-9a-f]{32}\.[a-z0-9]{2,4}$")

lock = threading.Lock()
mensajes = []   # en memoria, también en mensajes.jsonl
estados = {}    # cliente -> {nombre, estado, lugar, ofrece, ts}
ultimo_id = 0


def cargar():
    global ultimo_id, estados
    os.makedirs(ARCHIVOS, exist_ok=True)
    if os.path.exists(F_MENSAJES):
        with open(F_MENSAJES, encoding="utf-8") as f:
            for linea in f:
                try:
                    m = json.loads(linea)
                    mensajes.append(m)
                    ultimo_id = max(ultimo_id, m["id"])
                except (ValueError, KeyError):
                    pass
    if os.path.exists(F_ESTADOS):
        try:
            with open(F_ESTADOS, encoding="utf-8") as f:
                estados = json.load(f)
        except ValueError:
            estados = {}


def limpiar(txt, maximo):
    return str(txt or "").strip()[:maximo]


def agregar_mensaje(datos):
    global ultimo_id
    with lock:
        ultimo_id += 1
        m = dict(datos, id=ultimo_id, ts=int(time.time()))
        mensajes.append(m)
        if len(mensajes) > 2000:
            del mensajes[:500]
        with open(F_MENSAJES, "a", encoding="utf-8") as f:
            f.write(json.dumps(m, ensure_ascii=False) + "\n")
    return m


def guardar_estado(cliente, datos):
    with lock:
        estados[cliente] = dict(datos, ts=int(time.time()))
        tmp = F_ESTADOS + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(estados, f, ensure_ascii=False)
        os.replace(tmp, F_ESTADOS)


class Manejador(BaseHTTPRequestHandler):
    server_version = "RadioBarrio/1.0"

    def log_message(self, fmt, *args):  # silencioso salvo errores
        pass

    # ---------- utilidades ----------
    def responder(self, codigo, cuerpo, tipo="application/json; charset=utf-8", extra=None):
        if isinstance(cuerpo, (dict, list)):
            cuerpo = json.dumps(cuerpo, ensure_ascii=False).encode("utf-8")
        elif isinstance(cuerpo, str):
            cuerpo = cuerpo.encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", tipo)
        self.send_header("Content-Length", str(len(cuerpo)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(cuerpo)

    def leer_cuerpo(self, maximo):
        largo = int(self.headers.get("Content-Length") or 0)
        if largo <= 0 or largo > maximo:
            return None
        return self.rfile.read(largo)

    def leer_json(self):
        crudo = self.leer_cuerpo(64 * 1024)
        if crudo is None:
            return None
        try:
            d = json.loads(crudo.decode("utf-8"))
            return d if isinstance(d, dict) else None
        except ValueError:
            return None

    # ---------- GET ----------
    def do_GET(self):
        url = urlparse(self.path)
        ruta = url.path

        if ruta in ("/", "/index.html"):
            with open(F_INDEX, "rb") as f:
                return self.responder(200, f.read(), "text/html; charset=utf-8")

        if ruta == "/api/mensajes":
            q = parse_qs(url.query)
            try:
                desde = int(q.get("desde", ["0"])[0])
            except ValueError:
                desde = 0
            with lock:
                nuevos = [m for m in mensajes if m["id"] > desde][-MAX_EN_RESPUESTA:]
                lista_estados = [dict(v, cliente=k) for k, v in estados.items()]
                ult = ultimo_id
            return self.responder(200, {
                "mensajes": nuevos, "ultimo": ult,
                "estados": lista_estados, "ahora": int(time.time()),
            })

        if ruta.startswith("/archivos/"):
            nombre = ruta[len("/archivos/"):]
            if not RE_ARCHIVO.match(nombre):
                return self.responder(404, {"error": "no existe"})
            camino = os.path.join(ARCHIVOS, nombre)
            if not os.path.isfile(camino):
                return self.responder(404, {"error": "no existe"})
            ext = nombre.rsplit(".", 1)[1]
            with open(camino, "rb") as f:
                datos = f.read()
            return self.responder(200, datos, EXT_MIME.get(ext, "application/octet-stream"),
                                  {"Cache-Control": "public, max-age=86400"})

        if ruta == "/api/ping":
            return self.responder(200, {"ok": True})

        # Cualquier otra ruta: llevar al chat
        self.send_response(302)
        self.send_header("Location", "/")
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------- POST ----------
    def do_POST(self):
        ruta = urlparse(self.path).path

        if ruta == "/api/mensajes":
            d = self.leer_json()
            if not d:
                return self.responder(400, {"error": "datos inválidos"})
            nombre = limpiar(d.get("nombre"), 40)
            texto = limpiar(d.get("texto"), MAX_TEXTO)
            canal = d.get("canal")
            sos = bool(d.get("sos"))
            if sos:
                canal = "emergencias"
            if not nombre or not texto or canal not in CANALES:
                return self.responder(400, {"error": "falta nombre, texto o canal"})
            m = agregar_mensaje({
                "nombre": nombre, "cliente": limpiar(d.get("cliente"), 40),
                "canal": canal, "tipo": "texto", "texto": texto, "sos": sos,
            })
            return self.responder(200, m)

        if ruta == "/api/archivo":
            tipo = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
            ext = MIME_EXT.get(tipo)
            if not ext and tipo.startswith("audio/"):
                ext = "ogg"
            if not ext:
                return self.responder(415, {"error": "solo audio o imagen"})
            nombre = limpiar(unquote(self.headers.get("X-Nombre") or ""), 40)
            canal = self.headers.get("X-Canal")
            if not nombre or canal not in CANALES:
                return self.responder(400, {"error": "falta nombre o canal"})
            datos = self.leer_cuerpo(MAX_ARCHIVO)
            if datos is None:
                return self.responder(413, {"error": "archivo vacío o mayor a 8 MB"})
            archivo = uuid.uuid4().hex + "." + ext
            with open(os.path.join(ARCHIVOS, archivo), "wb") as f:
                f.write(datos)
            m = agregar_mensaje({
                "nombre": nombre, "cliente": limpiar(self.headers.get("X-Cliente"), 40),
                "canal": canal, "tipo": "audio" if tipo.startswith("audio/") else "imagen",
                "archivo": "/archivos/" + archivo, "sos": False,
            })
            return self.responder(200, m)

        if ruta == "/api/estado":
            d = self.leer_json()
            if not d:
                return self.responder(400, {"error": "datos inválidos"})
            cliente = limpiar(d.get("cliente"), 40)
            nombre = limpiar(d.get("nombre"), 40)
            estado = d.get("estado")
            if not cliente or not nombre or estado not in ESTADOS:
                return self.responder(400, {"error": "falta nombre o estado"})
            guardar_estado(cliente, {
                "nombre": nombre, "estado": estado,
                "lugar": limpiar(d.get("lugar"), 120),
                "ofrece": limpiar(d.get("ofrece"), 200),
            })
            return self.responder(200, {"ok": True})

        self.responder(404, {"error": "ruta desconocida"})


def ips_locales():
    ips = set()
    try:
        salida = subprocess.run(["ip", "-4", "-o", "addr"], capture_output=True,
                                text=True, timeout=3).stdout
        ips.update(re.findall(r"inet (\d+\.\d+\.\d+\.\d+)", salida))
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        ips.update(i[4][0] for i in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET))
    except OSError:
        pass
    for destino in ("192.168.1.1", "10.0.0.1"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((destino, 80))
            ips.add(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


def main():
    cargar()
    servidor = ThreadingHTTPServer(("0.0.0.0", PUERTO), Manejador)
    servidor.daemon_threads = True
    print("=" * 52)
    print("  📻 RADIO BARRIO funcionando")
    print("=" * 52)
    ips = ips_locales()
    if ips:
        print("  Los vecinos deben abrir en el navegador:")
        for ip in ips:
            print(f"     http://{ip}:{PUERTO}")
    else:
        print("  No pude detectar la IP. Revísala en Ajustes > Wi-Fi")
        print(f"  y abre http://<esa-ip>:{PUERTO}")
    print(f"  Mensajes guardados: {len(mensajes)}")
    print("  (Ctrl+C para detener)")
    print("=" * 52)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        print("\nDetenido.")


if __name__ == "__main__":
    main()
