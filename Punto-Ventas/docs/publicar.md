# Publicar una versión

Los instaladores los construye GitHub Actions (`.github/workflows/release.yml`)
cuando se sube una etiqueta `v*`. No hay que compilar nada a mano.

## Pasos

1. Sube la versión en `Punto-Ventas/build.gradle` — es la **única** fuente de verdad:

   ```groovy
   version = '1.1.0'          // o '1.1.0-beta.1' para una versión de prueba
   ```

   De ahí salen `version.properties` (lo que ve el usuario en el login, la barra
   de estado y «Acerca de», y el `User-Agent` del buscador de actualizaciones),
   el manifiesto del jar y `--app-version` de jpackage (que solo acepta `x.y.z`:
   el sufijo `-beta.1` se recorta para el instalador).
2. Commit (en `pos-basico` o `main`, como prefieras) y etiqueta **con el mismo número**:

   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```

   Si la etiqueta no coincide con `build.gradle`, el flujo se detiene antes de compilar.
3. Espera el flujo (unos 15–25 min). Crea la release «Caja Libre 1.1.0» con (los archivos
   conservan el prefijo técnico `PuntoDeVenta-`: lo usan el cask de Homebrew y el PKGBUILD; el
   buscador de actualizaciones no mira el prefijo, solo `-windows-x64.exe`, `-macos-arm64.dmg`, etc.):
   - `PuntoDeVenta-1.1.0-windows-x64.exe` (obligatorio)
   - `PuntoDeVenta-1.1.0-macos-arm64.dmg` (obligatorio)
   - `PuntoDeVenta-1.1.0-windows-x86.exe` y `PuntoDeVenta-1.1.0-macos-x64.dmg`
     (opcionales: si su trabajo falla, la release sale sin ellos)
   - `PuntoDeVenta-1.1.0-linux-x64.deb`, `...-linux-x64.rpm` y `...-linux-x64.tar.gz`
     (opcionales; ver «Linux» abajo)
   - `SHA256SUMS.txt`
   - notas generadas de los commits/PRs (edítalas en GitHub si quieres).
4. Una etiqueta con guion (`v1.1.0-beta.1`, `v1.1.0-rc.1`) se publica como
   **prelanzamiento**: solo la ven los negocios con clave de apoyo vigente y
   bandera `beta` (acceso anticipado). Las finales las ven todos.

   **Cada `x.y.z` se usa en una sola etiqueta.** El instalador de Windows solo conoce
   `x.y.z` (el sufijo se recorta) y su identificador de producto sale de ese número, así
   que `v1.1.0-beta.1` seguida de `v1.1.0` no se podría instalar encima. Tras
   `v1.1.0-beta.1`, la siguiente prueba es `v1.1.1-beta.2` y la final `v1.1.1` (o la que
   siga). El flujo lo revisa y se detiene si el número ya lo usa otra etiqueta.

## Seguridad del flujo (lo que ya está y lo que falta en GitHub)

- `permissions: contents: read` en todo el flujo; `contents: write` solo en el
  trabajo `release`. `GITHUB_TOKEN` basta; el único secreto propio es el opcional
  `TAP_TOKEN` (solo escribe en `homebrew-tap`, ver «Homebrew»).
- Acciones fijadas por **SHA completo** con la versión en el comentario
  (`actions/checkout`, `setup-java`, `upload/download-artifact`,
  `gradle/actions/setup-gradle`, `softprops/action-gh-release`). Para subir
  una: cambiar el SHA y el comentario (o activar Dependabot para `github-actions`).
- Se publica `SHA256SUMS.txt`; las versiones de los runners salen del nombre
  de la etiqueta validado (`v1.2.3` o `v1.2.3-beta.1`), nunca de entradas libres.
- **Attestations de procedencia** (`actions/attest-build-provenance`, fijada por SHA): el trabajo
  `release` tiene además `id-token: write` y `attestations: write` y firma (Sigstore, vía GitHub)
  todos los archivos de `instaladores/`. Gratis solo en repositorios públicos. Cualquiera verifica:
  `gh attestation verify PuntoDeVenta-<v>-windows-x64.exe --repo Minepacman/Punto-Ventas`
  (también en <https://github.com/Minepacman/Punto-Ventas/attestations>). Ver `docs/firma-de-codigo.md`
  para la firma de Windows (SignPath) y la notarización de Apple, todavía pendientes.
- Pendiente, a mano: firmar `SHA256SUMS.txt` con una llave de releases distinta de la de donadores
  (`minisign`) desde tu computadora y subir el `.minisig` a la release; nunca desde Actions.
- Las notas de cada release empiezan con la guía de descarga, que incluye la línea fija «Caja Libre
  es gratuito… dona solo a la cuenta del sitio oficial… beneficiario Elias Oziel Sánchez Fernández».
- **Pendiente en GitHub (lo hace el dueño, una vez)**: Settings → Rules →
  New tag ruleset para `v*` que solo el dueño pueda crear/borrar. Sin eso,
  cualquier colaborador con permiso de push podría disparar una release.
- La misma consulta diaria de versiones baja `claves/revocadas.pdvr` (lista
  firmada de claves de donador revocadas) y `claves/donacion.pdvd` (datos de
  donación del autor firmados; ver `docs/donadores.md`).

## Qué hace el programa instalado

- Al entrar un ADMIN o GERENTE, a lo más **una vez al día**, consulta
  `https://api.github.com/repos/Minepacman/Punto-Ventas/releases` en segundo
  plano (5 s de espera; sin internet no pasa nada). Si hay versión nueva aparece
  un enlace discreto en la barra de estado; al abrirlo se ven las notas (texto
  plano) y el botón **Descargar**, que abre el instalador en el navegador. El
  programa **nunca** descarga ni ejecuta instaladores por su cuenta, no sigue
  redirecciones, acota la respuesta a 1 MB y **solo abre enlaces que empiecen
  con `https://github.com/Minepacman/Punto-Ventas/`** (si el cuerpo de una
  release apuntara a otro sitio, se abre la página de releases en su lugar).
