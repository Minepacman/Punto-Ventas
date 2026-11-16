package me.eliassanchezfernandez.puntodeventa.util.apariencia;

import me.eliassanchezfernandez.puntodeventa.util.RutasApp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copia local de la apariencia de la tienda ({@code apariencia.json} en la carpeta de datos): la
 * ventana "Abriendo la tienda…" sale antes de que arranque la base de datos y así ya tiene el tema
 * elegido (sin un destello claro antes del oscuro). Si falta o está dañada: la de fábrica.
 */
public final class RecuerdoApariencia {

    private RecuerdoApariencia() { }

    /** Desactivable en pruebas: -Dapp.apariencia.recordar=false */
    private static boolean activo() {
        return !"false".equals(System.getProperty("app.apariencia.recordar"));
    }

    private static Path archivo() {
        return RutasApp.carpetaAplicacion().resolve("apariencia.json");
    }

    public static Apariencia leer() {
        if (!activo()) return Apariencia.porDefecto();
        try {
            Path f = archivo();
            if (!Files.isRegularFile(f) || Files.size(f) > 4096) return Apariencia.porDefecto();
            return Apariencia.desdeJson(Files.readString(f, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Apariencia.porDefecto();
        }
    }

    public static void guardar(Apariencia a) {
        if (!activo() || a == null) return;
        try {
            Path f = archivo();
            String json = a.aJson();
            if (Files.isRegularFile(f) && json.equals(Files.readString(f, StandardCharsets.UTF_8))) return;
            if (a.esPorDefecto() && !Files.exists(f)) return;
            Files.createDirectories(f.getParent());
            Files.writeString(f, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // solo es una comodidad para la ventana de arranque
        }
    }
}
