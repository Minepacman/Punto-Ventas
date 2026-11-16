# Notificaciones en el teléfono (Web Push + HTTPS local)

Los avisos de la campana de la página móvil (proveedores que entregan o pasan a preventa, productos que se
están acabando, hora pico) llegan como **notificaciones reales** del teléfono, con la pantalla bloqueada o la
página cerrada. Se usa Web Push estándar (RFC 8030/8291/8292) desde la propia página; no hay servicios de
terceros (ntfy, Telegram) ni cuentas que crear.

## Lo que hace falta (decisiones aceptadas por el dueño)

| Requisito | Por qué | Cómo se resuelve |
|---|---|---|
| Página abierta por **HTTPS** | Los navegadores solo permiten service worker y Web Push en páginas seguras. | El programa abre un segundo servidor HTTPS (puerto HTTP + 1) con un certificado firmado por una **CA local** de la instalación. |
| Cada teléfono instala **una vez** el certificado | La CA no es pública; el teléfono debe confiar en ella. | Página pública `http://<ip>:<puerto>/certificado` con guía para Android / iPhone y QR en Configuración → Acceso móvil. |
| iPhone: página en la **pantalla de inicio** (iOS ≥ 16.4) | Apple solo entrega Web Push a webapps instaladas. | La tarjeta Notificaciones de Ajustes lo indica cuando detecta iPhone en Safari. |
| La computadora necesita **internet** | El mensaje viaja al servicio de push del navegador (Google, Apple, Mozilla, Microsoft). | Si no hay internet el envío falla y se anota; la campana dentro de la página sigue igual. |
| El HTTP normal **sigue funcionando** | Teléfonos sin certificado o sin interés en notificaciones. | Sin HSTS; la única diferencia es que la tarjeta Notificaciones muestra "Abre la página segura". |

## Dónde viven las claves

Carpeta `claves-privadas/` junto a la base de datos (`RutasApp.carpetaClaves()`; en pruebas `app.claves-dir` apunta a
una carpeta temporal). Permisos de solo el usuario en macOS/Linux (0600 / 0700); en Windows heredan los del
perfil. **No entran en los respaldos** (copian solo `datos.db`) ni en el repositorio: un respaldo restaurado en
otra computadora genera CA y clave VAPID nuevas, y los teléfonos tendrán que instalar el certificado nuevo y
volver a activar las notificaciones.

| Archivo | Contenido |
|---|---|
| `movil-ca.key` / `movil-ca.crt` | CA local (EC P-256, 10 años, `CN=Caja Libre CA local <id>`). Se crea la primera vez. |
| `movil-servidor.key` / `movil-servidor.crt` | Certificado del servidor HTTPS (2 años; SAN: todas las IP de la red local, `127.0.0.1`, `localhost`, nombre del equipo, `nombre.local`). Se reemite solo cuando cambian las direcciones, le queda menos de un mes o lo firmó otra CA. La CA no cambia. |
| `push-vapid.key` / `push-vapid.pub` | Par VAPID (P-256). La pública se entrega a los teléfonos; la privada firma el JWT de cada envío. |

Todo con el JDK (`util/Der` escribe el DER del X.509; `servidor/CertificadosLocales`; `servidor/WebPushCripto`).
Sin BouncyCastle ni dependencias nuevas.

## Flujo para el dueño

1. Configuración → Acceso móvil: con el acceso encendido aparece la sección **Notificaciones en el teléfono**
   con un QR a `/certificado` y la **clave del certificado** (huella SHA-256 corta, p. ej. `DB1F 1A39 EB3B F177`).
2. El teléfono escanea el QR, compara la clave con la de la computadora y sigue los pasos:
   - **Android**: descarga `caja-libre.crt` → Ajustes → Seguridad → Cifrado y credenciales → Instalar un
     certificado → Certificado de CA.
   - **iPhone**: descarga el perfil `caja-libre.mobileconfig` → Ajustes → Perfil descargado → Instalar
     (dirá "No verificado": es normal) → Ajustes → General → Información → Ajustes de confianza de
     certificados → activar. Después "Agregar a pantalla de inicio" desde Safari.
3. Abre la dirección segura (`https://<ip>:<puerto+1>/`), entra con su usuario y en Ajustes → Notificaciones
   toca **Permitir notificaciones**. Puede mandarse una **notificación de prueba**.
4. En esa tarjeta elige qué recibe (Proveedores, Se está acabando, Hora pico) y las horas de silencio
   (21:00 a 7:00 por defecto).

