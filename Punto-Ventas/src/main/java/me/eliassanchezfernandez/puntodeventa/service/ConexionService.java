package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Configuración local de la conexión (Configuración → Base de datos / Cajas):
 * leer y guardar conexion.properties, probar una conexión a PostgreSQL y
 * recordar el hash del administrador para la pantalla sin conexión.
 *
 * Los cambios aplican al REINICIAR el programa (la base en uso no se puede
 * cambiar con Spring arrancado). Solo ADMIN; queda en la bitácora sin la
 * contraseña.
 */
@Service
public class ConexionService {

    @Autowired private SesionService    sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private RutasApp         rutas;
    @Autowired private IdentidadCaja    identidadCaja;

    /** Configuración local vigente (la del archivo; si no existe, la de una sola caja) */
    public ConexionLocal actual() {
        return ConexionLocal.cargar(carpeta());
    }

    public Path carpeta() {
        return RutasApp.carpetaAplicacion();
    }

    /** Motor contra el que está corriendo AHORA el programa (puede diferir del archivo hasta reiniciar) */
    public PerfilBaseDatos perfilEnUso() {
        return rutas.perfil();
    }

    public IdentidadCaja identidad() {
        return identidadCaja;
    }

    /**
     * Intenta conectarse al servidor con los datos dados (sin tocar la base en
     * uso). Devuelve la versión del servidor; lanza NegocioException con un
     * mensaje entendible si no se pudo.
     */
    public String probar(ConexionLocal c) {
        try {
            c.validarServidor();
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (!c.esPostgresql()) throw new NegocioException("Elige el modo \"Varias cajas\" para probar la conexión al servidor.");
        try (Connection con = abrir(c); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT version()")) {
            return rs.next() ? rs.getString(1) : "PostgreSQL";
        } catch (SQLException e) {
            throw new NegocioException(explicar(e, c));
        }
    }

    /** Conexión JDBC directa al servidor descrito (la usan la prueba y la migración) */
    public static Connection abrir(ConexionLocal c) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", c.getUsuario());
        props.setProperty("password", c.getContrasena());
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        props.setProperty("ApplicationName", "PuntoDeVenta prueba de conexión");
        return DriverManager.getConnection(c.urlPostgresql(), props);
    }

    /** Mensaje en español para los fallos más comunes de conexión */
    static String explicar(SQLException e, ConexionLocal c) {
        String estado = e.getSQLState() == null ? "" : e.getSQLState();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (estado.equals("28P01") || estado.equals("28000")) {
            return "El servidor rechazó el usuario o la contraseña (" + c.getUsuario() + ").";
        }
        if (estado.equals("3D000")) {
            return "La base de datos \"" + c.getBaseDatos() + "\" no existe en el servidor. Créala primero (ver instrucciones).";
        }
        if (estado.startsWith("08") || msg.contains("Connection refused") || msg.contains("timed out")
                || msg.contains("UnknownHost") || msg.contains("connect")) {
            return "No se pudo conectar con " + c.getHost() + ":" + c.getPuerto() + ". Revisa que el servidor esté encendido, "
                    + "que PostgreSQL acepte conexiones de la red (postgresql.conf y pg_hba.conf) y que el firewall permita el puerto.";
        }
        return "No se pudo conectar: " + msg;
    }

    /**
     * Guarda la configuración local (ADMIN). Si cambia el modo o el servidor,
     * el cambio aplica al reiniciar. Devuelve true si hace falta reiniciar.
     */
    public boolean guardar(ConexionLocal nueva) {
        sesionService.verificarAdmin();
        try {
            nueva.validarServidor();
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (nueva.getCajaNumero() < 1 || nueva.getCajaNumero() > 999) {
            throw new NegocioException("El número de caja debe estar entre 1 y 999.");
        }
        ConexionLocal anterior = actual();
        nueva.setAdminHash(anterior.getAdminHash());
        Cajero admin = sesionService.getCajeroActual();
        if (admin != null) nueva.setAdminHash(admin.getContrasenaHash());
        try {
            nueva.guardar(carpeta());
        } catch (IOException e) {
            throw new NegocioException("No se pudo guardar la configuración de la conexión: " + e.getMessage());
        }
        boolean reiniciar = anterior.getModo() != nueva.getModo()
                || (nueva.esPostgresql() && !nueva.urlPostgresql().equals(anterior.urlPostgresql()))
                || !nueva.getUsuario().equals(anterior.getUsuario())
                || !nueva.getContrasena().equals(anterior.getContrasena())
                || anterior.getCajaNumero() != nueva.getCajaNumero()
                || !anterior.getCajaNombre().equals(nueva.getCajaNombre());
        auditoriaService.registrar(admin, "CONEXION_MODIFICADA",
                describir(anterior) + " → " + describir(nueva), (Cajero) null);
        return reiniciar;
    }

    /** Sin contraseña */
    static String describir(ConexionLocal c) {
        return c.getModo().name() + (c.esPostgresql() ? " " + c.descripcionServidor() : "")
                + ", caja " + c.getCajaNumero() + " \"" + c.getCajaNombre() + "\"";
    }

    /**
     * Recuerda el hash del administrador que acaba de iniciar sesión, para que la
     * pantalla "sin conexión con el servidor" pueda pedir su contraseña aunque la
     * base no responda. Nunca lanza.
     */
    public void recordarAdministrador(Cajero cajero) {
        if (cajero == null || cajero.getRol() != Cajero.Rol.ADMIN || cajero.getContrasenaHash() == null) return;
        try {
            Path carpeta = carpeta();
            ConexionLocal c = ConexionLocal.cargar(carpeta);
            if (cajero.getContrasenaHash().equals(c.getAdminHash())) return;
            c.setAdminHash(cajero.getContrasenaHash());
            if (ConexionLocal.existe(carpeta) || c.esPostgresql()) c.guardar(carpeta);
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudo recordar al administrador para la pantalla sin conexión: " + e.getMessage());
        }
    }
}
