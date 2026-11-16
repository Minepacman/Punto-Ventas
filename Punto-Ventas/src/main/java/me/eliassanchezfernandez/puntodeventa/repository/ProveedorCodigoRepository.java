package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ProveedorCodigo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProveedorCodigoRepository extends JpaRepository<ProveedorCodigo, Long> {

    Optional<ProveedorCodigo> findByProveedorIdAndClave(Long proveedorId, String clave);

    List<ProveedorCodigo> findByProveedorId(Long proveedorId);

    /** Para adivinar el proveedor de un ticket sin nombre: qué proveedores conocen estas claves */
    @Query("select c.proveedor.id, count(c) from ProveedorCodigo c where c.clave in :claves group by c.proveedor.id")
    List<Object[]> contarPorProveedor(@Param("claves") Collection<String> claves);

    void deleteByProductoId(Long productoId);
}
