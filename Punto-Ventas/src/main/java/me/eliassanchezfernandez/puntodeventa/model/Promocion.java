package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Promoción aplicable a un producto O a todo un departamento (PRO-01).
 *
 * Tipos:
 *  - PORCENTAJE  → valor = % de descuento sobre el precio de venta (ej. 10 = 10 %)
 *  - PRECIO_FIJO → valor = precio unitario promocional (ej. 25.00)
 *  - NXM         → "lleva N y paga M" (2x1 = N 2, M 1; 3x2 = N 3, M 2)
 *
 * Vigencia (PRO-04): rango de fechas (fechaFin null = sin vencimiento),
 * días de la semana opcionales, horario opcional y límite de piezas
 * "hasta agotar" opcional. También puede limitarse por ticket (PRO-07).
 *
 * Si varias promociones aplican al mismo producto gana la creada más
 * recientemente (PRO-05): la de mayor id.
 */
@Entity
@Table(name = "promociones")
@Data
@NoArgsConstructor
public class Promocion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Producto al que aplica (null si la promoción es por departamento) */
    @ManyToOne
    @JoinColumn(name = "producto_id")
    private Producto producto;

    /** Departamento al que aplica (null si la promoción es por producto) */
    @ManyToOne
    @org.hibernate.annotations.NotFound(action = org.hibernate.annotations.NotFoundAction.IGNORE)
    @JoinColumn(name = "departamento_id",
                foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Departamento departamento;

    /** Texto corto para identificarla en la UI y en el ticket (ej. "2x1 fin de semana") */
    @Column(length = 150)
    private String descripcion;

    @Column(nullable = false)
    private Tipo tipo = Tipo.PORCENTAJE;

    /** Porcentaje (0-100) o precio fijo unitario, según el tipo; 0 para NXM */
    @Column(nullable = false)
    private double valor;

    /** NXM: lleva N ... */
    @Column(name = "cantidad_n")
    private Integer cantidadN;

    /** NXM: ... paga M */
    @Column(name = "cantidad_m")
    private Integer cantidadM;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDate fechaInicio = LocalDate.now();

    /** null = la promoción no vence */
    @Column(name = "fecha_fin")
    private LocalDate fechaFin;

    /** Días de la semana en que aplica, como "MONDAY,SATURDAY"; null/vacío = todos */
    @Column(name = "dias_semana", length = 80)
    private String diasSemana;

    /** Horario "HH:mm" en que aplica; null = todo el día */
    @Column(name = "hora_inicio", length = 5)
    private String horaInicio;

    @Column(name = "hora_fin", length = 5)
    private String horaFin;

    /** "Hasta agotar": piezas totales que se pueden vender con la promoción; null = sin límite */
    @Column(name = "limite_piezas")
    private Integer limitePiezas;

    /** Piezas ya vendidas con esta promoción (se suma al cobrar) */
    @Column(name = "piezas_usadas")
    private Double piezasUsadas;

    /** Máximo de piezas con promoción por ticket; null = sin límite (PRO-07) */
    @Column(name = "limite_por_ticket")
    private Integer limitePorTicket;

    @Column(nullable = false)
    private boolean activa = true;

    // ── Lógica de dominio ────────────────────────────────────────────────

    public boolean esPorDepartamento() {
        return departamento != null;
    }

    /** ¿Esta promoción cubre al producto dado? */
    public boolean aplicaA(Producto p) {
        if (p == null) return false;
        if (producto != null) return producto.getId() != null && producto.getId().equals(p.getId());
        return departamento != null && p.getDepartamento() != null
                && departamento.getId() != null && departamento.getId().equals(p.getDepartamento().getId());
    }

    /** Nombre para el ticket: la descripción o un texto generado por tipo */
    public String getNombre() {
        if (descripcion != null && !descripcion.isBlank()) return descripcion;
        return switch (tipo) {
            case PORCENTAJE  -> formatear(valor) + " % de descuento";
            case PRECIO_FIJO -> "Precio especial $" + String.format("%.2f", valor);
            case NXM         -> (cantidadN != null ? cantidadN : 0) + "x" + (cantidadM != null ? cantidadM : 0);
        };
    }

    /**
     * Días en que aplica: null = todos; cadena vacía = ninguno (inválido, lo
     * rechaza el servicio); un valor no reconocido se ignora (nunca rompe una venta).
     */
    public Set<DayOfWeek> getDias() {
        if (diasSemana == null) return EnumSet.allOf(DayOfWeek.class);
        if (diasSemana.isBlank()) return EnumSet.noneOf(DayOfWeek.class);
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        for (String s : diasSemana.split(",")) {
            try {
                if (!s.isBlank()) dias.add(DayOfWeek.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignorada) { /* día mal escrito: se omite */ }
        }
        return dias;
    }

    public void setDias(Set<DayOfWeek> dias) {
        if (dias == null || dias.size() == 7) {
            diasSemana = null;          // todos los días
        } else if (dias.isEmpty()) {
            diasSemana = "";            // ninguno: el servicio lo rechaza al guardar
        } else {
            diasSemana = dias.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
        }
    }

    public double getPiezasUsadasOCero() {
        return piezasUsadas == null ? 0 : piezasUsadas;
    }

    /** ¿Quedan piezas disponibles en la promoción ("hasta agotar")? */
    public boolean tieneCupo() {
        return limitePiezas == null || getPiezasUsadasOCero() < limitePiezas;
    }

    public double piezasDisponibles() {
        return limitePiezas == null ? Double.MAX_VALUE : Math.max(0, limitePiezas - getPiezasUsadasOCero());
    }

    /** true si la promoción está activa y el momento cae dentro de fechas, días y horario */
    public boolean estaVigente(LocalDateTime momento) {
        if (!activa || momento == null) return false;
        LocalDate fecha = momento.toLocalDate();
        if (fechaInicio != null && fecha.isBefore(fechaInicio)) return false;
        if (fechaFin != null && fecha.isAfter(fechaFin)) return false;
        if (!getDias().contains(fecha.getDayOfWeek())) return false;
        LocalTime ini, fin;
        try {
            ini = parsearHora(horaInicio);
            fin = parsearHora(horaFin);
        } catch (RuntimeException horaInvalida) {
            return false;   // horario mal guardado = la promoción no aplica, pero la venta sigue
        }
        LocalTime hora = momento.toLocalTime();
        if (ini != null && hora.isBefore(ini)) return false;
        if (fin != null && hora.isAfter(fin)) return false;
        return tieneCupo();
    }

    /** Compatibilidad: vigencia por fecha a las 12:00 (sin horario) */
    public boolean estaVigente(LocalDate fecha) {
        return fecha != null && estaVigente(fecha.atTime(12, 0));
    }

    /**
     * Piezas del renglón que realmente entran a la promoción, respetando el
     * límite por ticket y las piezas que quedan "hasta agotar" (un solo renglón).
     */
    public double piezasConPromocion(double cantidad) {
        return piezasConPromocion(cantidad, 0);
    }

    /**
     * Igual, descontando las piezas que OTROS renglones del mismo ticket ya
     * tomaron de esta promoción: los límites se comparten en todo el ticket.
     */
    public double piezasConPromocion(double cantidad, double yaUsadasEnTicket) {
        double piezas = Math.max(0, cantidad);
        if (limitePorTicket != null) piezas = Math.min(piezas, Math.max(0, limitePorTicket - yaUsadasEnTicket));
        piezas = Math.min(piezas, Math.max(0, piezasDisponibles() - yaUsadasEnTicket));
        return piezas;
    }

    /**
     * Descuento TOTAL del renglón para un precio unitario y una cantidad.
     * Nunca negativo ni mayor que precio × cantidad.
     */
    public double descuentoPara(double precioUnitario, double cantidad) {
        return descuentoPara(precioUnitario, cantidad, piezasConPromocion(cantidad));
    }

    /** Descuento del renglón para un número de piezas en promoción ya calculado */
    public double descuentoPara(double precioUnitario, double cantidad, double piezas) {
        if (piezas <= 0 || precioUnitario <= 0) return 0;
        double descuento = switch (tipo) {
            case PORCENTAJE  -> precioUnitario * piezas * Math.min(100, Math.max(0, valor)) / 100.0;
            case PRECIO_FIJO -> Math.max(0, precioUnitario - valor) * piezas;
            case NXM         -> {
                int n = cantidadN != null ? cantidadN : 0;
                int m = cantidadM != null ? cantidadM : 0;
                if (n <= 0 || m <= 0 || m >= n) yield 0;
                long grupos = (long) Math.floor(piezas / n);
                yield grupos * (n - m) * precioUnitario;
            }
        };
        return Math.max(0, Math.min(precioUnitario * cantidad, descuento));
    }

    /**
     * Precio unitario resultante de aplicar la promoción a un precio base
     * (una sola pieza, sin límites). Nunca mayor al base ni menor a cero.
     */
    public double precioConPromocion(double precioBase) {
        double precio = switch (tipo) {
            case PORCENTAJE  -> precioBase * (1 - valor / 100.0);
            case PRECIO_FIJO -> valor;
            case NXM         -> precioBase; // el ahorro de NxM depende de la cantidad
        };
        return Math.max(0, Math.min(precioBase, precio));
    }

    public static LocalTime parsearHora(String texto) {
        if (texto == null || texto.isBlank()) return null;
        return LocalTime.parse(texto.trim().length() == 4 ? "0" + texto.trim() : texto.trim());
    }

    private static String formatear(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    // ── Enum interno ─────────────────────────────────────────────────────

    public enum Tipo {
        PORCENTAJE("% de descuento"), PRECIO_FIJO("Precio fijo"), NXM("N x M (ej. 2x1)");

        private final String etiqueta;

        Tipo(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }
    }
}
