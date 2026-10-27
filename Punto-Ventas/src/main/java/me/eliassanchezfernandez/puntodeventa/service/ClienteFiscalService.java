package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.Rfc;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.repository.ClienteFiscalRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Catálogo de receptores de factura (ClienteFiscal). Alta y cambios con
 * FACTURAR; se valida el RFC, el régimen, el uso de CFDI y el código postal.
 */
@Service
public class ClienteFiscalService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Autowired private ClienteFiscalRepository repo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;

    public List<ClienteFiscal> listar() {
        return repo.findAllByOrderByNombreAsc();
    }

    public List<ClienteFiscal> buscar(String texto) {
        if (texto == null || texto.isBlank()) return listar();
        String t = texto.trim();
        return repo.findByRfcContainingIgnoreCaseOrNombreContainingIgnoreCaseOrderByNombreAsc(t, t);
    }

    public Optional<ClienteFiscal> porRfc(String rfc) {
        String r = Rfc.normalizar(rfc);
        return r == null ? Optional.empty() : repo.findByRfc(r);
    }

    public Optional<ClienteFiscal> porId(Long id) {
        return id == null ? Optional.empty() : repo.findById(id);
    }

    /** Alta (id null) o cambio. Requiere FACTURAR. */
    @Transactional
    public ClienteFiscal guardar(ClienteFiscal datos) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        ClienteFiscal limpio = validar(datos);

        repo.findByRfc(limpio.getRfc())
                .filter(otro -> datos.getId() == null || !otro.getId().equals(datos.getId()))
                .ifPresent(otro -> {
                    throw new NegocioException("Ya existe un cliente con el RFC " + otro.getRfc() + " (" + otro.getNombre() + ").");
                });

        ClienteFiscal guardado;
        String accion;
        String detalle;
        if (datos.getId() == null) {
            guardado = repo.save(limpio);
            accion = "CLIENTE_FISCAL_CREADO";
            detalle = descripcion(guardado);
        } else {
            ClienteFiscal enBd = repo.findById(datos.getId())
                    .orElseThrow(() -> new NegocioException("El cliente fiscal ya no existe."));
            detalle = descripcion(enBd) + " → ";
            enBd.setRfc(limpio.getRfc());
            enBd.setNombre(limpio.getNombre());
            enBd.setRegimenFiscal(limpio.getRegimenFiscal());
            enBd.setCodigoPostal(limpio.getCodigoPostal());
            enBd.setUsoCfdi(limpio.getUsoCfdi());
            enBd.setEmail(limpio.getEmail());
            enBd.setModificado(LocalDateTime.now());
            guardado = repo.save(enBd);
            accion = "CLIENTE_FISCAL_MODIFICADO";
            detalle += descripcion(guardado);
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), accion, detalle, (Cajero) null);
        return guardado;
    }

    @Transactional
    public void eliminar(Long id) {
        sesionService.verificarPermiso(Permiso.FACTURAR);
        ClienteFiscal c = repo.findById(id).orElseThrow(() -> new NegocioException("El cliente fiscal ya no existe."));
        repo.delete(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CLIENTE_FISCAL_ELIMINADO", descripcion(c), (Cajero) null);
    }

    /** Copia validada y normalizada (no toca el objeto recibido) */
    static ClienteFiscal validar(ClienteFiscal d) {
        if (d == null) throw new NegocioException("Faltan los datos del cliente.");
        ClienteFiscal c = new ClienteFiscal();
        try {
            c.setRfc(Rfc.validar(d.getRfc()));
            c.setCodigoPostal(CatalogoSat.normalizarCodigoPostal(d.getCodigoPostal()));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (c.getCodigoPostal() == null) throw new NegocioException("El código postal fiscal es obligatorio (5 dígitos).");

        String nombre = d.getNombre() == null ? "" : d.getNombre().trim().replaceAll("\\s+", " ");
        if (nombre.isEmpty()) throw new NegocioException("El nombre o razón social es obligatorio.");
        if (nombre.length() > 254) throw new NegocioException("El nombre no puede pasar de 254 caracteres.");
        ConfiguracionService.validarLargo(nombre, 254, "El nombre", false);
        // CFDI 4.0: el nombre va en mayúsculas tal como en la CSF (el SAT lo compara)
        c.setNombre(nombre.toUpperCase(Locale.ROOT));

        String regimen = Rfc.esGenerico(c.getRfc()) ? CatalogoSat.REGIMEN_SIN_OBLIGACIONES
                : d.getRegimenFiscal() == null ? "" : d.getRegimenFiscal().trim();
        if (!CatalogoSat.esRegimen(regimen)) throw new NegocioException("Elige el régimen fiscal del cliente (como aparece en su CSF).");
        if (CatalogoSat.regimenesPara(c.getRfc()).stream().noneMatch(r -> r.clave().equals(regimen))) {
            throw new NegocioException("El régimen " + regimen + " no aplica para un RFC de "
                    + (Rfc.esPersonaMoral(c.getRfc()) ? "persona moral." : "persona física."));
        }
        c.setRegimenFiscal(regimen);

        String uso = d.getUsoCfdi() == null ? "" : d.getUsoCfdi().trim().toUpperCase(Locale.ROOT);
        if (Rfc.esGenerico(c.getRfc())) uso = CatalogoSat.USO_SIN_EFECTOS;
        if (!CatalogoSat.esUsoCfdi(uso)) throw new NegocioException("Elige el uso de CFDI.");
        c.setUsoCfdi(uso);

        String email = d.getEmail() == null ? null : d.getEmail().trim();
        if (email != null && !email.isEmpty()) {
            if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
                throw new NegocioException("El correo electrónico no es válido.");
            }
            c.setEmail(email);
        }
        return c;
    }

    private static String descripcion(ClienteFiscal c) {
        return c.getRfc() + " " + c.getNombre() + " (régimen " + c.getRegimenFiscal() + ", CP " + c.getCodigoPostal()
                + ", uso " + c.getUsoCfdi() + (c.getEmail() != null ? ", " + c.getEmail() : "") + ")";
    }
}
