#!/usr/bin/env bash
# Baja spa.traineddata y eng.traineddata de tessdata_fast 4.1.0 (Apache-2.0) a la carpeta indicada y revisa su
# SHA-256 contra tessdata.sha256 y agrega configs/tsv (los mismos archivos que trae Homebrew: tesseract + tesseract-lang). Se usa en el
# CI para Windows y macOS; así todas las plataformas llevan exactamente los mismos idiomas.
#   bash packaging/tesseract/bajar-tessdata.sh <carpeta destino>
set -euo pipefail
destino="$1"
aqui="$(cd "$(dirname "$0")" && pwd)"
base="https://github.com/tesseract-ocr/tessdata_fast/raw/4.1.0"
mkdir -p "$destino"
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
while read -r suma nombre; do
  [ -z "$suma" ] && continue
  nombre="${nombre#\*}"
  nombre="${nombre%$'\r'}"
  curl -fsSL --retry 3 --retry-delay 5 -o "$destino/$nombre" "$base/$nombre"
  real=$(sha "$destino/$nombre")
  if [ "$real" != "$suma" ]; then
    echo "::error::$nombre: sha256 $real, se esperaba $suma"
    exit 1
  fi
  echo "$nombre ok ($(wc -c < "$destino/$nombre" | tr -d ' ') bytes)"
done < "$aqui/tessdata.sha256"
# configs/tsv: el programa pide la salida con el config "tsv" (ocr/MotorTesseract); sin este archivo Tesseract
# devuelve texto plano y el programa no entiende la lectura. Es el de Tesseract (Apache-2.0), de una línea.
mkdir -p "$destino/configs"
cp "$aqui/configs/tsv" "$destino/configs/tsv"
# Licencia de los datos de idioma (Apache-2.0, la misma de Tesseract)
curl -fsSL --retry 3 -o "$destino/LICENSE-tessdata_fast.txt" "$base/LICENSE"
