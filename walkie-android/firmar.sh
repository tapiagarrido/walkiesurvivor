#!/usr/bin/env bash
# Compila Walkie Barrio y la firma con la clave propia de la app (firma/walkie-barrio.jks).
# La firma incluye la "prueba de rotación" desde la clave antigua de desarrollo, así los
# teléfonos que tenían la versión anterior se actualizan sin desinstalar (y sin perder datos).
#
# ⚠️ Respalda la carpeta firma/ (por ejemplo en un pendrive). Sin ella no se pueden
#    publicar actualizaciones: cada vecino tendría que desinstalar y perder sus contactos.
set -euo pipefail
cd "$(dirname "$0")"

F=firma
[ -f "$F/walkie-barrio.jks" ] || { echo "Falta $F/walkie-barrio.jks (la clave de la app)"; exit 1; }
PW=$(grep '^storePassword=' "$F/firma.properties" | cut -d= -f2-)
APKSIGNER=$(ls "$HOME"/Android/Sdk/build-tools/*/apksigner | sort -V | tail -1)

# JDK de Android Studio si está instalado (en otro computador, el JAVA_HOME del sistema)
[ -z "${JAVA_HOME:-}" ] && [ -d /snap/android-studio/current/jbr ] && export JAVA_HOME=/snap/android-studio/current/jbr
./gradlew -q assembleRelease

"$APKSIGNER" sign \
  --ks "$F/clave-anterior-debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$F/walkie-barrio.jks" --ks-key-alias walkiebarrio --ks-pass "pass:$PW" --key-pass "pass:$PW" \
  --lineage "$F/linaje-rotacion.bin" --rotation-min-sdk-version 29 \
  --out ../WalkieBarrio.apk app/build/outputs/apk/release/app-release.apk

"$APKSIGNER" verify ../WalkieBarrio.apk
echo "Listo: $(cd .. && pwd)/WalkieBarrio.apk"
