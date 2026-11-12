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
   - `SHA256SUMS.txt`
   - notas generadas de los commits/PRs (edítalas en GitHub si quieres).
4. Una etiqueta con guion (`v1.1.0-beta.1`, `v1.1.0-rc.1`) se publica como
   **prelanzamiento**: solo la ven los negocios con clave de apoyo vigente y
   bandera `beta` (acceso anticipado). Las finales las ven todos.

## Seguridad del flujo (lo que ya está y lo que falta en GitHub)

- `permissions: contents: read` en todo el flujo; `contents: write` solo en el
  trabajo `release`. Sin secretos propios (`GITHUB_TOKEN` basta).
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
  según la JVM instalada, `macos-arm64`/`macos-x64` según el procesador.
  **No cambiar esa convención de nombres.**
- Se puede apagar en Configuración → Actualizaciones, y buscar a mano desde
  ahí o desde «Acerca de».
- Windows: el instalador lleva un `--win-upgrade-uuid` fijo, así que instalar
  la versión nueva reemplaza a la anterior (datos y configuración viven en
  `%APPDATA%\PuntoDeVenta`, no se tocan). macOS: arrastrar la app nueva sobre la
  anterior.

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
