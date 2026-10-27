package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Franja;
import me.eliassanchezfernandez.puntodeventa.model.Producto;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reglas genéricas de una tienda de abarrotes para el arranque en frío
 * (semana 1): qué conviene ofrecer según la hora del día, el día de la semana
 * y el clima, SIN historial. Se aplican por palabras clave sobre la
 * descripción del producto o el nombre de su departamento (sin acentos ni
 * mayúsculas). Conforme hay historial, RecomendacionService les baja el peso.
 */
public final class ReglasGenericas {

    /** Una regla que aplicó a un producto, con el motivo para mostrar */
    public record Coincidencia(String motivo, double puntaje) { }

    /** Bebidas y comida caliente (con lluvia o frío); el pan y la leche van en las reglas de hora */
    private static final String[] CALIENTE = {
            "cafe", "chocolate", "te ", "te de", "sopa", "caldo", "atole", "maruchan", "avena",
            "canela", "consome", "tamal", "champurrado", "nescafe" };
    private static final String[] FRIO = {
            "agua", "refresco", "coca", "pepsi", "hielo", "helado", "paleta", "nieve", "cerveza",
            "jugo", "gatorade", "electrolit", "yogur", "boing", "jumex", "limon", "sabor", "frutsi" };
    private static final String[] MANANA = {
            "pan", "bolillo", "leche", "huevo", "jugo", "cafe", "tortilla", "yogur", "cereal",
            "jamon", "mantequilla", "mermelada", "avena", "concha" };
    private static final String[] MEDIODIA = {
            "refresco", "coca", "tortilla", "sopa", "arroz", "agua", "chile", "salsa", "pollo",
            "frijol", "aceite", "consome", "limon", "jitomate", "cebolla" };
    private static final String[] TARDE = {
            "galleta", "botana", "sabritas", "papas", "refresco", "dulce", "chocolate", "pan dulce",
            "cacahuate", "chicharron", "paleta", "gomita", "chamoy", "cafe" };
    private static final String[] NOCHE = {
            "cerveza", "pan", "leche", "botana", "cigarro", "huevo", "refresco", "hielo", "sabritas",
            "pañal", "panal", "galleta", "chocolate" };
    private static final String[] MADRUGADA = { "cafe", "pan", "cigarro", "agua", "leche" };
    private static final String[] FIN_DE_SEMANA = {
            "cerveza", "carbon", "hielo", "botana", "refresco", "limon", "sabritas", "cacahuate",
            "tortilla", "salsa", "desechable", "vaso", "plato" };

    private ReglasGenericas() { }

    /**
     * Reglas que aplican a un producto en este momento. clima puede ser null
     * (sin datos): entonces solo cuentan las reglas de hora y día.
     * @param categoriaClima Lluvia, Despejado… (ClimaService) o null
     * @param bandaTemperatura Frío, Templado, Calor o null
     */
    public static List<Coincidencia> evaluar(Producto producto, DayOfWeek dia, Franja franja,
                                             String categoriaClima, String bandaTemperatura) {
        String texto = normalizar(producto.getDescripcion()) + " "
                + (producto.getDepartamento() != null ? normalizar(producto.getDepartamento().getNombre()) : "");
        List<Coincidencia> r = new ArrayList<>();

        // Clima
        if (ClimaService.esLluvioso(categoriaClima) && contiene(texto, CALIENTE)) {
            r.add(new Coincidencia("Día lluvioso: se antoja algo caliente", 1.0));
        } else if (ClimaService.FRIO.equals(bandaTemperatura) && contiene(texto, CALIENTE)) {
            r.add(new Coincidencia("Hace frío: algo caliente", 0.9));
        }
        if (ClimaService.CALOR.equals(bandaTemperatura) && contiene(texto, FRIO)) {
            r.add(new Coincidencia("Hace calor: algo fresco", 1.0));
        }

        // Hora del día
        String[] palabrasFranja = switch (franja) {
            case MANANA    -> MANANA;
            case MEDIODIA  -> MEDIODIA;
            case TARDE     -> TARDE;
            case NOCHE     -> NOCHE;
            case MADRUGADA -> MADRUGADA;
        };
        if (contiene(texto, palabrasFranja)) {
            r.add(new Coincidencia("Se suele vender " + franja.getFrase(), 0.8));
        }

        // Fin de semana (viernes a domingo, de la tarde en adelante)
        boolean finDeSemana = dia == DayOfWeek.FRIDAY || dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY;
        boolean tardeONoche = franja == Franja.TARDE || franja == Franja.NOCHE;
        if (finDeSemana && tardeONoche && contiene(texto, FIN_DE_SEMANA)) {
            r.add(new Coincidencia("Fin de semana: reuniones y antojos", 0.8));
        }
        return r;
    }

    /** Minúsculas, sin acentos ni espacios dobles; con un espacio al final para "te " */
    static String normalizar(String s) {
        if (s == null) return "";
        String sinAcentos = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return " " + sinAcentos.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ") + " ";
    }

    private static boolean contiene(String texto, String[] palabras) {
        for (String p : palabras) {
            if (texto.contains(p)) return true;
        }
        return false;
    }
}
