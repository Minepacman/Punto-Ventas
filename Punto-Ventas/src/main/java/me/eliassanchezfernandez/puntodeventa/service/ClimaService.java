package me.eliassanchezfernandez.puntodeventa.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.ClimaRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

/**
 * Recolección del clima del negocio (Open-Meteo, gratuito y sin clave).
 *
 *  - Cada hora, si "Activar clima" está encendido y hay coordenadas, se pide
 *    la temperatura y el código de tiempo actuales y se guarda UN registro por
 *    hora en clima_registro. Lo único que viaja al servicio son la latitud y
 *    la longitud: ninguna venta sale del equipo.
 *  - OFFLINE FIRST: sin internet nada falla. El error se anota en una línea
 *    del log, la pantalla muestra "Sin datos de clima" y las sugerencias
 *    siguen con día/hora y canasta.
 *  - Categorías (código WMO): Despejado, Nublado, Lluvia, Tormenta, Niebla,
 *    Nieve. Bandas de temperatura: Frío &lt; 15 °C, Templado 15–25, Calor &gt; 25.
 */
@Service
public class ClimaService {

    public static final String URL_BASE = "https://api.open-meteo.com/v1/forecast";
    public static final double FRIO_HASTA = 15.0;
    public static final double CALOR_DESDE = 25.0;
    /** Un registro más viejo que esto ya no se considera "clima actual" */
    static final int VIGENCIA_HORAS = 3;

    public static final String DESPEJADO = "Despejado";
    public static final String NUBLADO   = "Nublado";
    public static final String LLUVIA    = "Lluvia";
    public static final String TORMENTA  = "Tormenta";
    public static final String NIEBLA    = "Niebla";
    public static final String NIEVE     = "Nieve";

    public static final String FRIO     = "Frío";
    public static final String TEMPLADO = "Templado";
    public static final String CALOR    = "Calor";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private ClimaRegistroRepository climaRepo;
    @Autowired private ConfiguracionRepository configuracionRepo;
    @Autowired private Clock reloj;
    @Autowired @Lazy private ClimaService self;

    @Value("${app.estadisticas.jobs:true}")
    private boolean trabajosActivos;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Último problema al consultar (para mostrarlo en Configuración); null si la última consulta salió bien */
    private volatile String ultimoError;

    // ── Mapeos (funciones puras) ─────────────────────────────────────────

    /** Código de tiempo WMO → categoría en español */
    public static String categoriaDe(int codigoWmo) {
        if (codigoWmo == 0 || codigoWmo == 1) return DESPEJADO;
        if (codigoWmo == 2 || codigoWmo == 3) return NUBLADO;
        if (codigoWmo == 45 || codigoWmo == 48) return NIEBLA;
        if (codigoWmo >= 51 && codigoWmo <= 67) return LLUVIA;      // llovizna y lluvia
        if (codigoWmo >= 71 && codigoWmo <= 77) return NIEVE;
        if (codigoWmo >= 80 && codigoWmo <= 82) return LLUVIA;      // chubascos
        if (codigoWmo == 85 || codigoWmo == 86) return NIEVE;
        if (codigoWmo >= 95 && codigoWmo <= 99) return TORMENTA;
        return NUBLADO;
    }

    /** Temperatura en °C → Frío / Templado / Calor */
    public static String bandaDe(double temperatura) {
        if (temperatura < FRIO_HASTA) return FRIO;
        if (temperatura > CALOR_DESDE) return CALOR;
        return TEMPLADO;
    }

    public static boolean esLluvioso(String categoria) {
        return LLUVIA.equals(categoria) || TORMENTA.equals(categoria);
    }

    /** Ícono para la pantalla */
    public static String iconoDe(String categoria) {
        if (categoria == null) return "☁";
        return switch (categoria) {
            case DESPEJADO -> "☀";
            case LLUVIA    -> "🌧";
            case TORMENTA  -> "⛈";
            case NIEBLA    -> "🌫";
            case NIEVE     -> "❄";
            default        -> "☁";
        };
    }

    /** Lectura cruda del servicio */
    public record Lectura(LocalDateTime hora, double temperatura, int codigo, double precipitacion) {
        public String categoria() { return categoriaDe(codigo); }
        public String banda()     { return bandaDe(temperatura); }
    }

    /**
     * Interpreta la respuesta de Open-Meteo:
     * {"current":{"time":"2026-10-26T15:00","temperature_2m":22.4,"weather_code":3,"precipitation":0.0}}
     */
    public static Lectura parsear(String json) throws IOException {
        JsonNode raiz = JSON.readTree(json);
        JsonNode actual = raiz.path("current");
        if (actual.isMissingNode() || !actual.has("temperature_2m") || !actual.has("weather_code")) {
            throw new IOException("Respuesta del servicio de clima sin datos actuales.");
        }
        LocalDateTime hora;
        try {
            hora = LocalDateTime.parse(actual.path("time").asText());
        } catch (RuntimeException e) {
            hora = null;   // el llamador usa la hora local
        }
        return new Lectura(hora, actual.get("temperature_2m").asDouble(), actual.get("weather_code").asInt(),
                actual.path("precipitation").asDouble(0));
    }

    /** Tope de la respuesta del servicio (una lectura "current" pesa menos de 1 KB) */
    static final int MAX_RESPUESTA_BYTES = 64 * 1024;

