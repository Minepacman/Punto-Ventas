package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.CorteRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.MovimientoInventarioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Base de datos SQLite: información, respaldo manual, restauración y respaldo
 * automático (CFG-04).
 *
 * - Los respaldos usan VACUUM INTO (copia consistente, aun con la app abierta).
 *   NO deben llamarse dentro de una transacción de SQLite, por eso estos
 *   métodos no son @Transactional.
 * - Restaurar NO reemplaza el archivo en uso: valida el respaldo, guarda una
 *   copia de seguridad de la base actual y deja el respaldo como
 *   datos.db.restaurar; RutasApp.aplicarRestauracionPendiente lo pone en su
 *   lugar al reiniciar. Pide la contraseña del administrador en sesión.
 * - Respaldo automático: un archivo por día (respaldo-auto-AAAAMMDD.db) en la
 *   carpeta elegida, al primer inicio de sesión del día y/o al cerrar la app;
 *   se conservan solo las N copias más recientes.
 */
@Service
public class BaseDatosService {

    private static final byte[] CABECERA_SQLITE = "SQLite format 3\0".getBytes(StandardCharsets.ISO_8859_1);
    /** Tablas que debe tener una base de datos de este programa */
    private static final List<String> TABLAS_REQUERIDAS =
            List.of("configuracion", "cajeros", "productos", "ventas", "detalle_ventas");

    private static final DateTimeFormatter FMT_DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FMT_MARCA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern ARCHIVO_AUTO = Pattern.compile("respaldo-auto-\\d{8}\\.db");

    @Autowired private DataSource dataSource;
    @Autowired private RutasApp rutas;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private CorteRepository corteRepo;
    @Autowired private DepartamentoRepository departamentoRepo;
    @Autowired private MovimientoInventarioRepository movimientoRepo;

    /** Datos que muestra la pantalla Base de datos */
    public record Informacion(Path archivo, long bytes, Map<String, Long> registros) { }

    public Informacion informacion() {
        sesionService.verificarAdmin();
        Path archivo = rutas.archivoBd();
        long bytes = 0;
        try {
            if (Files.exists(archivo)) bytes = Files.size(archivo);
        } catch (IOException ignorada) { /* se muestra 0 */ }
        Map<String, Long> n = new LinkedHashMap<>();
        n.put("Productos", productoRepo.count());
        n.put("Departamentos", departamentoRepo.count());
        n.put("Ventas", ventaRepo.count());
        n.put("Usuarios", cajeroRepo.count());
        n.put("Cortes", corteRepo.count());
        n.put("Movimientos de inventario", movimientoRepo.count());
        return new Informacion(archivo, bytes, n);
    }

    // ── Respaldo manual ───────────────────────────────────────────────────

