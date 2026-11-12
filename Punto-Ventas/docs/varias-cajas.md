# Varias cajas con un servidor PostgreSQL

Caja Libre trabaja de dos formas:

| Modo | Dónde viven los datos | Cuándo usarlo |
|------|-----------------------|---------------|
| **Una sola caja** (por defecto) | Archivo SQLite `datos.db` en la carpeta de datos del usuario | Una computadora. No hay que instalar nada más. |
| **Varias cajas** | Un servidor PostgreSQL en la red local de la tienda | Dos o más cajas que deben ver el mismo inventario, las mismas ventas y los mismos usuarios. |

El modo se elige la primera vez que se abre el programa o después en
**Configuración → Base de datos** (solo el administrador). Cada caja guarda su
configuración en un archivo local (`conexion.properties`) en:

- Windows: `%APPDATA%\PuntoDeVenta\`
- macOS: `~/Library/Application Support/PuntoDeVenta/`
- Linux: `~/.puntodeventa/`

La contraseña del servidor se guarda **ofuscada** (AES con una clave aleatoria en
`clave.local`, en la misma carpeta). No es un cifrado seguro: quien pueda leer
los dos archivos puede recuperarla. Lo que la protege de verdad son los permisos
de la carpeta del usuario y que el usuario de PostgreSQL solo tenga acceso a la
base de Caja Libre.

## 1. Instalar PostgreSQL en el equipo servidor

Sirve cualquier PC de la tienda que esté siempre encendida (puede ser la misma
que la caja 1). Se recomienda PostgreSQL 14 o más reciente.

**Windows**

1. Descarga el instalador de <https://www.postgresql.org/download/windows/> (EDB) y ejecútalo.
2. Deja el puerto `5432`, anota la contraseña del superusuario `postgres` y
   termina el asistente (no hace falta Stack Builder).
3. El servicio *postgresql-x64-NN* queda arrancando solo con Windows.

**macOS**

- Postgres.app (<https://postgresapp.com>): descargar, mover a Aplicaciones, "Initialize".
- O con Homebrew: `brew install postgresql@16` y `brew services start postgresql@16`.

**Linux (Debian/Ubuntu)**

```bash
sudo apt install postgresql
sudo systemctl enable --now postgresql
```

## 2. Crear la base de datos y el usuario

Abre una consola de PostgreSQL (en Windows: *SQL Shell (psql)* del menú Inicio;
en macOS/Linux: `psql -U postgres` o `sudo -u postgres psql`) y ejecuta:

```sql
CREATE USER puntodeventa WITH PASSWORD 'ElijeUnaContrasenaLarga';
CREATE DATABASE puntodeventa OWNER puntodeventa ENCODING 'UTF8';
```

La base debe quedar **vacía**: las tablas las crea el programa la primera vez
que una caja se conecta (o el asistente de migración, ver abajo).

## 3. Permitir conexiones desde la red local

1. Da una **dirección IP fija** al servidor en el router (o anota la que tiene,
   p. ej. `192.168.1.10`).
2. En `postgresql.conf` (Windows: `C:\Program Files\PostgreSQL\NN\data\`;
   Linux: `/etc/postgresql/NN/main/`) deja:
   ```
   listen_addresses = '*'
   ```
3. En `pg_hba.conf` (misma carpeta) agrega al final una línea con la red de la
   tienda (ajusta `192.168.1.0/24` a tu red):
   ```
   host    puntodeventa    puntodeventa    192.168.1.0/24    scram-sha-256
   ```
4. Reinicia el servicio de PostgreSQL.
5. Abre el puerto **5432/TCP** en el firewall del servidor:
   - Windows: Firewall de Windows Defender → Configuración avanzada → Reglas de
     entrada → Nueva regla → Puerto → TCP 5432 → Permitir → solo perfil *Privado*.
   - Linux: `sudo ufw allow from 192.168.1.0/24 to any port 5432`.
   - macOS: el firewall pregunta al arrancar Postgres.app; permitir.

## 4. Configurar cada caja

En cada computadora con Caja Libre:

1. Abre **Configuración → Base de datos** como administrador (o elige "Varias
   cajas" la primera vez que se abre el programa).
2. Marca **Varias cajas (servidor PostgreSQL)** y captura: servidor
   (`192.168.1.10`), puerto `5432`, base `puntodeventa`, usuario
   `puntodeventa` y su contraseña.
3. Pulsa **Probar conexión**: debe decir "Conexión correcta".
4. Pon el **número de caja** (1, 2, 3… distinto en cada equipo) y un nombre
   (p. ej. "Mostrador", "Caja 2").
5. **Guardar conexión y caja** y reinicia el programa.

El número de caja aparece en la cabecera junto al reloj, en los tickets y en los
cortes. Cada venta, fondo de caja, corte, ticket pendiente y movimiento de
inventario lleva su caja.

### Si la tienda ya tenía ventas con una sola caja

En la caja que tiene el archivo local, antes de cambiar el modo: en
**Configuración → Base de datos** captura los datos del servidor y pulsa
**Migrar datos de esta caja al servidor…**. El asistente copia todas las tablas
a la base vacía del servidor (una transacción por tabla, con avance), verifica
los conteos, deja el archivo local intacto como respaldo y cambia esta caja a
modo "Varias cajas". Se rehúsa si la base del servidor ya tiene tablas. Las
demás cajas solo se configuran como en el punto 4.

## Cómo trabajan varias cajas

- **Fondo de caja**: uno por caja y por día; lo captura el primero que entra en
  esa caja. El administrador lo puede corregir desde la pestaña Corte.
- **Corte**: "Hacer corte de esta caja" suma solo las ventas de la caja donde se
  hace. El administrador tiene además **Corte general (todas las cajas)**, que
  suma las ventas y los fondos de todas.
- **Folios**: salen del id de la venta, que lo asigna el servidor con una sola
  secuencia para toda la tienda: nunca se repiten aunque dos cajas cobren al mismo
  tiempo. Por eso no hay un contador por caja (que podría chocar): el folio es
  único y la caja va en su propia columna y en el ticket.
- **Existencias**: se descuentan en el servidor con un `UPDATE` condicionado
  ("resta solo si alcanza"). Si dos cajas venden las últimas piezas a la vez,
  una lo logra y la otra recibe el aviso de existencia insuficiente (con la
  autorización de "vender sin existencia" sigue funcionando como siempre). El
  cupo de una promoción "hasta agotar" se controla igual.
- **Usuarios, bloqueos y permisos**: están en el servidor, así que valen en
  todas las cajas. El ticket sin cobrar se conserva por cajero **y por caja**.
- **Estadísticas**: en la pestaña "Cajeros y horas" aparece una tabla de ventas
  por caja y un filtro de caja para las tablas por cajero y por hora. Las demás
  gráficas son de toda la tienda.
- **Respaldos**: en modo servidor se hacen en el servidor con `pg_dump`
  (la pantalla Base de datos muestra el comando); los botones de respaldo y
  restauración del archivo local quedan deshabilitados.
- **Configuración del negocio**: cada caja la relee cada 30 segundos, así que un
  cambio (IVA, formas de pago…) tarda hasta medio minuto en verse en las demás.
- **Fotos de productos**: se guardan en la base de datos (tabla
  `producto_imagen`, columnas `bytea`; en SQLite, `blob`), ya reducidas a 512 px
  (JPEG) más una miniatura de 96 px, así que todas las cajas ven la misma foto,
  viajan con `pg_dump`/el respaldo del archivo y la migración SQLite → servidor
  las copia como cualquier otra tabla. Las listas de productos nunca cargan los
  bytes: se piden aparte y bajo demanda.

## Si el servidor no responde

Al abrir el programa aparece la pantalla **"No hay conexión con el servidor de la
tienda"** con el detalle técnico y tres opciones: **Reintentar**, **Cambiar
conexión…** (pide la contraseña del administrador, comparada con el hash de la
última vez que un administrador entró en esa caja) y **Salir**. El programa
**no** cambia solo al archivo local: si lo hiciera, las ventas quedarían
repartidas en dos bases y el inventario dejaría de cuadrar.

Si en el futuro se quiere poder vender aunque se caiga la red, la ruta es una
**cola fuera de línea**: guardar las ventas en un archivo local mientras no hay
servidor y reenviarlas al reconectar, reservando por adelantado un rango de
folios por caja y resolviendo el inventario al sincronizar. No está hecho; esta
versión prefiere detener la caja a mezclar datos.

## Respaldo en el servidor

```
pg_dump -h localhost -p 5432 -U puntodeventa -Fc -f respaldo-AAAAMMDD.dump puntodeventa
pg_restore -h localhost -p 5432 -U puntodeventa -d puntodeventa --clean --if-exists respaldo-AAAAMMDD.dump
```

Conviene programarlo a diario (Programador de tareas en Windows, `cron` en
macOS/Linux) y copiar los `.dump` a otro disco o a la nube.

## Pruebas

- `./gradlew test`: la suite normal, contra SQLite.
- `./gradlew testPostgres`: pruebas con etiqueta `postgres` contra un PostgreSQL
  embebido temporal (zonky; descarga el binario la primera vez, no instala nada):
  creación del esquema, venta, dos cajas vendiendo lo mismo a la vez sin
  sobreventa, cupo "hasta agotar" entre cajas, cortes por caja y general, folios
  únicos con cuatro cajas cobrando a la vez y la migración SQLite → servidor.
