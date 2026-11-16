package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Vale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Vales de compra. El saldo solo se mueve con los UPDATE atómicos de aquí abajo: con varias
 * cajas, dos cobros con el mismo vale a la vez no pueden gastar más de lo que tiene (en
 * PostgreSQL la segunda caja espera a la primera y vuelve a evaluar la condición; en SQLite
 * las escrituras se serializan con BEGIN IMMEDIATE).
 */
@Repository
public interface ValeRepository extends JpaRepository<Vale, Long> {

    /** Filtro por caja (null = todas); las filas sin caja cuentan como caja 1 */
    String FILTRO_CAJA = " AND (:caja IS NULL OR COALESCE(v.caja, 1) = :caja)";

    Optional<Vale> findByCodigo(String codigo);

    boolean existsByCodigo(String codigo);

    List<Vale> findByDevolucionId(Long devolucionId);

    /** Resta {@code monto} SOLO si el vale lo tiene. 1 = se aplicó; 0 = no alcanza (otra caja lo acaba de usar) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Vale v SET v.saldo = v.saldo - :monto WHERE v.id = :id AND v.saldo >= :monto - 0.004")
    int usarSiAlcanza(Long id, double monto);

    /** Regresa saldo (venta cancelada) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Vale v SET v.saldo = v.saldo + :monto WHERE v.id = :id")
    int regresar(Long id, double monto);

    /** Saldo tal como está en la base (no el de la copia en memoria) */
    @Query("SELECT v.saldo FROM Vale v WHERE v.id = :id")
    Double saldoDe(Long id);

    /** Corte: cuántos vales se entregaron y por cuánto en [inicio, fin) en esa caja */
    @Query("SELECT COUNT(v), COALESCE(SUM(v.monto), 0.0) FROM Vale v WHERE v.fecha >= :inicio AND v.fecha < :fin" + FILTRO_CAJA)
    List<Object[]> emitidosEntre(LocalDateTime inicio, LocalDateTime fin, Integer caja);

    /** Lo que los clientes aún pueden gastar con sus vales (lo que la tienda debe en vales) */
    @Query("SELECT COALESCE(SUM(v.saldo), 0.0) FROM Vale v WHERE v.saldo > 0.004")
    Double saldoPendienteTotal();
}