- El instalador se elige por el nombre del archivo: `windows-x64`/`windows-x86`
  según la JVM instalada, `macos-arm64`/`macos-x64` según el procesador; en Linux
  `linux-x64.deb` si existe `/usr/bin/dpkg`, si no `linux-x64.rpm` si hay `rpm`, si no
  `linux-x64.tar.gz` (portable); si no hay archivo de la arquitectura, la página.
  **No cambiar esa convención de nombres.**
- Se puede apagar en Configuración → Actualizaciones, y buscar a mano desde
  ahí o desde «Acerca de».
- Windows: el instalador lleva un `--win-upgrade-uuid` fijo, así que instalar
  la versión nueva reemplaza a la anterior (datos y configuración viven en
  `%APPDATA%\PuntoDeVenta`, no se tocan). macOS: arrastrar la app nueva sobre la
  anterior.

## Linux

El trabajo `linux-x64` del flujo (opcional: si falla, la release sale sin Linux) construye
en `ubuntu-latest` tres archivos con el mismo `jpackageTask`:

| Archivo | Cómo | Para quién |
|---|---|---|
| `PuntoDeVenta-<v>-linux-x64.deb` | `./gradlew jpackageTask` (por defecto en Linux) | Ubuntu 24.04+, Debian 13+, Mint 22+ (`sudo apt install ./archivo.deb`) |
| `PuntoDeVenta-<v>-linux-x64.rpm` | `-PlinuxType=rpm` (necesita `rpmbuild`, paquete `rpm`) | Fedora, openSUSE (`sudo dnf install ./archivo.rpm`) |
| `PuntoDeVenta-<v>-linux-x64.tar.gz` | `-PlinuxType=tar.gz` (app-image comprimida) | Cualquier distro x64: descomprimir y correr `PuntoDeVenta/bin/PuntoDeVenta` |

- Paquete `punto-de-venta`, se instala en `/opt/punto-de-venta` con acceso en el menú
  (Office/Finance) y en el escritorio; el mantenedor del `.deb` es el correo *noreply* de
  GitHub del dueño. Licencia GPLv3, mismo icono (`packaging/icono/icono-512.png`) y mismas
  opciones de JVM que los demás instaladores. Datos en `~/.puntodeventa`.
- **Por qué el `.deb` pide Ubuntu 24.04+**: jpackage copia las dependencias del sistema donde
  construye (`libgtk-3-0t64`, etc., nombres nuevos del runner). En Ubuntu 22.04 / Debian 12 usar
  el `.tar.gz`. Si hiciera falta un `.deb` para ellos, construirlo en un `ubuntu-22.04` (ese
  runner se retira en abril de 2027) o en un contenedor `ubuntu:22.04`.
- El `.rpm` se construye en Ubuntu, así que no lleva dependencias automáticas: necesita GTK 3
  (lo trae cualquier escritorio).
