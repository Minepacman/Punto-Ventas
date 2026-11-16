package me.eliassanchezfernandez.puntodeventa.repository;

import me.eliassanchezfernandez.puntodeventa.model.TicketProveedorRegistro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketProveedorRegistroRepository extends JpaRepository<TicketProveedorRegistro, Long> {

    List<TicketProveedorRegistro> findByProveedorIdAndFolioOrderByRegistradoEnDesc(Long proveedorId, String folio);
}
