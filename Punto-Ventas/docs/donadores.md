# Donadores: claves de apoyo, libro y revocación

El programa es software libre (GPLv3) y gratuito. Quien dona recibe una
**clave de apoyo** (en pantalla, "clave de donador"): un texto firmado por el
desarrollador que el programa valida **sin conexión**. Es una **constancia**,
no un candado: lo que da es *tu tiempo* (soporte personal por WhatsApp,
prioridad en errores, aviso y acceso anticipado a versiones de prueba, el
nombre del negocio en «Acerca de»). **Ninguna función del programa depende de
la clave**: `ApoyoNoBloqueaTest` falla si un servicio de negocio inyecta
`DonadorService`. Un fork que quite la ventana de donación es legal y no te
quita nada que valga: la verificación real la haces tú contra tu libro cuando
alguien te pide soporte.

Mientras el negocio **no** tenga una clave vigente, el administrador ve la
ventana de donación (40 s) en cada inicio de sesión y al desbloquear
(decisión del dueño). La clave es del **negocio** (tabla `configuracion`), así
que con varias cajas basta activarla una vez.

## Formato de la clave

```
PDV2.<carga en Base64URL>.<firma Ed25519 en Base64URL>
carga = "PDV2|kid|id|nombre|emision|vence|nivel|flags"
```

- `kid`: id de la llave que firmó (8 hex de SHA-256 de la pública; la oficial es `0d2a501a`).
- `id`: 8 símbolos sin ambigüedad (`ABCD2345`), el identificador en tu libro.
- `nombre`: el negocio, tal cual lo verá en «Acerca de» (es lo que disuade de prestar la clave).
- `emision` / `vence`: `aaaa-mm-dd`; **vence al año** por defecto (renovable); vacío = sin vencimiento.
- `nivel`: `basico`, `plus` o `patrocinador` (cerrado; solo informativo por ahora).
- `flags`: `beta`, `soporte`; vacío = las dos.

Las claves **PDV1** (`PDV1|id|nombre|emision|nivel`, las del formato anterior)
se siguen aceptando: sin vencimiento, nivel libre.

El programa **guarda el texto de la clave** y **re-verifica la firma en cada
lectura** (`DonadorService`): no hay un booleano "es donador" en la base.
El estado sale de la carga firmada + la fecha de hoy + la lista de revocación:

| Estado | Qué pasa |
|---|---|
| vigente | sin ventana de donación, «Pedir soporte», versiones de prueba (si la clave trae `beta`) |
| vencida | «Acerca de» dice "tu periodo de apoyo terminó… renueva cuando quieras"; vuelve la ventana de donación; **nada se bloquea** |
| revocada | igual que vencida, con el aviso de revocación |

## Flujo cuando alguien dona

1. El donador transfiere a la CLABE de `donacion.properties` poniendo en el
   **concepto** "PDV + nombre del negocio". Pulsa **Ya doné: pedir mi clave**
   (ventana de donación o «Acerca de»): el programa arma un mensaje con
   negocio, monto, fecha, clave de rastreo (opcional), id de instalación y
   versión, y lo manda por WhatsApp/correo (el usuario lo ve antes de enviar).
