package me.eliassanchezfernandez.puntodeventa.facturacion;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Concepto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Impuesto;
import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento.Receptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prueba OPCIONAL contra el sandbox real de Facturama. No corre con
 * {@code ./gradlew test}: solo con {@code ./gradlew sandboxTest} y con las
 * variables de entorno FACTURAMA_SANDBOX_USER / FACTURAMA_SANDBOX_PASS.
 * Nunca se escriben credenciales en el código.
 *
 * Usa el RFC de pruebas del SAT (EKU9003173C9) como receptor, que es el que
 * las cuentas sandbox de Facturama traen configurado; timbra, descarga el PDF
 * y el XML y cancela con motivo 02.
 */
@Tag("sandbox")
class FacturamaSandboxTest {

    @Test
    void timbraDescargaYCancelaEnElSandbox() throws Exception {
        String usuario = System.getenv("FACTURAMA_SANDBOX_USER");
        String contrasena = System.getenv("FACTURAMA_SANDBOX_PASS");
        Assumptions.assumeTrue(usuario != null && !usuario.isBlank() && contrasena != null && !contrasena.isBlank(),
                "Sin FACTURAMA_SANDBOX_USER / FACTURAMA_SANDBOX_PASS: se omite la prueba de sandbox");

        FacturamaProveedor pac = new FacturamaProveedor(new CredencialesPac(usuario, contrasena, true));
        pac.probarConexion();

        Concepto c = new Concepto("50202306", "7501000000001", "Refresco 600 ml", "XBO", "Botella",
                new BigDecimal("2"), new BigDecimal("15.515000"), new BigDecimal("31.03"), null, "02",
                List.of(new Impuesto("IVA", new BigDecimal("31.03"), new BigDecimal("0.160000"), new BigDecimal("4.97"))),
                new BigDecimal("36.00"));
        String orden = "TEST-" + System.currentTimeMillis();
        CfdiDocumento doc = new CfdiDocumento(null, null, "78000", "01", "PUE",
                new Receptor("EKU9003173C9", "ESCUELA KEMPER URGATE", "601", "26015", "G03"),
                List.of(c), null, orden, "Prueba automática del punto de venta");

        ProveedorCfdi.Timbrado t = pac.timbrar(doc);
        assertThat(t.uuid()).hasSize(36);
        assertThat(pac.buscarPorNumeroOrden(orden)).isPresent();

        ProveedorCfdi.Archivo pdf = pac.descargar(t.idExterno(), ProveedorCfdi.Formato.PDF);
        assertThat(new String(pdf.contenido(), 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        ProveedorCfdi.Archivo xml = pac.descargar(t.idExterno(), ProveedorCfdi.Formato.XML);
        assertThat(new String(xml.contenido(), java.nio.charset.StandardCharsets.UTF_8)).contains(t.uuid());

        ProveedorCfdi.Cancelacion cancel = pac.cancelar(t.idExterno(), ProveedorCfdi.MotivoCancelacion.ERRORES_SIN_RELACION, null);
        assertThat(cancel.aceptada() || cancel.enEspera()).isTrue();
    }
}
