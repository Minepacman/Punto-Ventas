package me.eliassanchezfernandez.puntodeventa.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import me.eliassanchezfernandez.puntodeventa.service.CatalogoCodigosService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El catálogo de códigos de barras que viaja en el jar (./gradlew actualizarCatalogoIncluido):
 * existe, pesa poco, su sha256 es el que anuncia su version.json (el mismo que publica el
 * repositorio, así la revisión semanal no lo vuelve a bajar) y se lee con CatalogoCsv.
 */
class CatalogoIncluidoTest {

    @Test
    void elCatalogoIncluidoEsCoherenteYLigero() throws Exception {
        byte[] gz;
        try (InputStream in = CatalogoIncluidoTest.class.getResourceAsStream(CatalogoCodigosService.RECURSO_INCLUIDO)) {
            assertThat(in).as("catalogo-mx.csv.gz en el classpath").isNotNull();
            gz = in.readAllBytes();
        }
        assertThat(gz.length).as("comprimido ≤ 3 MB").isLessThanOrEqualTo(3 * 1024 * 1024);
        byte[] csv = new GZIPInputStream(new java.io.ByteArrayInputStream(gz)).readAllBytes();

        JsonNode version;
        try (InputStream in = CatalogoIncluidoTest.class.getResourceAsStream(CatalogoCodigosService.RECURSO_VERSION_INCLUIDA)) {
            assertThat(in).isNotNull();
            version = new ObjectMapper().readTree(in);
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
        assertThat(sha).isEqualTo(version.path("archivos").path("catalogo-mx.csv").path("sha256").asText());
        assertThat(version.path("fecha").asText()).matches("\\d{4}-\\d{2}-\\d{2}");

        List<CatalogoCodigo> filas = CatalogoCsv.leer(new StringReader(new String(csv, StandardCharsets.UTF_8)));
        assertThat(filas.size()).isGreaterThan(20_000);
        assertThat(filas).anyMatch(f -> f.getCodigo().equals("7501055300075") && f.getNombre().contains("Coca"));
    }

    @Test
    void seLeeEnFlujoSinCargarTodoEnMemoria() throws Exception {
        try (InputStream in = CatalogoIncluidoTest.class.getResourceAsStream(CatalogoCodigosService.RECURSO_INCLUIDO);
             InputStreamReader r = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)) {
            int[] n = {0};
            CatalogoCsv.Resultado res = CatalogoCsv.leer(r, f -> n[0]++);
            assertThat(res.validas()).isEqualTo(n[0]);
        }
    }
}