    /** Coordenadas válidas: finitas, latitud -90..90, longitud -180..180 */
    static void validarCoordenadas(double latitud, double longitud) {
        if (!Double.isFinite(latitud) || latitud < -90 || latitud > 90) {
            throw new IllegalArgumentException("La latitud debe estar entre -90 y 90.");
        }
        if (!Double.isFinite(longitud) || longitud < -180 || longitud > 180) {
            throw new IllegalArgumentException("La longitud debe estar entre -180 y 180.");
        }
    }

    static URI uriPara(double latitud, double longitud) {
        validarCoordenadas(latitud, longitud);
        return URI.create(String.format(Locale.ROOT,
                "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,precipitation&timezone=auto",
                URL_BASE, latitud, longitud));
    }

    // ── Consulta en línea ────────────────────────────────────────────────

    /** Llama al servicio con estas coordenadas (lo usa "Probar" en Configuración). Lanza si no hay conexión. */
    public Lectura consultar(double latitud, double longitud) throws IOException {
        try {
            validarCoordenadas(latitud, longitud);
        } catch (IllegalArgumentException e) {
            ultimoError = e.getMessage();
            throw new IOException(e.getMessage(), e);
        }
        HttpRequest req = HttpRequest.newBuilder(uriPara(latitud, longitud))
                .timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json")
                .GET().build();
        try {
            HttpResponse<java.io.InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream cuerpo = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new IOException("El servicio de clima respondió " + resp.statusCode() + ".");
                }
                byte[] bytes = cuerpo.readNBytes(MAX_RESPUESTA_BYTES + 1);
                if (bytes.length > MAX_RESPUESTA_BYTES) {
                    throw new IOException("La respuesta del servicio de clima es demasiado grande.");
                }
                return aceptar(parsear(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Consulta de clima interrumpida.", e);
        } catch (IOException | RuntimeException e) {
            ultimoError = e.getMessage();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private Lectura aceptar(Lectura l) {
        ultimoError = null;
        return l;
    }

    /**
     * Toma el clima de ahora y lo guarda como el registro de esta hora (si
     * el clima está activo). Nunca lanza: sin internet regresa vacío y deja
     * una línea en el log.
     * Sin @Transactional: la llamada de red no debe mantener abierta una
     * transacción ni una conexión de la base de datos.
     */
    public Optional<ClimaRegistro> registrarAhora() {
        Configuracion c = configuracion();
        if (!c.isClimaActivo()) return Optional.empty();
        try {
            Lectura l = consultar(c.getLatitud(), c.getLongitud());
            return Optional.of(self.guardar(l, LocalDateTime.now(reloj)));
        } catch (IOException | RuntimeException e) {
            System.err.println("Clima: sin datos (" + e.getMessage() + "). La aplicación sigue sin clima.");
            return Optional.empty();
        }
    }

    /** Guarda (o reemplaza) el registro de la hora de la lectura */
    @Transactional
    public ClimaRegistro guardar(Lectura l, LocalDateTime horaLocal) {
        LocalDateTime hora = horaLocal.truncatedTo(ChronoUnit.HOURS);
        ClimaRegistro r = climaRepo.findByFechaHora(hora).orElseGet(() -> new ClimaRegistro(hora, 0, 0, NUBLADO, TEMPLADO));
        r.setTemperatura(l.temperatura());
        r.setCodigo(l.codigo());
        r.setCategoria(l.categoria());
        r.setBanda(l.banda());
        return climaRepo.save(r);
    }

    // ── Lectura local (sin red) ──────────────────────────────────────────

    /** Clima "actual": el último registro guardado si tiene menos de VIGENCIA_HORAS */
    public Optional<ClimaRegistro> actual() {
        LocalDateTime limite = LocalDateTime.now(reloj).minusHours(VIGENCIA_HORAS);
        return climaRepo.findFirstByOrderByFechaHoraDesc().filter(r -> !r.getFechaHora().isBefore(limite));
    }

    /** Clima registrado para la hora dada */
    public Optional<ClimaRegistro> climaDe(LocalDateTime fechaHora) {
        return climaRepo.findByFechaHora(fechaHora.truncatedTo(ChronoUnit.HOURS));
    }

    public boolean estaActivo() {
        return configuracion().isClimaActivo();
    }

    public Optional<String> ultimoError() {
        return Optional.ofNullable(ultimoError);
    }

    // ── Trabajos programados ─────────────────────────────────────────────

    /** Cada hora, a los 5 minutos, mientras el clima esté activo */
    @Scheduled(cron = "0 5 * * * *")
    public void trabajoHorario() {
        if (!trabajosActivos) return;
        self.registrarAhora();
    }

    /** Al arrancar se toma el registro de la hora actual (en segundo plano) */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!trabajosActivos) return;
        Thread t = new Thread(() -> {
            try {
                self.registrarAhora();
            } catch (RuntimeException e) {
                System.err.println("Clima: no se pudo registrar al arrancar (" + e.getMessage() + ").");
            }
        }, "clima-arranque");
        t.setDaemon(true);
        t.start();
    }

    private Configuracion configuracion() {
        return configuracionRepo.findById(Configuracion.ID_UNICO).orElseGet(Configuracion::new);
    }
}
