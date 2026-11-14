# Si el administrador olvida su contraseña

El programa no guarda contraseñas en claro (solo su hash BCrypt), así que nadie
puede "verla". Hay tres caminos para recuperar el acceso, del más cómodo al más
técnico.

## 1. Código de recuperación (recomendado: generarlo HOY)

1. Entra como administrador y abre **Configuración → Cajeros**.
2. Pulsa **Código de recuperación…** y confirma. Aparece un código de 16 letras y
   números, por ejemplo `K7PM-3HRA-WQ2E-9DNB`.
3. **Anótalo y guárdalo fuera de la computadora** (libreta del dueño, caja fuerte,
   gestor de contraseñas). Se muestra una sola vez; en la base de datos queda solo
   su hash, como una contraseña más, **en la fila de ese administrador**
   (`cajeros.recuperacion_hash`).

El código es **personal**: solo restablece la contraseña del administrador que lo
generó. Si hay dos administradores, cada uno genera y guarda el suyo; el código de
uno no sirve para el otro aunque esté bien escrito.

Cuando haga falta:

1. En la pantalla de inicio de sesión pulsa **¿Olvidaste tu contraseña?**.
2. Escribe tu usuario de administrador, el código y la contraseña nueva (mínimo
   5 caracteres y un número; no vale tu usuario ni `admin123`).
3. Entra con la contraseña nueva y **genera otro código**: cada código sirve una
   sola vez y queda consumido al usarse.

Seguridad del mecanismo:

- Solo funciona para usuarios con rol **Administrador** y activos; un cajero no
  puede usarlo aunque lo tenga, y solo restablece la contraseña de **quien lo generó**.
- Cada intento (correcto o fallido) queda en la bitácora
  (`RECUPERACION_RECHAZADA`, `CONTRASENA_RECUPERADA`, `CODIGO_RECUPERACION_GENERADO`,
  `CODIGO_RECUPERACION_ANULADO`).
- Tras 5 códigos incorrectos seguidos hay que esperar 15 minutos.
- Al usarlo se quita también el bloqueo por intentos fallidos, se cierran las
  sesiones móviles de ese administrador y su contraseña vieja deja de abrir la
  pantalla "sin conexión con el servidor" de la caja (`admin.hash`); al entrar con
  la nueva se vuelve a recordar.
- Generar un código nuevo anula el anterior **del mismo administrador**. También
  puede anularlo sin generar otro (`RecuperacionService.anularCodigo`).
- El código **se anula solo** cuando a su dueño lo desactivan, lo eliminan, le
  quitan el rol de administrador o **otro** administrador le restablece la
  contraseña (cambiar la propia no lo afecta). Queda en la bitácora como
  `CODIGO_RECUPERACION_ANULADO` con el motivo.
- Al **restaurar un respaldo** (una caja, SQLite) el programa anula al arrancar
  todos los códigos que traiga la base restaurada: un código ya usado o anulado no
  "revive" por volver a una copia vieja. Cada administrador genera el suyo de nuevo.
  Con PostgreSQL la restauración se hace fuera del programa (`pg_restore`), así que
  conviene regenerar los códigos a mano después.

Códigos de la versión 1.0.3 o anterior (vivían en `configuracion.recuperacion_hash`,
sin dueño): se aceptan **una sola vez y solo si quien lo usa es el único
administrador activo**; con dos o más administradores se rechazan y hay que generar
uno nuevo. Cualquier generación, anulación, cambio de administrador o restauración
también los borra. En Configuración → Cajeros aparece "Hay un código de una versión
anterior, sin dueño: genera el tuyo".

Con varias cajas (PostgreSQL) el código vive en el servidor, así que sirve desde
cualquier caja.

## 2. Otro administrador restablece la contraseña

Si hay más de un administrador, cualquiera de ellos puede entrar a
**Configuración → Cajeros**, seleccionar al usuario y escribirle una contraseña
temporal. Al entrar, el programa obliga a cambiarla. Conviene tener siempre dos
administradores (el dueño y una persona de confianza).

## 3. Sin código y sin otro administrador (último recurso)

Solo para quien dé soporte técnico; implica editar la base de datos a mano.

- **Una sola caja (SQLite)**: con el programa cerrado, abre `datos.db` (en
  `%APPDATA%\PuntoDeVenta` en Windows o `~/Library/Application Support/PuntoDeVenta`
  en macOS; o `./datos.db` si el programa lo encuentra ahí) con una herramienta
  como DB Browser for SQLite y ejecuta, sustituyendo el hash por el de una
  contraseña conocida:

  ```sql
  UPDATE cajeros
     SET contrasena_hash = '$2a$10$...hash BCrypt...',
         debe_cambiar_contrasena = 1, bloqueado_hasta = NULL, intentos_fallidos = 0
   WHERE usuario = 'admin';
  ```

  El hash de `admin123` que siembra el programa sirve como valor conocido: se
  obtiene de cualquier `datos.db` recién creado (una instalación nueva en otra
  carpeta lo genera al primer arranque). Al entrar se exigirá cambiarla.

- **Varias cajas (PostgreSQL)**: la misma sentencia con `psql` contra la base
  del servidor (`debe_cambiar_contrasena = true`).

- Si lo que se perdió es la contraseña del **servidor PostgreSQL** (no la del
  administrador del programa), ver `docs/varias-cajas.md`.

Haz un respaldo antes de tocar la base y anota el cambio: no queda en la bitácora
porque se hizo fuera del programa.
