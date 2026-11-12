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
3. Espera el flujo (unos 15–25 min). Crea la release con:
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
- **Pendiente en GitHub (lo hace el dueño, una vez)**: Settings → Rules →
  New tag ruleset para `v*` que solo el dueño pueda crear/borrar. Sin eso,
  cualquier colaborador con permiso de push podría disparar una release.
- La misma consulta diaria de versiones baja `claves/revocadas.pdvr` (lista
  firmada de claves de donador revocadas; ver `docs/donadores.md`).

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

## Construir en tu Mac (para probar)

```bash
cd Punto-Ventas && ./gradlew jpackageTask      # → build/installer/PuntoDeVenta-1.1.0.dmg
```

En Windows igual con `gradlew.bat` (requiere WiX 3 en el PATH). El de 32 bits:

```bat
gradlew.bat jpackageTask -x test -PjavafxVersion=17.0.8 -PjavafxPlatform=win-x86 -PjdkJpackage=C:\ruta\jdk-17-x86
```

## Icono

`packaging/icono/GeneradorIcono.java` dibuja el icono (canasta sobre azul
marino) y escribe el `.icns`, el `.ico` y los PNG de `src/main/resources/icono`.
Solo hay que correrlo si se cambia el dibujo: `java packaging/icono/GeneradorIcono.java`
(desde `Punto-Ventas/`, en un Mac para el `.icns`).
