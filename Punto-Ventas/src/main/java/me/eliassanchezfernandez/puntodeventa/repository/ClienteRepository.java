package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Clientes con cuenta (fiado). El saldo solo se mueve con los UPDATE atómicos
 * de aquí abajo (nunca leer-sumar-guardar): con varias cajas, dos cargos al
 * mismo cliente a la vez no se pisan y el límite no se rebasa dos veces.
 */
@Repository
public interface ClienteRepository extends JpaRepository<Cliente, Long> {

    List<Cliente> findByNombreContainingIgnoreCase(String nombre);

    @Query("SELECT c FROM Cliente c ORDER BY LOWER(c.nombre) ASC, c.id ASC")
    List<Cliente> findTodosOrdenados();

    /**
     * Suma {@code monto} al saldo SOLO si no rebasa el límite (0 = sin límite) y el cliente
     * está activo. 1 = se aplicó; 0 = rebasaría el límite (o el cliente no existe / está inactivo).
     * La condición se evalúa en la base sobre la fila ya bloqueada: en PostgreSQL la segunda caja
     * espera a la primera y vuelve a evaluar con el saldo nuevo; en SQLite las escrituras se
     * serializan (BEGIN IMMEDIATE).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Cliente c SET c.saldoDeuda = c.saldoDeuda + :monto WHERE c.id = :id "
            + "AND (c.activo IS NULL OR c.activo = true) "
            + "AND (c.limiteCredito <= 0.004 OR c.saldoDeuda + :monto <= c.limiteCredito + 0.004)")
    int cargarSiCabe(Long id, double monto);

    /** Resta un abono SOLO si no deja el saldo por debajo de cero (dos cajas no cobran dos veces lo mismo) */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Cliente c SET c.saldoDeuda = c.saldoDeuda - :monto WHERE c.id = :id "
            + "AND c.saldoDeuda >= :monto - 0.004")
    int abonarSiDebe(Long id, double monto);

    /** Suma (o resta, con monto negativo) sin condición de límite */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Cliente c SET c.saldoDeuda = c.saldoDeuda + :monto WHERE c.id = :id")
    int mover(Long id, double monto);

    /** Saldo tal como está en la base (no el de la copia en memoria) */
    @Query("SELECT c.saldoDeuda FROM Cliente c WHERE c.id = :id")
    Double saldoDe(Long id);

    /** Clientes que deben algo (o tienen saldo a favor), de mayor a menor deuda */
    @Query("SELECT c FROM Cliente c WHERE c.saldoDeuda > 0.004 OR c.saldoDeuda < -0.004 "
            + "ORDER BY c.saldoDeuda DESC, LOWER(c.nombre) ASC")
    List<Cliente> findConSaldo();
}