2. **Verifica el abono en tu app del banco** (20 segundos: monto, fecha,
   concepto). El **CEP de Banxico** (<https://www.banxico.org.mx/cep/>) solo
   cuando haya duda (el dinero "aún no aparece" o el comprobante en imagen
   huele a editado): se captura a mano fecha + clave de rastreo + tu CLABE +
   monto; tiene captcha y horario (09:30–23:00). No se automatiza. Si algún
   día son decenas al mes, el servicio por lotes (`cep-scl`) acepta un TXT y
   manda los XML por correo.
3. Emite la clave desde `Punto-Ventas/`:

   ```bash
   ./gradlew emitirClaveDonador -Pdonador="Abarrotes Lupita" -Pmonto=300 -PfechaPago=2026-11-12 \
       -Prastreo=CLAVE_DE_RASTREO -Pcontacto=+5255XXXXXXXX        # -Pmeses=12 por defecto, -Pnivel=basico
   ```

   Imprime la clave (`PDV2.…`, una línea) y anota el evento en el libro.
4. Envíasela. El donador, como **administrador**, la pega en «Ya tengo mi
   clave» (ventana de donación, «Acerca de» o Configuración → Donador y
   soporte). Queda en la bitácora como `CLAVE_DONADOR_ACTIVADA` (y
   `CLAVE_DONADOR_QUITADA` si la quita).

## Renovar, revocar, buscar, verificar

```bash
./gradlew renovarClaveDonador -Pid=ABCD2345 -Pmonto=300 -PfechaPago=2027-11-10   # mismo id y nombre; +12 meses
                                                                                  # sobre el vencimiento actual
./gradlew revocarClaveDonador -Pid=ABCD2345 -Pmotivo="clave publicada en un foro"
./gradlew publicarRevocaciones          # escribe claves/revocadas.pdvr (raíz del repo) firmado → commit y push
./gradlew buscarDonador -Ptexto="Lupita"            # o -Pid=ABCD2345 / -Pinstalacion=7F3A-91C2 / nada = todo
./gradlew verificarClaveDonador -Ptexto="<pega aquí el mensaje de soporte>"   # o -Pclave="PDV2.…"
./gradlew registrarSoporte -Pid=ABCD2345 -Pinstalacion=7F3A-91C2 -Pversion=1.0.3 -Pnota="impresora"
```

**Cuando alguien pide soporte**, el mensaje que arma «Acerca de → Pedir
soporte» trae: versión y sistema, `Clave de apoyo: <id> · <negocio> ·
vigente hasta … · firma <12 caracteres>`, `Instalación: XXXX-XXXX` y el modo de
caja. Pégalo en `verificarClaveDonador -Ptexto=…`: te dice si el id existe en
tu libro, si está vigente/vencida/revocada, si la huella de la firma coincide
con la clave que emitiste (un fork con otra pública no pasa) y desde cuántas
instalaciones distintas se ha pedido soporte con ese id (si son muchas,
pregunta o revoca). Sin id válido no hay nada que verificar: responde con el
mensaje estándar ("el programa es libre; el soporte personal es para quien
apoya; así se hace") y no gastes tu tiempo.

**Revocación**: el libro es la verdad; `publicarRevocaciones` genera
`claves/revocadas.pdvr` (`PDVR1.<json>.<firma>`, misma llave) y el programa lo
baja a diario junto con la búsqueda de actualizaciones
(`raw.githubusercontent.com/Minepacman/Punto-Ventas/main/claves/revocadas.pdvr`).
Reglas: *fail-open* (sin internet se usa la copia local; sin copia nada está
revocado), anti-retroceso (una lista con fecha anterior a la guardada se
ignora) y `RevocadasPublicadasTest` comprueba que el archivo del repo esté
firmado con la llave oficial. Revoca solo claves claramente compartidas.

## Datos de donación firmados (`claves/donacion.pdvd`)

Contra la suplantación (una copia del programa con otra CLABE, un sitio falso): los datos
de `donacion.properties` (CLABE, banco, beneficiario, WhatsApp, correo) se publican **firmados
con la misma llave** en `claves/donacion.pdvd` (`PDVD1.<json>.<firma>`, `util/DatosDonacionFirmados`).
El programa lo baja con la consulta diaria de versiones
(`raw.githubusercontent.com/Minepacman/Punto-Ventas/main/claves/donacion.pdvd`), lo guarda en
`configuracion.datos_donacion_firmados` (fail-open, anti-retroceso por fecha) y lo coteja con lo
incrustado. Lo que ve el usuario en la ventana de donación y en «Acerca de»:

| Estado | Cuándo | Qué muestra |
|---|---|---|
| Datos verificados ✔ | coinciden CLABE, banco y beneficiario | la cuenta, con la fecha de la firma |
| Datos de donación aún no publicados | el archivo firmado dice `pendiente` y el programa tampoco trae CLABE | «CLABE no configurada» |
| Sin verificar | nunca se pudo bajar el archivo | la cuenta incrustada + «compara con el sitio oficial» |
| ⚠ NO coinciden | firma válida pero otra cuenta (copia modificada) | oculta la CLABE y el botón Copiar; «no dones a esta cuenta» |

Siempre, además: «dona solo a la cuenta oficial… revisa que tu banco muestre como beneficiario a
**Elias Oziel Sánchez Fernández**» (`Marca.AVISO_DONACION`) y el mensaje «Ya doné» incluye «Vi como
beneficiario: …» para que detectes de inmediato un pago a una cuenta ajena. El sitio lee el mismo
archivo firmado y lo verifica en el navegador (WebCrypto Ed25519) cuando el navegador lo permite.

**Cada vez que cambies `donacion.properties`** (la CLABE real, el banco, el beneficiario tal como lo
muestra el banco, o el contacto):

```bash
./gradlew firmarDatosDonacion        # escribe claves/donacion.pdvd con la llave de ~/.puntodeventa-desarrollador
git add Punto-Ventas/src/main/resources/donacion.properties claves/donacion.pdvd && git commit && git push
```

`DonacionPublicadaTest` falla si el archivo firmado no coincide con `donacion.properties`; así no se
publica una versión que se marque a sí misma como «no coincide». Mientras la CLABE siga PENDIENTE,
el archivo se publica como `pendiente` (sin CLABE) y la ventana dice «aún no publicados».

## Archivos del desarrollador (`~/.puntodeventa-desarrollador/`, fuera del repo)

- `donadores.key` — privada (PKCS#8, Base64). **Respáldala en dos sitios
  cifrados** (gestor de contraseñas + USB). Sin ella no puedes emitir ni
  publicar revocaciones; si se pierde, crea otro par y **agrega** la nueva
  pública a `ClaveDonador.CLAVES_PUBLICAS_OFICIALES` (las claves viejas siguen
  valiendo mientras no retires su `kid` en la lista de revocación).
- `donadores.pub` — pública; la oficial está incrustada en `ClaveDonador`.
- `donadores.jsonl` — el **libro**: una línea JSON por evento (`emitida`,
  `renovada`, `revocada`, `soporte`), solo se agrega. Guarda datos personales
  (nombre, contacto, clave de rastreo): bajo la **LFPDPPP** respondes por
  ellos; guarda lo mínimo, cifra el respaldo y no lo compartas. El mensaje
  que arma el programa ya incluye "tus datos solo se usan para atender tu
  soporte".
- Nada de esto va al repositorio (`.gitignore`: `*.key`, `donadores.pub`,
  `donadores.jsonl`). La herramienta vive en `src/herramientas` (no se
  empaqueta en el instalador) y se puede apuntar a otra carpeta con
  `-Dpuntodeventa.desarrollador.carpeta=…` (así la prueban los tests).
- Donativos a una persona física son ingreso: consulta a un contador sobre
  el ISR antes de publicar la CLABE. En `donacion.properties` ya están el WhatsApp
  (282 141 6799) y el correo (elias.osiel@hotmail.com); siguen PENDIENTE la CLABE,
  el banco y el beneficiario (escríbelo tal como lo muestra el banco al transferir).
  Después de llenarlos: `./gradlew firmarDatosDonacion` y commit.

## Para el desarrollo

- `ClaveDonadorTest`, `RevocacionesApoyoTest`, `DonadorServiceTest` y
  `HerramientaDonadoresTest` firman con pares de llaves generados al vuelo.
- `DonadorService.usarVerificadorParaPruebas` / `usarRelojParaPruebas` cambian
  la pública y la fecha solo en pruebas.
- `ActualizacionService` solo consulta `api.github.com/repos/Minepacman/Punto-Ventas/releases`
  y la lista de revocación; abre en el navegador únicamente enlaces que
  empiecen con `https://github.com/Minepacman/Punto-Ventas/`.
