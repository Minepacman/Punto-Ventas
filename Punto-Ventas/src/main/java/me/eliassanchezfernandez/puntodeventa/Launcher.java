package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.config.VersionEsquema;
import me.eliassanchezfernandez.puntodeventa.util.CalentadorCds;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class Launcher {

    public static void main(String[] args) {
        // Proceso hijo que genera el archivo de arranque rápido (CDS): nunca abre la base real ni ventanas
        if (CalentadorCds.esProcesoCalentador()) {
            CalentadorArranque.calentarYSalir(args);
            return;
        }
        try {
            // Cómo se conecta esta caja (una sola caja = SQLite local; varias = servidor PostgreSQL)
            // y qué número de caja es. Se lee del archivo local ANTES de arrancar Spring.
            Path carpeta = RutasApp.carpetaAplicacion();
            ConexionLocal conexion = ConexionLocal.cargar(carpeta);
            if (!ConexionLocal.existe(carpeta) && !RutasApp.hayUrlExplicita(args, System.getenv())) {
                // Primera vez: el programa pregunta en pantalla cómo se usará esta caja
                PuntoDeVentaApp.marcarPrimeraVez();
            }
            String url = RutasApp.prepararUrl(args, conexion);
            if (PerfilBaseDatos.deUrl(url).esSqlite()) {
                // Un respaldo restaurado desde Configuración se aplica aquí, antes de abrir la BD (solo SQLite)
                RutasApp.aplicarRestauracionPendiente(RutasApp.rutaDeUrl(url));
                omitirActualizacionDeEsquemaSiAlDia(url);
            }
        } catch (RuntimeException e) {
            System.err.println("No se pudo preparar la base de datos: " + e.getMessage());
            e.printStackTrace();
        }
        PuntoDeVentaApp.main(args);
    }

    /**
     * Si la base ya quedó al día con esta misma versión (lo anota ArranqueDiferido al final de un
     * arranque completo), Hibernate arranca con ddl-auto=none: se ahorra leer los metadatos de las
     * 34 tablas y los ALTER TABLE que SQLite rechaza en cada inicio. Solo con el programa instalado
     * (ver {@link VersionEsquema#puedeOmitir()}); cualquier duda = actualizar como siempre.
     */
    static void omitirActualizacionDeEsquemaSiAlDia(String url) {
        if (!VersionEsquema.puedeOmitir()) return;
        if (System.getProperty("spring.jpa.hibernate.ddl-auto") != null) return;   // lo fijó alguien a mano
        try {
            Path bd = RutasApp.rutaDeUrl(url);
            if (!Files.isRegularFile(bd)) return;          // base nueva: hay que crearla
            try (Connection c = DriverManager.getConnection(url)) {
                if (VersionEsquema.alDia(c)) {
                    System.setProperty("spring.jpa.hibernate.ddl-auto", "none");
                    System.out.println("ℹ️  Esquema al día con la versión " + VersionApp.version() + ": arranque rápido.");
                }
            }
        } catch (SQLException | RuntimeException e) {
            // Se actualiza como siempre
        }
    }

}
