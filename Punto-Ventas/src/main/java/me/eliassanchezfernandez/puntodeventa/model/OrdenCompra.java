package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Orden de compra a un proveedor. BORRADOR → ENVIADA → RECIBIDA (al recibir se
 * suman TODAS las líneas al inventario en una sola transacción) o CANCELADA.
 */
@Entity
@Table(name = "ordenes_compra")
@Getter
@Setter
@NoArgsConstructor
public class OrdenCompra {

    public enum Estado {
        BORRADOR("Borrador"), ENVIADA("Enviada"), RECIBIDA("Recibida"), CANCELADA("Cancelada");

        private final String etiqueta;
        Estado(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        @Override public String toString() { return etiqueta; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "proveedor_id")
    private Proveedor proveedor;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(nullable = false)
    private Estado estado = Estado.BORRADOR;

    @Column(length = 300)
    private String notas;

    @Column(name = "creada_por", length = 100)
    private String creadaPor;

    @Column(name = "fecha_recepcion")
    private LocalDateTime fechaRecepcion;

    @Column(name = "recibida_por", length = 100)
    private String recibidaPor;

    @OneToMany(mappedBy = "orden", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<OrdenCompraLinea> lineas = new ArrayList<>();

    public double getTotal() {
        return Math.round(lineas.stream().mapToDouble(OrdenCompraLinea::getImporte).sum() * 100.0) / 100.0;
    }

    public boolean esEditable() {
        return estado == Estado.BORRADOR;
    }

    @Override
    public String toString() {
        return "OrdenCompra{id=" + id + ", " + estado + "}";
    }
}
