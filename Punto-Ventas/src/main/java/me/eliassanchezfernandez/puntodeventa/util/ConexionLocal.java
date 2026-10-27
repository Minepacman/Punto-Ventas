package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Configuración LOCAL de esta caja: cómo se conecta a la base de datos y qué
 * número de caja es. No puede vivir en la base de datos (hace falta leerla
 * para poder abrirla), así que se guarda en un archivo pequeño en la carpeta
 * de datos del usuario ({@link RutasApp#carpetaAplicacion()}):
 *
 *   conexion.properties   modo, datos del servidor y número/nombre de caja
 *   clave.local           32 bytes aleatorios creados en esta instalación
 *
 * La contraseña del servidor se guarda OFUSCADA (AES-GCM con la clave de
 * clave.local). Es ofuscación, no seguridad real: quien pueda leer los dos
 * archivos puede recuperarla. Su propósito es que la contraseña no quede a la
 * vista en texto plano ni se copie por accidente; la protección verdadera son
 * los permisos de la carpeta del usuario (y, en el servidor, que el usuario de
 * PostgreSQL solo tenga acceso a esta base). Nunca se escribe en la bitácora
 * ni en la consola.
 *
 * Esta clase no depende de Spring: el Launcher la lee ANTES de arrancarlo
 * para fijar spring.datasource.* como propiedades del sistema.
 */
public final class ConexionLocal {

    public static final String ARCHIVO = "conexion.properties";
    public static final String ARCHIVO_CLAVE = "clave.local";
    public static final int PUERTO_PG_POR_DEFECTO = 5432;
    public static final String BASE_POR_DEFECTO = "puntodeventa";
    public static final String USUARIO_POR_DEFECTO = "puntodeventa";

    /** Propiedades del sistema que el Launcher fija y los beans leen */
    public static final String PROP_MODO        = "app.bd.modo";
    public static final String PROP_CAJA_NUMERO = "app.caja.numero";
    public static final String PROP_CAJA_NOMBRE = "app.caja.nombre";

    private static final String PREFIJO_OFUSCADO = "enc:";

    private PerfilBaseDatos modo = PerfilBaseDatos.SQLITE;
    private int cajaNumero = 1;
    private String cajaNombre = "Caja 1";
    private String host = "";
    private int puerto = PUERTO_PG_POR_DEFECTO;
    private String baseDatos = BASE_POR_DEFECTO;
    private String usuario = USUARIO_POR_DEFECTO;
    private String contrasena = "";
    /**
     * Hash BCrypt del último administrador que inició sesión en esta caja.
     * Sirve para pedir la contraseña del administrador en la pantalla de
     * "no hay conexión con el servidor", cuando la base no se puede consultar.
     * Es un hash (nunca la contraseña).
     */
    private String adminHash;

    // ── Acceso ────────────────────────────────────────────────────────────

    public PerfilBaseDatos getModo()  { return modo; }
    public int getCajaNumero()        { return cajaNumero; }
    public String getCajaNombre()     { return cajaNombre; }
    public String getHost()           { return host; }
    public int getPuerto()            { return puerto; }
    public String getBaseDatos()      { return baseDatos; }
    public String getUsuario()        { return usuario; }
    public String getContrasena()     { return contrasena; }
    public String getAdminHash()      { return adminHash; }

    public void setModo(PerfilBaseDatos modo)   { this.modo = modo == null ? PerfilBaseDatos.SQLITE : modo; }
    public void setCajaNumero(int n)            { this.cajaNumero = Math.max(1, n); }
    public void setCajaNombre(String nombre)    { this.cajaNombre = nombre == null || nombre.isBlank() ? "Caja " + cajaNumero : nombre.trim(); }
    public void setHost(String host)            { this.host = host == null ? "" : host.trim(); }
    public void setPuerto(int puerto)           { this.puerto = puerto <= 0 || puerto > 65535 ? PUERTO_PG_POR_DEFECTO : puerto; }
    public void setBaseDatos(String base)       { this.baseDatos = base == null || base.isBlank() ? BASE_POR_DEFECTO : base.trim(); }
    public void setUsuario(String usuario)      { this.usuario = usuario == null || usuario.isBlank() ? USUARIO_POR_DEFECTO : usuario.trim(); }
    public void setContrasena(String c)         { this.contrasena = c == null ? "" : c; }
    public void setAdminHash(String hash)       { this.adminHash = hash == null || hash.isBlank() ? null : hash; }

    public boolean esPostgresql() { return modo == PerfilBaseDatos.POSTGRESQL; }
    public boolean esSqlite()     { return modo == PerfilBaseDatos.SQLITE; }

    /** jdbc:postgresql://host:puerto/base (sin credenciales); null en modo SQLite */
    public String urlPostgresql() {
        if (!esPostgresql()) return null;
        return "jdbc:postgresql://" + host + ":" + puerto + "/" + baseDatos;
    }

    /** Texto para pantallas y bitácora: nunca incluye la contraseña */
    public String descripcionServidor() {
        return host + ":" + puerto + "/" + baseDatos + " (usuario " + usuario + ")";
    }

    /** Copia independiente (para editar en un formulario sin tocar la vigente) */
    public ConexionLocal copia() {
        ConexionLocal c = new ConexionLocal();
        c.modo = modo; c.cajaNumero = cajaNumero; c.cajaNombre = cajaNombre;
        c.host = host; c.puerto = puerto; c.baseDatos = baseDatos; c.usuario = usuario; c.contrasena = contrasena;
        c.adminHash = adminHash;
        return c;
    }

    /** Valida lo mínimo para poder intentar una conexión a PostgreSQL */
    public void validarServidor() {
        if (!esPostgresql()) return;
        if (host.isBlank()) throw new IllegalArgumentException("Indica la dirección (IP o nombre) del servidor.");
        if (host.contains("/") || host.contains(" ") || host.contains("?") || host.contains("&")) {
            throw new IllegalArgumentException("La dirección del servidor no es válida.");
        }
        if (!baseDatos.matches("[A-Za-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("El nombre de la base de datos solo admite letras, números y guion bajo.");
        }
        if (usuario.isBlank()) throw new IllegalArgumentException("Indica el usuario de PostgreSQL.");
    }

    // ── Archivo ───────────────────────────────────────────────────────────

    public static Path archivo(Path carpeta)      { return carpeta.resolve(ARCHIVO); }
    public static Path archivoClave(Path carpeta) { return carpeta.resolve(ARCHIVO_CLAVE); }

    /** ¿Ya se configuró esta caja alguna vez? (si no, es la primera vez que se abre el programa) */
    public static boolean existe(Path carpeta) {
        return Files.isRegularFile(archivo(carpeta));
    }

    /**
     * Lee la configuración de la carpeta; si no existe o está dañada regresa la
     * configuración por defecto (una sola caja, SQLite), que es lo que el
     * programa hacía siempre.
     */
    public static ConexionLocal cargar(Path carpeta) {
        ConexionLocal c = new ConexionLocal();
        Path archivo = archivo(carpeta);
        if (!Files.isRegularFile(archivo)) return c;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(archivo)) {
            p.load(in);
        } catch (IOException e) {
            System.err.println("No se pudo leer " + archivo + ": " + e.getMessage() + ". Se usa una sola caja (SQLite).");
            return c;
        }
        try {
            c.setModo(PerfilBaseDatos.valueOf(p.getProperty("modo", "SQLITE").trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            c.setModo(PerfilBaseDatos.SQLITE);
        }
        c.setCajaNumero(entero(p.getProperty("caja.numero"), 1));
        c.setCajaNombre(p.getProperty("caja.nombre"));
        c.setHost(p.getProperty("pg.host"));
        c.setPuerto(entero(p.getProperty("pg.puerto"), PUERTO_PG_POR_DEFECTO));
        c.setBaseDatos(p.getProperty("pg.basedatos"));
        c.setUsuario(p.getProperty("pg.usuario"));
        c.setAdminHash(p.getProperty("admin.hash"));
        String guardada = p.getProperty("pg.contrasena", "");
        if (guardada.startsWith(PREFIJO_OFUSCADO)) {
            try {
                c.setContrasena(desofuscar(guardada.substring(PREFIJO_OFUSCADO.length()), claveInstalacion(carpeta, false)));
            } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
                // Clave perdida o archivo copiado de otra instalación: se pedirá la contraseña de nuevo
                System.err.println("No se pudo leer la contraseña guardada del servidor; vuelve a capturarla en Configuración.");
                c.setContrasena("");
            }
        } else {
            c.setContrasena(guardada);   // compatibilidad con un archivo escrito a mano
        }
        return c;
    }

    /** Escribe el archivo (de forma atómica) con permisos solo para el usuario cuando el sistema lo permite */
    public void guardar(Path carpeta) throws IOException {
        Files.createDirectories(carpeta);
        Properties p = new Properties();
        p.setProperty("modo", modo.name());
        p.setProperty("caja.numero", String.valueOf(cajaNumero));
        p.setProperty("caja.nombre", cajaNombre);
        p.setProperty("pg.host", host);
        p.setProperty("pg.puerto", String.valueOf(puerto));
        p.setProperty("pg.basedatos", baseDatos);
        p.setProperty("pg.usuario", usuario);
        if (adminHash != null) p.setProperty("admin.hash", adminHash);
        if (!contrasena.isEmpty()) {
            try {
                p.setProperty("pg.contrasena", PREFIJO_OFUSCADO + ofuscar(contrasena, claveInstalacion(carpeta, true)));
            } catch (GeneralSecurityException e) {
                throw new IOException("No se pudo proteger la contraseña del servidor: " + e.getMessage(), e);
            }
        }
        Path destino = archivo(carpeta);
        Path temporal = carpeta.resolve(ARCHIVO + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporal)) {
            p.store(out, "Conexion de esta caja. La contrasena esta ofuscada con clave.local (no es cifrado seguro).");
        }
        restringirPermisos(temporal);
        Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        restringirPermisos(destino);
    }

    // ── Propiedades del sistema para Spring ───────────────────────────────

    /**
     * Fija las propiedades que Spring Boot lee al arrancar. En modo PostgreSQL
     * son la URL, el usuario, la contraseña, el driver y el dialecto; en SQLite
     * solo el modo y la caja (la URL del archivo la decide RutasApp).
     */
    public void aplicarAlSistema() {
        System.setProperty(PROP_MODO, modo.name());
        System.setProperty(PROP_CAJA_NUMERO, String.valueOf(cajaNumero));
        System.setProperty(PROP_CAJA_NOMBRE, cajaNombre);
        if (esPostgresql()) {
            System.setProperty("spring.datasource.url", urlPostgresql());
            System.setProperty("spring.datasource.username", usuario);
            System.setProperty("spring.datasource.password", contrasena);
            fijarDriverYDialecto(PerfilBaseDatos.POSTGRESQL);
        }
    }

    /** Driver y dialecto de Hibernate acordes a la URL (solo si nadie los indicó explícitamente) */
    public static void fijarDriverYDialecto(PerfilBaseDatos perfil) {
        if (System.getProperty("spring.datasource.driver-class-name") == null) {
            System.setProperty("spring.datasource.driver-class-name", perfil.driver());
        }
        if (System.getProperty("spring.jpa.database-platform") == null) {
            System.setProperty("spring.jpa.database-platform", perfil.dialecto());
        }
    }

    // ── Ofuscación ────────────────────────────────────────────────────────

    /**
     * Clave de esta instalación (32 bytes aleatorios en clave.local). Se crea la
     * primera vez que hace falta; si {@code crear} es false y no existe, falla.
     */
    static byte[] claveInstalacion(Path carpeta, boolean crear) throws IOException {
        Path archivo = archivoClave(carpeta);
        if (Files.isRegularFile(archivo)) {
            byte[] clave = Files.readAllBytes(archivo);
            if (clave.length == 32) return clave;
            if (!crear) throw new IOException("La clave local está dañada.");
        } else if (!crear) {
            throw new IOException("No existe la clave local.");
        }
        byte[] clave = new byte[32];
        new SecureRandom().nextBytes(clave);
        Files.createDirectories(carpeta);
        Files.write(archivo, clave);
        restringirPermisos(archivo);
        return clave;
    }

    static String ofuscar(String texto, byte[] clave) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
        cifrador.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clave, "AES"), new GCMParameterSpec(128, iv));
        byte[] cifrado = cifrador.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        byte[] todo = new byte[iv.length + cifrado.length];
        System.arraycopy(iv, 0, todo, 0, iv.length);
        System.arraycopy(cifrado, 0, todo, iv.length, cifrado.length);
        return Base64.getEncoder().encodeToString(todo);
    }

    static String desofuscar(String base64, byte[] clave) throws GeneralSecurityException {
        byte[] todo = Base64.getDecoder().decode(base64);
        if (todo.length < 13) throw new IllegalArgumentException("Dato ofuscado incompleto.");
        Cipher cifrador = Cipher.getInstance("AES/GCM/NoPadding");
        cifrador.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"), new GCMParameterSpec(128, todo, 0, 12));
        return new String(cifrador.doFinal(todo, 12, todo.length - 12), StandardCharsets.UTF_8);
    }

    /** rw------- donde hay permisos POSIX; en Windows la carpeta %APPDATA% ya es privada del usuario */
    static void restringirPermisos(Path archivo) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        try {
            Set<PosixFilePermission> soloUsuario = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(archivo, soloUsuario);
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("No se pudieron restringir los permisos de " + archivo.getFileName() + ": " + e.getMessage());
        }
    }

    private static int entero(String texto, int porDefecto) {
        if (texto == null || texto.isBlank()) return porDefecto;
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConexionLocal c)) return false;
        return cajaNumero == c.cajaNumero && puerto == c.puerto && modo == c.modo
                && Objects.equals(cajaNombre, c.cajaNombre) && Objects.equals(host, c.host)
                && Objects.equals(baseDatos, c.baseDatos) && Objects.equals(usuario, c.usuario)
                && Objects.equals(contrasena, c.contrasena) && Objects.equals(adminHash, c.adminHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modo, cajaNumero, cajaNombre, host, puerto, baseDatos, usuario);
    }

    /** Sin contraseña, a propósito */
    @Override
    public String toString() {
        return "ConexionLocal{" + modo + ", caja " + cajaNumero + " \"" + cajaNombre + "\""
                + (esPostgresql() ? ", " + descripcionServidor() : "") + "}";
    }
}
