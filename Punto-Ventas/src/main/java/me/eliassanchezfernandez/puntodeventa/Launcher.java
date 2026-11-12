package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;

import java.nio.file.Path;

public class Launcher {

    public static void main(String[] args) {
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
            // Un respaldo restaurado desde Configuración se aplica aquí, antes de abrir la BD (solo SQLite)
            if (PerfilBaseDatos.deUrl(url).esSqlite()) {
                RutasApp.aplicarRestauracionPendiente(RutasApp.rutaDeUrl(url));
            }
        } catch (RuntimeException e) {
            System.err.println("No se pudo preparar la base de datos: " + e.getMessage());
            e.printStackTrace();
        }
        PuntoDeVentaApp.main(args);
    }

}
