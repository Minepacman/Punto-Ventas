package me.eliassanchezfernandez.puntodeventa.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Revisa los paquetes de iconos generados por herramientas/iconos/generar-paquetes.mjs
 * (src/main/resources/iconos/*.json): que traigan todos los iconos lógicos en todos los estilos, que cada
 * trazo sea un path SVG válido dentro de la caja de 24x24 y que el conjunto siga siendo pequeño.
 */
class PaquetesIconosDatosTest {

    private static final List<String> PAQUETES = List.of("tabler", "phosphor", "heroicons", "material", "iconoir");
    private static final long TAMANO_MAXIMO = 1024 * 1024;

    /** Solo comandos de path SVG y números (sin letras de exponente sueltas ni otros caracteres). */
    private static final Pattern PATH_SVG = Pattern.compile("^[Mm][MmLlHhVvCcSsQqTtAaZz0-9eE.,+\\-\\s]*$");
    private static final Pattern NUMERO = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern COMANDO = Pattern.compile("([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cadaPaqueteTraeTodosLosIconosEnTodosLosEstilos() throws IOException {
        Set<String> nombres = nombresLogicos();
        long total = 0;
        for (String id : PAQUETES) {
            byte[] bytes = leer("/iconos/" + id + ".json");
            total += bytes.length;
            System.out.printf("iconos/%s.json: %.1f KB%n", id, bytes.length / 1024.0);
            JsonNode raiz = json.readTree(bytes);
            assertEquals(id, raiz.path("id").asText(), "id del paquete " + id);
            for (String campo : List.of("nombre", "version", "licencia", "autor", "url")) {
                assertFalse(raiz.path(campo).asText().isBlank(), id + ": falta «" + campo + "»");
            }
            assertTrue(raiz.path("origen").isObject(), id + ": falta «origen»");
            JsonNode estilos = raiz.path("estilos");
            assertTrue(estilos.isObject() && estilos.size() > 0, id + ": sin estilos");
            for (Iterator<Map.Entry<String, JsonNode>> it = estilos.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> estilo = it.next();
                String donde = id + "/" + estilo.getKey();
                String tipo = estilo.getValue().path("tipo").asText();
                assertTrue(tipo.equals("trazo") || tipo.equals("relleno"), donde + ": tipo «" + tipo + "»");
                if (tipo.equals("trazo")) {
                    double grosor = estilo.getValue().path("grosor").asDouble(0);
                    assertTrue(grosor > 0 && grosor <= 4, donde + ": grosor " + grosor);
                }
                JsonNode iconos = estilo.getValue().path("iconos");
                for (String nombre : nombres) {
                    JsonNode trazos = iconos.get(nombre);
                    assertNotNull(trazos, donde + ": falta el icono «" + nombre + "»");
                    assertTrue(trazos.isArray() && trazos.size() > 0, donde + "/" + nombre + ": sin trazos");
                    assertTrue(raiz.path("origen").hasNonNull(nombre), id + ": «origen» no dice de dónde sale " + nombre);
                    for (JsonNode t : trazos) revisarTrazo(t.asText(), tipo, donde + "/" + nombre);
                }
            }
        }
        System.out.printf("Total de los paquetes de iconos: %.1f KB%n", total / 1024.0);
        assertTrue(total < TAMANO_MAXIMO, "Los paquetes de iconos pesan " + total + " bytes (máximo 1 MB)");
        assertNotNull(PaquetesIconosDatosTest.class.getResource("/iconos/LICENCIAS.txt"), "falta iconos/LICENCIAS.txt");
    }

    @Test
    void losNombresLogicosIncluyenLosDeLaPaginaMovil() throws IOException {
        Set<String> movil = nombresMovil();
        assertTrue(movil.size() >= 10, "No se leyeron los iconos de movil/index.html: " + movil);
        assertTrue(movil.contains("camion") && movil.contains("grafica"), "ICONOS de la página móvil: " + movil);
    }

    private static void revisarTrazo(String crudo, String tipo, String donde) {
        int i = 0;
        while (i < crudo.length() && "~!#^".indexOf(crudo.charAt(i)) >= 0) i++;
        String prefijos = crudo.substring(0, i);
        String d = crudo.substring(i);
        if (prefijos.contains("#") && !tipo.equals("trazo")) fail(donde + ": «#» solo va en estilos de trazo");
        if (prefijos.contains("^") && !tipo.equals("relleno")) fail(donde + ": «^» solo va en estilos de relleno");
        assertTrue(PATH_SVG.matcher(d).matches(), donde + ": path inválido «" + abreviar(d) + "»");
        // Coordenadas de los comandos absolutos dentro de la caja de 24 (con margen)
        Matcher c = COMANDO.matcher(d);
        while (c.find()) {
            char cmd = c.group(1).charAt(0);
            if (!Character.isUpperCase(cmd) || cmd == 'Z') continue;
            List<Double> n = numeros(c.group(2));
            List<Double> revisar = new ArrayList<>();
            if (cmd == 'H' || cmd == 'V') {
                revisar.addAll(n);
            } else if (cmd == 'A') {
                for (int k = 0; k + 6 < n.size(); k += 7) {
                    revisar.add(n.get(k + 5));
                    revisar.add(n.get(k + 6));
                }
            } else {
                revisar.addAll(n);
            }
            for (double v : revisar) {
                assertTrue(v >= -2 && v <= 26, donde + ": coordenada " + v + " fuera de la caja en «" + abreviar(d) + "»");
            }
        }
    }

    private static List<Double> numeros(String texto) {
        List<Double> n = new ArrayList<>();
        Matcher m = NUMERO.matcher(texto);
        while (m.find()) n.add(Double.parseDouble(m.group()));
        return n;
    }

    private static String abreviar(String d) {
        return d.length() > 60 ? d.substring(0, 60) + "…" : d;
    }

    private static Set<String> nombresLogicos() throws IOException {
        Set<String> nombres = new LinkedHashSet<>(Iconos.nombres());
        assertFalse(nombres.isEmpty(), "Iconos.nombres() vacío");
        nombres.addAll(nombresMovil());
        return nombres;
    }

    /** Claves del objeto {@code const ICONOS = {...}} de la página móvil. */
    private static Set<String> nombresMovil() throws IOException {
        String html = new String(leer("/movil/index.html"), StandardCharsets.UTF_8);
        Matcher bloque = Pattern.compile("const ICONOS = \\{(.*?)\\n\\};", Pattern.DOTALL).matcher(html);
        assertTrue(bloque.find(), "No encontré «const ICONOS = {...}» en movil/index.html");
        Set<String> nombres = new LinkedHashSet<>();
        Matcher clave = Pattern.compile("(?m)^\\s*(\\w+)\\s*:").matcher(bloque.group(1));
        while (clave.find()) nombres.add(clave.group(1));
        return nombres;
    }

    private static byte[] leer(String recurso) throws IOException {
        try (InputStream in = PaquetesIconosDatosTest.class.getResourceAsStream(recurso)) {
            assertNotNull(in, "Falta el recurso " + recurso);
            return in.readAllBytes();
        }
    }
}