## Qué se manda y cuándo (`service/ProgramadorAvisosPush`)

Corre cada minuto (el primer tic a los 2 minutos de arrancar) solo si el acceso móvil está encendido.
Hora local de la computadora.

| Aviso | Cuándo |
|---|---|
| Proveedor que entrega / preventa **hoy** | desde las 8:00 |
| Proveedor de **mañana** (entrega o preventa) | desde las 19:00 |
| **Se está acabando** | dos cortes al día, 9:00 y 17:00; en cada corte solo los productos no avisados hoy |
| **Hora pico** | en la media hora anterior a la franja |

Por teléfono: interruptor general, tipo de aviso, horas de silencio, y **nunca el mismo aviso dos veces el
mismo día** (ids guardados en `suscripciones_push.enviados_ids` / `enviados_dia`, así sobrevive a reinicios).
Los avisos se calculan **ejecutando como el usuario** (`SesionService.ejecutarComo`), por eso los importes
(nota media del proveedor) solo llegan a quien tiene `VER_GANANCIAS` o `GESTIONAR_PROVEEDORES`, igual que en
la campana. TTL 4 h; `Urgency: high` para los de hoy. Un 404/410 del servicio de push borra la suscripción;
un usuario desactivado pierde sus teléfonos en el siguiente tic.

## Seguridad

- Las claves privadas nunca salen del disco ni se registran. `/certificado` y sus descargas solo entregan el
  certificado **público**, la huella, el nombre del negocio y la dirección https.
- Todo `/api/push/*` exige sesión (Bearer). Cada usuario solo ve y toca sus propias suscripciones.
- El endpoint que manda el teléfono se acepta solo si es `https` hacia un servicio de push conocido
  (`servidor/ValidadorEndpointPush`: `fcm.googleapis.com`, `*.push.apple.com`, `updates.push.services.mozilla.com`,
  `*.notify.windows.com`), sin usuario:clave, sin puerto raro ni IP literal: evita que la computadora de la
  tienda haga peticiones a la red local (SSRF). El envío (`EnviadorPushHttp`) no sigue redirecciones, con 10 s de
  espera y respuesta acotada.
- Límite de 20 altas/pruebas por IP cada 10 minutos y 10 suscripciones por usuario.
- CSP de la página incluye `worker-src 'self'`; el service worker no guarda páginas ni intercepta peticiones.
- "Regenerar certificado" (Opciones avanzadas) crea CA nueva; todos los teléfonos deben instalarla otra vez.

## Lo que el dueño debe probar en teléfonos reales

Imposible de probar sin dispositivos. Lista mínima:

**Android (Chrome)**
1. Escanear el QR de "Notificaciones en el teléfono", comprobar que la clave coincide.
2. Descargar e instalar `caja-libre.crt` como certificado de CA (ruta de Ajustes según marca; buscar "certificado").
3. Abrir `https://<ip>:<puerto+1>/`: debe cargar **sin** aviso de "conexión no privada" y con candado.
4. Entrar, Ajustes → Notificaciones → Permitir → aceptar el permiso → "Enviar notificación de prueba" llega
   (la PC necesita internet). Probar con la pantalla bloqueada y con Chrome cerrado.
5. Tocar la notificación: abre la página en Proveedores / Inventario con el filtro del aviso.
6. Cambiar la IP de la PC (otro Wi-Fi): el certificado se reemite solo; la página segura sigue abriendo
   sin volver a instalar nada (puede tardar hasta 10 min o reiniciar el acceso móvil).
7. Cerrar sesión y entrar con otro usuario en el mismo teléfono: las notificaciones pasan a ese usuario.

**iPhone (Safari, iOS ≥ 16.4)**
1. Descargar el perfil, instalarlo (aviso "No verificado") y activar la confianza en Ajustes → General →
   Información → Ajustes de confianza de certificados.
2. Abrir la dirección https en Safari (sin advertencia) y "Agregar a pantalla de inicio".
3. Abrir desde el icono, entrar, Ajustes → Notificaciones → Permitir; notificación de prueba con la pantalla
   bloqueada.
4. Tocar la notificación abre la webapp instalada en la sección correcta.

**Escritorio**
- Opciones avanzadas → puerto HTTPS fijo; "Regenerar certificado" (los teléfonos dejan de abrir la https hasta
  reinstalar); tabla "Teléfonos con notificaciones" con Quitar.
- Windows: comprobar que el firewall deja pasar el puerto HTTPS igual que el HTTP.
