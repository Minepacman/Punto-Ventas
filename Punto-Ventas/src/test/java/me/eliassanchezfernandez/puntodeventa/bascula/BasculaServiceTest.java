package me.eliassanchezfernandez.puntodeventa.bascula;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ProtocoloBascula;
import me.eliassanchezfernandez.puntodeventa.service.BaseServicioTest;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lectura de la báscula con un canal serie falso: ninguna prueba abre un puerto real. */
class BasculaServiceTest extends BaseServicioTest {

    @Autowired private BasculaService basculaService;
    @Autowired private DispositivosService dispositivosService;

    /** Canal que entrega trozos prefabricados y recuerda lo que se le escribió */
    static class FlujoFalso implements FlujoSerial {
        final Deque<byte[]> trozos = new ArrayDeque<>();
        final List<byte[]> escrito = new ArrayList<>();
        IOException falloAlAbrir;
        Error errorNativo;

        FlujoFalso responde(String... textos) {
            for (String t : textos) trozos.add(t.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        @Override public void abrir() throws IOException {
            if (errorNativo != null) throw errorNativo;
            if (falloAlAbrir != null) throw falloAlAbrir;
        }
        @Override public void escribir(byte[] datos) { escrito.add(datos); }
        @Override public void limpiarEntrada() { }
        @Override public byte[] leer(int esperaMs) {
            byte[] t = trozos.poll();
            if (t != null) return t;
            try { Thread.sleep(Math.min(esperaMs, 5)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new byte[0];
        }
        @Override public void close() { }
    }

    private FlujoFalso flujo;

    @BeforeEach
    void configurar() throws Exception {
        iniciarComoAdmin();
        flujo = new FlujoFalso();
        basculaService.setFabricaFlujo(c -> flujo);
        dispositivosService.guardarBascula(true, "COM9", 9600, ProtocoloBascula.PETICION, "05",
                "(\\d+(?:[.,]\\d+)?)", 200);
    }

    @AfterEach
    void restaurar() {
        basculaService.setFabricaFlujo(null);
    }

    @Test
    void porPeticionEnviaEnqYLeeElPeso() {
        flujo.responde("  0.500 kg\r\n");

        assertThat(basculaService.leerPeso()).isEqualTo(0.5);
        assertThat(flujo.escrito).hasSize(1);
        assertThat(flujo.escrito.get(0)).containsExactly(0x05);
    }

    @Test
    void elComandoDePeticionEsConfigurable() {
        dispositivosService.guardarBascula(true, "COM9", 9600, ProtocoloBascula.PETICION, "57 0D",
                "(\\d+(?:[.,]\\d+)?)", 200);
        flujo.responde("W: 2,50\r\n");

        assertThat(basculaService.leerPeso()).isEqualTo(2.5);
        assertThat(flujo.escrito.get(0)).containsExactly(0x57, 0x0D);
    }

    @Test
    void enFlujoContinuoNoEscribeYTomaLaUltimaTramaCompleta() {
        dispositivosService.guardarBascula(true, "COM9", 9600, ProtocoloBascula.CONTINUO, "05",
                "(\\d+(?:[.,]\\d+)?)", 200);
        flujo.responde("  0.4", "80 kg\r\n  0.495 kg\r\n  0.500 kg\r\n");

        assertThat(basculaService.leerPeso()).isEqualTo(0.5);
        assertThat(flujo.escrito).isEmpty();
    }

    @Test
    void conUnaExpresionPropiaExtraeSoloElPeso() {
        dispositivosService.guardarBascula(true, "COM9", 9600, ProtocoloBascula.PETICION, "05",
                "GS,\\s*(\\d+\\.\\d+)", 200);
        flujo.responde("ST,GS,   1.250kg\r\n");
        assertThat(basculaService.leerPeso()).isEqualTo(1.25);
    }

    @Test
    void sinRespuestaAvisaSinLanzarErroresRaros() {
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("no respondió");
    }

    @Test
    void unaTramaSinPesoSeReportaConLoQueLlego() {
        flujo.responde("ERROR\r\n");
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("ERROR");
    }

    @Test
    void conElPlatoVacioPideColocarElProducto() {
        flujo.responde("  0.000 kg\r\n");
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("marca 0");
    }

    @Test
    void siElPuertoNoAbreElMensajeEsClaro() {
        flujo.falloAlAbrir = new IOException("No se pudo abrir el puerto COM9");
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("COM9");
    }

    @Test
    void siLaLibreriaNativaNoCargaEnEsteEquipoNoSeCae() {
        flujo.errorNativo = new UnsatisfiedLinkError("jSerialComm");
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("no está disponible en este equipo");
    }

    @Test
    void conLaBasculaApagadaOSinSesionNoLee() {
        dispositivosService.guardarBascula(false, "", 9600, ProtocoloBascula.PETICION, "05", null, 200);
        assertThat(basculaService.disponible()).isFalse();
        assertThatThrownBy(() -> basculaService.leerPeso())
                .isInstanceOf(NegocioException.class).hasMessageContaining("no está activada");

        sesionService.cerrarSesion();
        assertThatThrownBy(() -> basculaService.leerPeso()).isInstanceOf(NegocioException.class);
    }

    @Test
    void listarPuertosNuncaLanzaAunqueNoHayaPuertos() {
        assertThat(basculaService.listarPuertos()).isNotNull();   // en CI puede estar vacío
    }

    @Test
    void laConfiguracionValidaVelocidadComandoYExpresion() {
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "COM1", 1234, ProtocoloBascula.PETICION, "05", null, 500))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Velocidad");
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "COM1", 9600, ProtocoloBascula.PETICION, "XYZ", null, 500))
                .isInstanceOf(NegocioException.class).hasMessageContaining("hexadecimal");
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "COM1", 9600, ProtocoloBascula.PETICION, "05", "([", 500))
                .isInstanceOf(NegocioException.class).hasMessageContaining("expresión regular");
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "COM1", 9600, ProtocoloBascula.PETICION, "05", null, 50))
                .isInstanceOf(NegocioException.class).hasMessageContaining("espera");
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "", 9600, ProtocoloBascula.PETICION, "05", null, 500))
                .isInstanceOf(NegocioException.class).hasMessageContaining("puerto");
    }

    @Test
    void soloElAdminGuardaLaConfiguracionDeLaBascula() throws Exception {
        crearUsuario("gerente1", Cajero.Rol.GERENTE, EnumSet.noneOf(me.eliassanchezfernandez.puntodeventa.model.Permiso.class));
        iniciarComo("gerente1", CLAVE_PRUEBA);
        assertThatThrownBy(() -> dispositivosService.guardarBascula(true, "COM1", 9600, ProtocoloBascula.PETICION, "05", null, 500))
                .isInstanceOf(NegocioException.class);
    }
}
