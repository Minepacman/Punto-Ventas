#!/usr/bin/env bash
# Baja los archivos de recursos.txt a la carpeta indicada y revisa tamaño y SHA-256 de cada uno.
#   bash packaging/ocr-modelos/bajar.sh <carpeta destino> [url base]
# Sin "url base" baja de las fuentes originales (lo que hace ocr-modelos.yml antes de publicar el espejo);
# con ella, de <url base><nombre> (para comprobar el espejo ya publicado). Sale con error si algo no coincide.
set -euo pipefail
destino="$1"
base="${2:-}"
lista="$(dirname "$0")/recursos.txt"
mkdir -p "$destino"
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
while read -r suma bytes nombre url; do
  case "$suma" in ''|'#'*) continue ;; esac
  if [ -n "$base" ]; then url="$base$nombre"; fi
  echo "Bajando $nombre de $url"
  curl -fsSL --retry 3 --retry-delay 5 -o "$destino/$nombre" "$url"
  real_bytes=$(wc -c < "$destino/$nombre" | tr -d ' ')
  real_sha=$(sha "$destino/$nombre")
  if [ "$real_bytes" != "$bytes" ] || [ "$real_sha" != "$suma" ]; then
    echo "::error::$nombre no coincide: $real_bytes bytes, sha256 $real_sha (se esperaba $bytes, $suma)"
    exit 1
  fi
  echo "  ok: $bytes bytes, sha256 $suma"
done < "$lista"
