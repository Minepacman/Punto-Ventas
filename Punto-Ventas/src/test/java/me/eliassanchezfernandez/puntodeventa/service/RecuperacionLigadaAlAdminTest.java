package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El código de recuperación está LIGADO al administrador que lo generó (revisión
 * de seguridad I-1): solo restablece su contraseña, cada administrador tiene el
 * suyo, y se anula cuando lo desactivan, eliminan, degradan, otro administrador le
 * restablece la contraseña o se restaura un respaldo. Compatibilidad con el código
 * "heredado" de la 1.0.3 (sin dueño, en configuracion) y limpieza de admin.hash.
 */
class RecuperacionLigadaAlAdminTest extends BaseServicioTest {

    @Autowired private RecuperacionService  recuperacionService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ConexionService      conexionService;
    @Autowired private AuditoriaRepository  auditoriaRepo;

    @TempDir Path carpeta;

    @AfterEach
    void limpiar() {
        recuperacionService.reiniciarLimiteParaPruebas();
        conexionService.usarCarpetaParaPruebas(null);
        System.clearProperty(RecuperacionService.PROP_RESPALDO_APLICADO);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Cajero releer(Cajero c) {
        return cajeroRepo.findById(c.getId()).orElseThrow();
    }

    private List<String> detallesAnulacion() {
        return auditoriaRepo.findTop200ByOrderByFechaDesc().stream()
                .filter(a -> "CODIGO_RECUPERACION_ANULADO".equals(a.getAccion()))
                .map(Auditoria::getDetalle).toList();
    }

    /** Siembra un código como lo dejaba la 1.0.3: hash en configuracion, sin dueño */
    private String sembrarCodigoHeredado() {
        String codigo = "HHHH-EEEE-RRRR-DDDD";
        configuracionService.guardarCodigoRecuperacion(passwordEncoder.encode(RecuperacionService.normalizar(codigo)));
        assertThat(recuperacionService.hayCodigoHeredado()).isTrue();
        return codigo;
    }

    private void esperarRechazo(String usuario, String codigo) {
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo(usuario, codigo, "nuevaClave9"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_RECHAZO);
    }

    // ── Ligado al administrador ───────────────────────────────────────────

    @Test
    void elCodigoDeUnAdminNoRestableceLaContrasenaDeOtroAdmin() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        String codigoDeAdmin = recuperacionService.generarCodigo();
        sesionService.cerrarSesion();

        // El código es correcto, pero no es el de admin2
        esperarRechazo("admin2", codigoDeAdmin);
        assertThat(passwordEncoder.matches(CLAVE_PRUEBA, releer(admin2).getContrasenaHash()))
                .as("la contraseña de admin2 no se tocó").isTrue();
        assertThat(releer(admin).tieneCodigoRecuperacion()).as("el código de admin sigue vigente").isTrue();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).filteredOn(a -> "RECUPERACION_RECHAZADA".equals(a.getAccion()))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("no tiene código de recuperación propio"));

        // Para su dueño sí sirve
        Cajero restablecido = recuperacionService.restablecerConCodigo("admin", codigoDeAdmin, "nuevaClave9");
        assertThat(restablecido.getId()).isEqualTo(admin.getId());
        assertThat(passwordEncoder.matches("nuevaClave9", releer(admin).getContrasenaHash())).isTrue();
        assertThat(releer(admin).tieneCodigoRecuperacion()).as("consumido").isFalse();
    }

    @Test
    void cadaAdministradorTieneSuPropioCodigoYGenerarElSuyoNoAnulaElDelOtro() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        String codigoAdmin = recuperacionService.generarCodigo();

        iniciarComo("admin2", CLAVE_PRUEBA);
        assertThat(recuperacionService.hayCodigoPropio()).as("admin2 aún no tiene el suyo").isFalse();
        assertThat(recuperacionService.hayCodigo()).as("pero en la tienda sí hay uno (el de admin)").isTrue();
        String codigoAdmin2 = recuperacionService.generarCodigo();
        assertThat(recuperacionService.hayCodigoPropio()).isTrue();
        sesionService.cerrarSesion();

        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isTrue();
        assertThat(codigoAdmin).isNotEqualTo(codigoAdmin2);

        // Cruzados no sirven; cada uno con el suyo sí, y consumir uno deja el otro vigente
        esperarRechazo("admin", codigoAdmin2);
        esperarRechazo("admin2", codigoAdmin);
        recuperacionService.restablecerConCodigo("admin2", codigoAdmin2, "claveDos9");
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        recuperacionService.restablecerConCodigo("admin", codigoAdmin, "claveUno9");
        assertThat(recuperacionService.hayCodigo()).isFalse();
    }

    @Test
    void anularElCodigoSoloAfectaAlPropio() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        recuperacionService.generarCodigo();
        iniciarComo("admin2", CLAVE_PRUEBA);
        assertThatThrownBy(() -> recuperacionService.anularCodigo()).as("admin2 no tiene código")
                .isInstanceOf(NegocioException.class);
        recuperacionService.generarCodigo();
        recuperacionService.anularCodigo();
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(releer(admin).tieneCodigoRecuperacion()).as("el de admin sigue").isTrue();
    }

    // ── Anulación automática ──────────────────────────────────────────────

    @Test
    void quitarleElRolDeAdministradorAnulaSuCodigo() throws Exception {
        iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        iniciarComo("admin2", CLAVE_PRUEBA);
        String codigo = recuperacionService.generarCodigo();

        iniciarComoAdmin();
        cajeroService.actualizar(admin2.getId(), "admin2", "Usuario admin2", Cajero.Rol.GERENTE, true, null, null);
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(detallesAnulacion()).anyMatch(d -> d.contains("admin2") && d.contains("dejó de ser administrador"));

        // Ni aunque lo vuelvan a hacer admin: el código ya no existe
        cajeroService.actualizar(admin2.getId(), "admin2", "Usuario admin2", Cajero.Rol.ADMIN, true, null, null);
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin2", codigo, "nuevaClave9"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_SIN_CODIGO);
    }

    @Test
    void desactivarAlAdministradorAnulaSuCodigo() throws Exception {
        iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        Cajero admin3 = crearUsuario("admin3", Cajero.Rol.ADMIN, Set.of());
        iniciarComo("admin2", CLAVE_PRUEBA);
        recuperacionService.generarCodigo();
        iniciarComo("admin3", CLAVE_PRUEBA);
        recuperacionService.generarCodigo();

        iniciarComoAdmin();
        cajeroService.cambiarEstado(admin2.getId(), false);                                                     // botón Desactivar
        cajeroService.actualizar(admin3.getId(), "admin3", "Usuario admin3", Cajero.Rol.ADMIN, false, null, null); // casilla Activo
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(releer(admin3).tieneCodigoRecuperacion()).isFalse();
        assertThat(detallesAnulacion()).filteredOn(d -> d.contains("usuario desactivado")).hasSize(2);

        // Reactivarlo no lo revive
        cajeroService.cambiarEstado(admin2.getId(), true);
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
    }

    @Test
    void eliminarAlAdministradorAnulaSuCodigoYElHeredado() throws Exception {
        iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        // Con código propio pero sin historial (si entrara y lo generara él, la bitácora lo referenciaría y no se podría eliminar)
        admin2.setRecuperacionHash(passwordEncoder.encode("ABCDEFGHJKMNPQRS"));
        cajeroRepo.save(admin2);
        sembrarCodigoHeredado();
        assertThat(recuperacionService.hayCodigo()).isTrue();

        cajeroService.eliminar(admin2.getId());
        assertThat(cajeroRepo.findById(admin2.getId())).isEmpty();
        assertThat(recuperacionService.hayCodigo()).isFalse();
        assertThat(recuperacionService.hayCodigoHeredado()).as("pudo ser suyo: también se borra").isFalse();
        assertThat(detallesAnulacion()).anyMatch(d -> d.contains("usuario eliminado") && d.contains("versión anterior"));
    }

    @Test
    void queOtroAdministradorLeRestablezcaLaContrasenaAnulaSuCodigo() throws Exception {
        iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        Cajero admin3 = crearUsuario("admin3", Cajero.Rol.ADMIN, Set.of());
        iniciarComo("admin2", CLAVE_PRUEBA);
        String codigo2 = recuperacionService.generarCodigo();
        iniciarComo("admin3", CLAVE_PRUEBA);
        recuperacionService.generarCodigo();

        iniciarComoAdmin();
        cajeroService.cambiarContrasena(admin2.getId(), "Temporal99");                                                  // botón
        cajeroService.actualizar(admin3.getId(), "admin3", "Usuario admin3", Cajero.Rol.ADMIN, true, "Temporal99", null); // formulario
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(releer(admin3).tieneCodigoRecuperacion()).isFalse();
        assertThat(detallesAnulacion()).filteredOn(d -> d.contains("contraseña restablecida por otro administrador")).hasSize(2);

        sesionService.cerrarSesion();
        // Ya no queda ningún código en la tienda: el de admin2 no sirve (y el mensaje lo dice)
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin2", codigo2, "nuevaClave9"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_SIN_CODIGO);
        assertThat(passwordEncoder.matches("Temporal99", releer(admin2).getContrasenaHash())).isTrue();
    }

    @Test
    void cambiarLaPropiaContrasenaConservaElCodigo() throws Exception {
        Cajero admin = iniciarComoAdmin();
        String codigo = recuperacionService.generarCodigo();

        sesionService.cambiarContrasenaPropia(SesionService.ADMIN_CONTRASENA, "miClave99");
        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        cajeroService.actualizar(admin.getId(), "admin", "Administrador", Cajero.Rol.ADMIN, true, "otraClave99", null);
        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        cajeroService.cambiarContrasena(admin.getId(), "unaMas999");
        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        assertThat(detallesAnulacion()).isEmpty();

        sesionService.cerrarSesion();
        recuperacionService.restablecerConCodigo("admin", codigo, "recuperada9");
        assertThat(passwordEncoder.matches("recuperada9", releer(admin).getContrasenaHash())).isTrue();
    }

    @Test
    void cambiosEnUnUsuarioQueNoEsAdminNoTocanLosCodigos() throws Exception {
        Cajero admin = iniciarComoAdmin();
        recuperacionService.generarCodigo();
        Cajero cajero = crearUsuario("caja2", Cajero.Rol.CAJERO, null);
        cajeroService.cambiarContrasena(cajero.getId(), "Temporal99");
        cajeroService.cambiarEstado(cajero.getId(), false);
        cajeroService.eliminar(cajero.getId());
        assertThat(releer(admin).tieneCodigoRecuperacion()).isTrue();
        assertThat(detallesAnulacion()).isEmpty();
    }

    // ── Restaurar un respaldo ─────────────────────────────────────────────

    @Test
    void alArrancarTrasRestaurarUnRespaldoSeAnulanTodosLosCodigos() throws Exception {
        Cajero admin = iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        recuperacionService.generarCodigo();
        iniciarComo("admin2", CLAVE_PRUEBA);
        recuperacionService.generarCodigo();
        sembrarCodigoHeredado();
        sesionService.cerrarSesion();

        // Sin la marca del Launcher no pasa nada (arranque normal)
        assertThat(recuperacionService.anularTrasRestauracion()).isEqualTo(-1);
        assertThat(recuperacionService.hayCodigo()).isTrue();

        // Con la marca: la base restaurada no revive ningún código
        System.setProperty(RecuperacionService.PROP_RESPALDO_APLICADO, "true");
        assertThat(recuperacionService.anularTrasRestauracion()).isEqualTo(2);
        assertThat(System.getProperty(RecuperacionService.PROP_RESPALDO_APLICADO)).as("se consume").isNull();
        assertThat(releer(admin).tieneCodigoRecuperacion()).isFalse();
        assertThat(releer(admin2).tieneCodigoRecuperacion()).isFalse();
        assertThat(recuperacionService.hayCodigoHeredado()).isFalse();
        assertThat(recuperacionService.hayCodigo()).isFalse();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc())
                .filteredOn(a -> "CODIGO_RECUPERACION_ANULADO".equals(a.getAccion()))
                .anyMatch(a -> "sistema".equals(a.getUsuarioLogin()) && a.getDetalle().contains("Respaldo restaurado")
                        && a.getDetalle().contains("admin2") && a.getDetalle().contains("versión anterior"));

        // Base restaurada sin códigos: ni fila en la bitácora ni error
        System.setProperty(RecuperacionService.PROP_RESPALDO_APLICADO, "true");
        assertThat(recuperacionService.anularTrasRestauracion()).isZero();
    }

    // ── Compatibilidad con el código de la 1.0.3 (sin dueño) ──────────────

    @Test
    void elCodigoHeredadoSoloValeUnaVezYParaElUnicoAdministradorActivo() throws Exception {
        Cajero admin = iniciarComoAdmin();
        String heredado = sembrarCodigoHeredado();
        sesionService.cerrarSesion();
        assertThat(recuperacionService.hayCodigo()).isTrue();

        esperarRechazo("admin", "AAAA-BBBB-CCCC-DDDD");
        Cajero restablecido = recuperacionService.restablecerConCodigo("admin", heredado, "nuevaClave9");
        assertThat(restablecido.getId()).isEqualTo(admin.getId());
        assertThat(recuperacionService.hayCodigoHeredado()).as("consumido").isFalse();
        assertThat(recuperacionService.hayCodigo()).isFalse();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).filteredOn(a -> "CONTRASENA_RECUPERADA".equals(a.getAccion()))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("versión anterior"));
    }

    @Test
    void conDosAdministradoresActivosElCodigoHeredadoSeRechazaYSeBorraAlGenerarUnoPropio() throws Exception {
        iniciarComoAdmin();
        crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        String heredado = sembrarCodigoHeredado();
        sesionService.cerrarSesion();

        esperarRechazo("admin", heredado);
        esperarRechazo("admin2", heredado);
        assertThat(recuperacionService.hayCodigoHeredado()).as("rechazado, pero no consumido").isTrue();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).filteredOn(a -> "RECUPERACION_RECHAZADA".equals(a.getAccion()))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("único administrador activo"));

        // Generar el propio lo borra
        iniciarComoAdmin();
        String propio = recuperacionService.generarCodigo();
        assertThat(recuperacionService.hayCodigoHeredado()).isFalse();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).filteredOn(a -> "CODIGO_RECUPERACION_GENERADO".equals(a.getAccion()))
                .extracting(Auditoria::getDetalle).anyMatch(d -> d.contains("versión anterior"));
        sesionService.cerrarSesion();
        recuperacionService.restablecerConCodigo("admin", propio, "nuevaClave9");
    }

    @Test
    void elCodigoHeredadoTambienSeBorraAlDegradarOAnular() throws Exception {
        iniciarComoAdmin();
        Cajero admin2 = crearUsuario("admin2", Cajero.Rol.ADMIN, Set.of());
        sembrarCodigoHeredado();
        // Degradar a un admin cualquiera: el heredado pudo ser suyo
        cajeroService.actualizar(admin2.getId(), "admin2", "Usuario admin2", Cajero.Rol.CAJERO, true, null, null);
        assertThat(recuperacionService.hayCodigoHeredado()).isFalse();
        assertThat(detallesAnulacion()).anyMatch(d -> d.contains("versión anterior"));

        sembrarCodigoHeredado();
        assertThat(recuperacionService.hayCodigoPropio()).isFalse();
        recuperacionService.anularCodigo();   // sin código propio, anula el heredado
        assertThat(recuperacionService.hayCodigoHeredado()).isFalse();
    }

    // ── admin.hash de la pantalla "sin conexión" ──────────────────────────

    @Test
    void recuperarLaContrasenaOlvidaElAdminHashViejoDeLaCaja() throws Exception {
        conexionService.usarCarpetaParaPruebas(carpeta);
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.SQLITE);
        c.guardar(carpeta);

        Cajero admin = iniciarComoAdmin();
        conexionService.recordarAdministrador(admin);
        assertThat(ConexionLocal.cargar(carpeta).getAdminHash()).isEqualTo(admin.getContrasenaHash());
        String codigo = recuperacionService.generarCodigo();
        sesionService.cerrarSesion();

        recuperacionService.restablecerConCodigo("admin", codigo, "recuperada9");
        assertThat(ConexionLocal.cargar(carpeta).getAdminHash())
                .as("la contraseña vieja ya no abre la pantalla sin conexión").isNull();

        // Al entrar con la nueva se vuelve a recordar (lo hace LoginController)
        Cajero deNuevo = sesionService.autenticar("admin", "recuperada9");
        conexionService.recordarAdministrador(deNuevo);
        assertThat(ConexionLocal.cargar(carpeta).getAdminHash()).isEqualTo(deNuevo.getContrasenaHash());
    }
}
