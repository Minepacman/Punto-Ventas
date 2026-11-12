package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que pasa justo después de iniciar sesión, antes de trabajar:
 *
 *  1. ASISTENTE_INICIAL — solo ADMIN y una sola vez por instalación: nombre,
 *     logotipo y ubicación del negocio. La marca vive en {@code configuracion}
 *     ({@code asistente_inicial_hecho}); omitirlo también la deja puesta (los
 *     datos se pueden capturar después en Configuración).
 *  2. TUTORIAL_GENERAL — recorrido por las pestañas, una vez por usuario
 *     ({@code cajeros.tutorial_general_visto}); se puede volver a abrir desde
 *     la barra de estado. Independiente del tutorial de Estadísticas.
 *  3. DONACION — cada vez que un ADMIN abre la ventana principal.
 *
 * El orden lo decide {@link #pasos}, que es Java puro para poder probarlo.
 * Requiere sesión; los cambios de configuración exigen ADMIN y se auditan.
 */
@Service
public class BienvenidaService {

    public enum Paso { ASISTENTE_INICIAL, TUTORIAL_GENERAL, DONACION }

    public static final int NOMBRE_NEGOCIO_MAXIMO = 150;

    @Autowired private CajeroRepository        cajeroRepo;
    @Autowired private ConfiguracionRepository configuracionRepo;
    @Autowired private ConfiguracionService    configuracionService;
    @Autowired private SesionService           sesionService;
    @Autowired private AuditoriaService        auditoriaService;
    @Autowired private DonadorService          donadorService;

    // ── Orden del flujo ───────────────────────────────────────────────────

    /**
     * Pasos que faltan, en orden, para un usuario con ese rol.
     * ADMIN: asistente (si no está hecho) → tutorial (si no lo vio) → donación (salvo que el negocio ya sea donador).
     * GERENTE y CAJERO: solo el tutorial la primera vez.
     */
    public static List<Paso> pasos(Cajero.Rol rol, boolean asistenteHecho, boolean tutorialVisto, boolean donador) {
        List<Paso> lista = new ArrayList<>();
        boolean admin = rol == Cajero.Rol.ADMIN;
        if (admin && !asistenteHecho) lista.add(Paso.ASISTENTE_INICIAL);
        if (!tutorialVisto) lista.add(Paso.TUTORIAL_GENERAL);
        if (admin && !donador) lista.add(Paso.DONACION);
        return lista;
    }

    /** Compatibilidad: negocio sin clave de donador */
    public static List<Paso> pasos(Cajero.Rol rol, boolean asistenteHecho, boolean tutorialVisto) {
        return pasos(rol, asistenteHecho, tutorialVisto, false);
    }

    /** Pasos pendientes para el usuario de la sesión (vacío si no hay sesión activa) */
    @Transactional(readOnly = true)
    public List<Paso> pasosPendientes() {
        Cajero actual = sesionService.getCajeroActual();
        if (actual == null || actual.isDebeCambiarContrasena()) return List.of();
        return pasos(actual.getRol(), configuracionService.obtener().isAsistenteInicialHecho(), tutorialGeneralVisto(),
                donadorService.esDonador());
    }

    // ── Asistente inicial (por instalación, solo ADMIN) ──────────────────

    @Transactional(readOnly = true)
    public boolean asistenteInicialPendiente() {
        return sesionService.esAdmin() && !configuracionService.obtener().isAsistenteInicialHecho();
    }

    /** Guarda el nombre del negocio y deja el asistente como hecho (solo ADMIN, auditado) */
    @Transactional
    public Configuracion completarAsistenteInicial(String nombreNegocio) {
        sesionService.verificarAdmin();
        if (nombreNegocio == null || nombreNegocio.isBlank()) {
            throw new NegocioException("Escribe el nombre del negocio.");
        }
        String nombre = nombreNegocio.trim();
        if (nombre.length() > NOMBRE_NEGOCIO_MAXIMO) {
            throw new NegocioException("El nombre del negocio no puede tener más de " + NOMBRE_NEGOCIO_MAXIMO + " caracteres.");
        }
        Configuracion c = configuracionService.obtenerParaEditar();
        String antes = c.getNombreTienda();
        c.setNombreTienda(nombre);
        c.setAsistenteInicialHecho(true);
        Configuracion g = guardar(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ASISTENTE_INICIAL",
                "negocio: " + antes + " → " + nombre, (Cajero) null);
        return g;
    }

    /** El administrador prefirió capturar los datos después: no se vuelve a preguntar (solo ADMIN, auditado) */
    @Transactional
    public void omitirAsistenteInicial() {
        sesionService.verificarAdmin();
        Configuracion c = configuracionService.obtenerParaEditar();
        if (c.isAsistenteInicialHecho()) return;
        c.setAsistenteInicialHecho(true);
        guardar(c);
        auditoriaService.registrar(sesionService.getCajeroActual(), "ASISTENTE_INICIAL_OMITIDO",
                "datos del negocio pendientes en Configuración", (Cajero) null);
    }

    private Configuracion guardar(Configuracion c) {
        Configuracion g = configuracionRepo.save(c);
        configuracionService.invalidarCache();
        return g;
    }

    // ── Tutorial general (por usuario) ───────────────────────────────────

    @Transactional(readOnly = true)
    public boolean tutorialGeneralVisto() {
        Cajero actual = sesionService.requerirSesion();
        return cajeroRepo.findById(actual.getId()).map(Cajero::isTutorialGeneralVisto)
                .orElse(actual.isTutorialGeneralVisto());
    }

    /** Idempotente; también se marca si el usuario lo saltó (puede reabrirlo cuando quiera) */
    @Transactional
    public void marcarTutorialGeneralVisto() {
        cambiarTutorialGeneral(true);
    }

    @Transactional
    public void reiniciarTutorialGeneral() {
        cambiarTutorialGeneral(false);
    }

    private void cambiarTutorialGeneral(boolean visto) {
        Cajero actual = sesionService.requerirSesion();
        cajeroRepo.findById(actual.getId()).ifPresent(c -> {
            if (c.isTutorialGeneralVisto() != visto) {
                c.setTutorialGeneralVisto(visto);
                cajeroRepo.save(c);
            }
        });
        actual.setTutorialGeneralVisto(visto);   // el objeto de la sesión puede estar desligado de JPA
    }

    // ── Donación ─────────────────────────────────────────────────────────

    /** La ventana de donación la ve solo el administrador (en cada inicio de sesión) y nunca un negocio donador */
    public boolean mostrarDonacion() {
        return sesionService.esAdmin() && !donadorService.esDonador();
    }
}
