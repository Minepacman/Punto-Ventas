package me.eliassanchezfernandez.puntodeventa.facturacion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Concepto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Impuesto;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * PAC Facturama, "API Web" (https://api.facturama.mx, pruebas en
 * https://apisandbox.facturama.mx) con autenticación HTTP Basic.
 *
 * Por qué API Web y no API Multiemisor (api-lite): el negocio emite SUS
 * propias facturas con UN RFC. En la API Web el CSD (.cer/.key y contraseña)
 * se carga UNA vez en el portal de Facturama y la API solo recibe los datos
 * del comprobante; el programa nunca maneja la llave privada del negocio.
 * La API Multiemisor existe para plataformas que timbran a nombre de muchos
 * contribuyentes y obliga a subir y resguardar los CSD por la API.
 *
 * Endpoints usados:
 *  - POST   /3/cfdis                                   timbrar CFDI 4.0
 *  - GET    /cfdi?type=issued&status=active&orderNumber=X   buscar por referencia (reintentos)
 *  - GET    /cfdi/{id}?type=issued                     detalle
 *  - GET    /cfdi/pdf/issued/{id}  y  /cfdi/xml/issued/{id}   archivos (Content en base64)
 *  - DELETE /cfdi/{id}?type=issued&motive=0X[&uuidReplacement=UUID]   cancelar
 *  - POST   /cfdi?cfdiType=issued&cfdiId={id}&email=correo    enviar por correo
 *  - GET    /catalogs/paymentforms                     probar credenciales
 *
 * Las credenciales viajan solo en la cabecera Authorization; no se registran
 * ni aparecen en los mensajes de error.
 */
public class FacturamaProveedor implements ProveedorCfdi {

    public static final String URL_SANDBOX = "https://apisandbox.facturama.mx";
    public static final String URL_PRODUCCION = "https://api.facturama.mx";

    static final Duration TIMEOUT_CONEXION = Duration.ofSeconds(10);
    static final Duration TIMEOUT_LECTURA = Duration.ofSeconds(60);
    static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    static final int MAX_ARCHIVO_BYTES = 12 * 1024 * 1024;   // el PDF viene en base64 dentro del JSON

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String autorizacion;
    private final TransporteHttp transporte;

    public FacturamaProveedor(CredencialesPac credenciales) {
        this(credenciales, new TransporteHttp.Real(TIMEOUT_CONEXION));
    }

    public FacturamaProveedor(CredencialesPac credenciales, TransporteHttp transporte) {
        this(credenciales.sandbox() ? URL_SANDBOX : URL_PRODUCCION, credenciales, transporte);
    }

    FacturamaProveedor(String baseUrl, CredencialesPac credenciales, TransporteHttp transporte) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.autorizacion = "Basic " + Base64.getEncoder().encodeToString(
                (credenciales.usuario() + ":" + credenciales.contrasena()).getBytes(StandardCharsets.UTF_8));
        this.transporte = transporte;
    }

    // ── ProveedorCfdi ────────────────────────────────────────────────────

    @Override
    public Timbrado timbrar(CfdiDocumento doc) throws PacException {
        ObjectNode cuerpo = aJson(doc);
        JsonNode r = llamar("POST", "/3/cfdis", cuerpo, MAX_JSON_BYTES);
        return timbradoDe(r);
    }

    @Override
    public Optional<Timbrado> buscarPorNumeroOrden(String numeroOrden) throws PacException {
        JsonNode lista = llamar("GET", "/cfdi?type=issued&status=active&orderNumber=" + codificar(numeroOrden), null, MAX_JSON_BYTES);
        if (lista == null || !lista.isArray() || lista.isEmpty()) return Optional.empty();
        for (JsonNode item : lista) {
            if (numeroOrden.equals(item.path("OrderNumber").asText(numeroOrden))) {
                String id = item.path("Id").asText(null);
                if (id == null || id.isBlank()) continue;
                JsonNode detalle = llamar("GET", "/cfdi/" + codificar(id) + "?type=issued", null, MAX_JSON_BYTES);
                return Optional.of(timbradoDe(detalle));
            }
        }
        return Optional.empty();
    }

    @Override
    public Archivo descargar(String idExterno, Formato formato) throws PacException {
        String f = formato == Formato.PDF ? "pdf" : "xml";
        JsonNode r = llamar("GET", "/cfdi/" + f + "/issued/" + codificar(idExterno), null, MAX_ARCHIVO_BYTES);
        String contenido = r.path("Content").asText(null);
        if (contenido == null || contenido.isBlank()) {
            throw new PacException(PacException.Tipo.RESPUESTA, "El PAC no devolvió el archivo " + f.toUpperCase() + ".");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(contenido.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new PacException(PacException.Tipo.RESPUESTA, "El archivo " + f.toUpperCase() + " llegó dañado del PAC.");
        }
        String tipo = r.path("ContentType").asText(formato == Formato.PDF ? "application/pdf" : "application/xml");
        return new Archivo(bytes, tipo);
    }

    @Override
    public Cancelacion cancelar(String idExterno, MotivoCancelacion motivo, String uuidSustitucion) throws PacException {
        if (motivo == null) throw new PacException(PacException.Tipo.VALIDACION, "Indica el motivo de cancelación.");
        if (motivo.requiereSustitucion() && (uuidSustitucion == null || uuidSustitucion.isBlank())) {
            throw new PacException(PacException.Tipo.VALIDACION, "El motivo 01 requiere el UUID de la factura que sustituye.");
        }
        String ruta = "/cfdi/" + codificar(idExterno) + "?type=issued&motive=" + motivo.getClave();
        if (uuidSustitucion != null && !uuidSustitucion.isBlank()) ruta += "&uuidReplacement=" + codificar(uuidSustitucion.trim());
        JsonNode r = llamar("DELETE", ruta, null, MAX_JSON_BYTES);
        String estado = r == null ? "" : r.path("Status").asText("");
        String mensaje = r == null ? null : r.path("Message").asText(null);
        String e = estado.toLowerCase();
        boolean enEspera = e.contains("pending") || e.contains("proceso") || e.contains("espera");
        boolean aceptada = e.isEmpty() || e.contains("cancel") || e.contains("accept");
        return new Cancelacion(aceptada && !enEspera, enEspera, estado, mensaje);
    }

    @Override
    public void enviarPorCorreo(String idExterno, String email) throws PacException {
        if (email == null || email.isBlank()) throw new PacException(PacException.Tipo.VALIDACION, "Indica el correo del receptor.");
        llamar("POST", "/cfdi?cfdiType=issued&cfdiId=" + codificar(idExterno) + "&email=" + codificar(email.trim()),
                JSON.createObjectNode(), MAX_JSON_BYTES);
    }

    @Override
    public void probarConexion() throws PacException {
        JsonNode r = llamar("GET", "/catalogs/paymentforms", null, MAX_JSON_BYTES);
        if (r == null || !r.isArray()) {
            throw new PacException(PacException.Tipo.RESPUESTA, "El PAC respondió, pero no con lo esperado.");
        }
    }

    // ── JSON del comprobante (modelo de Facturama) ───────────────────────

    static ObjectNode aJson(CfdiDocumento d) {
        ObjectNode n = JSON.createObjectNode();
        n.put("NameId", "1");                                 // tipo de documento: factura
        n.put("CfdiType", CfdiDocumento.TIPO_INGRESO);
        n.put("Currency", CfdiDocumento.MONEDA);
        n.put("ExpeditionPlace", d.lugarExpedicion());
        n.put("PaymentForm", d.formaPago());
        n.put("PaymentMethod", d.metodoPago());
        n.put("Exportation", CfdiDocumento.EXPORTACION_NO_APLICA);
        if (d.serie() != null && !d.serie().isBlank()) n.put("Serie", d.serie());
        if (d.folio() != null && !d.folio().isBlank()) n.put("Folio", d.folio());
        if (d.numeroOrden() != null) n.put("OrderNumber", d.numeroOrden());
        if (d.observaciones() != null) n.put("Observations", d.observaciones());

        ObjectNode r = n.putObject("Receiver");
        r.put("Rfc", d.receptor().rfc());
        r.put("Name", d.receptor().nombre());
        r.put("CfdiUse", d.receptor().usoCfdi());
        r.put("FiscalRegime", d.receptor().regimenFiscal());
        r.put("TaxZipCode", d.receptor().codigoPostal());

        if (d.informacionGlobal() != null) {
            ObjectNode g = n.putObject("GlobalInformation");
            g.put("Periodicity", d.informacionGlobal().periodicidad());
            g.put("Months", d.informacionGlobal().meses());
            g.put("Year", d.informacionGlobal().anio());
        }

        ArrayNode items = n.putArray("Items");
        for (Concepto c : d.conceptos()) {
            ObjectNode i = items.addObject();
            i.put("ProductCode", c.claveProdServ());
            if (c.noIdentificacion() != null) i.put("IdentificationNumber", recortar(c.noIdentificacion(), 100));
            i.put("Description", recortar(c.descripcion(), 1000));
            i.put("Unit", recortar(c.unidad(), 20));
            i.put("UnitCode", c.claveUnidad());
            i.put("Quantity", c.cantidad());
            i.put("UnitPrice", c.valorUnitario());
            i.put("Subtotal", c.importe());
            if (c.descuento() != null && c.descuento().signum() > 0) i.put("Discount", c.descuento());
            i.put("TaxObject", c.objetoImp());
            if (c.impuestos() != null && !c.impuestos().isEmpty()) {
                ArrayNode taxes = i.putArray("Taxes");
                for (Impuesto imp : c.impuestos()) {
                    ObjectNode t = taxes.addObject();
                    t.put("Name", imp.nombre());
                    t.put("Rate", imp.tasa());
                    t.put("Base", imp.base());
                    t.put("Total", imp.importe());
                    t.put("IsRetention", false);
                }
            }
            i.put("Total", c.total());
        }
        return n;
    }

    private static Timbrado timbradoDe(JsonNode r) throws PacException {
        if (r == null || !r.isObject()) throw new PacException(PacException.Tipo.RESPUESTA, "El PAC no devolvió el comprobante.");
        String id = r.path("Id").asText(null);
        String uuid = r.path("Complement").path("TaxStamp").path("Uuid").asText(null);
        if (id == null || uuid == null || uuid.isBlank()) {
            throw new PacException(PacException.Tipo.RESPUESTA, "El PAC respondió sin folio fiscal (UUID); revisa el comprobante en el portal de Facturama.");
        }
        LocalDateTime fecha = null;
        String f = r.path("Complement").path("TaxStamp").path("Date").asText(r.path("Date").asText(null));
        if (f != null) {
            try { fecha = LocalDateTime.parse(f.length() > 19 ? f.substring(0, 19) : f); } catch (DateTimeParseException ignorada) { }
        }
        BigDecimal total = r.hasNonNull("Total") ? new BigDecimal(r.get("Total").asText()) : null;
        return new Timbrado(id, uuid.toUpperCase(), r.path("Serie").asText(null), r.path("Folio").asText(null),
                fecha != null ? fecha : LocalDateTime.now(), total);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private JsonNode llamar(String metodo, String ruta, JsonNode cuerpo, int maxBytes) throws PacException {
        Map<String, String> cabeceras = new LinkedHashMap<>();
        cabeceras.put("Authorization", autorizacion);
        cabeceras.put("Accept", "application/json");
        byte[] bytes = null;
        if (cuerpo != null) {
            cabeceras.put("Content-Type", "application/json; charset=utf-8");
            try {
                bytes = JSON.writeValueAsBytes(cuerpo);
            } catch (IOException e) {
                throw new PacException(PacException.Tipo.RESPUESTA, "No se pudo armar la petición al PAC.");
            }
        }
        TransporteHttp.Respuesta r;
        try {
            r = transporte.enviar(new TransporteHttp.Solicitud(metodo, URI.create(baseUrl + ruta), cabeceras, bytes, TIMEOUT_LECTURA), maxBytes);
        } catch (HttpTimeoutException e) {
            throw new PacException(PacException.Tipo.RED, "El PAC tardó demasiado en responder. Revisa la conexión a internet.", 0, e);
        } catch (IOException e) {
            throw new PacException(PacException.Tipo.RED, "No se pudo conectar con el PAC (" + resumen(e) + "). Revisa la conexión a internet.", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PacException(PacException.Tipo.RED, "La operación se interrumpió.", 0, e);
        }
        return interpretar(r);
    }

    private static JsonNode interpretar(TransporteHttp.Respuesta r) throws PacException {
        int st = r.estado();
        if (st == 401 || st == 403) {
            throw new PacException(PacException.Tipo.AUTENTICACION,
                    "Facturama rechazó el usuario o la contraseña (o la suscripción no está activa). Revísalos en Configuración → Facturación.", st, null);
        }
        if (st >= 500) {
            throw new PacException(PacException.Tipo.SERVIDOR, "El servidor de Facturama respondió con error " + st + ". Intenta más tarde.", st, null);
        }
        if (st == 204 || r.cuerpo() == null || r.cuerpo().length == 0) {
            if (st >= 200 && st < 300) return null;
            throw new PacException(st == 404 ? PacException.Tipo.VALIDACION : PacException.Tipo.RESPUESTA,
                    "El PAC respondió con el código " + st + " sin detalle.", st, null);
        }
        JsonNode json;
        try {
            json = JSON.readTree(r.cuerpo());
        } catch (IOException e) {
            if (st >= 200 && st < 300) return null;
            throw new PacException(PacException.Tipo.RESPUESTA, "El PAC respondió con el código " + st + " y un contenido ilegible.", st, e);
        }
        if (st >= 200 && st < 300) return json;
        throw new PacException(st == 404 ? PacException.Tipo.VALIDACION : PacException.Tipo.VALIDACION, mensajeDeError(json, st), st, null);
    }

    /** "Message" + detalles de "ModelState"/"Details" (una línea por campo) */
    static String mensajeDeError(JsonNode json, int estado) {
        StringBuilder sb = new StringBuilder();
        String msg = json.path("Message").asText(json.path("message").asText(null));
        if (msg != null && !msg.isBlank()) sb.append(msg.trim());
        for (String campo : new String[] {"ModelState", "Details", "details"}) {
            JsonNode detalles = json.path(campo);
            if (detalles.isObject()) {
                for (Iterator<Map.Entry<String, JsonNode>> it = detalles.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    StringBuilder linea = new StringBuilder();
                    if (e.getValue().isArray()) {
                        for (JsonNode v : e.getValue()) linea.append(linea.length() > 0 ? "; " : "").append(v.asText());
                    } else {
                        linea.append(e.getValue().asText());
                    }
                    if (linea.length() > 0) sb.append(sb.length() > 0 ? "\n" : "").append(e.getKey()).append(": ").append(linea);
                }
            }
        }
        if (sb.length() == 0) sb.append("El PAC rechazó la petición (código ").append(estado).append(").");
        return sb.length() > 1500 ? sb.substring(0, 1497) + "..." : sb.toString();
    }

    private static String codificar(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String recortar(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String resumen(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
