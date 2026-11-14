package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Catálogo local de códigos de barras (solo consulta por código; la carga masiva va por JDBC en el servicio) */
@Repository
public interface CatalogoCodigoRepository extends JpaRepository<CatalogoCodigo, String> {
}
