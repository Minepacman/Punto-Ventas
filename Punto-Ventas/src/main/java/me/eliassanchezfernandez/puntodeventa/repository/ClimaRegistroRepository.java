package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ClimaRegistroRepository extends JpaRepository<ClimaRegistro, Long> {

    Optional<ClimaRegistro> findByFechaHora(LocalDateTime fechaHora);

    /** Registro más reciente (para "clima actual" sin volver a consultar el servicio) */
    Optional<ClimaRegistro> findFirstByOrderByFechaHoraDesc();

    /** Registros del rango [desde, hasta) en orden cronológico */
    List<ClimaRegistro> findByFechaHoraGreaterThanEqualAndFechaHoraLessThanOrderByFechaHoraAsc(
            LocalDateTime desde, LocalDateTime hasta);
}
