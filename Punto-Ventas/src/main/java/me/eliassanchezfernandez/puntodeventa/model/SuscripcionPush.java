package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Suscripción Web Push de un teléfono (RFC 8030): a dónde mandar (endpoint del servicio de
 * push del navegador) y con qué cifrar (p256dh y auth del teléfono). Pertenece a un usuario;
 * guarda sus preferencias (qué avisos y horas de silencio) y qué avisos ya se le mandaron hoy
 * para no repetirlos. No contiene nada secreto del servidor: la clave VAPID vive en la carpeta
 * de claves, no en la base.
 *
 * Fechas en UTC, como en {@link SesionMovil}; las horas de silencio son horas LOCALES (0-23).
 */
@Entity
@Table(name = "suscripciones_push")
@Getter
@Setter
@NoArgsConstructor
public class SuscripcionPush {

    public static final int SILENCIO_DESDE_POR_DEFECTO = 21;
    public static final int SILENCIO_HASTA_POR_DEFECTO = 7;
    public static final int MAX_ENDPOINT = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cajero_id", nullable = false)
    private Long cajeroId;

    /** Sesión móvil desde la que se suscribió (informativo; puede ser null si ya se purgó) */
    @Column(name = "sesion_movil_id")
    private Long sesionMovilId;

    @Column(nullable = false, length = MAX_ENDPOINT)
    private String endpoint;

    /** SHA-256 hex del endpoint: clave única corta para buscar y para no duplicar */
    @Column(name = "endpoint_hash", nullable = false, unique = true, length = 64)
    private String endpointHash;

    /** Clave pública P-256 del teléfono (base64url, 65 bytes) */
    @Column(nullable = false, length = 100)
    private String p256dh;

    /** Secreto de autenticación del teléfono (base64url, 16 bytes) */
    @Column(nullable = false, length = 30)
    private String auth;

    @Column(name = "user_agent", length = 250)
    private String userAgent;

    /** UTC */
    @Column(nullable = false)
    private LocalDateTime creada;

    /** UTC: última entrega aceptada por el servicio de push */
    @Column(name = "ultimo_ok")
    private LocalDateTime ultimoOk;

    /** UTC: último intento fallido */
    @Column(name = "ultimo_error")
    private LocalDateTime ultimoError;

    @Column(name = "error_detalle", length = 200)
    private String errorDetalle;

    /** Interruptor general del teléfono */
    @Column(nullable = false)
    private boolean activa = true;

    @Column(name = "tipo_proveedores", nullable = false)
    private boolean proveedores = true;

    @Column(name = "tipo_stock", nullable = false)
    private boolean stock = true;

    @Column(name = "tipo_hora_pico", nullable = false)
    private boolean horaPico = true;

    /** Hora local (0-23) desde la que no se manda nada; igual a silencioHasta = sin silencio */
    @Column(name = "silencio_desde", nullable = false)
    private int silencioDesde = SILENCIO_DESDE_POR_DEFECTO;

    @Column(name = "silencio_hasta", nullable = false)
    private int silencioHasta = SILENCIO_HASTA_POR_DEFECTO;

    /** Día (local) al que corresponden los ids enviados; otro día = lista nueva */
    @Column(name = "enviados_dia")
    private LocalDate enviadosDia;

    /** ids de aviso ya mandados ese día, separados por coma (también marcas como "stock@9") */
    @Column(name = "enviados_ids", length = 2000)
    private String enviadosIds;

    /** ¿Está en horas de silencio a esa hora local? Con desde == hasta nunca */
    public boolean enSilencio(int hora) {
        if (silencioDesde == silencioHasta) return false;
        if (silencioDesde < silencioHasta) return hora >= silencioDesde && hora < silencioHasta;
        return hora >= silencioDesde || hora < silencioHasta;
    }

    /** ids enviados en {@code dia} (vacío si la lista es de otro día) */
    public Set<String> enviados(LocalDate dia) {
        Set<String> r = new LinkedHashSet<>();
        if (enviadosDia == null || !enviadosDia.equals(dia) || enviadosIds == null || enviadosIds.isBlank()) return r;
        for (String s : enviadosIds.split(",")) if (!s.isBlank()) r.add(s.trim());
        return r;
    }

    /** Anota un id como enviado hoy (reinicia la lista si es otro día); recorta si crece demasiado */
    public void marcarEnviado(LocalDate dia, String id) {
        Set<String> s = enviados(dia);
        s.add(id);
        enviadosDia = dia;
        String texto = String.join(",", s);
        while (texto.length() > 1900 && s.size() > 1) {
            s.remove(s.iterator().next());
            texto = String.join(",", s);
        }
        enviadosIds = texto;
    }

    /** ¿Recibe avisos de este tipo? */
    public boolean quiere(String tipo) {
        return switch (tipo) {
            case "PROVEEDOR" -> proveedores;
            case "STOCK"     -> stock;
            case "HORA_PICO" -> horaPico;
            default -> false;
        };
    }
}
