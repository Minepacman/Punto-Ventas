package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.servidor.LimitadorIntentos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Recuperación local de la contraseña del administrador (si la olvida).
 *
 * Funciona así:
 *  1. Un administrador, ya dentro del programa, genera SU CÓDIGO DE RECUPERACIÓN
 *     (Configuración → Cajeros). Se muestra UNA sola vez; el dueño lo anota y lo
 *     guarda en un lugar seguro (no en la computadora). En la base de datos solo
 *     queda su hash BCrypt, igual que una contraseña, en la fila de ESE
 *     administrador ({@code cajeros.recuperacion_hash}).
 *  2. Si algún día olvida su contraseña, en la pantalla de inicio de sesión elige
 *     "¿Olvidaste tu contraseña?", escribe su usuario, el código y una contraseña
 *     nueva. El código es de UN SOLO USO: al usarse queda consumido y hay que
 *     generar otro al entrar.
 *
 * El código está LIGADO al administrador que lo generó: solo restablece la
 * contraseña de ese usuario (el de otro administrador no sirve, aunque sea
 * correcto), cada administrador puede tener el suyo, y se anula cuando a su dueño
 * lo desactivan, lo eliminan, lo degradan o le restablece la contraseña OTRO
 * administrador ({@link #anularAlCambiarAdministrador}, lo llama CajeroService).
 * Cambiar la propia contraseña no lo toca. Al arrancar tras restaurar un respaldo
 * se anulan todos ({@link #anularTrasRestauracion}): la base restaurada podría
 * traer un código ya consumido o anulado.
 *
 * Compatibilidad: hasta la 1.0.3 el hash vivía en {@code configuracion.recuperacion_hash}
 * sin dueño ("código heredado"). Se acepta UNA vez y solo si quien lo usa es el
 * único administrador activo (entonces no hay a quién más pudiera pertenecer); con
 * dos o más administradores se rechaza y hay que generar uno nuevo. Cualquier
 * generación, anulación, cambio de administrador o restauración lo borra.
 *
 * Seguridad: el código tiene 16 caracteres de un alfabeto de 31 (≈ 2^79), solo
 * sirve para usuarios ADMIN activos, cada intento fallido queda en la bitácora
 * y tras {@value #MAX_INTENTOS} fallos hay que esperar {@link #VENTANA}. Sin código
 * generado no hay forma de recuperar desde el programa (queda documentado en
 * docs/recuperacion-administrador.md).
 */
@Service
public class RecuperacionService {

    /** Alfabeto sin 0/O, 1/I/L: el dueño lo copia a mano sin confundirse */
    static final String ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int GRUPOS = 4;
    static final int LARGO_GRUPO = 4;

    public static final int MAX_INTENTOS = 5;
    public static final Duration VENTANA = Duration.ofMinutes(15);
    private static final String CLAVE_LIMITE = "recuperacion";

    /**
     * Propiedad del sistema que el Launcher fija cuando acaba de poner un respaldo
     * como base de datos (RutasApp.aplicarRestauracionPendiente). Al arrancar Spring,
     * {@link #anularTrasRestauracion} la lee y anula los códigos que trajera la base.
     */
    public static final String PROP_RESPALDO_APLICADO = "app.respaldo.aplicado";

    public static final String MENSAJE_SIN_CODIGO =
            "No hay un código de recuperación generado. Pide a otro administrador que restablezca tu contraseña "
            + "(Configuración → Cajeros) o consulta docs/recuperacion-administrador.md.";
    public static final String MENSAJE_RECHAZO = "El usuario o el código de recuperación no son correctos.";

    @Autowired private CajeroRepository     cajeroRepo;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaService     auditoriaService;
    @Autowired private SesionService        sesionService;
    @Autowired private SesionMovilService   sesionMovilService;
    @Autowired private ConexionService      conexionService;
    @Autowired private TransactionTemplate  transaccion;

    private final SecureRandom aleatorio = new SecureRandom();
    private final LimitadorIntentos limitador = new LimitadorIntentos(Clock.systemUTC(), MAX_INTENTOS, VENTANA);
    /** Hash contra el que se compara cuando no hay código aplicable: mismo tiempo de respuesta */
    private volatile String hashFicticio;

    // ── Consultas ─────────────────────────────────────────────────────────

    /** ¿Hay algún código vigente (propio de un administrador activo o heredado)? Se consulta desde el login, sin sesión. */
    public boolean hayCodigo() {
        return cajeroRepo.existsByRolAndActivoTrueAndRecuperacionHashIsNotNull(Cajero.Rol.ADMIN) || hayCodigoHeredado();
    }

    /** ¿El administrador en sesión tiene su propio código vigente? */
    public boolean hayCodigoPropio() {
        Cajero actual = sesionService.getCajeroActual();
        if (actual == null) return false;
        return cajeroRepo.findById(actual.getId()).map(Cajero::tieneCodigoRecuperacion).orElse(false);
    }

    /** ¿Queda un código de la versión 1.0.3 o anterior (sin dueño, en configuracion)? */
    public boolean hayCodigoHeredado() {
        return configuracionService.isHayCodigoRecuperacion();
    }

    // ── Generar y anular (administrador en sesión) ────────────────────────

    /**
     * Genera un código nuevo para el administrador en sesión (su código anterior,
     * y el heredado si existía, dejan de servir) y lo devuelve en claro UNA sola
     * vez. Solo ADMIN; queda en la bitácora (sin el código).
     */
    @Transactional
    public String generarCodigo() {
        sesionService.verificarAdmin();
        Cajero enBd = administradorEnBd();
        boolean teniaPropio = enBd.tieneCodigoRecuperacion();
        boolean habiaHeredado = hayCodigoHeredado();

        String codigo = nuevoCodigo(aleatorio);
        enBd.setRecuperacionHash(passwordEncoder.encode(normalizar(codigo)));
        enBd.setRecuperacionGenerado(LocalDateTime.now());
        cajeroRepo.save(enBd);
        if (habiaHeredado) configuracionService.guardarCodigoRecuperacion(null);
        sesionService.refrescarSesion();

        auditoriaService.registrar(sesionService.getCajeroActual(), "CODIGO_RECUPERACION_GENERADO",
                "Código de recuperación de " + enBd.getUsuario() + " generado (solo restablece su contraseña)"
                + (teniaPropio ? "; su código anterior quedó anulado" : "")
                + (habiaHeredado ? "; el código de la versión anterior (sin dueño) quedó anulado" : ""), (Cajero) null);
        return codigo;
    }

    /** Anula el código propio del administrador en sesión (y el heredado, si queda) sin generar otro. Solo ADMIN. */
    @Transactional
    public void anularCodigo() {
        sesionService.verificarAdmin();
        Cajero enBd = administradorEnBd();
        boolean habiaPropio = enBd.quitarCodigoRecuperacion();
        boolean habiaHeredado = hayCodigoHeredado();
        if (!habiaPropio && !habiaHeredado) throw new NegocioException("No tienes ningún código de recuperación que anular.");
        cajeroRepo.save(enBd);
        if (habiaHeredado) configuracionService.guardarCodigoRecuperacion(null);
        sesionService.refrescarSesion();
        auditoriaService.registrar(sesionService.getCajeroActual(), "CODIGO_RECUPERACION_ANULADO",
                "Código de recuperación de " + enBd.getUsuario() + " anulado por él mismo"
                + (habiaHeredado ? " (también el de la versión anterior, sin dueño)" : ""), (Cajero) null);
    }

    // ── Anulaciones automáticas ───────────────────────────────────────────

    /**
     * Lo llama CajeroService (dentro de su transacción, ANTES de guardar o borrar
     * la fila) cuando a un administrador lo desactivan, eliminan, degradan o le
     * restablece la contraseña otro administrador: su código ya no debe servir.
     * También borra el código heredado (sin dueño: pudo ser suyo). Deja los
     * campos en null en la entidad recibida; el que la guarda es el llamador.
     * @return true si había algún código y se anuló (queda en la bitácora)
     */
    public boolean anularAlCambiarAdministrador(Cajero admin, String motivo) {
        boolean habiaPropio = admin.quitarCodigoRecuperacion();
        boolean habiaHeredado = hayCodigoHeredado();
        if (habiaHeredado) configuracionService.guardarCodigoRecuperacion(null);
        if (!habiaPropio && !habiaHeredado) return false;
        auditoriaService.registrar(sesionService.getCajeroActual(), "CODIGO_RECUPERACION_ANULADO",
                (habiaPropio ? "Código de recuperación de " + admin.getUsuario() + " anulado" : "Código de recuperación anulado")
                + ": " + motivo
                + (habiaHeredado ? " (incluye el código de la versión anterior, sin dueño)" : ""), (Cajero) null);
        return true;
    }

    /**
     * Al arrancar después de que el Launcher aplicó un respaldo (datos.db.restaurar):
     * la base restaurada puede traer códigos ya consumidos o anulados desde entonces,
     * o de administradores que ya no lo eran, así que se anulan TODOS (propios y
     * heredado). Corre en el acto, no en ArranqueDiferido: es una sola actualización
     * y debe estar hecha antes de que el login pueda aceptar un código. Nunca lanza.
     * @return cuántos códigos propios se anularon (−1 si no había restauración)
     */
    public int anularTrasRestauracion() {
        if (!Boolean.getBoolean(PROP_RESPALDO_APLICADO)) return -1;
        System.clearProperty(PROP_RESPALDO_APLICADO);
        try {
            Integer anulados = transaccion.execute(estado -> {
                List<Cajero> conCodigo = cajeroRepo.findByRecuperacionHashIsNotNull();
                boolean habiaHeredado = hayCodigoHeredado();
                if (conCodigo.isEmpty() && !habiaHeredado) return 0;
                for (Cajero c : conCodigo) {
                    c.quitarCodigoRecuperacion();
                    cajeroRepo.save(c);
                }
                if (habiaHeredado) configuracionService.guardarCodigoRecuperacion(null);
                auditoriaService.registrarSistema("CODIGO_RECUPERACION_ANULADO",
                        "Respaldo restaurado: se anularon los códigos de recuperación que traía la base"
                        + (conCodigo.isEmpty() ? "" : " (de " + conCodigo.stream().map(Cajero::getUsuario)
                                .collect(Collectors.joining(", ")) + ")")
                        + (habiaHeredado ? " y el de la versión anterior" : "")
                        + "; cada administrador debe generar el suyo de nuevo");
                return conCodigo.size();
            });
            return anulados == null ? 0 : anulados;
        } catch (RuntimeException e) {
            System.err.println("No se pudieron anular los códigos de recuperación tras restaurar el respaldo: " + e.getMessage());
            return 0;
        }
    }

    /** Sin valor de retorno a propósito: lo que devuelve un @EventListener se publicaría como otro evento */
    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        anularTrasRestauracion();
    }

    // ── Uso desde el inicio de sesión ─────────────────────────────────────

    /**
     * Desde el inicio de sesión (sin sesión abierta): si el usuario es un
     * administrador activo y el código es EL SUYO, fija la contraseña nueva, quita
     * el bloqueo por intentos y consume el código. No abre sesión: el
     * administrador entra después con su contraseña nueva.
     *
     * No es @Transactional a propósito: el registro de un intento fallido debe
     * quedar en la bitácora aunque se lance la excepción; el éxito se guarda
     * completo o nada dentro de transaccion.execute().
     */
    public Cajero restablecerConCodigo(String usuario, String codigo, String nuevaContrasena) {
        String nombre = usuario == null ? "" : usuario.trim();
        if (nombre.length() > 60) nombre = nombre.substring(0, 60);
        String cod = normalizar(codigo);
        if (nombre.isEmpty() || cod.isEmpty()) {
            throw new NegocioException("Captura el usuario del administrador y el código de recuperación.");
        }
        long espera = limitador.segundosDeEspera(CLAVE_LIMITE);
        if (espera > 0) {
            auditoriaService.registrarIndependiente(nombre, "RECUPERACION_LIMITADA",
                    "Demasiados intentos con el código de recuperación; espera " + espera + " s");
            throw new NegocioException("Demasiados intentos. Espera " + Math.max(1, (espera + 59) / 60)
                    + " minuto(s) antes de volver a intentarlo.");
        }
        if (!hayCodigo()) {
            auditoriaService.registrarIndependiente(nombre, "RECUPERACION_RECHAZADA", "no hay código de recuperación generado");
            throw new NegocioException(MENSAJE_SIN_CODIGO);
        }

        Optional<Cajero> encontrado = cajeroRepo.findByUsuarioIgnoreCase(nombre);
        boolean esAdminActivo = encontrado.isPresent() && encontrado.get().isActivo()
                && encontrado.get().getRol() == Cajero.Rol.ADMIN;

        // Qué hash se compara: el PROPIO de ese administrador; si no tiene, el heredado de 1.0.3
        // solo cuando es el único administrador activo (no hay a quién más pudiera pertenecer).
        String hash = null;
        boolean heredado = false;
        String motivo;
        if (!esAdminActivo) {
            motivo = "el usuario no es un administrador activo";
        } else if (encontrado.get().tieneCodigoRecuperacion()) {
            hash = encontrado.get().getRecuperacionHash();
            motivo = "el código no es el de este administrador o está mal escrito";
        } else if (hayCodigoHeredado() && cajeroRepo.countByRolAndActivoTrue(Cajero.Rol.ADMIN) == 1) {
            hash = configuracionService.obtener().getRecuperacionHash();
            heredado = true;
            motivo = "código incorrecto (se comparó con el de la versión anterior)";
        } else {
            motivo = hayCodigoHeredado()
                    ? "el administrador no tiene código propio y el de la versión anterior solo vale con un único administrador activo"
                    : "el administrador no tiene código de recuperación propio";
        }
        // Siempre se calcula un BCrypt: mismo tiempo exista o no el usuario o su código
        boolean coincide = passwordEncoder.matches(cod, hash != null ? hash : hashFicticio());
        if (hash == null || !coincide) {
            limitador.registrarFallo(CLAVE_LIMITE);
            auditoriaService.registrarIndependiente(nombre, "RECUPERACION_RECHAZADA", motivo);
            throw new NegocioException(MENSAJE_RECHAZO);
        }
        Cajero admin = encontrado.get();

        // El código era correcto: una contraseña nueva inválida NO cuenta como intento fallido
        PoliticaContrasena.validar(nuevaContrasena, admin.getUsuario());
        if (nuevaContrasena.equals(SesionService.ADMIN_CONTRASENA)) {
            throw new NegocioException("Elige una contraseña distinta a la contraseña por defecto.");
        }

        boolean usoHeredado = heredado;
        String hashAnterior = admin.getContrasenaHash();   // antes de la transacción: puede ser la misma instancia que se modifica
        Cajero restablecido = transaccion.execute(estado -> {
            Cajero enBd = cajeroRepo.findById(admin.getId())
                    .orElseThrow(() -> new NegocioException("El usuario ya no existe."));
            enBd.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
            enBd.setDebeCambiarContrasena(false);
            enBd.setBloqueadoHasta(null);
            enBd.setIntentosFallidos(0);
            enBd.quitarCodigoRecuperacion();                                     // de un solo uso
            Cajero guardado = cajeroRepo.save(enBd);
            if (usoHeredado) configuracionService.guardarCodigoRecuperacion(null);   // el heredado también se consume
            sesionMovilService.revocarDeCajero(guardado.getId(), "contraseña restablecida con código de recuperación");
            auditoriaService.registrarIndependiente(guardado.getUsuario(), "CONTRASENA_RECUPERADA",
                    "Contraseña del administrador restablecida con su código de recuperación; el código quedó consumido"
                    + (usoHeredado ? " (código de la versión anterior, sin dueño: se aceptó por ser el único administrador activo)" : ""));
            return guardado;
        });
        // Su contraseña anterior ya no debe abrir la pantalla "sin conexión" de esta caja; al entrar con la nueva
        // se vuelve a recordar (LoginController → ConexionService.recordarAdministrador)
        conexionService.olvidarAdministradorSi(hashAnterior);
        limitador.olvidar(CLAVE_LIMITE);
        return restablecido;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Fila actual del administrador en sesión (el objeto de la sesión puede estar desligado de JPA) */
    private Cajero administradorEnBd() {
        Cajero actual = sesionService.getCajeroActual();
        return cajeroRepo.findById(actual.getId())
                .orElseThrow(() -> new NegocioException("El usuario ya no existe."));
    }

    private String hashFicticio() {
        String h = hashFicticio;
        if (h == null) hashFicticio = h = passwordEncoder.encode("codigo-ficticio-sin-dueno");
        return h;
    }

    /** Solo para pruebas: olvida los intentos fallidos (viven en memoria, no en la transacción) */
    void reiniciarLimiteParaPruebas() {
        limitador.olvidar(CLAVE_LIMITE);
    }

    /** "ABCD-EFGH-JKMN-PQRS": 4 grupos de 4 del alfabeto, separados por guiones */
    static String nuevoCodigo(SecureRandom aleatorio) {
        StringBuilder sb = new StringBuilder(GRUPOS * (LARGO_GRUPO + 1));
        for (int g = 0; g < GRUPOS; g++) {
            if (g > 0) sb.append('-');
            for (int i = 0; i < LARGO_GRUPO; i++) {
                sb.append(ALFABETO.charAt(aleatorio.nextInt(ALFABETO.length())));
            }
        }
        return sb.toString();
    }

    /** Mayúsculas y sin guiones ni espacios: el dueño puede teclearlo como quiera */
    static String normalizar(String codigo) {
        if (codigo == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : codigo.toUpperCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch)) sb.append(ch);
        }
        return sb.toString();
    }
}
