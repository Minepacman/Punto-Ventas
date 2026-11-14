package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Proveedor de mercancía. No se borra: se desactiva (las entradas y órdenes de
 * compra anteriores lo siguen mostrando).
 *
 * Tiene dos calendarios semanales, guardados como números ISO separados por coma
 * (1 = lunes ... 7 = domingo):
 *  - {@code diasVisita}: días en que ENTREGA la mercancía (llega el camión);
 *  - {@code diasPreventa}: días en que pasa el PREVENTA (el vendedor que levanta
 *    el pedido antes de la entrega). Muchos proveedores no lo tienen: queda vacío.
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

    /** Días de ENTREGA (texto "1,4"); ver {@link #getDias()} */
    @Column(name = "dias_visita", length = 20)
    private String diasVisita;

    /** Días de PREVENTA (texto "2"); ver {@link #getPreventa()}. Nullable: filas anteriores no lo tienen. */
    @Column(name = "dias_preventa", length = 20)
    private String diasPreventa;

    @Column(length = 500)
    private String notas;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "creado_en")
    private LocalDateTime creadoEn = LocalDateTime.now();

    // ── Días de entrega ──────────────────────────────────────────────────

    public Set<DayOfWeek> getDias() {
        return parsearDias(diasVisita);
    }

    public void setDias(Collection<DayOfWeek> dias) {
        diasVisita = formatearDias(dias);
    }

    public boolean visitaEn(DayOfWeek dia) {
        return getDias().contains(dia);
    }

    /** "Lun, Mié, Vie" (o "—" si no tiene días de entrega) */
    public String etiquetaDias() {
        return etiquetaDias(getDias());
    }

    // ── Días de preventa ─────────────────────────────────────────────────

    public Set<DayOfWeek> getPreventa() {
        return parsearDias(diasPreventa);
    }

    public void setPreventa(Collection<DayOfWeek> dias) {
        diasPreventa = formatearDias(dias);
    }

    public boolean preventaEn(DayOfWeek dia) {
        return getPreventa().contains(dia);
    }

    /** "Mar" (o "—" si no tiene preventa) */
    public String etiquetaPreventa() {
        return etiquetaDias(getPreventa());
    }

    // ── Utilidades de formato ────────────────────────────────────────────

    /** "1,4" → {LUNES, JUEVES}; los valores basura se ignoran */
    public static Set<DayOfWeek> parsearDias(String texto) {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        if (texto == null) return dias;
        for (String t : texto.split(",")) {
            try {
                dias.add(DayOfWeek.of(Integer.parseInt(t.trim())));
            } catch (RuntimeException ignorado) { /* valor basura: se ignora */ }
        }
        return dias;
    }

    /** {LUNES, JUEVES} → "1,4"; vacío o null → null */
    public static String formatearDias(Collection<DayOfWeek> dias) {
        if (dias == null || dias.isEmpty()) return null;
        List<String> numeros = new ArrayList<>();
        for (DayOfWeek d : EnumSet.copyOf(dias)) numeros.add(String.valueOf(d.getValue()));
        return String.join(",", numeros);
    }

    /** "Lun, Mié, Vie" en español, "—" si no hay días */
    public static String etiquetaDias(Collection<DayOfWeek> dias) {
        if (dias == null || dias.isEmpty()) return "—";
        List<String> nombres = new ArrayList<>();
        for (DayOfWeek d : EnumSet.copyOf(dias)) {
            String corto = d.getDisplayName(TextStyle.SHORT, new Locale("es", "MX")).replace(".", "");
            nombres.add(Character.toUpperCase(corto.charAt(0)) + corto.substring(1));
        }
        return String.join(", ", nombres);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
