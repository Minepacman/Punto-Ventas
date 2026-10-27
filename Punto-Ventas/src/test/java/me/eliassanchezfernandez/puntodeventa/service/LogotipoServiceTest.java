package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Logotipo: PNG/JPG de hasta 1 MB copiado a la carpeta "datos" (desechable en pruebas). */
class LogotipoServiceTest extends BaseServicioTest {

    private static final byte[] CABECERA_PNG = cabeceraPng(200, 100);
    private static final byte[] CABECERA_JPG = cabeceraJpg(200, 100);

    /** Firma PNG + bloque IHDR con ancho y alto */
    static byte[] cabeceraPng(int ancho, int alto) {
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (ancho >> 24), (byte) (ancho >> 16), (byte) (ancho >> 8), (byte) ancho,
                (byte) (alto >> 24), (byte) (alto >> 16), (byte) (alto >> 8), (byte) alto};
    }

    /** SOI + APP0 mínimo + SOF0 con alto y ancho */
    static byte[] cabeceraJpg(int ancho, int alto) {
        return new byte[] {(byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0,
                (byte) 0xFF, (byte) 0xC0, 0, 17, 8,
                (byte) (alto >> 8), (byte) alto, (byte) (ancho >> 8), (byte) ancho};
    }

    @Autowired private LogotipoService logotipoService;
    @Autowired private RutasApp        rutas;

    @TempDir Path temporal;

    @AfterEach
    void limpiarCarpetaDatos() throws IOException {
        Files.deleteIfExists(rutas.carpetaDatos().resolve("logo.png"));
        Files.deleteIfExists(rutas.carpetaDatos().resolve("logo.jpg"));
    }

    private Path archivo(String nombre, byte[] cabecera, int tamano) throws IOException {
        byte[] datos = new byte[tamano];
        System.arraycopy(cabecera, 0, datos, 0, Math.min(cabecera.length, tamano));
        return Files.write(temporal.resolve(nombre), datos);
    }

    @Test
    void copiaElLogotipoALaCarpetaDeDatosYSePuedeQuitar() throws Exception {
        iniciarComoAdmin();
        assertThat(logotipoService.logoActual()).isEmpty();

        Path destino = logotipoService.establecer(archivo("mi logo.png", CABECERA_PNG, 2000));

        assertThat(destino.getFileName().toString()).isEqualTo("logo.png");
        assertThat(destino.getParent()).isEqualTo(rutas.carpetaDatos());
        assertThat(logotipoService.logoActual()).contains(destino);
        assertThat(Files.size(destino)).isEqualTo(2000);

        // Cambiar a JPG reemplaza al PNG (solo hay un logotipo)
        Path jpg = logotipoService.establecer(archivo("otro.JPEG", CABECERA_JPG, 3000));
        assertThat(jpg.getFileName().toString()).isEqualTo("logo.jpg");
        assertThat(rutas.carpetaDatos().resolve("logo.png")).doesNotExist();

        logotipoService.quitar();
        assertThat(logotipoService.logoActual()).isEmpty();
        assertThatThrownBy(() -> logotipoService.quitar()).isInstanceOf(NegocioException.class);
    }

    @Test
    void validaTipoTamanoYContenido() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> logotipoService.establecer(archivo("logo.gif", CABECERA_PNG, 100)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("PNG o JPG");
        assertThatThrownBy(() -> logotipoService.establecer(archivo("grande.png", CABECERA_PNG, 1024 * 1024 + 1)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("1 MB");
        assertThatThrownBy(() -> logotipoService.establecer(archivo("falso.png", new byte[] {'h', 'o', 'l', 'a'}, 100)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una imagen");
        assertThatThrownBy(() -> logotipoService.establecer(temporal.resolve("no-existe.png")))
                .isInstanceOf(NegocioException.class);
        assertThat(logotipoService.establecer(archivo("justo.png", CABECERA_PNG, 1024 * 1024))).exists();   // 1 MB exacto cabe
    }

    @Test
    void rechazaImagenesConDimensionesExcesivas() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> logotipoService.establecer(archivo("enorme.png", cabeceraPng(30000, 30000), 2000)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("2000");
        assertThatThrownBy(() -> logotipoService.establecer(archivo("ancha.jpg", cabeceraJpg(2001, 100), 2000)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("2000");
        assertThatThrownBy(() -> logotipoService.establecer(archivo("cero.png", cabeceraPng(0, 0), 2000)))
                .isInstanceOf(NegocioException.class);
        // Sin bloque IHDR ni SOF: no es una imagen válida
        assertThatThrownBy(() -> logotipoService.establecer(archivo("trunca.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 100)))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una imagen");
        assertThat(logotipoService.establecer(archivo("limite.png", cabeceraPng(2000, 2000), 2000))).exists();
        assertThat(logotipoService.establecer(archivo("limite.jpg", cabeceraJpg(2000, 1999), 2000))).exists();
    }

    @Test
    void soloElAdminCambiaElLogotipo() throws Exception {
        iniciarComoCajero("cajeroLogo", null);
        Path png = archivo("logo.png", CABECERA_PNG, 100);
        assertThatThrownBy(() -> logotipoService.establecer(png)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> logotipoService.quitar()).isInstanceOf(NegocioException.class);
        assertThat(logotipoService.logoActual()).isEmpty();
    }
}
