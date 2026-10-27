package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.util.RutasApp;

import java.nio.file.Path;

public class Launcher {

    public static void main(String[] args) {
        try {
            // Dónde vive la base de datos (carpeta del usuario, o ./datos.db si ya existe aquí)
            String url = RutasApp.prepararUrl(args);
            // Un respaldo restaurado desde Configuración se aplica aquí, antes de abrir la BD
            Path bd = RutasApp.rutaDeUrl(url);
            RutasApp.aplicarRestauracionPendiente(bd);
        } catch (RuntimeException e) {
            System.err.println("No se pudo preparar la base de datos: " + e.getMessage());
            e.printStackTrace();
        }
        PuntoDeVentaApp.main(args);
    }

}
