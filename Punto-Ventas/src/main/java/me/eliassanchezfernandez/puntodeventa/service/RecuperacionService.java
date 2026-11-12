package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.servidor.LimitadorIntentos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Recuperación local de la contraseña del administrador (si la olvida).
 *
 * Funciona así:
 *  1. Un administrador, ya dentro del programa, genera un CÓDIGO DE RECUPERACIÓN
 *     (Configuración → Cajeros). Se muestra UNA sola vez; el dueño lo anota y lo
 *     guarda en un lugar seguro (no en la computadora). En la base de datos solo
 *     queda su hash BCrypt, igual que una contraseña.
 *  2. Si algún día olvida su contraseña, en la pantalla de inicio de sesión elige
 *     "¿Olvidaste tu contraseña?", escribe su usuario, el código y una contraseña
 *     nueva. El código es de UN SOLO USO: al usarse queda consumido y hay que
 *     generar otro al entrar.
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
    @Autowired private TransactionTemplate  transaccion;

    private final SecureRandom aleatorio = new SecureRandom();
    private final LimitadorIntentos limitador = new LimitadorIntentos(Clock.systemUTC(), MAX_INTENTOS, VENTANA);

    /** ¿Hay un código vigente? Se consulta desde el login, sin sesión. */
    public boolean hayCodigo() {
        return configuracionService.isHayCodigoRecuperacion();
    }

    /**
     * Genera un código nuevo (el anterior deja de servir) y lo devuelve en claro
     * UNA sola vez. Solo ADMIN; queda en la bitácora (sin el código).
     */
    @Transactional
    public String generarCodigo() {
        sesionService.verificarAdmin();
        String codigo = nuevoCodigo(aleatorio);
        configuracionService.guardarCodigoRecuperacion(passwordEncoder.encode(normalizar(codigo)));
        auditoriaService.registrar(sesionService.getCajeroActual(), "CODIGO_RECUPERACION_GENERADO",
                "Código de recuperación del administrador generado (el anterior, si había, quedó anulado)", (Cajero) null);
        return codigo;
    }

    /** Anula el código vigente sin generar otro. Solo ADMIN. */
    @Transactional
    public void anularCodigo() {
        sesionService.verificarAdmin();
        if (!hayCodigo()) throw new NegocioException("No hay ningún código de recuperación que anular.");
        configuracionService.guardarCodigoRecuperacion(null);
        auditoriaService.registrar(sesionService.getCajeroActual(), "CODIGO_RECUPERACION_ANULADO",
                "Código de recuperación anulado", (Cajero) null);
    }

    /**
     * Desde el inicio de sesión (sin sesión abierta): si el usuario es un
     * administrador activo y el código coincide, fija la contraseña nueva, quita
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
        String hash = configuracionService.obtener().getRecuperacionHash();
        if (hash == null || hash.isBlank()) {
            auditoriaService.registrarIndependiente(nombre, "RECUPERACION_RECHAZADA", "no hay código de recuperación generado");
            throw new NegocioException(MENSAJE_SIN_CODIGO);
        }

        Optional<Cajero> encontrado = cajeroRepo.findByUsuarioIgnoreCase(nombre);
        boolean coincide = passwordEncoder.matches(cod, hash);   // siempre se calcula: mismo tiempo exista o no el usuario
        boolean esAdminActivo = encontrado.isPresent() && encontrado.get().isActivo()
                && encontrado.get().getRol() == Cajero.Rol.ADMIN;
        if (!coincide || !esAdminActivo) {
            limitador.registrarFallo(CLAVE_LIMITE);
            auditoriaService.registrarIndependiente(nombre, "RECUPERACION_RECHAZADA",
                    !coincide ? "código incorrecto" : "el usuario no es un administrador activo");
            throw new NegocioException(MENSAJE_RECHAZO);
        }
        Cajero admin = encontrado.get();

        // El código era correcto: una contraseña nueva inválida NO cuenta como intento fallido
        PoliticaContrasena.validar(nuevaContrasena, admin.getUsuario());
        if (nuevaContrasena.equals(SesionService.ADMIN_CONTRASENA)) {
            throw new NegocioException("Elige una contraseña distinta a la contraseña por defecto.");
        }

        Cajero restablecido = transaccion.execute(estado -> {
            Cajero enBd = cajeroRepo.findById(admin.getId())
                    .orElseThrow(() -> new NegocioException("El usuario ya no existe."));
            enBd.setContrasenaHash(passwordEncoder.encode(nuevaContrasena));
            enBd.setDebeCambiarContrasena(false);
            enBd.setBloqueadoHasta(null);
            enBd.setIntentosFallidos(0);
            Cajero guardado = cajeroRepo.save(enBd);
            configuracionService.guardarCodigoRecuperacion(null);   // de un solo uso
            sesionMovilService.revocarDeCajero(guardado.getId(), "contraseña restablecida con código de recuperación");
            auditoriaService.registrarIndependiente(guardado.getUsuario(), "CONTRASENA_RECUPERADA",
                    "Contraseña del administrador restablecida con el código de recuperación; el código quedó consumido");
            return guardado;
        });
        limitador.olvidar(CLAVE_LIMITE);
        return restablecido;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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
