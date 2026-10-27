package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catálogo de proveedores. Administrarlo (crear, editar, activar/desactivar)
 * exige GESTIONAR_PROVEEDORES y deja bitácora; consultar los activos o quiénes
 * visitan hoy no pide permiso (los usa la pantalla de Inventario).
 *
 * Los proveedores no se borran, se desactivan: las entradas de inventario y las
 * órdenes de compra anteriores siguen apuntando a ellos.
 */
@Service
public class ProveedorService {

    private static final Pattern RFC = Pattern.compile("^[A-ZÑ&0-9]{12,13}$");
    private static final Pattern TELEFONO = Pattern.compile("^[0-9 +()\\-.ext]{0,30}$", Pattern.CASE_INSENSITIVE);

    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;

    // ── Consultas ─────────────────────────────────────────────────────────

    /** Proveedores activos por nombre (combo de la entrada de mercancía). Requiere sesión. */
    @Transactional(readOnly = true)
    public List<Proveedor> listarActivos() {
        sesionService.requerirSesion();
        return proveedorRepo.findByActivoTrueOrderByNombreAsc();
    }

    /** Todos, activos o no (pantalla de Proveedores) */
    @Transactional(readOnly = true)
    public List<Proveedor> listarTodos() {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        return proveedorRepo.findAllByOrderByNombreAsc();
    }

    @Transactional(readOnly = true)
    public Proveedor obtener(Long id) {
        return proveedorRepo.findById(id).orElseThrow(() -> new NegocioException("El proveedor ya no existe."));
    }

    /** Proveedores activos que visitan en ese día de la semana */
    @Transactional(readOnly = true)
    public List<Proveedor> queVisitanEn(DayOfWeek dia) {
        sesionService.requerirSesion();
        List<Proveedor> r = new ArrayList<>();
        for (Proveedor p : proveedorRepo.findByActivoTrueOrderByNombreAsc()) if (p.visitaEn(dia)) r.add(p);
        return r;
    }

    /** "Proveedores que visitan hoy" (Inventario) */
    public List<Proveedor> queVisitanHoy() {
        return queVisitanEn(LocalDate.now().getDayOfWeek());
    }

    // ── Altas y cambios ───────────────────────────────────────────────────

    /**
     * Crea (id null) o edita un proveedor. Valida nombre (obligatorio, único sin
     * importar mayúsculas ni acentos), RFC opcional, teléfono y largos.
     */
    @Transactional
    public Proveedor guardar(Long id, String nombre, String rfc, String telefono, String contacto,
                             Collection<DayOfWeek> diasVisita, String notas) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        String nombreLimpio = limpiar(nombre);
        if (nombreLimpio == null) throw new NegocioException("El nombre del proveedor es obligatorio.");
        ConfiguracionService.validarLargo(nombreLimpio, 150, "El nombre", false);
        String rfcLimpio = limpiar(rfc) == null ? null : limpiar(rfc).toUpperCase().replace(" ", "");
        if (rfcLimpio != null && !RFC.matcher(rfcLimpio).matches()) {
            throw new NegocioException("El RFC debe tener 12 o 13 caracteres (letras y números).");
        }
        String tel = limpiar(telefono);
        if (tel != null && (tel.length() > 30 || !TELEFONO.matcher(tel).matches())) {
            throw new NegocioException("El teléfono solo puede llevar números, espacios y + ( ) - . (máximo 30).");
        }
        ConfiguracionService.validarLargo(limpiar(contacto), 100, "El contacto", false);
        ConfiguracionService.validarLargo(notas == null || notas.isBlank() ? null : notas.trim(), 500, "Las notas", true);

        String clave = clave(nombreLimpio);
        for (Proveedor otro : proveedorRepo.findAll()) {
            if (!otro.getId().equals(id) && clave(otro.getNombre()).equals(clave)) {
                throw new NegocioException("Ya existe un proveedor llamado \"" + otro.getNombre() + "\".");
            }
        }

