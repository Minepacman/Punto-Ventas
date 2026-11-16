#!/usr/bin/env bash
# Monta el .dmg (solo lectura), comprueba el Tesseract que va DENTRO de la app (firma ad hoc válida, arranca con sus
# propias bibliotecas, ve spa/eng y lee el ticket de muestra en TSV) y dice desde qué macOS funciona (minos).
#   bash packaging/tesseract/comprobar-dmg.sh build/installer/PuntoDeVenta-x.y.z.dmg
set -euo pipefail
dmg="$1"
aqui="$(cd "$(dirname "$0")" && pwd)"
muestra="$aqui/../../src/main/resources/ocr/ticket-muestra.jpg"
montaje="$(mktemp -d)"
# El dmg lleva la licencia (GPLv3) como acuerdo: "yes" la acepta para poder montarlo sin pantalla
# (termina por SIGPIPE cuando hdiutil deja de leer: "|| true" para que pipefail no lo tome como error).
{ yes || true; } | PAGER=cat hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$montaje" "$dmg" > /dev/null
trap 'hdiutil detach "$montaje" -force > /dev/null 2>&1 || true' EXIT
t=$(ls -d "$montaje"/*.app/Contents/app/ocr/tesseract 2>/dev/null | head -1 || true)
if [ -z "$t" ]; then echo "::error::El dmg no trae <app>/Contents/app/ocr/tesseract"; exit 1; fi
echo "Tesseract incluido: $t"
du -sh "$t"
for f in "$t"/bin/tesseract "$t"/lib/*.dylib; do
  codesign --verify "$f" || { echo "::error::Firma inválida: $f"; exit 1; }
  if otool -L "$f" | tail -n +2 | grep -E '/opt/homebrew|/usr/local' > /dev/null; then
    echo "::error::$f todavía apunta a Homebrew:"; otool -L "$f"; exit 1
  fi
done
echo "Requiere macOS (minos): $(vtool -show-build "$t/bin/tesseract" | awk '/minos/ {print $2; exit}')"
bash "$aqui/comprobar.sh" "$t/bin/tesseract" "$t/tessdata" "$muestra"
