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
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Catálogo de proveedores. Administrarlo (crear, editar, activar/desactivar)
 * exige GESTIONAR_PROVEEDORES y deja bitácora; consultar los activos, quiénes
 * visitan hoy o la {@link #agenda()} semanal no pide permiso (los usa la pantalla
 * de Inventario y la página móvil, para que todo el equipo sepa quién viene).
 *
 * Los proveedores no se borran, se desactivan: las entradas de inventario y las
 * órdenes de compra anteriores siguen apuntando a ellos.
 *
 * Cada proveedor tiene días de ENTREGA (llega la mercancía) y, si lo tiene, días de
 * PREVENTA (pasa el vendedor a levantar el pedido). La "nota media" es el gasto
 * promedio por visita en los últimos {@link #DIAS_NOTA_MEDIA} días y solo la ve
 * quien puede ver costos (VER_GANANCIAS o GESTIONAR_PROVEEDORES).
 */
@Service
public class ProveedorService {

    private static final Pattern RFC = Pattern.compile("^[A-ZÑ&0-9]{12,13}$");
    private static final Pattern TELEFONO = Pattern.compile("^[0-9 +()\\-.ext]{0,30}$", Pattern.CASE_INSENSITIVE);

    /** Ventana de la nota media: compras de los últimos 90 días */
    public static final int DIAS_NOTA_MEDIA = 90;

    @Autowired private ProveedorRepository proveedorRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private Clock reloj;

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

    /** Proveedores activos que ENTREGAN en ese día de la semana */
    @Transactional(readOnly = true)
    public List<Proveedor> queVisitanEn(DayOfWeek dia) {
        sesionService.requerirSesion();
        List<Proveedor> r = new ArrayList<>();
        for (Proveedor p : proveedorRepo.findByActivoTrueOrderByNombreAsc()) if (p.visitaEn(dia)) r.add(p);
        return r;
    }

    /** "Proveedores que visitan hoy" (Inventario) */
    public List<Proveedor> queVisitanHoy() {
        return queVisitanEn(hoy().getDayOfWeek());
    }

    // ── Agenda semanal (móvil y escritorio) ───────────────────────────────

    /** Gasto promedio por visita: promedio de las "notas" (entradas del mismo día) con costo */
    public record NotaMedia(double promedio, int notas, LocalDate ultima) { }

    /**
     * Un proveedor activo con sus próximas visitas (null si no tiene ese calendario) y su nota
     * media (null si el usuario no puede ver costos o aún no hay compras con costo).
     */
    public record ResumenProveedor(Proveedor proveedor, LocalDate proximaEntrega, LocalDate proximaPreventa,
                                   NotaMedia notaMedia) { }

    /** Lo que ve cualquier usuario: hoy según el reloj de la tienda, qué puede hacer y los proveedores activos */
    public record Agenda(LocalDate hoy, boolean costosVisibles, boolean puedeEditar, List<ResumenProveedor> proveedores) { }

    /**
     * Agenda de proveedores activos ordenada por nombre. Requiere sesión; la nota media solo
     * se calcula (y se entrega) a quien puede ver costos.
     */
    @Transactional(readOnly = true)
    public Agenda agenda() {
        sesionService.requerirSesion();
        boolean costos = puedeVerCostos();
        boolean editar = sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
        LocalDate hoy = hoy();
        Map<Long, NotaMedia> notas = costos ? notasMedias(hoy) : Map.of();
        List<ResumenProveedor> lista = new ArrayList<>();
        for (Proveedor p : proveedorRepo.findByActivoTrueOrderByNombreAsc()) {
            lista.add(new ResumenProveedor(p, proximaFecha(p.getDias(), hoy), proximaFecha(p.getPreventa(), hoy),
                    notas.get(p.getId())));
        }
        return new Agenda(hoy, costos, editar, lista);
    }

    /** Los costos de compra los ve quien ve ganancias o quien administra proveedores */
    public boolean puedeVerCostos() {
        return sesionService.tienePermiso(Permiso.VER_GANANCIAS) || sesionService.tienePermiso(Permiso.GESTIONAR_PROVEEDORES);
    }

    /**
     * Próxima fecha (hoy incluido) en que cae alguno de los días; null si no hay días.
     * Da la vuelta a la semana: con {VIE} y hoy sábado devuelve el viernes siguiente.
     */
    public static LocalDate proximaFecha(Collection<DayOfWeek> dias, LocalDate desde) {
        if (dias == null || dias.isEmpty() || desde == null) return null;
        for (int i = 0; i < 7; i++) {
            LocalDate f = desde.plusDays(i);
            if (dias.contains(f.getDayOfWeek())) return f;
        }
        return null;
    }

    /**
     * Nota media por proveedor: cada día con entradas CON COSTO de ese proveedor cuenta como una
     * "nota" (una visita) cuyo importe es la suma de cantidad × costo; la media es sobre esas notas
     * dentro de la ventana. Las entradas de una orden de compra recibida ya traen su costo y su
     * proveedor, así que quedan incluidas sin contarse dos veces.
     */
    Map<Long, NotaMedia> notasMedias(LocalDate hoy) {
        LocalDateTime desde = hoy.minusDays(DIAS_NOTA_MEDIA - 1L).atStartOfDay();
        Map<Long, TreeMap<LocalDate, Double>> porProveedor = new HashMap<>();
        for (Object[] fila : movimientoRepo.entradasConCostoDesde(MovimientoInventario.Tipo.ENTRADA, desde)) {
            Long proveedorId = (Long) fila[0];
            LocalDate dia = ((LocalDateTime) fila[1]).toLocalDate();
            double importe = ((Number) fila[2]).doubleValue() * ((Number) fila[3]).doubleValue();
            if (!Double.isFinite(importe) || importe <= 0) continue;
            porProveedor.computeIfAbsent(proveedorId, k -> new TreeMap<>()).merge(dia, importe, Double::sum);
        }
        Map<Long, NotaMedia> r = new HashMap<>();
        porProveedor.forEach((id, dias) -> {
            double suma = 0;
            for (double v : dias.values()) suma += v;
            r.put(id, new NotaMedia(Math.round(suma / dias.size() * 100.0) / 100.0, dias.size(), dias.lastKey()));
        });
        return r;
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    // ── Altas y cambios ───────────────────────────────────────────────────

    /**
     * Crea o edita SIN tocar los días de preventa (los conserva al editar). Lo usan la importación
     * y el código anterior a la preventa; las pantallas usan la versión con los dos calendarios.
     */
    @Transactional
    public Proveedor guardar(Long id, String nombre, String rfc, String telefono, String contacto,
                             Collection<DayOfWeek> diasVisita, String notas) {
        Collection<DayOfWeek> preventa = id == null ? Set.of() : obtener(id).getPreventa();
        return guardar(id, nombre, rfc, telefono, contacto, diasVisita, preventa, notas);
    }

    /**
     * Crea (id null) o edita un proveedor. Valida nombre (obligatorio, único sin
     * importar mayúsculas ni acentos), RFC opcional, teléfono y largos.
     * @param diasVisita   días de ENTREGA de mercancía
     * @param diasPreventa días en que pasa el PREVENTA a levantar pedido (vacío si no tiene)
     */
    @Transactional
    public Proveedor guardar(Long id, String nombre, String rfc, String telefono, String contacto,
                             Collection<DayOfWeek> diasVisita, Collection<DayOfWeek> diasPreventa, String notas) {
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
        p.setPreventa(diasPreventa == null ? Set.of() : Set.copyOf(diasPreventa));
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
        return String.format("%s (rfc=%s, tel=%s, contacto=%s, entrega=%s, preventa=%s, activo=%s)", p.getNombre(),
                p.getRfc(), p.getTelefono(), p.getContacto(), p.etiquetaDias(), p.etiquetaPreventa(), p.isActivo());
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
