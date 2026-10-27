package me.eliassanchezfernandez.puntodeventa.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Concepto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Impuesto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Receptor;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.MotivoCancelacion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cliente de Facturama sin red: JSON enviado, respuestas de archivo (éxito, validación, cancelación) y fallas. */
class FacturamaProveedorTest {

    private static final CredencialesPac CRED = new CredencialesPac("usuario@tienda.com", "secreta", true);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static CfdiDocumento documento() {
        Concepto c = new Concepto("50202306", "7501000000001", "Refresco 600 ml", "XBO", "Botella",
                new BigDecimal("2"), new BigDecimal("15.517241"), new BigDecimal("31.03"), null, "02",
                List.of(new Impuesto("IVA", new BigDecimal("31.03"), new BigDecimal("0.160000"), new BigDecimal("4.97"))),
                new BigDecimal("36.00"));
        return new CfdiDocumento("A", "7", "78240", "01", "PUE",
                new Receptor("GODE561231GR8", "JUAN PEREZ", "612", "78240", "G03"),
                List.of(c), null, "V-42", "Venta 42");
    }

    @Test
    void timbrarEnviaElJsonDeFacturamaConBasicAuthYLeeElUuid() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.recurso(201, "timbrado_ok.json"));
        FacturamaProveedor pac = new FacturamaProveedor(CRED, t);

        ProveedorCfdi.Timbrado r = pac.timbrar(documento());

        assertThat(r.uuid()).isEqualTo("A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D");
        assertThat(r.idExterno()).isEqualTo("6ac7d6a0b7a84d4a9d2b2d5c9f3e1a11");
        assertThat(r.serie()).isEqualTo("A");
        assertThat(r.folio()).isEqualTo("7");
        assertThat(r.total()).isEqualByComparingTo("116.0");
        assertThat(r.fecha()).hasYear(2026).hasMonth(java.time.Month.OCTOBER).hasDayOfMonth(26);

        TransporteHttp.Solicitud s = t.ultima();
        assertThat(s.metodo()).isEqualTo("POST");
        assertThat(s.uri().toString()).isEqualTo("https://apisandbox.facturama.mx/3/cfdis");
        String esperado = "Basic " + Base64.getEncoder().encodeToString("usuario@tienda.com:secreta".getBytes(StandardCharsets.UTF_8));
        assertThat(s.cabeceras()).containsEntry("Authorization", esperado);
        assertThat(s.cabeceras().get("Content-Type")).startsWith("application/json");

        JsonNode j = JSON.readTree(s.cuerpo());
        assertThat(j.path("CfdiType").asText()).isEqualTo("I");
        assertThat(j.path("PaymentMethod").asText()).isEqualTo("PUE");
        assertThat(j.path("PaymentForm").asText()).isEqualTo("01");
        assertThat(j.path("ExpeditionPlace").asText()).isEqualTo("78240");
        assertThat(j.path("Currency").asText()).isEqualTo("MXN");
        assertThat(j.path("Exportation").asText()).isEqualTo("01");
        assertThat(j.path("Serie").asText()).isEqualTo("A");
        assertThat(j.path("Folio").asText()).isEqualTo("7");
        assertThat(j.path("OrderNumber").asText()).isEqualTo("V-42");
        assertThat(j.has("GlobalInformation")).isFalse();
        JsonNode rec = j.path("Receiver");
        assertThat(rec.path("Rfc").asText()).isEqualTo("GODE561231GR8");
        assertThat(rec.path("Name").asText()).isEqualTo("JUAN PEREZ");
        assertThat(rec.path("CfdiUse").asText()).isEqualTo("G03");
        assertThat(rec.path("FiscalRegime").asText()).isEqualTo("612");
        assertThat(rec.path("TaxZipCode").asText()).isEqualTo("78240");
        JsonNode item = j.path("Items").get(0);
        assertThat(item.path("ProductCode").asText()).isEqualTo("50202306");
        assertThat(item.path("UnitCode").asText()).isEqualTo("XBO");
        assertThat(item.path("Quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(item.path("UnitPrice").decimalValue()).isEqualByComparingTo("15.517241");
        assertThat(item.path("Subtotal").decimalValue()).isEqualByComparingTo("31.03");
        assertThat(item.has("Discount")).isFalse();
        assertThat(item.path("TaxObject").asText()).isEqualTo("02");
        JsonNode tax = item.path("Taxes").get(0);
        assertThat(tax.path("Name").asText()).isEqualTo("IVA");
        assertThat(tax.path("Rate").decimalValue()).isEqualByComparingTo("0.16");
        assertThat(tax.path("Base").decimalValue()).isEqualByComparingTo("31.03");
        assertThat(tax.path("Total").decimalValue()).isEqualByComparingTo("4.97");
        assertThat(tax.path("IsRetention").asBoolean()).isFalse();
        assertThat(item.path("Total").decimalValue()).isEqualByComparingTo("36.00");
        // Las credenciales no van en el cuerpo
        assertThat(new String(s.cuerpo(), StandardCharsets.UTF_8)).doesNotContain("secreta");
    }

    @Test
    void laFacturaGlobalLlevaInformacionGlobal() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.recurso(201, "timbrado_ok.json"));
        CfdiDocumento d = documento();
        CfdiDocumento global = new CfdiDocumento(d.serie(), d.folio(), d.lugarExpedicion(), d.formaPago(), d.metodoPago(),
                new Receptor(Rfc.PUBLICO_EN_GENERAL, "PUBLICO EN GENERAL", "616", "78240", "S01"), d.conceptos(),
                new CfdiDocumento.InformacionGlobal("01", "10", "2026"), "G-1", null);
        new FacturamaProveedor(CRED, t).timbrar(global);
        JsonNode j = JSON.readTree(t.ultima().cuerpo());
        assertThat(j.path("GlobalInformation").path("Periodicity").asText()).isEqualTo("01");
        assertThat(j.path("GlobalInformation").path("Months").asText()).isEqualTo("10");
        assertThat(j.path("GlobalInformation").path("Year").asText()).isEqualTo("2026");
        assertThat(j.path("Receiver").path("Rfc").asText()).isEqualTo("XAXX010101000");
    }

    @Test
    void unErrorDeValidacionEsDefinitivoYTraeElDetalle() {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.recurso(400, "error_validacion.json"));
        FacturamaProveedor pac = new FacturamaProveedor(CRED, t);
        assertThatThrownBy(() -> pac.timbrar(documento()))
                .isInstanceOf(PacException.class)
                .hasMessageContaining("The request is invalid.")
                .hasMessageContaining("Receiver.Name: El nombre del receptor")
                .hasMessageContaining("99999999")
                .satisfies(e -> {
                    assertThat(((PacException) e).getTipo()).isEqualTo(PacException.Tipo.VALIDACION);
                    assertThat(((PacException) e).esDefinitivo()).isTrue();
                    assertThat(((PacException) e).getCodigoHttp()).isEqualTo(400);
                });
    }

    @Test
    void credencialesRechazadasYErroresDeRedSeDistinguen() {
        FacturamaProveedor sinAuth = new FacturamaProveedor(CRED, new TransporteFicticio(s -> TransporteFicticio.texto(401, "")));
        assertThatThrownBy(sinAuth::probarConexion).isInstanceOf(PacException.class)
                .hasMessageContaining("usuario o la contraseña")
                .satisfies(e -> assertThat(((PacException) e).esDefinitivo()).isTrue());

        FacturamaProveedor caido = new FacturamaProveedor(CRED, new TransporteFicticio(s -> TransporteFicticio.texto(503, "<html>")));
        assertThatThrownBy(() -> caido.timbrar(documento())).isInstanceOf(PacException.class)
                .satisfies(e -> {
                    assertThat(((PacException) e).getTipo()).isEqualTo(PacException.Tipo.SERVIDOR);
                    assertThat(((PacException) e).esDefinitivo()).isFalse();   // pudo haberse timbrado
                });

        FacturamaProveedor lento = new FacturamaProveedor(CRED, new TransporteFicticio(s -> null).queTardeDemasiado());
        assertThatThrownBy(() -> lento.timbrar(documento())).isInstanceOf(PacException.class)
                .hasMessageContaining("tardó demasiado")
                .satisfies(e -> assertThat(((PacException) e).getTipo()).isEqualTo(PacException.Tipo.RED));

        FacturamaProveedor sinRed = new FacturamaProveedor(CRED, new TransporteFicticio(s -> null)
                .queFalleCon(new IOException("No route to host")));
        assertThatThrownBy(() -> sinRed.timbrar(documento())).isInstanceOf(PacException.class)
                .hasMessageContaining("internet").hasMessageNotContaining("secreta")
                .satisfies(e -> assertThat(((PacException) e).esDefinitivo()).isFalse());

        // Respuesta 2xx sin UUID: no se da por timbrado
        FacturamaProveedor raro = new FacturamaProveedor(CRED, new TransporteFicticio(s -> TransporteFicticio.texto(201, "{\"Id\":\"x\"}")));
        assertThatThrownBy(() -> raro.timbrar(documento())).isInstanceOf(PacException.class).hasMessageContaining("UUID");
    }

    @Test
    void descargaPdfYXmlDecodificandoBase64() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.recurso(200, "archivo_pdf.json"));
        FacturamaProveedor pac = new FacturamaProveedor(CRED, t);
        ProveedorCfdi.Archivo a = pac.descargar("6ac7d6a0", ProveedorCfdi.Formato.PDF);
        assertThat(new String(a.contenido(), StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
        assertThat(a.tipoContenido()).isEqualTo("application/pdf");
        assertThat(t.ultima().uri().toString()).isEqualTo("https://apisandbox.facturama.mx/cfdi/pdf/issued/6ac7d6a0");

        pac.descargar("6ac7d6a0", ProveedorCfdi.Formato.XML);
        assertThat(t.ultima().uri().toString()).endsWith("/cfdi/xml/issued/6ac7d6a0");
        assertThat(t.ultima().metodo()).isEqualTo("GET");
    }

    @Test
    void cancelaConMotivoYSustitucion() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.recurso(200, "cancelacion_ok.json"));
        FacturamaProveedor pac = new FacturamaProveedor(CRED, t);

        ProveedorCfdi.Cancelacion c = pac.cancelar("6ac7d6a0", MotivoCancelacion.NO_SE_LLEVO_A_CABO, null);
        assertThat(c.aceptada()).isTrue();
        assertThat(c.enEspera()).isFalse();
        assertThat(c.estado()).isEqualTo("canceled");
        assertThat(t.ultima().metodo()).isEqualTo("DELETE");
        assertThat(t.ultima().uri().toString()).isEqualTo("https://apisandbox.facturama.mx/cfdi/6ac7d6a0?type=issued&motive=03");

        pac.cancelar("6ac7d6a0", MotivoCancelacion.ERRORES_CON_RELACION, "AAAA-BBBB");
        assertThat(t.ultima().uri().toString()).endsWith("?type=issued&motive=01&uuidReplacement=AAAA-BBBB");

        assertThatThrownBy(() -> pac.cancelar("x", MotivoCancelacion.ERRORES_CON_RELACION, " "))
                .isInstanceOf(PacException.class).hasMessageContaining("UUID");

        TransporteFicticio pendiente = new TransporteFicticio(s -> TransporteFicticio.recurso(200, "cancelacion_pendiente.json"));
        ProveedorCfdi.Cancelacion p = new FacturamaProveedor(CRED, pendiente).cancelar("x", MotivoCancelacion.ERRORES_SIN_RELACION, null);
        assertThat(p.aceptada()).isFalse();
        assertThat(p.enEspera()).isTrue();
        assertThat(p.mensaje()).contains("72 horas");
    }

    @Test
    void buscaPorNumeroDeOrdenParaNoDuplicarYEnviaCorreo() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> {
            String u = s.uri().toString();
            if (u.contains("orderNumber=V-42")) return TransporteFicticio.recurso(200, "lista_por_orden.json");
            if (u.contains("orderNumber=")) return TransporteFicticio.texto(200, "[]");
            if (u.contains("/cfdi/6ac7d6a0b7a84d4a9d2b2d5c9f3e1a11?type=issued")) return TransporteFicticio.recurso(200, "timbrado_ok.json");
            return TransporteFicticio.texto(200, "{\"success\":true}");
        });
        FacturamaProveedor pac = new FacturamaProveedor(CRED, t);

        Optional<ProveedorCfdi.Timbrado> encontrado = pac.buscarPorNumeroOrden("V-42");
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().uuid()).isEqualTo("A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D");
        assertThat(t.enviadas.get(0).uri().toString()).isEqualTo("https://apisandbox.facturama.mx/cfdi?type=issued&status=active&orderNumber=V-42");
        assertThat(pac.buscarPorNumeroOrden("V-99")).isEmpty();

        pac.enviarPorCorreo("6ac7d6a0", "cliente@correo.com");
        assertThat(t.ultima().metodo()).isEqualTo("POST");
        assertThat(t.ultima().uri().toString()).isEqualTo(
                "https://apisandbox.facturama.mx/cfdi?cfdiType=issued&cfdiId=6ac7d6a0&email=cliente%40correo.com");
    }

    @Test
    void produccionUsaOtroHost() throws Exception {
        TransporteFicticio t = new TransporteFicticio(s -> TransporteFicticio.texto(200, "[]"));
        new FacturamaProveedor(new CredencialesPac("u", "p", false), t).probarConexion();
        assertThat(t.ultima().uri().toString()).startsWith("https://api.facturama.mx/");
    }
}
