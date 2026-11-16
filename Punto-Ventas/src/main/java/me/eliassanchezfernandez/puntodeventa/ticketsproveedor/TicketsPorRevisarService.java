package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.TicketPorRevisar;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.repository.TicketPorRevisarRepository;
import me.eliassanchezfernandez.puntodeventa.repository.TicketProveedorRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Tickets de proveedor que se mandaron desde el teléfono «a la computadora para revisar»: la foto y,
 * si se pudo leer, lo que leyó el OCR. En la computadora se abren en «Leer ticket» (Inventario) y se
 * vuelven a interpretar con el intérprete de ese momento (las mejoras del intérprete aplican también a
 * lo que ya estaba en la cola). Quedan REGISTRADO al dar la entrada o DESCARTADO.
 */
@Service
public class TicketsPorRevisarService {

    /** Tope de la foto que se guarda (el teléfono la reduce antes de mandarla: suele pesar menos de 1 MB) */
    public static final int MAX_FOTO_BYTES = 8 * 1024 * 1024;
    /** Pendientes a la vez: si nadie los revisa, la cola no crece sin fin */
    public static final int MAX_PENDIENTES = 30;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private TicketPorRevisarRepository repo;
    @Autowired private TicketProveedorRegistroRepository registroRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;

    public record Pendiente(Long id, byte[] foto, String mime, ResultadoOcr lectura, String origen, Long proveedorId,
                            String nota, String usuarioNombre, LocalDateTime creadoEn, TicketPorRevisar.Estado estado) { }

