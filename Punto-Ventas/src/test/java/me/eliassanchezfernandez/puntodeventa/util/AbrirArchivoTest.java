package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reglas de {@link AbrirArchivo}: solo PDF/XML regulares dentro de las carpetas propias del programa,
 * comparando rutas reales (sin escapes por enlaces simbólicos ni «..»), y carpetas solo las declaradas.
 * En macOS la carpeta temporal ya es un enlace simbólico (/var → /private/var), así que estas pruebas
 * ejercitan de paso la resolución de la carpeta permitida.
 */
class AbrirArchivoTest {

    @TempDir
    Path tmp;

    Path facturas;
    Path fuera;

    @BeforeEach
    void preparar() throws IOException {
        facturas = Files.createDirectories(tmp.resolve("datos").resolve("facturas").resolve("2026"));
        fuera = Files.createDirectories(tmp.resolve("otra-carpeta"));
    }

    private Path archivo(Path carpeta, String nombre) throws IOException {
        return Files.writeString(carpeta.resolve(nombre), "contenido");
    }

    @Test
    void aceptaPdfYXmlDentroDeLaCarpetaDeFacturas() throws IOException {
        Path raiz = tmp.resolve("datos").resolve("facturas");
        Path pdf = archivo(facturas, "A-12-UUID.pdf");
        Path xml = archivo(facturas, "A-12-UUID.xml");
        Path mayusculas = archivo(facturas, "A-13-UUID.PDF");

        assertThat(AbrirArchivo.archivoPermitido(pdf, List.of(raiz))).isPresent();
        assertThat(AbrirArchivo.archivoPermitido(xml, List.of(raiz))).isPresent();
        assertThat(AbrirArchivo.archivoPermitido(mayusculas, List.of(raiz))).isPresent();
        // la ruta devuelta es la real (resuelta), siempre absoluta
        assertThat(AbrirArchivo.archivoPermitido(pdf, List.of(raiz)).orElseThrow()).isAbsolute()
                .isEqualTo(pdf.toRealPath());
    }

