package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Qué columna del archivo alimenta cada campo. Es editable por el usuario en el
 * asistente; {@link #automatico} propone uno a partir de los encabezados.
 */
public final class Mapeo {

    private final Map<Campo, Integer> columnas = new LinkedHashMap<>();

    public Mapeo() { }

    public Mapeo(Map<? extends Campo, Integer> inicial) {
        inicial.forEach((campo, col) -> { if (col != null && col >= 0) columnas.put(campo, col); });
    }

    public void asignar(Campo campo, Integer columna) {
        if (columna == null || columna < 0) {
            columnas.remove(campo);
            return;
        }
        // Una columna solo puede alimentar un campo
        columnas.values().removeIf(c -> c.equals(columna));
        columnas.put(campo, columna);
    }

    public Integer columnaDe(Campo campo) {
        return columnas.get(campo);
    }

    public boolean tiene(Campo campo) {
        return columnas.containsKey(campo);
    }

    public Campo campoDeColumna(int columna) {
        for (Map.Entry<Campo, Integer> e : columnas.entrySet()) {
            if (e.getValue() == columna) return e.getKey();
        }
        return null;
    }

    public Map<Campo, Integer> comoMapa() {
        return Collections.unmodifiableMap(columnas);
    }

    /** Valor de un campo en una fila; "" si el campo no está mapeado o la celda no existe */
    public String valor(List<String> fila, Campo campo) {
        Integer c = columnas.get(campo);
        if (c == null || c >= fila.size()) return "";
        String v = fila.get(c);
        return v == null ? "" : Texto.limpiar(v);
    }

    /** Campos obligatorios que faltan por mapear */
    public List<Campo> obligatoriosSinMapear(Campo[] campos) {
        List<Campo> faltan = new ArrayList<>();
        for (Campo c : campos) if (c.obligatorio() && !columnas.containsKey(c)) faltan.add(c);
        return faltan;
    }

    /**
     * Propuesta automática: para cada campo, en el orden del enum, se recorren sus
     * alias por prioridad y se toma la primera columna libre cuyo encabezado
     * normalizado coincida exactamente. Los encabezados que no coinciden con nada
     * quedan sin mapear (el usuario los asigna o se ignoran).
     */
    public static Mapeo automatico(List<String> encabezados, Campo[] campos) {
        Mapeo m = new Mapeo();
        List<String> claves = new ArrayList<>(encabezados.size());
        for (String h : encabezados) claves.add(Texto.clave(h));
        Set<Integer> usadas = new HashSet<>();
        for (Campo campo : campos) {
            Integer elegida = null;
            for (String alias : campo.alias()) {
                for (int c = 0; c < claves.size() && elegida == null; c++) {
                    if (!usadas.contains(c) && claves.get(c).equals(alias)) elegida = c;
                }
                if (elegida != null) break;
            }
            if (elegida != null) {
                usadas.add(elegida);
                m.columnas.put(campo, elegida);
            }
        }
        return m;
    }
}
