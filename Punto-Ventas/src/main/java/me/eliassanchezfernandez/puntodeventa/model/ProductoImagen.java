package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Foto de un producto, guardada EN la base de datos (tabla "producto_imagen")
 * para que con varias cajas contra PostgreSQL todas vean la misma imagen y
 * para que viaje con los respaldos y la migración SQLite → servidor.
 *
 * La llave es el id del producto (una imagen por producto). A propósito NO
 * hay relación desde {@link Producto}: las listas de productos y las ventas
 * nunca cargan estos bytes; se piden aparte y bajo demanda
 * ({@code ProductoImagenService}). Los bytes siempre están normalizados por
 * el servicio: JPEG de hasta 512 px por lado y una miniatura de hasta 96 px.
 *
 * byte[] sin @Lob: Hibernate lo mapea a "blob" en SQLite y a "bytea" en
 * PostgreSQL (con @Lob sería un "large object" con oid, incómodo de respaldar).
 */
@Entity
@Table(name = "producto_imagen")
@Data
@NoArgsConstructor
public class ProductoImagen {

    @Id
    @Column(name = "producto_id")
    private Long productoId;

    /** JPEG normalizado (lado mayor ≤ 512 px) */
    @Column(name = "imagen", nullable = false)
    private byte[] imagen;

    /** JPEG pequeño (lado mayor ≤ 96 px) para listas y resultados de búsqueda */
    @Column(name = "miniatura", nullable = false)
    private byte[] miniatura;

    @Column(name = "mime", nullable = false, length = 40)
    private String mime;

    /** SHA-256 (hex) de {@link #imagen}; sirve de ETag en el acceso móvil */
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "ancho")
    private int ancho;

    @Column(name = "alto")
    private int alto;

    @Column(name = "actualizado")
    private LocalDateTime actualizado;
}
