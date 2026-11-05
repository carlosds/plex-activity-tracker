#!/usr/bin/env bash
# Genera una clave de firma propia para el APK y muestra los valores que hay que
# guardar como secretos del repo (Settings → Secrets and variables → Actions).
# Si tienes `gh` autenticado, puede subirlos por ti con --upload.
#
# Uso: scripts/create-signing-key.sh [--upload]
# IMPORTANTE: guarda el .jks y la contraseña en un lugar seguro (gestor de contraseñas).
# Si los pierdes no podrás actualizar la app instalada; si se filtran, otros podrían firmar como tú.
set -euo pipefail

ALIAS="plexactivitytracker"
JKS="plexactivitytracker.jks"

if [ -e "$JKS" ]; then echo "Ya existe $JKS; bórralo o muévelo antes." >&2; exit 1; fi

read -rsp "Contraseña para el keystore y la clave (mín. 6 caracteres): " PASS; echo
keytool -genkeypair -v -keystore "$JKS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass "$PASS" -keypass "$PASS" -dname "CN=Plex Activity Tracker"

B64=$(base64 -w0 "$JKS" 2>/dev/null || base64 -i "$JKS")

if [ "${1:-}" = "--upload" ]; then
  gh secret set KEYSTORE_BASE64 --body "$B64"
  gh secret set KEYSTORE_PASSWORD --body "$PASS"
  gh secret set KEY_ALIAS --body "$ALIAS"
  gh secret set KEY_PASSWORD --body "$PASS"
  echo "Secretos subidos. Guarda $JKS y la contraseña en un lugar seguro (no lo subas al repo)."
else
  echo
  echo "Crea estos 4 secretos en el repo:"
  echo "  KEYSTORE_PASSWORD = (la contraseña que has escrito)"
  echo "  KEY_PASSWORD      = (la misma contraseña)"
  echo "  KEY_ALIAS         = $ALIAS"
  echo "  KEYSTORE_BASE64   = (contenido de abajo)"
  echo
  echo "$B64"
fi
