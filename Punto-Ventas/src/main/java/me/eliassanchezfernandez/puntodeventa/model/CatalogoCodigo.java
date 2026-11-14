package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Una fila del catálogo público de códigos de barras de productos que se
 * venden en México (repositorio {@code Minepacman/codigos-barras-mx}, datos de
 * Open Food Facts y proyectos hermanos bajo ODbL). Copia local, de solo
 * consulta, que {@code CatalogoCodigosService} descarga y reemplaza completa
 * cuando hay versión nueva; los productos que se encuentran en línea también
 * se guardan aquí para la siguiente vez.
 *
 * No tiene relación con {@link Producto}: el POS la usa solo para proponer
 * descripción, departamento e imagen al dar de alta un producto. Los textos
 * vienen recortados a las longitudes de las columnas (ver util/CatalogoCsv).
 */
@Entity
@Table(name = "catalogo_codigos")
@Data
@NoArgsConstructor
public class CatalogoCodigo {

    public static final int NOMBRE_MAX = 200;
    public static final int MARCA_MAX = 100;
    public static final int UNIDAD_MAX = 10;
    public static final int CONTENIDO_MAX = 60;
    public static final int CATEGORIA_MAX = 40;
    public static final int IMAGEN_MAX = 300;
    public static final int FUENTE_MAX = 10;
    public static final int ACTUALIZADO_MAX = 10;

    /** Código normalizado: 13 dígitos (EAN-13; UPC-A con 0 al frente) u 8 (EAN-8) */
    @Id
    @Column(name = "codigo", length = 14)
    private String codigo;

    @Column(name = "nombre", nullable = false, length = NOMBRE_MAX)
    private String nombre;

    @Column(name = "marca", length = MARCA_MAX)
    private String marca;

    /** Cantidad numérica del contenido (600 para "600 ml"); null si la fuente no la trae */
    @Column(name = "cantidad")
    private Double cantidad;

    /** g, kg, mg, ml, cl, l, oz, fl oz, lb, pz */
    @Column(name = "unidad", length = UNIDAD_MAX)
    private String unidad;

    /** Texto original de la cantidad ("6 x 355 ml") */
    @Column(name = "contenido", length = CONTENIDO_MAX)
    private String contenido;

    /** Categoría del catálogo (Bebidas, Botanas, Lácteos...); se traduce a un departamento de la tienda */
    @Column(name = "categoria", length = CATEGORIA_MAX)
    private String categoria;

    /** URL https de la foto frontal (200 px) en los servidores de Open Food Facts; solo la dirección */
    @Column(name = "imagen", length = IMAGEN_MAX)
    private String imagen;

    /** off, obf, opff, opf o manual */
    @Column(name = "fuente", length = FUENTE_MAX)
    private String fuente;

    /** Fecha de la última modificación en la fuente (AAAA-MM-DD, texto) */
    @Column(name = "actualizado", length = ACTUALIZADO_MAX)
    private String actualizado;

    public boolean tieneImagen() {
        return imagen != null && imagen.startsWith("https://");
    }
}
