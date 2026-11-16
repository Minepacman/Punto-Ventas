# Firma de código y procedencia de los instaladores

Estado (noviembre de 2026, última versión publicada 1.0.4): los instaladores de Caja Libre **no** están
firmados con certificado de Windows ni notarizados por Apple; por eso SmartScreen y Gatekeeper avisan la
primera vez. Lo que sí existe hoy, gratis y comprobable por cualquiera, es la **procedencia verificable**
de cada archivo (sección 1). La sección 2 dice qué falta y en qué orden; la 3 es la política de firma de
código que piden los programas de firma gratuita para proyectos de código abierto (SignPath Foundation).

Este documento describe solo lo que se puede comprobar en el repositorio público
(`.github/workflows/release.yml`, `Punto-Ventas/build.gradle`) o en la API de GitHub. Donde algo está
pendiente, lo dice.

## 1. Lo que ya se publica con cada versión (hecho)

Cada etiqueta `v*` del repositorio oficial `Minepacman/Punto-Ventas` dispara `release.yml`, que:

1. Comprueba que la etiqueta coincide con `version = '…'` de `Punto-Ventas/build.gradle` (si no, se detiene).
2. Construye los instaladores en *runners* de GitHub Actions a partir del commit etiquetado: Windows x64
   (.exe) y macOS Apple Silicon (.dmg) son obligatorios; Windows x86, macOS Intel y Linux (deb/rpm/tar.gz)
   son opcionales y pueden fallar sin detener la publicación.
3. Ejecuta la suite de pruebas en los trabajos de Windows x64, macOS Apple Silicon y Linux. Los trabajos
   opcionales de Windows x86 y macOS Intel la omiten (`-x test`): compilan el mismo código ya probado.
4. En el trabajo `release`: genera `SHA256SUMS.txt` con las sumas de todos los archivos, crea una
   **attestation de procedencia** por archivo (incluido el propio `SHA256SUMS.txt`) y publica la release
   «Caja Libre <versión>» con una guía de descarga.

### 1.1 Sumas SHA-256

`SHA256SUMS.txt` está en cada release. Para comparar el archivo descargado:

```bash
# macOS
shasum -a 256 -c SHA256SUMS.txt --ignore-missing
# Linux
sha256sum -c SHA256SUMS.txt --ignore-missing
```

```powershell
# Windows (PowerShell): la suma debe aparecer en SHA256SUMS.txt junto al nombre del archivo
Get-FileHash .\PuntoDeVenta-1.0.4-windows-x64.exe -Algorithm SHA256
```

La suma por sí sola solo prueba que el archivo no se corrompió al bajar; la procedencia la da la
attestation.

### 1.2 Attestations de procedencia (Sigstore, emitidas por GitHub)

`actions/attest-build-provenance` firma cada archivo con el token OIDC del flujo. La firma liga el archivo
al repositorio `Minepacman/Punto-Ventas`, al flujo `.github/workflows/release.yml` y a la etiqueta/commit
exactos. Solo el flujo del repositorio oficial puede producirlas a nombre de ese repositorio: un *fork*
puede generar las suyas, pero quedan a nombre del *fork* y la verificación con `--repo
Minepacman/Punto-Ventas` falla.

Con la CLI de GitHub (<https://cli.github.com>, requiere `gh auth login` o una red con acceso a
api.github.com):

```bash
gh attestation verify PuntoDeVenta-1.0.4-windows-x64.exe --repo Minepacman/Punto-Ventas
gh attestation verify PuntoDeVenta-1.0.4-macos-arm64.dmg --repo Minepacman/Punto-Ventas
gh attestation verify SHA256SUMS.txt --repo Minepacman/Punto-Ventas
```

Para exigir además que la firma venga del flujo de release y no de otro *workflow* del mismo repositorio:

```bash
gh attestation verify PuntoDeVenta-1.0.4-windows-x64.exe --repo Minepacman/Punto-Ventas \
  --signer-workflow Minepacman/Punto-Ventas/.github/workflows/release.yml
```

