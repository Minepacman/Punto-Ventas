package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento;
import me.eliassanchezfernandez.puntodeventa.facturacion.PacException;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.Formato;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Facturar una venta con un PAC en memoria: emisión, archivos, duplicados, reintento idempotente, permisos. */
class FacturacionServiceTest extends BaseServicioTest {

    @Autowired private FacturacionService facturacionService;
    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private FacturaRepository facturaRepo;
    @Autowired private AuditoriaRepository auditoriaRepo;

    private ProveedorCfdiFicticio pac;
    private ClienteFiscal cliente;

    @BeforeEach
    void configurar() throws Exception {
        iniciarComoAdmin();
        pac = new ProveedorCfdiFicticio();
        fiscalService.usarFabricaProveedor(cred -> pac);
        fiscalService.guardarEmisor("GODE561231GR8", "Abarrotes Don Pepe", "626", "78240", "A");
        fiscalService.guardarCredenciales("u@x.com", "clave", true);
        ClienteFiscal c = new ClienteFiscal();
        c.setRfc("GOLM800101AB1"); c.setNombre("Maria Lopez"); c.setRegimenFiscal("612");
        c.setCodigoPostal("06600"); c.setUsoCfdi("G03"); c.setEmail("maria@correo.com");
        cliente = clienteFiscalService.guardar(c);
    }

    @AfterEach
    void restaurar() {
        fiscalService.usarFabricaProveedor(null);
    }