- Las pruebas corren sin pantalla en el runner: las que necesitan el toolkit de JavaFX se
  saltan solas (`assumeTrue`). Si alguna nueva lo necesitara, poner `xvfb-run -a` delante.
- El plugin de JavaFX elige el clasificador del equipo (`linux` x64 o `linux-aarch64`); no hay
  paquete ARM publicado (en un Linux ARM el buscador abre la página de la versión).
- Para instalar sin interfaz: `sudo apt install ./PuntoDeVenta-<v>-linux-x64.deb`; para
  actualizar, instalar el nuevo encima (mismo nombre de paquete, versión mayor).
- **Lector de tickets**: el `.deb` depende de `tesseract-ocr` y `tesseract-ocr-spa` (Debian main / Ubuntu
  universe) y el `.rpm` de `/usr/bin/tesseract` y `(tesseract-langpack-spa or tesseract-ocr-traineddata-spanish)`
  (Fedora / openSUSE); `apt`/`dnf`/`zypper` los instalan solos (con `dpkg -i` hay que correr después
  `sudo apt -f install`). El `.tar.gz` no declara nada: `sudo apt install tesseract-ocr tesseract-ocr-spa`,
  `sudo dnf install tesseract tesseract-langpack-spa` o, en Arch, `tesseract tesseract-data-spa` (optdepends del
  PKGBUILD). Sin Tesseract el programa sigue funcionando y la pantalla del lector ofrece escribir el texto.
  `-PdependenciasOcr=false` construye los paquetes sin esas dependencias.

### Repositorios Linux (evaluación, nada publicado todavía)

| Opción | Costo | Esfuerzo | Veredicto |
|---|---|---|---|
| **AUR** (Arch/Manjaro) | $0 | Bajo: `packaging/aur/PKGBUILD` ya está escrito (paquete `punto-de-venta-bin` a partir del `.tar.gz`). Falta: cuenta en aur.archlinux.org con llave SSH, `updpkgsums`, `.SRCINFO`, push; cada versión repetir. | Cuando haya un usuario de Arch que lo pida. |
| **Repositorio APT en GitHub Pages** | $0 | Medio: llave GPG del dueño (guardarla como secreto), `dpkg-scanpackages`/`apt-ftparchive` en el flujo, rama `gh-pages` con `dists/` y `pool/`, y el usuario agrega la fuente y la llave. ~1 día y hay que custodiar la llave. | Solo si Linux crece; mientras, el `.deb` suelto ya se actualiza desde el programa. |
| **Flathub** | $0 | Alto: manifiesto Flatpak con compilación *offline* (Gradle sin red → hay que generar la lista de dependencias con `flatpak-builder-tools`), runtime `org.freedesktop.Platform` + extensión OpenJDK 17 (JavaFX no viene: empaquetar el runtime de jpackage o los jars), permisos (`--share=network`, `--device=all` para báscula/impresora serial, `--filesystem` para respaldos), revisión humana de Flathub. 2-4 días. Lo más visible para usuarios finales. | Postergar. |
| **Snapcraft** | $0 | Medio-alto: `snapcraft.yaml` con `core22`, plugs `serial-port`/`cups`/`network`; publicación automática desde GitHub (`snapcore/action-publish`) con token. Confinamiento estricto complica la impresora/báscula; `classic` requiere aprobación. 1-2 días. | Postergar. |

## Homebrew (macOS)

Tap propio: `Minepacman/homebrew-tap` (código en `~/Desarrollo/GitHub/homebrew-tap`). Los
usuarios instalan con:

```bash
brew tap minepacman/tap && brew install --cask punto-de-venta
```

- Cask `Casks/punto-de-venta.rb`: `version`, `sha256` por arquitectura (`arm:`/`intel:`) y la URL
  del `.dmg` de la release (`PuntoDeVenta-<v>-macos-{arm64,x64}.dmg`). **Necesita los dos dmg**:
  si el trabajo opcional de Intel falla, el cask de esa versión no se actualiza.
- No está firmada/notarizada: `brew install --cask --no-quarantine` **ya no existe** (Homebrew lo
  quitó en la 4.7); el cask lo explica en `caveats` (Ajustes → Privacidad y seguridad → «Abrir de
  todos modos», o `xattr -dr com.apple.quarantine /Applications/PuntoDeVenta.app`). Homebrew
  deshabilita desde septiembre de 2026 los casks sin firma **de su tap oficial**; en un tap
  propio siguen funcionando.
