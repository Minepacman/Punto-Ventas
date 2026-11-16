package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Una entrada de mercancía con fecha de caducidad ("lote" en versión sencilla) de un producto
 * que tiene encendido "Controlar caducidad".
 *
 * NO es inventario por lote: las ventas no saben de lotes ni los tocan. Cuánto queda de cada
 * entrada se ESTIMA al leer (ver {@code CaducidadService.estimar}): la existencia actual del
 * producto se reparte entre sus entradas vigentes empezando por la que caduca más tarde, porque
 * se supone que lo que se vende primero es lo que caduca primero (PEPS por caducidad). Por eso
 * aquí no hay "cantidad restante" guardada: se calcula siempre con la existencia del momento.
 *
 * Una entrada deja de contar cuando se retira ({@link #retiradaEn} no nulo): con merma
 * ("Retirar": se dio de baja por caducada) o sin movimiento ("Ya no queda").
 *
 * Sin relación JPA con {@link Producto} (solo el id): las listas de productos nunca cargan esto.
 */
@Entity
@Table(name = "caducidades", indexes = {
        @Index(name = "idx_caducidades_producto", columnList = "producto_id"),
        @Index(name = "idx_caducidades_fecha", columnList = "fecha_caducidad")
})
@Getter
@Setter
@NoArgsConstructor
public class Caducidad {

    /** De dónde vino la entrada */
    public enum Origen {
        MANUAL("Entrada en Inventario"), ORDEN("Orden de compra"), MOVIL("Entrada desde el teléfono");

        private final String etiqueta;
        Origen(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "producto_id", nullable = false)
    private Long productoId;

    @Column(name = "fecha_caducidad", nullable = false)
    private LocalDate fechaCaducidad;

    /** Lo que entró con esta fecha (tope de lo que se le puede asignar al estimar) */
    @Column(name = "cantidad_entrada", nullable = false)
    private double cantidadEntrada;

    @Column(name = "fecha_entrada", nullable = false)
    private LocalDateTime fechaEntrada;

    @Column(name = "origen", length = 20, nullable = false)
    private String origen;

    /** Movimiento de inventario (ENTRADA) que la creó */
    @Column(name = "movimiento_id")
    private Long movimientoId;

    /** Cuándo dejó de contar (retirada por caducada o "ya no queda"); null = vigente */
    @Column(name = "retirada_en")
    private LocalDateTime retiradaEn;

    @Column(name = "retirada_por", length = 100)
    private String retiradaPor;

    /** Lo que se dio de baja como merma al retirarla; null = "ya no queda" (sin movimiento) */
    @Column(name = "retirada_cantidad")
    private Double retiradaCantidad;

    public boolean isVigente() {
        return retiradaEn == null;
    }
}
