package me.eliassanchezfernandez.puntodeventa.ocr;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descargas de PaddleOCR: primero el espejo (release "ocr-modelos-1" de este repositorio) y, si falla, la fuente
 * original; y la lista de packaging/ocr-modelos/recursos.txt (la que usa el flujo que publica el espejo) debe decir
 * lo mismo que {@link RecursosPpOcr}.
 */
class EspejoOcrModelosTest {

    @Test
    void cadaArchivoTieneEspejoPrimeroYFuenteOriginalDespues() {
        assertThat(RecursosPpOcr.ESPEJO)
                .isEqualTo("https://github.com/Minepacman/Punto-Ventas/releases/download/" + RecursosPpOcr.ETIQUETA_ESPEJO + "/");
        assertThat(RecursosPpOcr.TODOS).allSatisfy(a -> {
            assertThat(a.urls()).hasSize(2);
            assertThat(a.url()).isEqualTo(RecursosPpOcr.ESPEJO + a.nombre());
            assertThat(a.urls().get(1)).startsWith("https://").doesNotContain("github.com/Minepacman");
        });
    }

    @Test
    void laListaDelFlujoCoincideConElPrograma() throws IOException {
        Path lista = Path.of("packaging/ocr-modelos/recursos.txt");
        Map<String, String[]> lineas = Files.readAllLines(lista).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.split("\\s+"))
                .collect(Collectors.toMap(p -> p[2], p -> p));
        assertThat(lineas.keySet()).containsExactlyInAnyOrderElementsOf(
                RecursosPpOcr.TODOS.stream().map(RecursosPpOcr.Archivo::nombre).toList());
        for (RecursosPpOcr.Archivo a : RecursosPpOcr.TODOS) {
            String[] p = lineas.get(a.nombre());
            assertThat(p).as(a.nombre()).hasSize(4);
            assertThat(p[0]).as("sha256 de " + a.nombre()).isEqualTo(a.sha256());
            assertThat(Long.parseLong(p[1])).as("bytes de " + a.nombre()).isEqualTo(a.bytes());
            assertThat(p[3]).as("fuente original de " + a.nombre()).isEqualTo(a.urls().get(1));
        }
    }

    @Test
    void siElEspejoFallaSeBajaDeLaFuenteOriginal(@TempDir Path dir) throws Exception {
        byte[] bueno = "modelo de prueba".getBytes(StandardCharsets.UTF_8);
        byte[] otro = "modelo cambiado!".getBytes(StandardCharsets.UTF_8);   // mismo tamaño, otra suma
        List<String> pedidos = new ArrayList<>();
        Map<String, byte[]> rutas = new ConcurrentHashMap<>(Map.of(
                "/original/a.onnx", bueno,
                "/espejo-malo/b.onnx", otro,
                "/original/b.onnx", bueno,
                "/espejo/c.onnx", bueno));
        HttpServer servidor = servidor(rutas, pedidos);
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            String sha = sha256(bueno);
            List<RecursosPpOcr.Archivo> archivos = List.of(
                    // espejo sin el archivo (404) → original
                    new RecursosPpOcr.Archivo("a.onnx", List.of(base + "/espejo/a.onnx", base + "/original/a.onnx"), bueno.length, sha),
                    // espejo con un archivo distinto (SHA-256) → original
                    new RecursosPpOcr.Archivo("b.onnx", List.of(base + "/espejo-malo/b.onnx", base + "/original/b.onnx"), bueno.length, sha),
                    // espejo bien → la fuente original ni se toca
                    new RecursosPpOcr.Archivo("c.onnx", List.of(base + "/espejo/c.onnx", base + "/original/c.onnx"), bueno.length, sha));
            RecursosPpOcr r = new RecursosPpOcr(dir, archivos);
            List<String> mensajes = new ArrayList<>();
            r.descargar(mensajes::add);
            assertThat(r.listos()).isTrue();
            for (RecursosPpOcr.Archivo a : archivos) {
                assertThat(Files.readAllBytes(r.ruta(a))).isEqualTo(bueno);
                assertThat(RecursosPpOcr.coincide(r.ruta(a), a)).isTrue();
            }
            assertThat(pedidos).containsExactly("/espejo/a.onnx", "/original/a.onnx",
                    "/espejo-malo/b.onnx", "/original/b.onnx", "/espejo/c.onnx");
            assertThat(mensajes).last().asString().contains("listos");
            // sin temporales
            try (var archivosEnCarpeta = Files.list(dir.resolve(RecursosPpOcr.CARPETA))) {
                assertThat(archivosEnCarpeta.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".parcial"));
            }
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    void siTodosLosOrigenesFallanLanzaYNoDejaNada(@TempDir Path dir) throws Exception {
        HttpServer servidor = servidor(Map.of(), new ArrayList<>());
        try {
            String base = "http://127.0.0.1:" + servidor.getAddress().getPort();
            RecursosPpOcr.Archivo a = new RecursosPpOcr.Archivo("x.onnx",
                    List.of(base + "/espejo/x.onnx", base + "/original/x.onnx"), 10, "0".repeat(64));
            RecursosPpOcr r = new RecursosPpOcr(dir, List.of(a));
            assertThatThrownBy(() -> r.descargar(m -> { })).isInstanceOf(IOException.class).hasMessageContaining("404");
            assertThat(Files.exists(r.ruta(a))).isFalse();
            assertThat(r.listos()).isFalse();
        } finally {
            servidor.stop(0);
        }
    }

    private static HttpServer servidor(Map<String, byte[]> rutas, List<String> pedidos) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", ex -> {
            String ruta = ex.getRequestURI().getPath();
            synchronized (pedidos) {
                pedidos.add(ruta);
            }
            byte[] cuerpo = rutas.get(ruta);
            if (cuerpo == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, cuerpo.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(cuerpo);
                }
            }
            ex.close();
        });
        s.start();
        return s;
    }

    private static String sha256(byte[] datos) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
    }
}