Salida esperada: `✓ Verification succeeded!` seguido del archivo, el repositorio y el *workflow* firmante
(`.github/workflows/release.yml@refs/tags/v1.0.4`). Con `--format json` se ve el certificado completo.
Las attestations también se consultan en <https://github.com/Minepacman/Punto-Ventas/attestations>.

Comprobado el 2026-11-14 con `SHA256SUMS.txt` de la v1.0.4: la verificación pasa y el firmante es
`https://github.com/Minepacman/Punto-Ventas/.github/workflows/release.yml@refs/tags/v1.0.4`.

### 1.3 Medidas del flujo que se pueden leer en `release.yml`

- `permissions: contents: read` en todo el flujo; solo el trabajo `release` tiene `contents: write`
  (crear la release), `id-token: write` y `attestations: write` (firmar).
- Todas las acciones de terceros van fijadas por SHA completo de commit, con la versión en un comentario.
- `actions/checkout` con `persist-credentials: false` en todos los trabajos. La única excepción es el clon
  de `Minepacman/homebrew-tap` dentro del trabajo `release`, que usa el secreto `TAP_TOKEN` (PAT de grano
  fino con permiso de escritura **solo** sobre ese repositorio) para actualizar el cask de Homebrew.
- El flujo solo se dispara con `push` de etiquetas `v*`. No hay `pull_request_target`, `workflow_dispatch`
  ni entradas externas que lleguen a un shell; no se ejecuta nada que venga del cuerpo de la release.

## 2. Lo que falta (plan, en orden)

### 2.1 Regla de etiquetas en GitHub (pendiente, gratis, lo hace el dueño)

Hoy el repositorio **no tiene** *rulesets* ni protección de rama (comprobado el 2026-11-14 con
`gh api repos/Minepacman/Punto-Ventas/rulesets` → `[]`). Lo que impide que otra persona publique es que
el dueño es el único colaborador con permiso de escritura. Para que eso quede garantizado por GitHub y no
solo por la lista de colaboradores: *Settings → Rules → New tag ruleset* para `v*`, que solo el dueño pueda
crear, mover o borrar. Cuando exista, actualizar esta sección y `docs/publicar.md`.

### 2.2 Firma de `SHA256SUMS.txt` con llave propia (pendiente, barato)

Firmar `SHA256SUMS.txt` con una llave de releases **distinta** de la de claves de donador (por ejemplo
`minisign`), desde la computadora del dueño después de publicar, y subir `SHA256SUMS.txt.minisig` como
archivo de la release; publicar la llave pública en el sitio y en el README. No se hace desde Actions para
no subir ninguna llave privada a GitHub. Añade una segunda cadena de confianza independiente de GitHub.

### 2.3 Windows: SignPath Foundation (pendiente de solicitar)