    /** Copia la base de datos al archivo elegido (si existe, se reemplaza) */
    public Path respaldar(Path destino) {
        sesionService.verificarAdmin();
        if (destino == null) throw new NegocioException("Elige dónde guardar el respaldo.");
        destino = destino.toAbsolutePath();
        if (destino.equals(rutas.archivoBd())) {
            throw new NegocioException("El respaldo no puede sobrescribir la base de datos en uso.");
        }
        if (!destino.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".db")) {
            throw new NegocioException("El respaldo debe guardarse en un archivo con extensión .db.");
        }
        if (Files.isSymbolicLink(destino)) {
            throw new NegocioException("El destino del respaldo no puede ser un enlace simbólico.");
        }
        if (Files.exists(destino) && !esArchivoSqlite(destino)) {
            throw new NegocioException("Ese archivo ya existe y no es una base de datos SQLite; no se sobrescribe.");
        }
        copiarBase(destino);
        auditoriaService.registrar(sesionService.getCajeroActual(), "RESPALDO_MANUAL",
                destino.toString(), (Cajero) null);
        return destino;
    }

    /** ¿Empieza con la cabecera "SQLite format 3"? (un respaldo anterior sí se puede reemplazar) */
    static boolean esArchivoSqlite(Path archivo) {
        if (!Files.isRegularFile(archivo)) return false;
        try (InputStream in = Files.newInputStream(archivo)) {
            byte[] cab = in.readNBytes(CABECERA_SQLITE.length);
            return java.util.Arrays.equals(cab, CABECERA_SQLITE);
        } catch (IOException e) {
            return false;
        }
    }

    // ── Restauración ──────────────────────────────────────────────────────

    /**
     * Valida el respaldo, guarda una copia de seguridad de la base actual y
     * deja el respaldo listo para aplicarse al reiniciar.
     * @return la copia de seguridad de la base que estaba en uso
     */
    public Path restaurar(Path respaldo, String contrasenaAdmin) {
        sesionService.verificarAdmin();
        Cajero admin = sesionService.getCajeroActual();
        try {
            sesionService.verificarCredenciales(admin.getUsuario(), contrasenaAdmin);
        } catch (SesionService.CredencialesInvalidasException e) {
            throw new NegocioException(e.getMessage().startsWith("Usuario bloqueado")
                    ? e.getMessage() : "La contraseña del administrador es incorrecta.");
        }
        if (respaldo == null) throw new NegocioException("Elige el archivo de respaldo.");
        respaldo = respaldo.toAbsolutePath();
        if (respaldo.equals(rutas.archivoBd())) {
            throw new NegocioException("Elige un respaldo distinto de la base de datos en uso.");
        }
        validarRespaldo(respaldo);

        Path seguridad = RutasApp.hermano(rutas.archivoBd(), ".antes-de-restaurar-" + LocalDateTime.now().format(FMT_MARCA));
        copiarBase(seguridad);
        try {
            Files.copy(respaldo, rutas.archivoRestauracionPendiente(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new NegocioException("No se pudo preparar la restauración: " + e.getMessage());
        }
        auditoriaService.registrar(admin, "RESPALDO_RESTAURADO",
                "respaldo " + respaldo + "; copia de seguridad previa " + seguridad, (Cajero) null);
        return seguridad;
    }

    /** ¿Hay una restauración esperando al reinicio? */
    public boolean hayRestauracionPendiente() {
        return Files.isRegularFile(rutas.archivoRestauracionPendiente());
    }

    /**
     * El archivo debe ser SQLite, íntegro y de este programa (tener sus tablas
     * principales). Se abre en modo solo lectura.
     */
    void validarRespaldo(Path archivo) {
        if (!Files.isRegularFile(archivo)) throw new NegocioException("El archivo de respaldo no existe.");
        byte[] cabecera = new byte[CABECERA_SQLITE.length];
        try (InputStream in = Files.newInputStream(archivo)) {
            if (in.readNBytes(cabecera, 0, cabecera.length) < cabecera.length
                    || !java.util.Arrays.equals(cabecera, CABECERA_SQLITE)) {
                throw new NegocioException("El archivo no es una base de datos SQLite.");
            }
        } catch (IOException e) {
            throw new NegocioException("No se pudo leer el respaldo: " + e.getMessage());
        }
        Properties props = new Properties();
        props.setProperty("open_mode", "1");   // SQLITE_OPEN_READONLY
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + archivo, props);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new NegocioException("El respaldo está dañado (falló la revisión de integridad).");
                }
            }
            List<String> tablas = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (rs.next()) tablas.add(rs.getString(1));
            }
            if (!tablas.containsAll(TABLAS_REQUERIDAS)) {
                throw new NegocioException("El archivo es una base SQLite, pero no es un respaldo de este Punto de Venta.");
            }
            // Este programa no crea disparadores ni vistas: un respaldo que los traiga es ajeno (o manipulado)
            // y se ejecutarían solos al escribir en la base restaurada.
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type IN ('trigger', 'view')")) {
                if (rs.next() && rs.getLong(1) > 0) {
                    throw new NegocioException("El respaldo contiene disparadores o vistas que este programa no usa; no se restaura.");
                }
            }
            // Debe quedar al menos un administrador activo con el que entrar
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cajeros WHERE rol = 'ADMIN' AND activo = 1")) {
                if (rs.next() && rs.getLong(1) < 1) {
                    throw new NegocioException("El respaldo no tiene ningún administrador activo; no se restaura.");
                }
            }
        } catch (SQLException e) {
            throw new NegocioException("El archivo no es una base de datos válida: " + e.getMessage());
        }
    }

    // ── Respaldo automático ───────────────────────────────────────────────

    /** Primer inicio de sesión del día: respalda si está activo y hoy no se ha hecho */
    public Optional<Path> respaldoAutomaticoDelDia() {
        Configuracion c = configuracionService.obtener();
        if (!c.isRespaldoAuto() || c.getRespaldoCarpeta() == null) return Optional.empty();
        if (Files.exists(archivoAutomatico(Path.of(c.getRespaldoCarpeta()), LocalDate.now()))) {
            return Optional.empty();
        }
        return respaldoAutomatico("inicio de sesión");
    }

    /** Al cerrar la app: deja el respaldo de hoy con los últimos cambios */
    public Optional<Path> respaldoAutomaticoAlCerrar() {
        Configuracion c = configuracionService.obtener();
        if (!c.isRespaldoAuto() || c.getRespaldoCarpeta() == null) return Optional.empty();
        return respaldoAutomatico("cierre");
    }

    /** Nunca lanza: un respaldo fallido no debe impedir iniciar sesión ni cerrar */
    private synchronized Optional<Path> respaldoAutomatico(String motivo) {
        Configuracion c = configuracionService.obtener();
        Path carpeta = Path.of(c.getRespaldoCarpeta());
        try {
            Files.createDirectories(carpeta);
            LocalDate hoy = LocalDate.now();
            Path destino = archivoAutomatico(carpeta, hoy);
            Path temporal = destino.resolveSibling(destino.getFileName() + ".tmp");
            copiarBase(temporal);
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
            conservarUltimas(carpeta, c.getRespaldoCopias());
            configuracionService.marcarRespaldoAutomatico(hoy);
            auditoriaService.registrarSistema("RESPALDO_AUTOMATICO", motivo + ": " + destino);
            return Optional.of(destino);
        } catch (Exception e) {
            System.err.println("Respaldo automático fallido: " + e.getMessage());
            try {
                auditoriaService.registrarSistema("RESPALDO_AUTOMATICO_FALLIDO", motivo + ": " + e.getMessage());
            } catch (Exception ignorada) { /* sin bitácora tampoco se detiene */ }
            return Optional.empty();
        }
    }

    /** Borra los respaldos automáticos más viejos, dejando solo los `copias` más recientes */
    public void conservarUltimas(Path carpeta, int copias) throws IOException {
        List<Path> archivos;
        try (Stream<Path> s = Files.list(carpeta)) {
            archivos = s.filter(p -> ARCHIVO_AUTO.matcher(p.getFileName().toString()).matches())
                    .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        for (int i = Math.max(1, copias); i < archivos.size(); i++) {
            Files.deleteIfExists(archivos.get(i));
        }
    }

    static Path archivoAutomatico(Path carpeta, LocalDate dia) {
        return carpeta.resolve("respaldo-auto-" + dia.format(FMT_DIA) + ".db");
    }

    // ── Copia con VACUUM INTO ─────────────────────────────────────────────

    private void copiarBase(Path destino) {
        try {
            if (destino.getParent() != null) Files.createDirectories(destino.getParent());
            Files.deleteIfExists(destino);   // VACUUM INTO exige que no exista
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                st.execute("VACUUM INTO '" + destino.toString().replace("'", "''") + "'");
            }
        } catch (IOException | SQLException e) {
            throw new NegocioException("No se pudo crear el respaldo: " + e.getMessage());
        }
    }
}
