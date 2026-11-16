package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.SuscripcionPush;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SuscripcionPushRepository extends JpaRepository<SuscripcionPush, Long> {

    Optional<SuscripcionPush> findByEndpointHash(String endpointHash);

    List<SuscripcionPush> findByCajeroIdOrderByCreadaAsc(Long cajeroId);

    List<SuscripcionPush> findByActivaTrueOrderByCajeroIdAscCreadaAsc();

    List<SuscripcionPush> findAllByOrderByCreadaDesc();

    long countByCajeroId(Long cajeroId);
}
