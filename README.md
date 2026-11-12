# Caja Libre

**Caja Libre** es el punto de venta gratis y de código abierto para tiendas de abarrotes,
verdulerías y misceláneas de México: ventas con lector de códigos, inventario, corte, estadísticas,
acceso móvil en la red de la tienda, varias cajas y facturación CFDI. Funciona sin internet, en PCs
viejas, en Windows, macOS y Linux. Hecho en Java 17 + Spring Boot + JavaFX.

- **Sitio y descargas:** <https://minepacman.github.io/Punto-Ventas/>
- **Versiones:** <https://github.com/Minepacman/Punto-Ventas/releases> (cada una con `SHA256SUMS.txt`
  y *attestations* de GitHub, ver abajo)
- **macOS con Homebrew:** `brew tap minepacman/tap && brew install --cask punto-de-venta`

> El nombre del repositorio (`Punto-Ventas`), la carpeta de datos (`PuntoDeVenta`), el nombre del
> instalador (`PuntoDeVenta.app`, `punto-de-venta`) y el prefijo de los archivos de cada versión
> siguen siendo los técnicos: así las instalaciones existentes se actualizan sin cambios.

## Licencia y autoría

Copyright (C) 2026 **Elias Oziel Sánchez Fernández**.

Este programa es software libre: puedes redistribuirlo y/o modificarlo bajo los términos de la
**GNU General Public License** publicada por la Free Software Foundation, versión 3 o posterior.
Se distribuye SIN NINGUNA GARANTÍA. Texto completo en [`LICENSE`](LICENSE) (sin modificar);
términos adicionales permitidos por la sección 7 de la GPL en
[`AVISOS-ADICIONALES.md`](AVISOS-ADICIONALES.md); licencias de las bibliotecas que se distribuyen
con el programa en [`AVISOS-TERCEROS.md`](AVISOS-TERCEROS.md).

## Canales oficiales y marca

**Canales oficiales (los únicos que controla el autor):**

- Sitio y descargas: <https://minepacman.github.io/Punto-Ventas/>
- Código y versiones: <https://github.com/Minepacman/Punto-Ventas>
- Homebrew (macOS): `brew tap minepacman/tap && brew install --cask punto-de-venta`
- Contacto: elias.osiel@hotmail.com · WhatsApp 282 141 6799
- Llave pública del autor (claves de apoyo, lista de revocación y datos de donación firmados):
  `kid 0d2a501a` · Ed25519 SPKI `MCowBQYDK2VwAyEAt8n1iOJpkwZQDteDtvp7WNUvSpUopLbVrZG7PtzEABM=`
  (la huella SHA-256 completa se muestra en «Acerca de» del programa).

**La versión oficial es gratuita.** El autor no cobra por el programa ni por descargarlo. Si alguien
te lo vendió, te lo instaló por una cuota o te ofreció «claves» o «soporte oficial», esa persona no
es el autor y el autor no responde por ello. El soporte personal del autor se obtiene únicamente
apoyando al proyecto por los canales oficiales. Dona únicamente a la cuenta publicada en el sitio
oficial (firmada en [`claves/donacion.pdvd`](claves/donacion.pdvd)) y, antes de confirmar la
transferencia, revisa que tu banco muestre como beneficiario a **Elias Oziel Sánchez Fernández**.

**Marca y versiones modificadas.** «Caja Libre» y su logotipo identifican la versión del autor
(política completa en [`MARCA.md`](MARCA.md)). Conforme a la sección 7 de la GPLv3, las versiones
modificadas o redistribuidas por terceros deben conservar el aviso de autoría, identificarse como
modificadas con otro nombre y otro logotipo, y no pueden usar el nombre del autor, la marca o el
logotipo para promocionarse, afirmar ser la versión oficial o presentar sus propios datos de
donación o soporte como si fueran del autor. ¿Viste una copia que se hace pasar por esta? Escribe a
elias.osiel@hotmail.com.

## Comprobar que tu descarga es la oficial

1. Descarga solo desde el sitio oficial o desde la página de versiones de GitHub.
2. Compara la suma SHA-256 del archivo con la de `SHA256SUMS.txt` de la misma versión
   (`shasum -a 256 archivo` en macOS/Linux, `Get-FileHash archivo` en PowerShell).
3. Cada instalador lleva una *attestation* de procedencia generada por GitHub Actions (prueba
   criptográfica de que salió de este repositorio, de ese flujo y de ese commit). Con la
   [CLI de GitHub](https://cli.github.com):

   ```bash
   gh attestation verify PuntoDeVenta-1.1.0-windows-x64.exe --repo Minepacman/Punto-Ventas
   ```

Los instaladores todavía no están firmados con certificado de Windows ni notarizados por Apple
(ver `Punto-Ventas/docs/firma-de-codigo.md`): por eso el sistema muestra una advertencia la primera
vez. La verificación de arriba es la forma de saber que el archivo es el original.

## Para desarrolladores

Proyecto Gradle en `Punto-Ventas/` (Java 17). Pruebas: `cd Punto-Ventas && ./gradlew test`;
instalador del sistema actual: `./gradlew jpackageTask`. Guías en `Punto-Ventas/docs/`
(`publicar.md`, `varias-cajas.md`, `donadores.md`, `firma-de-codigo.md`). El sitio público es la
carpeta `sitio/`, publicada con GitHub Pages.
