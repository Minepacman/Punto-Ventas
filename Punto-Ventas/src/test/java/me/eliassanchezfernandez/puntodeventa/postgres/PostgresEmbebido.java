package me.eliassanchezfernandez.puntodeventa.postgres;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Un PostgreSQL embebido (zonky) para TODA la corrida de pruebas con etiqueta
 * "postgres": arranca en un puerto libre dentro de una carpeta temporal la
 * primera vez que alguien lo pide y se apaga al terminar la JVM. No instala
 * nada en el equipo. Si el binario no se puede arrancar, las pruebas fallan con
 * el motivo en el mensaje.
 */
public final class PostgresEmbebido {

    public static final String USUARIO = "postgres";
    public static final String CONTRASENA = "postgres";
    public static final String BASE_APP = "puntodeventa_test";

    private static EmbeddedPostgres servidor;

    private PostgresEmbebido() { }

    public static synchronized EmbeddedPostgres servidor() {
        if (servidor == null) {
            try {
                servidor = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("No se pudo arrancar el PostgreSQL embebido: " + e.getMessage(), e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    servidor.close();
                } catch (IOException ignorada) { /* al apagar la JVM */ }
            }, "apagar-postgres-embebido"));
            crearBase(BASE_APP);
        }
        return servidor;
    }

    public static int puerto() {
        return servidor().getPort();
    }

    /** jdbc:postgresql://localhost:puerto/base */
    public static String url(String base) {
        return "jdbc:postgresql://localhost:" + puerto() + "/" + base;
    }

    /** Crea una base vacía (si ya existía, la borra primero) */
    public static void crearBase(String nombre) {
        try (Connection c = servidor().getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + nombre);
            st.execute("CREATE DATABASE " + nombre);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo crear la base " + nombre + ": " + e.getMessage(), e);
        }
    }

    /** Configuración local apuntando a una base del servidor embebido */
    public static ConexionLocal conexion(String base) {
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        c.setHost("localhost");
        c.setPuerto(puerto());
        c.setBaseDatos(base);
        c.setUsuario(USUARIO);
        c.setContrasena(CONTRASENA);
        return c;
    }
}
