package me.eliassanchezfernandez.puntodeventa.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo de iconos de la interfaz de escritorio (los mismos de la página móvil, movil/index.html).
 *
 * <p>Trazos de Lucide (https://lucide.dev), licencia ISC:
 * Copyright (c) 2022 Lucide Contributors. Se permite usar, copiar, modificar y distribuir con o sin
 * cambios, con este aviso. Solo se incluyen los iconos que usa el programa. Cada icono es una lista de
 * trazos SVG en un viewBox de 24x24 (trazo 2, extremos y uniones redondeados); círculos, rectángulos y
 * líneas de Lucide están convertidos a trazos de ruta.</p>
 *
 * <p>Para usarlos: {@code Iconos.de("buscar")} (16 px) o {@link Icono} en FXML:
 * {@code <graphic><Icono nombre="buscar"/></graphic>}.</p>
 */
public final class Iconos {

    private static final Map<String, List<String>> TRAZOS = new LinkedHashMap<>();

    private Iconos() { }

    private static void icono(String nombre, String... trazos) {
        TRAZOS.put(nombre, List.of(trazos));
    }

    /** ¿Existe el icono? (lo usa la prueba que revisa los FXML). */
    public static boolean existe(String nombre) {
        return nombre != null && TRAZOS.containsKey(nombre);
    }

    public static List<String> trazos(String nombre) {
        return TRAZOS.getOrDefault(nombre, List.of());
    }

    public static java.util.Set<String> nombres() {
        return java.util.Collections.unmodifiableSet(TRAZOS.keySet());
    }

    /** Icono de 16 px que toma el color del texto del botón/etiqueta que lo contiene. */
    public static Icono de(String nombre) {
        return new Icono(nombre);
    }

    public static Icono de(String nombre, double tamano) {
        return new Icono(nombre, tamano);
    }

    static {
        icono("grafica",
                "M3 3v16a2 2 0 0 0 2 2h16",
                "M18 17V9",
                "M13 17V5",
                "M8 17v-3");
        icono("recibo",
                "M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1Z",
                "M16 8h-6a2 2 0 1 0 0 4h4a2 2 0 1 1 0 4H8",
                "M12 17.5v-11");
        icono("paquete",
                "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
                "M12 22V12",
                "m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7",
                "m7.5 4.27 9 5.15");
        icono("camara",
                "M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3z",
                "M9 13a3 3 0 1 0 6 0a3 3 0 1 0-6 0");
        icono("ajustes",
                "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
                "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0");
        icono("ayuda",
                "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0",
                "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3",
                "M12 17h.01");
        icono("izquierda",
                "m15 18-6-6 6-6");
        icono("derecha",
                "m9 18 6-6-6-6");
        icono("cerrar",
                "M18 6 6 18",
                "m6 6 12 12");
        icono("listo",
                "M20 6 9 17l-5-5");
        icono("sube",
                "M22 7 13.5 15.5 8.5 10.5 2 17",
                "M16 7h6v6");
        icono("baja",
                "M22 17 13.5 8.5 8.5 13.5 2 7",
                "M16 17h6v-6");
        icono("salir",
                "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",
                "m16 17 5-5-5-5",
                "M21 12H9");
        icono("tienda",
                "m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7",
                "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",
                "M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4",
                "M2 7h20");
        icono("buscar",
                "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
                "m21 21-4.3-4.3");
        icono("eliminar",
                "M3 6h18",
                "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6",
                "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2",
                "M10 11L10 17",
                "M14 11L14 17");
        icono("pausa",
                "M15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-14a1 1 0 0 1 1 -1z",
                "M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-14a1 1 0 0 1 1 -1z");
        icono("reanudar",
                "M6 3 20 12 6 21 6 3Z");
        icono("impresora",
                "M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2",
                "M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6",
                "M7 14h10a1 1 0 0 1 1 1v6a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1v-6a1 1 0 0 1 1 -1z");
        icono("efectivo",
                "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z",
                "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
                "M6 12h.01M18 12h.01");
        icono("bascula",
                "m16 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z",
                "m2 16 3-8 3 8c-.87.65-1.92 1-3 1s-2.13-.35-3-1Z",
                "M7 21h10",
                "M12 3v18",
                "M3 7h2c2 0 5-1 7-2 2 1 5 2 7 2h2");
        icono("tijeras",
                "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
                "M8.12 8.12 12 12",
                "M20 4 8.12 15.88",
                "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
                "M14.8 14.8 20 20");
        icono("nuevaVersion",
                "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
                "m16 12-4-4-4 4",
                "M12 16V8");
        icono("corazon",
                "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z");
        icono("editar",
                "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
                "m15 5 4 4");
        icono("intro",
                "M9 10 4 15 9 20",
                "M20 4v7a4 4 0 0 1-4 4H4");
        icono("mas",
                "M5 12h14",
                "M12 5v14");
        icono("prohibido",
                "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
                "m4.9 4.9 14.2 14.2");
        icono("desbloquear",
                "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-7a2 2 0 0 1 2 -2z",
                "M7 11V7a5 5 0 0 1 9.9-1");
        icono("llave",
                "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z",
                "M16 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0");
        icono("guardar",
                "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
                "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7",
                "M7 3v4a1 1 0 0 0 1 1h7");
        icono("subir",
                "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
                "M17 8 12 3 7 8",
                "M12 3L12 15");
        icono("bajar",
                "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
                "M7 10 12 15 17 10",
                "M12 15L12 3");
        icono("lista",
                "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z",
                "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
                "M12 11h4",
                "M12 16h4",
                "M8 11h.01",
                "M8 16h.01");
        icono("fabrica",
                "M2 20a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8l-7 5V8l-7 5V4a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z",
                "M17 18h1",
                "M12 18h1",
                "M7 18h1");
        icono("estrella",
                "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z");
        icono("idea",
                "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5",
                "M9 18h6",
                "M10 22h4");
        icono("carpeta",
                "m6 14 1.5-2.9A2 2 0 0 1 9.24 10H20a2 2 0 0 1 1.94 2.5l-1.54 6a2 2 0 0 1-1.95 1.5H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.9a2 2 0 0 1 1.69.9l.81 1.2a2 2 0 0 0 1.67.9H18a2 2 0 0 1 2 2v2");
        icono("mundo",
                "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
                "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
                "M2 12h20");
        icono("documento",
                "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
                "M14 2v4a2 2 0 0 0 2 2h4",
                "M10 9H8",
                "M16 13H8",
                "M16 17H8");
        icono("usuarios",
                "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",
                "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
                "M22 21v-2a4 4 0 0 0-3-3.87",
                "M16 3.13a4 4 0 0 1 0 7.75");
        icono("usuario",
                "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2",
                "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0");
        icono("baseDatos",
                "M3 5a9 3 0 1 0 18 0a9 3 0 1 0 -18 0",
                "M3 5V19A9 3 0 0 0 21 19V5",
                "M3 12A9 3 0 0 0 21 12");
        icono("numeros",
                "M4 9L20 9",
                "M4 15L20 15",
                "M10 3L8 21",
                "M16 3L14 21");
        icono("climaSol",
                "M12 2v2",
                "m4.93 4.93 1.41 1.41",
                "M20 12h2",
                "m19.07 4.93-1.41 1.41",
                "M15.947 12.65a4 4 0 0 0-5.925-4.128",
                "M13 22H7a5 5 0 1 1 4.9-6H13a3 3 0 0 1 0 6Z");
        icono("celular",
                "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2z",
                "M12 18h.01");
        icono("imagen",
                "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
                "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
                "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21");
        icono("boleto",
                "M2 9a3 3 0 0 1 0 6v2a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-2a3 3 0 0 1 0-6V7a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2Z",
                "M13 5v2",
                "M13 17v2",
                "M13 11v2");
        icono("tarjeta",
                "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z",
                "M2 10L22 10");
        icono("regla",
                "M21.3 15.3a2.4 2.4 0 0 1 0 3.4l-2.6 2.6a2.4 2.4 0 0 1-3.4 0L2.7 8.7a2.41 2.41 0 0 1 0-3.4l2.6-2.6a2.41 2.41 0 0 1 3.4 0Z",
                "m14.5 12.5 2-2",
                "m11.5 9.5 2-2",
                "m8.5 6.5 2-2",
                "m17.5 15.5 2-2");
        icono("actualizar",
                "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
                "M21 3v5h-5",
                "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
                "M8 16H3v5");
        icono("alerta",
                "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
                "M12 9v4",
                "M12 17h.01");
        icono("energia",
                "M12 2v10",
                "M18.4 6.6a9 9 0 1 1-12.77.04");
        icono("sol",
                "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
                "M12 2v2",
                "M12 20v2",
                "m4.93 4.93 1.41 1.41",
                "m17.66 17.66 1.41 1.41",
                "M2 12h2",
                "M20 12h2",
                "m6.34 17.66-1.41 1.41",
                "m19.07 4.93-1.41 1.41");
        icono("nube",
                "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z");
        icono("lluvia",
                "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
                "M16 14v6",
                "M8 14v6",
                "M12 16v6");
        icono("tormenta",
                "M6 16.326A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 .5 8.973",
                "m13 12-3 5h4l-3 5");
        icono("niebla",
                "M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242",
                "M16 17H7",
                "M17 21H9");
        icono("nieve",
                "m10 20-1.25-2.5L6 18",
                "M10 4 8.75 6.5 6 6",
                "m14 20 1.25-2.5L18 18",
                "m14 4 1.25 2.5L18 6",
                "m17 21-3-6h-4",
                "m17 3-3 6 1.5 3",
                "M2 12h6.5L10 9",
                "m20 10-1.5 2 1.5 2",
                "M22 12h-6.5L14 15",
                "m4 10 1.5 2L4 14",
                "m7 21 3-6-1.5-3",
                "m7 3 3 6h4");
        icono("etiqueta",
                "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z",
                "M7 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0");
        icono("portapapeles",
                "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1z",
                "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2");
    }
}
