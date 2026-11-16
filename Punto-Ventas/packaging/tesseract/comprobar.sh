#!/usr/bin/env bash
# Comprueba un Tesseract tal como lo usa el programa (ocr/LocalizadorTesseract y ocr/MotorTesseract):
#   1. arranca (--version) — falla si faltan bibliotecas/DLLs o es de otra arquitectura que no corre aquí;
#   2. ve "spa" y "eng" en la carpeta de idiomas;
#   3. lee el ticket de muestra desde un archivo y por stdin en TSV (como el programa) y encuentra el total.
#   bash packaging/tesseract/comprobar.sh <ejecutable> <carpeta tessdata | ""> <imagen de muestra>
# Corre en macOS (bash 3.2), Linux y Git Bash de Windows.
set -euo pipefail
exe="$1"
tessdata="${2:-}"
muestra="$3"
export OMP_THREAD_LIMIT=1
opciones=()
if [ -n "$tessdata" ]; then opciones=(--tessdata-dir "$tessdata"); fi

echo "== $exe --version"
"$exe" --version 2>&1 | tr -d '\r'

echo "== idiomas"
idiomas=$("$exe" ${opciones[@]+"${opciones[@]}"} --list-langs 2>&1 | tr -d '\r')
echo "$idiomas"
for l in spa eng; do
  if ! printf '%s\n' "$idiomas" | grep -x "$l" >/dev/null; then echo "::error::Tesseract no ve el idioma $l"; exit 1; fi
done

echo "== lectura del ticket de muestra (archivo)"
texto=$("$exe" ${opciones[@]+"${opciones[@]}"} "$muestra" stdout -l spa+eng --psm 4 2>/dev/null | tr -d '\r')
printf '%s\n' "$texto" | sed -n '1,40p'
if ! printf '%s\n' "$texto" | grep '228\.48' >/dev/null; then echo "::error::No leyó el total 228.48 del ticket de muestra"; exit 1; fi

echo "== lectura por stdin en TSV (como el programa)"
tsv=$("$exe" ${opciones[@]+"${opciones[@]}"} stdin stdout -l spa+eng --psm 4 tsv < "$muestra" 2>/dev/null | tr -d '\r')
if ! printf '%s\n' "$tsv" | sed -n '1p' | grep '^level.page_num' >/dev/null; then
  echo "::error::La salida no es TSV: falta tessdata/configs/tsv (el programa no podría interpretar la lectura)"; exit 1
fi
if ! printf '%s\n' "$tsv" | grep '228\.48' >/dev/null; then echo "::error::La lectura por stdin (TSV) no encontró 228.48"; exit 1; fi
echo "Tesseract OK: $(printf '%s\n' "$tsv" | wc -l | tr -d ' ') renglones TSV"
