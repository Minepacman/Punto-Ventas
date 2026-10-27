package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ProductoDiario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProductoDiarioRepository extends JpaRepository<ProductoDiario, Long> {

    /** Filas del rango con el producto y su departamento ya cargados */
    @Query("SELECT pd FROM ProductoDiario pd JOIN FETCH pd.producto p LEFT JOIN FETCH p.departamento " +
           "WHERE pd.fecha >= :desde AND pd.fecha <= :hasta")
    List<ProductoDiario> findEntre(LocalDate desde, LocalDate hasta);

    @Modifying
    @Query("DELETE FROM ProductoDiario pd WHERE pd.fecha IN :fechas")
    void deleteByFechas(List<LocalDate> fechas);
}