- Sin `zap` a propósito: `brew uninstall --zap` borraría `~/Library/Application Support/PuntoDeVenta`
  (la base de datos de la tienda).
- **Pasos del dueño (una vez)**:
  1. Crear el repositorio público vacío `Minepacman/homebrew-tap` en GitHub (el nombre
     `homebrew-tap` es obligatorio para que `brew tap minepacman/tap` lo encuentre).
  2. `cd ~/Desarrollo/GitHub/homebrew-tap && git remote add origin git@github.com:Minepacman/homebrew-tap.git && git push -u origin main`.
  3. Opcional, para que el flujo actualice el cask solo: token de grano fino
     (GitHub → Settings → Developer settings → Fine-grained tokens) con acceso **solo** a
     `homebrew-tap` y permiso *Contents: Read and write*; guardarlo como secreto `TAP_TOKEN` en
     `Minepacman/Punto-Ventas` (Settings → Secrets and variables → Actions). Sin el secreto el
     paso se salta con un aviso; si falla, la release ya está publicada.
- **Cada versión** (si no hay `TAP_TOKEN`): con la release ya publicada,
  `scripts/actualizar-cask.sh 1.0.1` en el tap (baja `SHA256SUMS.txt` y reescribe `version` y
  las sumas), commit y push. Los prelanzamientos no van al cask.

## Lector de tickets (Tesseract) en los instaladores

Cada instalador lleva Tesseract en `<app>/ocr/tesseract/{bin,lib,tessdata}` (lo busca `ocr/LocalizadorTesseract`)
con los idiomas `spa` y `eng` de **tessdata_fast 4.1.0** (SHA-256 fijos en `packaging/tesseract/tessdata.sha256`) y
`tessdata/configs/tsv` (sin él Tesseract no contesta en TSV y la lectura no se entiende). `jpackageTask` llama a
`empaquetarTesseract`; Gradle no baja nada de internet.

| Instalador | De dónde sale | Tamaño (sin comprimir) | Comprobación en el flujo |
|---|---|---|---|
| macOS arm64 / x64 | `brew install tesseract` en el runner; `empaquetarTesseract` copia el ejecutable y sus ~15 bibliotecas de Homebrew, las reapunta y firma ad hoc; idiomas de `bajar-tessdata.sh` (`-PtessdataDir`) | 14.7 MB | `comprobar-dmg.sh`: monta el `.dmg`, revisa firmas, que nada apunte a Homebrew, `minos`, y lee el ticket de muestra |
| Windows x64 / x86 | `tesseract-windows.yml`: `tesseract.exe` **estático** compilado con vcpkg (commit fijo, `packaging/tesseract/windows/`) — sin DLLs ni runtime de Visual C++; `-PtesseractDir` | ≈ 13–15 MB (estimado: exe 6–8 MB + idiomas 6.4 MB) | arquitectura del exe (PE Machine), y desde la app-image intermedia (`build/jpackage-temp`, `--temp`) arranca y lee el ticket |
| Linux deb / rpm | Tesseract de la distribución (dependencia del paquete) | 0 | `Depends`/`Requires` del paquete; en el runner se instala el `.deb` y se lee el ticket con `/usr/bin/tesseract` |
| Linux tar.gz | — (instalarlo aparte, ver «Linux») | 0 | — |

- `-PexigirTesseract=true` (el flujo lo usa) hace fallar la construcción si no se pudo incluir; sin él solo avisa y el
  instalador sale sin lector incluido (el programa usará un Tesseract del sistema si lo hay). `-PincluirTesseract=false`
  lo omite.
- La compilación con vcpkg tarda ~10–20 min por arquitectura y se guarda en la caché de Actions. Una etiqueta `v*` solo
  ve la caché de `main` (o la suya): `tesseract-windows.yml` corre también al llegar a `main` un cambio en
  `packaging/tesseract/` y a mano (Actions → «Tesseract para Windows» → Run workflow), para que la release la encuentre
  lista. Con o sin caché se comprueba antes de usarla.
- El de 32 bits es opcional como su instalador: si su compilación falla, el `.exe` x86 sale **sin** lector incluido
  (aviso en el flujo) y en esa PC el lector ofrece escribir el texto o leer el ticket desde el teléfono.
- macOS: Homebrew compila para la versión del runner (`minos`): el Tesseract incluido solo arranca en ese macOS o
  posterior (la del runner `macos-latest` / `macos-15-intel`; el paso «Lector de tickets dentro del dmg» la imprime). En uno más viejo `LocalizadorTesseract` lo descarta (no arranca) y se usa uno del
  sistema o la captura manual. Un dmg construido en tu Mac hereda el `minos` de tu Homebrew (p. ej. 27.2).
