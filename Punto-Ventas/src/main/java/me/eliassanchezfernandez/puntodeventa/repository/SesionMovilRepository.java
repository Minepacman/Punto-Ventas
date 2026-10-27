package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.SesionMovil;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SesionMovilRepository extends JpaRepository<SesionMovil, Long> {

    Optional<SesionMovil> findByTokenHash(String tokenHash);

    List<SesionMovil> findByCajeroIdAndRevocadaFalse(Long cajeroId);

    List<SesionMovil> findByRevocadaFalseOrderByCreadaDesc();

    /**
     * Borra sesiones ya inútiles: revocadas (último uso anterior al límite),
     * vencidas a las 48 h (expira anterior al límite) y permanentes sin uso
     * desde antes de limitePermanentes (60 días de inactividad + 30 de gracia).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SesionMovil s WHERE " +
           "(s.revocada = true AND COALESCE(s.ultimoUso, s.creada) < :limite) " +
           "OR (s.expira IS NOT NULL AND s.expira < :limite) " +
           "OR (s.expira IS NULL AND COALESCE(s.ultimoUso, s.creada) < :limitePermanentes)")
    int purgar(LocalDateTime limite, LocalDateTime limitePermanentes);
}
