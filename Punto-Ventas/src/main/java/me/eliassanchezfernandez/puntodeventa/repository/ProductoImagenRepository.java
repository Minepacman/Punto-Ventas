package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ProductoImagen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Imágenes de producto: consultas que traen SOLO lo necesario (nunca la fila completa para una lista). */
@Repository
public interface ProductoImagenRepository extends JpaRepository<ProductoImagen, Long> {

    /** Solo la miniatura (unos KB), sin cargar la imagen grande */
    @Query("SELECT i.miniatura FROM ProductoImagen i WHERE i.productoId = :id")
    Optional<byte[]> miniatura(@Param("id") Long productoId);

    /** Solo la imagen grande */
    @Query("SELECT i.imagen FROM ProductoImagen i WHERE i.productoId = :id")
    Optional<byte[]> imagen(@Param("id") Long productoId);

    /** De estos productos, cuáles tienen imagen (una sola consulta para marcar una lista) */
    @Query("SELECT i.productoId FROM ProductoImagen i WHERE i.productoId IN :ids")
    List<Long> idsConImagen(@Param("ids") Collection<Long> ids);
}
