"""Convierte las pruebas fallidas (JUnit XML de Gradle) en anotaciones ::error de GitHub Actions.

Las anotaciones se ven en la página del flujo sin descargar el registro completo.
Uso: python anotar-pruebas.py <carpeta build/test-results>
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def limpiar(texto: str) -> str:
    texto = (texto or "").strip().replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    return texto[:1500]


def main() -> None:
    raiz = sys.argv[1] if len(sys.argv) > 1 else "build/test-results"
    fallas = 0
    for ruta in glob.glob(os.path.join(raiz, "**", "*.xml"), recursive=True):
        try:
            arbol = ET.parse(ruta)
        except ET.ParseError:
            continue
        for caso in arbol.iter("testcase"):
            for tipo in ("failure", "error"):
                for nodo in caso.findall(tipo):
                    fallas += 1
                    titulo = f"{caso.get('classname', '?')}.{caso.get('name', '?')}"
                    detalle = nodo.get("message") or ""
                    if nodo.text:
                        detalle += "\n" + "\n".join(nodo.text.strip().splitlines()[:15])
                    print(f"::error title={limpiar(titulo)}::{limpiar(detalle)}")
    print(f"Pruebas fallidas: {fallas}")


if __name__ == "__main__":
    main()