        boolean nuevo = id == null;
        Proveedor p = nuevo ? new Proveedor() : obtener(id);
        String antes = nuevo ? "—" : resumen(p);
        p.setNombre(nombreLimpio);
        p.setRfc(rfcLimpio);
        p.setTelefono(tel);
        p.setContacto(limpiar(contacto));
        p.setDias(diasVisita == null ? Set.of() : Set.copyOf(diasVisita));
        p.setNotas(notas == null || notas.isBlank() ? null : notas.trim());
        Proveedor guardado = proveedorRepo.save(p);
        auditoriaService.registrar(sesionService.getCajeroActual(), nuevo ? "PROVEEDOR_CREADO" : "PROVEEDOR_EDITADO",
                (nuevo ? "" : "antes: " + antes + " | después: ") + resumen(guardado), (Cajero) null);
        return guardado;
    }

    @Transactional
    public Proveedor cambiarActivo(Long id, boolean activo) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        Proveedor p = obtener(id);
        if (p.isActivo() == activo) return p;
        p.setActivo(activo);
        Proveedor g = proveedorRepo.save(p);
        auditoriaService.registrar(sesionService.getCajeroActual(),
                activo ? "PROVEEDOR_ACTIVADO" : "PROVEEDOR_DESACTIVADO", resumen(g), (Cajero) null);
        return g;
    }

    private static String resumen(Proveedor p) {
        return String.format("%s (rfc=%s, tel=%s, contacto=%s, visita=%s, activo=%s)", p.getNombre(), p.getRfc(),
                p.getTelefono(), p.getContacto(), p.etiquetaDias(), p.isActivo());
    }

    // ── Migración de los textos escritos en entradas anteriores ───────────

    /**
     * Crea un proveedor por cada nombre distinto que se escribió a mano en las
     * entradas de inventario, y liga esas entradas con su proveedor. Es segura:
     *  - idempotente (se puede correr cuantas veces sea: lo que ya existe no se repite);
     *  - une variantes que solo difieren en mayúsculas, acentos o espacios
     *    ("Bimbo", "BIMBO ", "bímbo"), conservando la primera grafía;
     *  - NO cambia el texto original de las entradas (queda como historial);
     *  - no usa sesión ni permisos (corre al arrancar) y deja una nota en la bitácora.
     * @return cuántos proveedores nuevos se crearon
     */
    @Transactional
    public int migrarDesdeTextosDeEntradas() {
        List<String> escritos = new ArrayList<>(movimientoRepo.proveedoresEscritos(MovimientoInventario.Tipo.ENTRADA));
        escritos.sort(String::compareToIgnoreCase);     // orden estable: siempre gana la misma grafía

        Map<String, Proveedor> porClave = new HashMap<>();
        for (Proveedor existente : proveedorRepo.findAll()) porClave.put(clave(existente.getNombre()), existente);

        Map<String, Proveedor> creados = new LinkedHashMap<>();
        for (String texto : escritos) {
            String nombre = limpiar(texto);
            if (nombre == null) continue;
            if (nombre.length() > 150) nombre = nombre.substring(0, 150).trim();
            String k = clave(nombre);
            if (k.isEmpty()) continue;
            Proveedor p = porClave.get(k);
            if (p == null) {
                p = new Proveedor();
                p.setNombre(nombre);
                p.setNotas("Creado automáticamente a partir de las entradas de inventario.");
                p = proveedorRepo.save(p);
                porClave.put(k, p);
                creados.put(k, p);
            }
            movimientoRepo.ligarProveedor(p.getId(), texto, MovimientoInventario.Tipo.ENTRADA);
        }
        if (!creados.isEmpty()) {
            auditoriaService.registrarSistema("PROVEEDORES_MIGRADOS", creados.size()
                    + " proveedor(es) creados desde las entradas de inventario: "
                    + String.join(", ", creados.values().stream().map(Proveedor::getNombre).toList()));
        }
        return creados.size();
    }

    /** Nombre comparable: minúsculas, sin acentos, sin espacios repetidos ("Bímbo  S.A." ≈ "bimbo s.a.") */
    public static String clave(String nombre) {
        if (nombre == null) return "";
        String sinAcentos = Normalizer.normalize(nombre, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinAcentos.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    private static String limpiar(String texto) {
        if (texto == null || texto.isBlank()) return null;
        return texto.trim().replaceAll("\\s+", " ");
    }
}