- Para probar en local: `bash packaging/tesseract/bajar-tessdata.sh build/tessdata` y
  `./gradlew jpackageTask -PtessdataDir=build/tessdata -PexigirTesseract=true`; luego
  `bash packaging/tesseract/comprobar-dmg.sh build/installer/*.dmg`. En Windows:
  `pwsh packaging/tesseract/windows/construir.ps1 -Arquitectura x64 -Destino build/ocr-tesseract-x64`, el mismo
  `bajar-tessdata.sh` (Git Bash) hacia `build/ocr-tesseract-x64/tessdata` y
  `gradlew.bat jpackageTask "-PtesseractDir=build/ocr-tesseract-x64"`.
- Licencias: `ocr/tesseract/LICENSE-tesseract.txt` y `ocr/tesseract/licencias/` (una por biblioteca enlazada; en
  Windows las `copyright` de cada puerto de vcpkg, en macOS los LICENSE/COPYING de cada fórmula). Resumen en
  `AVISOS-TERCEROS.md`.

### Espejo de los modelos de PaddleOCR (`ocr-modelos-1`)

El programa baja bajo demanda ONNX Runtime y los modelos PP-OCRv6 (`ocr/RecursosPpOcr`): primero de la release
`ocr-modelos-1` de este repositorio y, si falla, de la fuente original (Maven Central / modelscope.cn), siempre con
el mismo SHA-256. Esa release la crea, una sola vez, el flujo manual `ocr-modelos.yml` (baja de las fuentes
originales, verifica, publica como **prelanzamiento** no «latest» y vuelve a bajar del espejo para comprobarlo):

```bash
gh workflow run ocr-modelos.yml -R Minepacman/Punto-Ventas      # el flujo debe estar ya en main
```

Mientras no exista, el programa usa la fuente original sin que nadie lo note. Si cambia un modelo: etiqueta nueva
(`ocr-modelos-2`) y cambiar a la vez `RecursosPpOcr` y `packaging/ocr-modelos/recursos.txt` (`EspejoOcrModelosTest`
revisa que coincidan); no reemplazar los archivos de `ocr-modelos-1`, porque las versiones instaladas esperan esas sumas.

## Construir en tu Mac (para probar)

```bash
cd Punto-Ventas && ./gradlew jpackageTask      # → build/installer/PuntoDeVenta-1.1.0.dmg
```

En Windows igual con `gradlew.bat` (requiere WiX 3 en el PATH). El de 32 bits:

```bat
gradlew.bat jpackageTask -x test -PjavafxVersion=17.0.8 -PjavafxPlatform=win-x86 -PjdkJpackage=C:\ruta\jdk-17-x86
```

- `-PjdkJpackage` debe ser un JDK 17 **de 32 bits** (el lanzador, el runtime y el `-arch` de WiX salen del JDK
  que corre jpackage); `jpackageTask` lo comprueba con el `OS_ARCH` de su archivo `release`.
- Con `-PjavafxPlatform=win-x86` jpackage usa `packaging/windows-x86/main.wxs` (`--resource-dir`): es la
  plantilla interna de jpackage 17 con `Platform="x86"`. Sin ella el MSI quedaba marcado x64 (jpackage 17 trae
  `Platform="x64"` fijo y ese atributo gana sobre `-arch`) y Windows de 32 bits lo rechazaba. El flujo comprueba
  después que el `.exe` sea de 32 bits y que el MSI intermedio (`build/jpackage-x86-temp`) tenga `Template=Intel;1033`.
- En PowerShell escribe cada `-P` entre comillas (`"-PjavafxVersion=17.0.8"`): sin ellas pwsh parte el argumento
  en el punto (`-PjavafxVersion=17` y `.0.8`). El flujo usa `shell: bash` por eso.
- Mismo `--win-upgrade-uuid` que el x64: en una PC de 64 bits, instalar el x64 encima del x86 (o al revés) reemplaza
  al anterior.

## Icono

`packaging/icono/GeneradorIcono.java` dibuja el icono (canasta sobre azul
marino) y escribe el `.icns`, el `.ico` y los PNG de `src/main/resources/icono`.
Solo hay que correrlo si se cambia el dibujo: `java packaging/icono/GeneradorIcono.java`
(desde `Punto-Ventas/`, en un Mac para el `.icns`).