    @Test
    void rechazaOtrasExtensionesAunqueEstenDentro() throws IOException {
        Path raiz = tmp.resolve("datos").resolve("facturas");
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, "nota.exe"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, "factura.pdf.bat"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, "sin-extension"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, "factura.html"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, ".pdf"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(archivo(facturas, "termina-en-punto."), List.of(raiz))).isEmpty();
    }

    @Test
    void rechazaArchivosFueraDeLasCarpetasPermitidas() throws IOException {
        Path raiz = tmp.resolve("datos").resolve("facturas");
        Path ajeno = archivo(fuera, "ajeno.pdf");
        assertThat(AbrirArchivo.archivoPermitido(ajeno, List.of(raiz))).isEmpty();
        // «..» desde dentro de la carpeta tampoco escapa
        Path conPuntos = facturas.resolve("..").resolve("..").resolve("..").resolve("otra-carpeta").resolve("ajeno.pdf");
        assertThat(Files.isRegularFile(conPuntos)).isTrue();
        assertThat(AbrirArchivo.archivoPermitido(conPuntos, List.of(raiz))).isEmpty();
        // sin carpetas permitidas no se abre nada
        Path pdf = archivo(facturas, "A-1-U.pdf");
        assertThat(AbrirArchivo.archivoPermitido(pdf, List.of())).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(pdf, null)).isEmpty();
        // una carpeta permitida que no existe no habilita nada
        assertThat(AbrirArchivo.archivoPermitido(pdf, List.of(tmp.resolve("no-existe")))).isEmpty();
    }

    @Test
    void rechazaEnlaceSimbolicoQueApuntaFueraDeLaCarpeta() throws IOException {
        Path raiz = tmp.resolve("datos").resolve("facturas");
        Path ajeno = archivo(fuera, "secreto.pdf");
        Path enlace = facturas.resolve("inocente.pdf");
        try {
            Files.createSymbolicLink(enlace, ajeno);
        } catch (UnsupportedOperationException | IOException sinPermisoDeEnlaces) {
            assumeTrue(false, "este sistema no permite crear enlaces simbólicos");
        }
        assertThat(Files.isRegularFile(enlace)).isTrue();
        assertThat(AbrirArchivo.archivoPermitido(enlace, List.of(raiz))).isEmpty();

        // un enlace que apunta DENTRO de la carpeta sí vale: se abre el destino real
        Path destino = archivo(facturas, "real.pdf");
        Path enlaceInterno = facturas.resolve("alias.pdf");
        Files.createSymbolicLink(enlaceInterno, destino);
        assertThat(AbrirArchivo.archivoPermitido(enlaceInterno, List.of(raiz))).contains(destino.toRealPath());
    }

    @Test
    void rechazaCarpetasInexistentesYNulosComoArchivo() {
        Path raiz = tmp.resolve("datos").resolve("facturas");
        assertThat(AbrirArchivo.archivoPermitido(facturas, List.of(raiz))).as("una carpeta no es un archivo").isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(facturas.resolve("no-existe.pdf"), List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.archivoPermitido(null, List.of(raiz))).isEmpty();
        assertThat(AbrirArchivo.abrir(null, List.of(raiz))).isFalse();
        assertThat(AbrirArchivo.abrir(facturas.resolve("no-existe.pdf"), List.of(raiz))).isFalse();
    }

    @Test
    void carpetasSoloLasPropiasOSusSubcarpetas() throws IOException {
        Path logs = Files.createDirectories(tmp.resolve("logs"));
        Path raizFacturas = tmp.resolve("datos").resolve("facturas");

        assertThat(AbrirArchivo.carpetaPermitida(logs, List.of(logs))).contains(logs.toRealPath());
        assertThat(AbrirArchivo.carpetaPermitida(facturas, List.of(raizFacturas))).as("facturas/2026").isPresent();
        assertThat(AbrirArchivo.carpetaPermitida(tmp, List.of(logs))).as("la carpeta padre no").isEmpty();
        assertThat(AbrirArchivo.carpetaPermitida(fuera, List.of(logs, raizFacturas))).isEmpty();
        assertThat(AbrirArchivo.carpetaPermitida(archivo(logs, "errores.log"), List.of(logs))).as("un archivo no es carpeta").isEmpty();
        assertThat(AbrirArchivo.carpetaPermitida(tmp.resolve("no-existe"), List.of(logs))).isEmpty();
        assertThat(AbrirArchivo.carpetaPermitida(null, List.of(logs))).isEmpty();
        assertThat(AbrirArchivo.abrirCarpeta(fuera, List.of(logs))).isFalse();
    }

    @Test
    void usaElComandoDelSistemaConLaRutaComoArgumentoAparte() {
        Path ruta = Path.of("/Users/x/Library/Application Support/PuntoDeVenta/datos/facturas/2026/A-1-U.pdf");
        assertThat(AbrirArchivo.comando("Mac OS X", ruta)).contains(List.of("open", ruta.toString()));
        assertThat(AbrirArchivo.comando("Windows 11", ruta)).contains(List.of("rundll32", "url.dll,FileProtocolHandler", ruta.toString()));
        assertThat(AbrirArchivo.comando("Linux", ruta)).contains(List.of("xdg-open", ruta.toString()));
        assertThat(AbrirArchivo.comando("SunOS", ruta)).isEmpty();
    }

    @Test
    void extensionEnMinusculasYSinTrucos() {
        assertThat(AbrirArchivo.extension(Path.of("a/b/F-1-U.PDF"))).isEqualTo("pdf");
        assertThat(AbrirArchivo.extension(Path.of("F-1-U.xml"))).isEqualTo("xml");
        assertThat(AbrirArchivo.extension(Path.of("factura.pdf.exe"))).isEqualTo("exe");
        assertThat(AbrirArchivo.extension(Path.of(".pdf"))).isEmpty();
        assertThat(AbrirArchivo.extension(Path.of("sin"))).isEmpty();
        assertThat(AbrirArchivo.extension(Path.of("punto."))).isEmpty();
    }
}
