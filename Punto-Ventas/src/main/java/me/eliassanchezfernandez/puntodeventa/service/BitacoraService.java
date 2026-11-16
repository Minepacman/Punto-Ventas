package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consulta de la bitácora (USR-18) para la pantalla Configuración → Bitácora: solo el
 * administrador la ve. {@link AuditoriaService} solo escribe; aquí se lee con filtros
 * (fechas, acción, texto) y se traducen las claves de acción a texto llano.
 */
@Service
public class BitacoraService {

    /** Tope de filas por consulta: la pantalla filtra en memoria sobre este máximo */
    public static final int MAXIMO_FILAS = 5000;

    /** Filtro rápido "Solo cajón": aperturas automáticas (al cobrar) y manuales */
    public static final List<String> ACCIONES_CAJON = List.of(ImpresionService.ACCION_CAJON_ABIERTO);

    private static final Map<String, String> ETIQUETAS = Map.ofEntries(
            Map.entry(ImpresionService.ACCION_CAJON_ABIERTO, "Cajón abierto"),
            Map.entry("EFECTIVO_ENTRADA",     "Entrada de efectivo"),
            Map.entry("EFECTIVO_SALIDA",      "Salida de efectivo"),
            Map.entry("VENTA_CANCELADA",      "Venta cancelada"),
            Map.entry("VENTA_AUTORIZADA",     "Venta autorizada"),
            Map.entry("CREDITO_LIMITE_EXCEDIDO", "Fiado por encima del límite"),
            Map.entry("CLIENTE_ABONO",        "Abono de cliente (fiado)"),
            Map.entry("CLIENTE_AJUSTE",       "Ajuste de saldo de cliente"),
            Map.entry("CLIENTE_LIMITE_CAMBIADO", "Límite de crédito cambiado"),
            Map.entry("CLIENTE_ALTA",         "Cliente dado de alta"),
            Map.entry("CLIENTE_EDITADO",      "Cliente modificado"),
            Map.entry("TICKET_REIMPRESO",     "Ticket reimpreso"),
            Map.entry("CORTE_IMPRESO",        "Corte impreso"),
            Map.entry("FONDO_REGISTRADO",     "Fondo registrado"),
            Map.entry("FONDO_CORREGIDO",      "Fondo corregido"),
            Map.entry("LOGIN_FALLIDO",        "Contraseña incorrecta"),
            Map.entry("LOGIN_BLOQUEADO",      "Usuario bloqueado por intentos"),
            Map.entry("LOGIN_LIMITADO",       "Login limitado"),
            Map.entry("AUTORIZACION_CONCEDIDA", "Autorización de administrador"),
            Map.entry("AUTORIZACION_RECHAZADA", "Autorización rechazada"),
            Map.entry("USUARIO_CREADO",       "Usuario creado"),
            Map.entry("USUARIO_MODIFICADO",   "Usuario modificado"),
            Map.entry("USUARIO_ELIMINADO",    "Usuario eliminado"),
            Map.entry("USUARIO_BLOQUEADO",    "Usuario bloqueado"),
            Map.entry("USUARIO_DESBLOQUEADO", "Usuario desbloqueado"),
            Map.entry("CONFIGURACION_MODIFICADA", "Configuración modificada"),
            Map.entry("RESPALDO_MANUAL",      "Respaldo manual"),
            Map.entry("RESPALDO_AUTOMATICO",  "Respaldo automático"),
            Map.entry("RESPALDO_RESTAURADO",  "Respaldo restaurado"),
            Map.entry("LOGIN_MOVIL",          "Entrada desde el móvil"),
            Map.entry("LOGOUT_MOVIL",         "Salida desde el móvil"),
            Map.entry("INVENTARIO_MOVIL",     "Inventario desde el móvil"));

    @Autowired private AuditoriaRepository auditoriaRepo;
    @Autowired private SesionService sesionService;

    /**
     * Entradas entre dos días inclusive, de la más reciente a la más vieja (solo ADMIN).
     * Como mucho {@link #MAXIMO_FILAS}; si se alcanzan, acorta el periodo.
     */
    @Transactional(readOnly = true)
    public List<Auditoria> entre(LocalDate desde, LocalDate hasta) {
        sesionService.verificarAdmin();
        if (desde == null) desde = LocalDate.now();
        if (hasta == null) hasta = desde;
        if (hasta.isBefore(desde)) throw new NegocioException("La fecha final no puede ser anterior a la inicial.");
        return auditoriaRepo.findByFechaGreaterThanEqualAndFechaLessThanOrderByFechaDescIdDesc(
                desde.atStartOfDay(), hasta.plusDays(1).atStartOfDay(), PageRequest.of(0, MAXIMO_FILAS));
    }

    /** Texto llano de una clave de acción: "CAJON_ABIERTO" → "Cajón abierto"; desconocidas → "Orden compra creada" */
    public static String etiqueta(String accion) {
        if (accion == null || accion.isBlank()) return "—";
        String conocida = ETIQUETAS.get(accion);
        if (conocida != null) return conocida;
        String texto = accion.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    /** ¿La fila es una apertura del cajón de dinero? */
    public static boolean esDelCajon(Auditoria a) {
        return a != null && ACCIONES_CAJON.contains(a.getAccion());
    }
}