    /** Tipo de imagen por sus primeros bytes (no por lo que diga quien la manda); null si no es JPEG, PNG ni WEBP */
    public static String tipoDeImagen(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Guarda la foto (y lo leído, si se leyó) para revisarla en la computadora. Cualquier usuario con sesión
     * puede mandarla; registrarla después exige AJUSTAR_INVENTARIO (o autorización) en la computadora.
     * @return id del pendiente
     */
    @Transactional
    public Long encolar(byte[] foto, ResultadoOcr lectura, String origen, Long proveedorId, String nota) {
        Cajero quien = sesionService.requerirSesion();
        if (foto == null || foto.length == 0) throw new NegocioException("La foto está vacía.");
        if (foto.length > MAX_FOTO_BYTES) throw new NegocioException("La foto pesa demasiado (más de 8 MB).");
        String mime = tipoDeImagen(foto);
        if (mime == null) throw new NegocioException("El archivo no es una foto JPG, PNG o WEBP.");
        if (repo.countByEstado(TicketPorRevisar.Estado.PENDIENTE) >= MAX_PENDIENTES) {
            throw new NegocioException("Ya hay " + MAX_PENDIENTES + " tickets esperando en la computadora: revisa esos primero (Inventario → Por revisar).");
        }
        TicketPorRevisar t = new TicketPorRevisar();
        t.setFoto(foto);
        t.setFotoMime(mime);
        t.setLectura(lectura == null ? null : lecturaJson(lectura, origen));
        t.setProveedorId(proveedorId);
        String n = nota == null ? null : nota.strip();
        t.setNota(n == null || n.isEmpty() ? null : (n.length() > 300 ? n.substring(0, 300) : n));
        t.setCreadoEn(LocalDateTime.now());
        t.setUsuarioId(quien.getId());
        t.setUsuarioNombre(recorte(sesionService.getNombreCajero(), 100));
        t = repo.save(t);
        auditoriaService.registrar(sesionService.getCajeroActual(), "TICKET_PROVEEDOR_POR_REVISAR",
                "Foto de ticket mandada a la computadora (#" + t.getId() + (lectura == null ? ", sin leer" : ", leída") + ")", (Cajero) null);
        return t.getId();
    }

    /** Pendientes, del más viejo al más nuevo (sin fotos) */
    @Transactional(readOnly = true)
    public List<TicketPorRevisarRepository.Resumen> pendientes() {
        sesionService.requerirSesion();
        return repo.resumenes(TicketPorRevisar.Estado.PENDIENTE);
    }

    /** Cuántos esperan revisión (para el botón de Inventario y la campana) */
    @Transactional(readOnly = true)
    public long cuantosPendientes() {
        return repo.countByEstado(TicketPorRevisar.Estado.PENDIENTE);
    }

    @Transactional(readOnly = true)
    public Pendiente obtener(Long id) {
        sesionService.requerirSesion();
        TicketPorRevisar t = repo.findById(id).orElseThrow(() -> new NegocioException("Ese ticket ya no está en la lista."));
        LecturaGuardada l = leerLectura(t.getLectura());
        return new Pendiente(t.getId(), t.getFoto(), t.getFotoMime(), l == null ? null : l.ocr(), l == null ? null : l.origen(),
                t.getProveedorId(), t.getNota(), t.getUsuarioNombre(), t.getCreadoEn(), t.getEstado());
    }

    /** Lo leído, interpretado otra vez con el intérprete actual; lista vacía si no se había leído */
    public static List<TicketLeido> interpretar(Pendiente p) {
        if (p.lectura() == null) return List.of();
        ResultadoOcr r = p.lectura();
        return r.renglones() != null && !r.renglones().isEmpty()
                ? InterpreteTicket.interpretar(r.renglones())
                : InterpreteTicket.interpretar(r.textoCompleto() == null ? "" : r.textoCompleto());
    }

    /** Ya se registró la entrada con este ticket. Devuelve false si alguien más ya lo había cerrado. */
    @Transactional
    public boolean marcarRegistrado(Long id, Long registroId) {
        sesionService.requerirSesion();
        if (registroId == null || !registroRepo.existsById(registroId)) throw new NegocioException("Esa entrada no existe.");
        return repo.cerrar(id, TicketPorRevisar.Estado.REGISTRADO, LocalDateTime.now(), recorte(sesionService.getNombreCajero(), 100),
                registroId, TicketPorRevisar.Estado.PENDIENTE) > 0;
    }

    /** No sirve (foto repetida, borrosa…): sale de la lista. AJUSTAR_INVENTARIO o quien la mandó. */
    @Transactional
    public boolean descartar(Long id) {
        Cajero quien = sesionService.requerirSesion();
        TicketPorRevisar t = repo.findById(id).orElseThrow(() -> new NegocioException("Ese ticket ya no está en la lista."));
        if (!quien.getId().equals(t.getUsuarioId())) sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO);
        boolean ok = repo.cerrar(id, TicketPorRevisar.Estado.DESCARTADO, LocalDateTime.now(), recorte(sesionService.getNombreCajero(), 100),
                null, TicketPorRevisar.Estado.PENDIENTE) > 0;
        if (ok) {
            auditoriaService.registrar(sesionService.getCajeroActual(), "TICKET_PROVEEDOR_DESCARTADO",
                    "Ticket por revisar #" + id + " descartado (lo mandó " + t.getUsuarioNombre() + ")", (Cajero) null);
        }
        return ok;
    }

    // ── Lectura guardada en JSON ───────────────────────────────────────────

    record LecturaGuardada(ResultadoOcr ocr, String origen) { }

    static String lecturaJson(ResultadoOcr r, String origen) {
        ObjectNode o = JSON.createObjectNode();
        o.put("origen", origen);
        o.put("texto", r.textoCompleto());
        ArrayNode rs = o.putArray("renglones");
        if (r.renglones() != null) {
            for (Renglon x : r.renglones()) {
                ObjectNode n = rs.addObject();
                n.put("t", x.texto());
                n.put("c", x.confianza());
                n.put("y", x.y());
            }
        }
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static LecturaGuardada leerLectura(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode o = JSON.readTree(json);
            List<Renglon> rs = new ArrayList<>();
            for (JsonNode n : o.path("renglones")) rs.add(new Renglon(n.path("t").asText(""), n.path("c").asDouble(0), n.path("y").asInt(-1)));
            String origen = o.hasNonNull("origen") ? o.get("origen").asText() : null;
            String texto = o.hasNonNull("texto") ? o.get("texto").asText() : "";
            return new LecturaGuardada(new ResultadoOcr(rs, texto), origen);
        } catch (IOException e) {
            return null;
        }
    }

    private static String recorte(String s, int n) {
        if (s == null) return null;
        return s.length() > n ? s.substring(0, n) : s;
    }
}
