package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Código de recuperación del administrador: generación, uso único, rechazos y bitácora. */
class RecuperacionServiceTest extends BaseServicioTest {

    @Autowired private RecuperacionService recuperacionService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private AuditoriaRepository auditoriaRepo;

    @org.junit.jupiter.api.AfterEach
    void olvidarIntentos() {
        recuperacionService.reiniciarLimiteParaPruebas();
    }

    @Test
    void elCodigoTieneElFormatoEsperadoYSeNormalizaComoLoTecleeElDueno() {
        String codigo = RecuperacionService.nuevoCodigo(new SecureRandom());
        assertThat(codigo).matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(codigo).doesNotContain("0", "O", "1", "I", "L");
        assertThat(RecuperacionService.normalizar(" abcd-efgh jkmn_pqrs ")).isEqualTo("ABCDEFGHJKMNPQRS");
        assertThat(RecuperacionService.normalizar(null)).isEmpty();
    }

    @Test
    void soloUnAdminGeneraElCodigoYQuedaSoloSuHash() throws Exception {
        iniciarComoCajero("cajero1", null);
        assertThatThrownBy(() -> recuperacionService.generarCodigo()).isInstanceOf(NegocioException.class);
        assertThat(recuperacionService.hayCodigo()).isFalse();

        iniciarComoAdmin();
        String codigo = recuperacionService.generarCodigo();

        assertThat(recuperacionService.hayCodigo()).isTrue();
        String hash = configuracionService.obtener().getRecuperacionHash();
        assertThat(hash).startsWith("$2").doesNotContain(codigo);
        assertThat(passwordEncoder.matches(RecuperacionService.normalizar(codigo), hash)).isTrue();
        assertThat(configuracionService.obtener().getRecuperacionGenerado()).isAfter(LocalDateTime.now().minusMinutes(1));
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).extracting(Auditoria::getAccion)
                .contains("CODIGO_RECUPERACION_GENERADO");
    }

    @Test
    void sinCodigoGeneradoNoHayRecuperacionPosible() {
        assertThat(recuperacionService.hayCodigo()).isFalse();
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", "AAAA-BBBB-CCCC-DDDD", "nueva789"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_SIN_CODIGO);
    }

    @Test
    void conElCodigoCorrectoElAdminFijaSuContrasenaNuevaYElCodigoSeConsume() throws Exception {
        iniciarComoAdmin();
        String codigo = recuperacionService.generarCodigo();
        sesionService.cerrarSesion();

        // Simula al admin que olvidó su clave y además quedó bloqueado por intentos
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setBloqueadoHasta(LocalDateTime.now().plusHours(1));
        admin.setIntentosFallidos(7);
        admin.setDebeCambiarContrasena(true);
        cajeroRepo.save(admin);

        Cajero restablecido = recuperacionService.restablecerConCodigo("ADMIN", codigo.toLowerCase(), "recuperada9");

        assertThat(restablecido.getUsuario()).isEqualTo("admin");
        assertThat(sesionService.haySesionActiva()).as("no abre sesión: entra con la clave nueva").isFalse();
        Cajero enBd = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        assertThat(passwordEncoder.matches("recuperada9", enBd.getContrasenaHash())).isTrue();
        assertThat(enBd.isBloqueado()).isFalse();
        assertThat(enBd.getIntentosFallidosOCero()).isZero();
        assertThat(enBd.isDebeCambiarContrasena()).isFalse();
        assertThat(recuperacionService.hayCodigo()).as("de un solo uso").isFalse();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).extracting(Auditoria::getAccion)
                .contains("CONTRASENA_RECUPERADA");

        // Entra con la nueva; el código ya no sirve
        assertThat(sesionService.autenticar("admin", "recuperada9").getUsuario()).isEqualTo("admin");
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", codigo, "otra12345"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_SIN_CODIGO);
    }

    @Test
    void rechazaCodigoIncorrectoUsuarioNoAdminYContrasenaDebil() throws Exception {
        iniciarComoAdmin();
        Cajero cajero = crearUsuario("caja2", Cajero.Rol.CAJERO, null);
        String codigo = recuperacionService.generarCodigo();
        sesionService.cerrarSesion();

        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", "AAAA-BBBB-CCCC-DDDD", "nueva789"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_RECHAZO);
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("caja2", codigo, "nueva789"))
                .as("un cajero no puede usar el código").isInstanceOf(NegocioException.class)
                .hasMessage(RecuperacionService.MENSAJE_RECHAZO);
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("nadie", codigo, "nueva789"))
                .isInstanceOf(NegocioException.class).hasMessage(RecuperacionService.MENSAJE_RECHAZO);
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", codigo, "ab1"))
                .as("código correcto pero contraseña débil: no consume el código")
                .isInstanceOf(NegocioException.class).hasMessageContaining("5");
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", codigo, "admin123"))
                .isInstanceOf(NegocioException.class);

        assertThat(recuperacionService.hayCodigo()).as("sigue vigente tras los rechazos").isTrue();
        assertThat(passwordEncoder.matches(CLAVE_PRUEBA, cajeroRepo.findById(cajero.getId()).orElseThrow().getContrasenaHash()))
                .as("la clave del cajero no se tocó").isTrue();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).extracting(Auditoria::getAccion)
                .contains("RECUPERACION_RECHAZADA");
    }

    @Test
    void trasVariosCodigosIncorrectosHayQueEsperar() throws Exception {
        iniciarComoAdmin();
        String codigo = recuperacionService.generarCodigo();
        sesionService.cerrarSesion();

        for (int i = 0; i < RecuperacionService.MAX_INTENTOS; i++) {
            assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", "XXXX-XXXX-XXXX-XXXX", "nueva789"))
                    .hasMessage(RecuperacionService.MENSAJE_RECHAZO);
        }
        assertThatThrownBy(() -> recuperacionService.restablecerConCodigo("admin", codigo, "nueva789"))
                .as("ni con el código correcto durante la espera")
                .isInstanceOf(NegocioException.class).hasMessageContaining("Espera");
        assertThat(recuperacionService.hayCodigo()).isTrue();
    }

    @Test
    void elAdminPuedeAnularElCodigo() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> recuperacionService.anularCodigo()).isInstanceOf(NegocioException.class);
        recuperacionService.generarCodigo();
        recuperacionService.anularCodigo();
        assertThat(recuperacionService.hayCodigo()).isFalse();
    }
}