    private Venta venta(double... precios) {
        var detalles = new java.util.ArrayList<me.eliassanchezfernandez.puntodeventa.model.DetalleVenta>();
        for (double p : precios) detalles.add(ventaService.crearDetalle(producto("Prod " + p, p, 100), 2));
        double total = detalles.stream().mapToDouble(d -> d.getImporte()).sum();
        return ventaService.cobrar(detalles, List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, 500),
                new PagoVenta(Venta.FormaPago.TARJETA, Math.max(0, total - 500))), null);
    }

    @Test
    void facturaUnaVentaGuardaArchivosYConsumeFolio() throws Exception {
        Venta v = venta(36.00, 25.50);                              // 2×36 + 2×25.5 = 123.00
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);

        assertThat(f.isVigente()).isTrue();
        assertThat(f.getUuid()).isNotBlank();
        assertThat(f.getSerie()).isEqualTo("A");
        assertThat(f.getFolio()).isEqualTo("1");
        assertThat(f.getNumeroOrden()).isEqualTo("V-" + v.getId());
        assertThat(f.getReceptorRfc()).isEqualTo("GOLM800101AB1");
        assertThat(f.getUsoCfdi()).isEqualTo("G03");
        assertThat(f.getFormaPago()).isEqualTo("01");                // efectivo 500 > tarjeta
        assertThat(f.getTotal()).isCloseTo(123.00, within(0.001));
        assertThat(f.getSubtotal() - f.getDescuento() + f.getIva()).isCloseTo(123.00, within(0.011));
        assertThat(f.isSandbox()).isTrue();
        assertThat(f.getVentas()).extracting("ventaId").containsExactly(v.getId());
        assertThat(fiscalService.obtener().getFolioUltimo()).isEqualTo(1);

        CfdiDocumento enviado = pac.timbrados.get(0);
        assertThat(enviado.conceptos()).hasSize(2);
        assertThat(enviado.total()).isEqualByComparingTo(new BigDecimal("123.00"));
        assertThat(enviado.receptor().codigoPostal()).isEqualTo("06600");
        assertThat(enviado.lugarExpedicion()).isEqualTo("78240");

        Path pdf = facturacionService.archivo(f.getId(), Formato.PDF);
        Path xml = facturacionService.archivo(f.getId(), Formato.XML);
        assertThat(Files.readString(pdf)).startsWith("%PDF");
        assertThat(Files.readString(xml)).contains("cfdi:Comprobante");
        assertThat(pdf.toString()).contains("facturas");
        assertThat(facturacionService.facturaQueBloquea(v.getId())).isPresent();
        assertThat(facturacionService.facturasEntre(LocalDate.now(), LocalDate.now())).extracting(Factura::getId).contains(f.getId());

        // Segunda factura: folio 2, forma de pago elegida por el usuario (débito) y uso distinto
        Venta v2 = venta(10);
        Factura f2 = facturacionService.facturarVenta(v2.getId(), cliente.getId(), "G01", "28");
        assertThat(f2.getFolio()).isEqualTo("2");
        assertThat(f2.getFormaPago()).isEqualTo("28");
        assertThat(f2.getUsoCfdi()).isEqualTo("G01");

        facturacionService.enviarPorCorreo(f.getId(), null);
        assertThat(pac.correos).containsExactly("fid-1→maria@correo.com");
        assertThat(facturaRepo.findById(f.getId()).orElseThrow().getEmailEnviado()).isNotNull();

        List<Auditoria> bitacora = auditoriaRepo.findTop200ByOrderByFechaDesc();
        assertThat(bitacora).anyMatch(a -> "FACTURA_EMITIDA".equals(a.getAccion()) && a.getDetalle().contains(f.getUuid()));
        assertThat(bitacora).anyMatch(a -> "FACTURA_ENVIADA".equals(a.getAccion()));
    }

    @Test
    void noSeFacturaDosVecesNiUnaVentaCancelada() {
        Venta v = venta(50);
        facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThatThrownBy(() -> facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya está facturada");
        assertThat(pac.timbrados).hasSize(1);

        Venta cancelada = venta(20);
        ventaService.cancelar(cancelada.getId(), "prueba", null);
        assertThatThrownBy(() -> facturacionService.facturarVenta(cancelada.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("cancelada");
        assertThat(facturaRepo.findDeVenta(cancelada.getId())).isEmpty();
    }

    @Test
    void unRechazoDefinitivoDelPacNoDejaNadaAMedias() {
        Venta v = venta(50);
        pac.fallaTimbrar = new PacException(PacException.Tipo.VALIDACION, "Receiver.Name: no coincide con el SAT");
        assertThatThrownBy(() -> facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("rechazó").hasMessageContaining("no coincide");
        assertThat(facturaRepo.findDeVenta(v.getId())).isEmpty();
        assertThat(facturacionService.facturaQueBloquea(v.getId())).isEmpty();
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).anyMatch(a -> "FACTURA_RECHAZADA".equals(a.getAccion()));

        // Se puede volver a intentar después de corregir
        pac.fallaTimbrar = null;
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThat(f.isVigente()).isTrue();
    }

    @Test
    void unTiempoAgotadoDejaPendienteYElReintentoNoDuplica() {
        Venta v = venta(80);
        pac.perderRespuesta = true;      // el PAC timbra pero la respuesta no llega
        assertThatThrownBy(() -> facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("No se sabe si la factura se timbró");
        Factura pendiente = facturaRepo.findDeVenta(v.getId()).get(0);
        assertThat(pendiente.isPendiente()).isTrue();
        assertThat(pendiente.getUltimoError()).contains("tardó");
        assertThat(pendiente.getFolio()).isEqualTo("1");
        assertThat(facturacionService.facturaQueBloquea(v.getId())).isPresent();   // bloquea la global mientras tanto

        // Reintento: consulta por referencia, encuentra el timbrado previo y NO vuelve a timbrar
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThat(f.getId()).isEqualTo(pendiente.getId());
        assertThat(f.isVigente()).isTrue();
        assertThat(f.getFolio()).isEqualTo("1");
        assertThat(f.getUuid()).isEqualTo(pac.porOrden.get("V-" + v.getId()).uuid());
        assertThat(pac.timbrados).hasSize(1);
        assertThat(pac.consultasPorOrden).isEqualTo(1);
        assertThat(facturaRepo.findDeVenta(v.getId())).hasSize(1);

        // Si el PAC tampoco lo tenía (la petición nunca llegó), el reintento sí timbra
        Venta v2 = venta(15);
        pac.fallaTimbrar = new PacException(PacException.Tipo.RED, "sin internet");
        assertThatThrownBy(() -> facturacionService.facturarVenta(v2.getId(), cliente.getId(), null, null)).isInstanceOf(NegocioException.class);
        pac.fallaTimbrar = null;
        Factura f2 = facturacionService.facturarVenta(v2.getId(), cliente.getId(), null, null);
        assertThat(f2.isVigente()).isTrue();
        assertThat(pac.timbrados).hasSize(2);
        assertThat(facturaRepo.findDeVenta(v2.getId())).hasSize(1);
    }

    @Test
    void losArchivosSeBajanDespuesSiFallaronAlEmitir() throws Exception {
        Venta v = venta(30);
        pac.fallarDescarga = true;
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThat(f.isVigente()).isTrue();                       // la factura vale aunque no bajaran los archivos
        assertThat(f.getArchivoPdf()).isNull();
        assertThat(f.getUltimoError()).contains("No se pudo guardar");
        assertThatThrownBy(() -> facturacionService.archivo(f.getId(), Formato.PDF)).isInstanceOf(NegocioException.class);

        pac.fallarDescarga = false;
        Path pdf = facturacionService.archivo(f.getId(), Formato.PDF);
        assertThat(Files.exists(pdf)).isTrue();
        assertThat(facturaRepo.findById(f.getId()).orElseThrow().getArchivoXml()).isNotNull();
        assertThat(facturaRepo.findById(f.getId()).orElseThrow().getUltimoError()).isNull();
    }

    @Test
    void facturarExigeElPermisoYLaConfiguracion() throws Exception {
        Venta v = venta(30);
        iniciarComoCajero("cajero", null);
        assertThatThrownBy(() -> facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null))
                .isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> facturacionService.facturasEntre(null, null)).isInstanceOf(PermisoRequeridoException.class);

        iniciarComoCajero("gerente", EnumSet.of(Permiso.FACTURAR));
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThat(f.getEmitidaPorNombre()).isEqualTo("Usuario gerente");

        iniciarComoAdmin();
        fiscalService.borrarCredenciales();
        Venta v2 = venta(30);
        assertThatThrownBy(() -> facturacionService.facturarVenta(v2.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Configuración");
        assertThat(facturaRepo.findDeVenta(v2.getId())).isEmpty();
    }

    @Test
    void elProductoSinIvaYLasPromocionesLleganAlComprobante() {
        Producto medicina = producto("Medicina", 100, 10);
        medicina.setObjetoImp("01");
        medicina.setClaveProdServ("51000000");
        medicina = productoService.guardar(medicina);
        Producto jabon = producto("Jabón", 25, 10);
        promocion(jabon, me.eliassanchezfernandez.puntodeventa.model.Promocion.Tipo.PORCENTAJE, 20);
        Venta v = ventaService.cobrar(List.of(ventaService.crearDetalle(medicina, 1), ventaService.crearDetalle(jabon, 2)),
                Venta.FormaPago.EFECTIVO, 200);
        assertThat(v.getTotal()).isCloseTo(140.00, within(0.001));            // 100 + (50 − 10)

        facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        CfdiDocumento d = pac.timbrados.get(0);
        assertThat(d.conceptos().get(0).impuestos()).isEmpty();
        assertThat(d.conceptos().get(0).claveProdServ()).isEqualTo("51000000");
        assertThat(d.conceptos().get(0).total()).isEqualByComparingTo("100.00");
        assertThat(d.conceptos().get(1).descuento()).isEqualByComparingTo("8.62");   // 10 de descuento sin IVA
        assertThat(d.conceptos().get(1).total()).isEqualByComparingTo("40.00");
        assertThat(d.total()).isEqualByComparingTo("140.00");
    }
}