SignPath Foundation (<https://signpath.org/terms>) firma gratis los binarios de proyectos de código abierto
con un certificado cuyo *publisher* es «SignPath Foundation» y el nombre del proyecto. Estado de cada
requisito:

| Requisito | Estado |
|---|---|
| Licencia aprobada por la OSI | **Cumplido**: GPL-3.0 (`LICENSE`). |
| Repositorio público con el código completo | **Cumplido**: `Minepacman/Punto-Ventas`. |
| Compilación por integración continua a partir del repositorio, sin artefactos subidos a mano | **Cumplido**: `release.yml` en GitHub Actions (sección 1). |
| Página pública de política de firma de código | **Cumplido**: sección 3 de este documento, en el repositorio público. |
| Autenticación de dos factores en la cuenta del propietario | **Lo confirma el dueño** al solicitar (no es verificable desde fuera). |
| Solicitud en <https://signpath.org/apply> con enlace al repositorio y a esta política | **Pendiente**. |

Al aprobarse se integra `signpath/github-action-submit-signing-request` en el trabajo `release`, con
aprobación manual por versión desde el panel de SignPath. Hasta entonces, el `.exe` no lleva firma Authenticode
y SmartScreen muestra «Windows protegió tu PC» la primera vez.

Alternativa de pago si SignPath no acepta: certificado «Open Source Code Signing» de Certum (del orden de
€50–70 al año, a nombre del autor).

### 2.4 macOS: Apple Developer Program (pendiente, US$99/año, opcional)

Con la cuenta de Apple Developer se firma el `.app` con un certificado *Developer ID Application* y se
notariza el `.dmg` (`xcrun notarytool submit --wait`, luego `stapler staple`). Pasos previstos en
`release.yml`: importar el certificado (secreto en base64 + contraseña) a un llavero temporal del *runner*,
`jpackage --mac-sign --mac-signing-key-user-name`, y los secretos `APPLE_ID`, `APPLE_TEAM_ID`,
`APPLE_APP_PASSWORD`. Hasta entonces, el sitio y el cask de Homebrew explican «Abrir de todos modos».

## 3. Política de firma de código (Code Signing Policy)

**Proyecto:** Caja Libre (repositorio `Minepacman/Punto-Ventas`). **Responsable:** Elias Oziel Sánchez
Fernández, único colaborador con permiso de escritura en el repositorio.

- **Qué se firma.** Únicamente los instaladores que construye el flujo `.github/workflows/release.yml` en
  GitHub Actions a partir de una etiqueta `v*` del repositorio oficial. Nunca se firman archivos compilados
  fuera de ese flujo ni subidos a mano. Mientras no haya certificado, «firma» significa la attestation de
  procedencia de GitHub (sección 1.2).
- **Quién puede publicar.** Solo quien tiene permiso de escritura en el repositorio puede crear una etiqueta
  `v*` y, con ello, disparar una release; hoy esa persona es únicamente el responsable. La regla de
  etiquetas de GitHub que lo fije de forma explícita está pendiente (sección 2.1). Cuando haya servicio de
  firma, cada solicitud de firma la aprobará el responsable a mano.
- **Cómo se construye.** El flujo descarga el código del commit etiquetado, ejecuta las pruebas (en los
  trabajos obligatorios de Windows x64 y macOS Apple Silicon, y en Linux) y genera los instaladores con
  `jpackage` del JDK 17. Las herramientas se obtienen de fuentes fijas y con versión declarada: JDK Temurin 17
  (`actions/setup-java`), Gradle por su *wrapper* (`gradle-wrapper.properties`, descarga de
  services.gradle.org), bibliotecas únicamente de Maven Central (`repositories { mavenCentral() }`), WiX
  Toolset 3.14.1 vía Chocolatey en Windows y `fakeroot`/`rpm` del repositorio de Ubuntu en Linux. No hay
  todavía verificación de dependencias de Gradle (`verification-metadata.xml`). Las acciones de terceros van
  fijadas por SHA de commit. Nada del cuerpo de la release ni de entradas externas se ejecuta.
- **Credenciales.** No hay llaves privadas de firma en el repositorio ni en los *runners*: cuando existan
  vivirán en el servicio de firma (SignPath / Apple) o en el equipo del responsable. El `GITHUB_TOKEN` solo
  tiene escritura en el trabajo `release`; el único secreto adicional es `TAP_TOKEN` (PAT de grano fino con
  escritura solo sobre `Minepacman/homebrew-tap`). La llave Ed25519 con que se firman los datos de donación
  y las claves de donador está fuera del repositorio, en el equipo del responsable.
- **Dependencias.** Solo bibliotecas publicadas en Maven Central con las licencias listadas en
  `AVISOS-TERCEROS.md`.
- **Red y privacidad del programa.** El programa no envía telemetría. Conexiones que puede hacer, todas por
  HTTPS y a petición del usuario o con una opción que él puede apagar: GitHub (`api.github.com`,
  `raw.githubusercontent.com`, `github.com`) para buscar versiones nuevas una vez al día y bajar dos archivos
  firmados del repositorio (lista de revocación y datos de donación); Open-Meteo para las sugerencias por
  clima (con un servicio de geolocalización por IP para proponer la ciudad); `cdn.jsdelivr.net`/GitHub para
  el catálogo de códigos de barras; Facturama solo si el negocio configura facturación; y la URL que el
  usuario pegue para una imagen de producto. El acceso móvil funciona solo en la red local.
- **Reporte de problemas de seguridad:** elias.osiel@hotmail.com (o WhatsApp 282 141 6799).
