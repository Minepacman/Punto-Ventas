package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClienteFiscalRepository extends JpaRepository<ClienteFiscal, Long> {

    Optional<ClienteFiscal> findByRfc(String rfc);

    List<ClienteFiscal> findAllByOrderByNombreAsc();

    List<ClienteFiscal> findByRfcContainingIgnoreCaseOrNombreContainingIgnoreCaseOrderByNombreAsc(String rfc, String nombre);
}
