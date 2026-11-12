package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Política de contraseñas (USR-07), cambio obligatorio en el primer inicio (USR-06) y lista de usuarios del login. */
class ContrasenasTest extends BaseServicioTest {

    @org.springframework.beans.factory.annotation.Autowired
    private ConfiguracionService configuracionService;

    @Test
    void laPoliticaExigeCincoCaracteresYUnNumero() {
        assertThatThrownBy(() -> PoliticaContrasena.validar("ab1")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> PoliticaContrasena.validar("abcdef")).isInstanceOf(NegocioException.class)
                .hasMessageContaining("número");
        assertThatThrownBy(() -> PoliticaContrasena.validar(null)).isInstanceOf(NegocioException.class);
        assertThat(PoliticaContrasena.cumple("abcd1")).isTrue();
        assertThat(PoliticaContrasena.cumple("clave123")).isTrue();
    }

    @Test
    void laPoliticaRechazaContrasenasObviasYLaIgualAlUsuario() {
        assertThatThrownBy(() -> PoliticaContrasena.validar("12345")).isInstanceOf(NegocioException.class)
                .hasMessageContaining("fácil");
        assertThatThrownBy(() -> PoliticaContrasena.validar("Admin123")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> PoliticaContrasena.validar("11111")).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> PoliticaContrasena.validar("Lupita7", "lupita7")).isInstanceOf(NegocioException.class)
                .hasMessageContaining("usuario");
        assertThat(PoliticaContrasena.cumple("Lupita7", "lupita")).isTrue();
        assertThat(PoliticaContrasena.cumple("clave123")).isTrue();
        assertThat(PoliticaContrasena.cumple("a".repeat(PoliticaContrasena.LONGITUD_MAXIMA) + "1")).isFalse();
    }

    @Test
    void noSePuedeCrearUnUsuarioCuyaContrasenaSeaSuUsuario() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> cajeroService.crear("pepe1", "Pepe", "PEPE1", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("usuario");
        Cajero c = cajeroService.crear("pepe1", "Pepe", "otra789", Cajero.Rol.CAJERO);
        assertThatThrownBy(() -> cajeroService.cambiarContrasena(c.getId(), "pepe1"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("usuario");
        assertThatThrownBy(() -> cajeroService.cambiarContrasena(c.getId(), "123456"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("fácil");
    }

    @Test
    void laListaDeUsuariosDelLoginSoloSaleSiElAdminLaActivaYSinHash() throws Exception {
        iniciarComoAdmin();
        crearUsuario("visible", Cajero.Rol.CAJERO, null);
        Cajero oculto = crearUsuario("oculto", Cajero.Rol.CAJERO, null);
        cajeroService.cambiarEstado(oculto.getId(), false);
        sesionService.cerrarSesion();

        assertThat(cajeroService.usuariosParaLogin()).as("apagada por defecto").isEmpty();

        iniciarComoAdmin();
        configuracionService.guardarOpcionesLogin(true);
        sesionService.cerrarSesion();   // se consulta sin sesión, como en el login

        var lista = cajeroService.usuariosParaLogin();
        assertThat(lista).extracting(CajeroService.UsuarioLogin::usuario).contains("admin", "visible").doesNotContain("oculto");
        assertThat(lista.size()).isLessThanOrEqualTo(CajeroService.MAX_USUARIOS_LOGIN);

        iniciarComoAdmin();
        configuracionService.guardarOpcionesLogin(false);
        sesionService.cerrarSesion();
        assertThat(cajeroService.usuariosParaLogin()).isEmpty();
    }

    @Test
    void crearYRestablecerContrasenasRespetanLaPolitica() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> cajeroService.crear("x1", "X", "abcde", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("número");
        assertThatThrownBy(() -> cajeroService.crear("x1", "X", "a1", Cajero.Rol.CAJERO))
                .isInstanceOf(NegocioException.class).hasMessageContaining("5");

        Cajero c = cajeroService.crear("x1", "X", "abcd1", Cajero.Rol.CAJERO);
        assertThatThrownBy(() -> cajeroService.cambiarContrasena(c.getId(), "sinnumero"))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void unUsuarioNuevoDebeCambiarSuContrasenaAntesDeOperar() throws Exception {
        iniciarComoAdmin();
        Cajero nuevo = crearUsuario("nueva", Cajero.Rol.CAJERO, null);
        assertThat(nuevo.isDebeCambiarContrasena()).isTrue();

        sesionService.cerrarSesion();
        Cajero autenticado = sesionService.autenticar("nueva", CLAVE_PRUEBA);
        assertThat(autenticado.isDebeCambiarContrasena()).isTrue();
        assertThat(sesionService.haySesionActiva()).isFalse();
        assertThat(sesionService.sesionPendienteDeCambio()).isTrue();
        assertThatThrownBy(() -> sesionService.requerirSesion())
                .isInstanceOf(NegocioException.class).hasMessageContaining("contraseña");
        assertThatThrownBy(() -> ventaService.cobrar(java.util.List.of(), null, 0))
                .isInstanceOf(NegocioException.class);

        // Debe conocer la actual, cumplir la política y no repetirla
        assertThatThrownBy(() -> sesionService.cambiarContrasenaPropia("equivocada", "nueva456"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("actual");
        assertThatThrownBy(() -> sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, "corta"))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, CLAVE_PRUEBA))
                .isInstanceOf(NegocioException.class).hasMessageContaining("distinta");
        assertThatThrownBy(() -> sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, "admin123"))
                .isInstanceOf(NegocioException.class);

        sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, "nueva456");

        assertThat(sesionService.haySesionActiva()).isTrue();
        assertThat(sesionService.sesionPendienteDeCambio()).isFalse();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase("nueva").orElseThrow().isDebeCambiarContrasena()).isFalse();

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> sesionService.autenticar("nueva", CLAVE_PRUEBA))
                .isInstanceOf(SesionService.CredencialesInvalidasException.class);
        assertThat(sesionService.autenticar("nueva", "nueva456").getUsuario()).isEqualTo("nueva");
    }

    @Test
    void cuandoElAdminRestableceUnaContrasenaElUsuarioDebeCambiarla() throws Exception {
        iniciarComoAdmin();
        Cajero c = crearUsuario("reset", Cajero.Rol.CAJERO, null);
        iniciarComo("reset", CLAVE_PRUEBA);
        sesionService.cambiarContrasenaPropia(CLAVE_PRUEBA, "propia789");
        assertThat(cajeroRepo.findById(c.getId()).orElseThrow().isDebeCambiarContrasena()).isFalse();

        iniciarComoAdmin();
        cajeroService.cambiarContrasena(c.getId(), "temporal1");

        assertThat(cajeroRepo.findById(c.getId()).orElseThrow().isDebeCambiarContrasena()).isTrue();
    }
}
