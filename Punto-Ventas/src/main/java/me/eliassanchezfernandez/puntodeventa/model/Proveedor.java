package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Proveedor de mercancía. No se borra: se desactiva (las entradas y órdenes de
 * compra anteriores lo siguen mostrando). Los días de visita se guardan como
 * números ISO separados por coma (1 = lunes ... 7 = domingo).
 */
@Entity
@Table(name = "proveedores")
@Getter
@Setter
@NoArgsConstructor
public class Proveedor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String nombre;

    /** Opcional; 12 o 13 caracteres */
    @Column(length = 13)
    private String rfc;

    @Column(length = 30)
    private String telefono;

    /** Persona de contacto / repartidor */
    @Column(length = 100)
    private String contacto;

    @Column(name = "dias_visita", length = 20)
    private String diasVisita;

    @Column(length = 500)
    private String notas;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en")
    private LocalDateTime creadoEn = LocalDateTime.now();

    // ── Días de visita ───────────────────────────────────────────────────

    public Set<DayOfWeek> getDias() {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        if (diasVisita == null) return dias;
        for (String t : diasVisita.split(",")) {
            try {
                dias.add(DayOfWeek.of(Integer.parseInt(t.trim())));
            } catch (RuntimeException ignorado) { /* valor basura: se ignora */ }
        }
        return dias;
    }

    public void setDias(Set<DayOfWeek> dias) {
        if (dias == null || dias.isEmpty()) {
            diasVisita = null;
            return;
        }
        List<String> numeros = new ArrayList<>();
        for (DayOfWeek d : EnumSet.copyOf(dias)) numeros.add(String.valueOf(d.getValue()));
        diasVisita = String.join(",", numeros);
    }

    public boolean visitaEn(DayOfWeek dia) {
        return getDias().contains(dia);
    }

    /** "Lun, Mié, Vie" */
    public String etiquetaDias() {
        List<String> nombres = new ArrayList<>();
        for (DayOfWeek d : getDias()) {
            String corto = d.getDisplayName(TextStyle.SHORT, new Locale("es", "MX")).replace(".", "");
            nombres.add(Character.toUpperCase(corto.charAt(0)) + corto.substring(1));
        }
        return nombres.isEmpty() ? "—" : String.join(", ", nombres);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
