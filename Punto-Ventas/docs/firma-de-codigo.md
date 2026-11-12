# Firma de código y procedencia de los instaladores

Estado (noviembre de 2026): los instaladores de Caja Libre **no** están firmados con certificado
de Windows ni notarizados por Apple; por eso SmartScreen y Gatekeeper avisan la primera vez. Lo que
sí existe hoy, gratis, es la **procedencia verificable** de cada archivo (sección 1). Las secciones
2 y 3 son el plan para quitar las advertencias; la 4 es el texto de política que piden los
programas de firma gratuita para proyectos de código abierto.

## 1. Lo que ya se publica con cada versión

- `SHA256SUMS.txt` con las sumas de todos los instaladores (lo genera `release.yml`).
- **Attestations de GitHub** (`actions/attest-build-provenance`): una firma Sigstore, emitida por
  GitHub con el token OIDC del flujo, que liga cada archivo al repositorio
  `Minepacman/Punto-Ventas`, al flujo `release.yml` y al commit de la etiqueta. Solo se pueden
  generar desde el flujo del repositorio oficial; un fork genera las suyas, pero a nombre del fork.
  Cualquiera las comprueba con la CLI de GitHub:

  ```bash
  gh attestation verify PuntoDeVenta-1.1.0-windows-x64.exe --repo Minepacman/Punto-Ventas
  gh attestation verify PuntoDeVenta-1.1.0-macos-arm64.dmg --repo Minepacman/Punto-Ventas
  ```

  También se ven en <https://github.com/Minepacman/Punto-Ventas/attestations>.

- Pendiente, barato: firmar `SHA256SUMS.txt` con una llave de releases **distinta** de la de
  donadores (p. ej. `minisign`), desde la computadora del dueño después de publicar, y subir
  `SHA256SUMS.txt.minisig` como archivo de la release. Documentar la llave pública en el sitio.
  No se hace desde Actions para no subir ninguna llave privada a GitHub.

## 2. Windows: SignPath Foundation (gratis para proyectos de código abierto)

SignPath Foundation firma gratis los binarios de proyectos OSS con un certificado cuyo *publisher*
es «SignPath Foundation» y el nombre del proyecto. Requisitos (<https://signpath.org/terms>):

1. Licencia aprobada por la OSI: **GPL-3.0, cumplido**.
2. Repositorio público con el código completo: **cumplido**.
3. Compilación reproducible por un sistema de integración continua a partir del repositorio
   (no artefactos subidos a mano): **cumplido** (`release.yml` en GitHub Actions).
4. Página pública de **política de firma de código** que describa quién puede publicar, cómo se
   construye y cómo se protegen las credenciales: **la sección 4 de este documento**, enlazada
   desde el sitio.
5. Autenticación de dos factores en la cuenta de GitHub del propietario: **lo hace el dueño**.
6. Solicitud en <https://signpath.org/apply> con el enlace al repositorio y a esta política.
   SignPath responde en días o semanas; al aprobarse se integra su acción de GitHub
   (`signpath/github-action-submit-signing-request`) al trabajo `release`, con aprobación manual
   por release desde su panel.

Alternativa de pago si SignPath no acepta: certificado «Open Source Code Signing» de Certum
(≈ €49–69/año, a nombre del autor).

## 3. macOS: Apple Developer Program (US$99/año)

Con la cuenta de Apple Developer se firma el `.app` con un certificado *Developer ID Application*
y se notariza el `.dmg` (`xcrun notarytool`). Pasos en `release.yml`: importar el certificado
(secreto en base64 + contraseña) a un llavero temporal, `jpackage --mac-sign --mac-signing-key-user-name`,
`notarytool submit --wait` con `APPLE_ID`, `APPLE_TEAM_ID` y `APPLE_APP_PASSWORD`, y
`stapler staple`. Hasta entonces, el sitio y el cask explican «Abrir de todos modos».

## 4. Política de firma de código (Code Signing Policy)

**Proyecto:** Caja Libre (repositorio `Minepacman/Punto-Ventas`). **Responsable:** Elias Oziel
Sánchez Fernández, único mantenedor con permiso de escritura.

- **Qué se firma.** Únicamente los instaladores que construye el flujo `.github/workflows/release.yml`
  en GitHub Actions a partir de una etiqueta `v*` del repositorio oficial. Nunca se firman
  archivos compilados fuera de ese flujo ni subidos a mano.
- **Quién puede publicar.** Solo el responsable puede crear etiquetas `v*` (regla de etiquetas en
  GitHub) y aprobar una solicitud de firma. Su cuenta de GitHub usa autenticación de dos factores.
- **Cómo se construye.** El flujo descarga el código del commit etiquetado, ejecuta las pruebas,
  genera los instaladores con jpackage del JDK 17 (Temurin) y publica `SHA256SUMS.txt` y las
  attestations de procedencia. Las acciones de terceros van fijadas por SHA de commit.
- **Credenciales.** Las llaves privadas de firma nunca se guardan en el repositorio ni en los
  *runners*; viven en el servicio de firma (SignPath / Apple) o en el equipo del responsable. Los
  secretos del flujo (`TAP_TOKEN`, y en su momento los de Apple) son de alcance mínimo.
- **Dependencias.** Solo se incluyen bibliotecas publicadas en Maven Central con las licencias
  listadas en `AVISOS-TERCEROS.md`; no se descarga ni ejecuta código de fuera durante la
  construcción.
- **Privacidad y conducta.** El programa no envía telemetría; solo consulta la lista pública de
  versiones de GitHub y dos archivos firmados del repositorio (lista de revocación y datos de
  donación), siempre a petición del usuario o una vez al día si él lo permite.
- **Reporte de problemas de seguridad:** elias.osiel@hotmail.com.
