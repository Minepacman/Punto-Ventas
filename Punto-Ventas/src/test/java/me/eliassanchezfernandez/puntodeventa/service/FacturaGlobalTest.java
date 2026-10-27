package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Factura global de público en general: selección de ventas, periodos, doble facturación y reintento. */
class FacturaGlobalTest extends BaseServicioTest {

    @Autowired private FacturacionService facturacionService;
    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private FacturaRepository facturaRepo;

    private ProveedorCfdiFicticio pac;
    private ClienteFiscal cliente;
    private final LocalDate hoy = LocalDate.now();

    @BeforeEach
    void configurar() throws Exception {
        iniciarComoAdmin();
        pac = new ProveedorCfdiFicticio();
        fiscalService.usarFabricaProveedor(cred -> pac);
        fiscalService.guardarEmisor("GODE561231GR8", "Abarrotes Don Pepe", "626", "78240", "A");
        fiscalService.guardarCredenciales("u@x.com", "clave", true);
        ClienteFiscal c = new ClienteFiscal();
        c.setRfc("GOLM800101AB1"); c.setNombre("Maria Lopez"); c.setRegimenFiscal("612"); c.setCodigoPostal("06600"); c.setUsoCfdi("G03");
        cliente = clienteFiscalService.guardar(c);
    }

    @AfterEach
    void restaurar() {
        fiscalService.usarFabricaProveedor(null);
    }

    private Venta venta(double precio) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(producto("P" + precio, precio, 100), 1)), Venta.FormaPago.EFECTIVO, 1000);
    }

    @Test
    void laGlobalExcluyeCanceladasYFacturadasYBloqueaLaIndividual() {
        Venta a = venta(116);
        Venta b = venta(58);
        Venta cancelada = venta(10);
        ventaService.cancelar(cancelada.getId(), "prueba", null);
        Venta individual = venta(30);
        facturacionService.facturarVenta(individual.getId(), cliente.getId(), null, null);

        FacturacionService.SeleccionGlobal sel = facturacionService.ventasParaGlobal(hoy, hoy);
        assertThat(sel.ventas()).extracting(Venta::getId).contains(a.getId(), b.getId())
                .doesNotContain(cancelada.getId(), individual.getId());
        assertThat(sel.canceladas()).isGreaterThanOrEqualTo(1);
        assertThat(sel.yaFacturadas()).isGreaterThanOrEqualTo(1);

        Factura g = facturacionService.facturarGlobal(hoy, hoy, "01");
        assertThat(g.esGlobal()).isTrue();
        assertThat(g.isVigente()).isTrue();
        assertThat(g.getReceptorRfc()).isEqualTo("XAXX010101000");
        assertThat(g.getUsoCfdi()).isEqualTo("S01");
        assertThat(g.getPeriodicidad()).isEqualTo("01");
        assertThat(g.getMeses()).isEqualTo(String.format("%02d", hoy.getMonthValue()));
        assertThat(g.getAnio()).isEqualTo(String.valueOf(hoy.getYear()));
        assertThat(g.getPeriodoDesde()).isEqualTo(hoy);
        assertThat(g.getVentas()).extracting("ventaId").contains(a.getId(), b.getId()).doesNotContain(cancelada.getId(), individual.getId());
        assertThat(g.getTotal()).isCloseTo(sel.total(), within(0.001));

        CfdiDocumento doc = pac.timbrados.get(pac.timbrados.size() - 1);
        assertThat(doc.esGlobal()).isTrue();
        assertThat(doc.receptor().regimenFiscal()).isEqualTo("616");
        assertThat(doc.receptor().codigoPostal()).isEqualTo("78240");
        assertThat(doc.conceptos()).hasSameSizeAs(g.getVentas());
        assertThat(doc.conceptos()).allSatisfy(c -> {
            assertThat(c.claveProdServ()).isEqualTo("01010101");
            assertThat(c.claveUnidad()).isEqualTo("ACT");
            assertThat(c.cantidad()).isEqualByComparingTo(BigDecimal.ONE);
        });
        assertThat(doc.conceptos()).extracting(CfdiDocumento.Concepto::noIdentificacion).contains(a.getFolioMostrado(), b.getFolioMostrado());

        // Las ventas de la global ya no se pueden facturar individualmente ni entrar en otra global
        assertThatThrownBy(() -> facturacionService.facturarVenta(a.getId(), cliente.getId(), null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("factura global");
        assertThat(facturacionService.ventasParaGlobal(hoy, hoy).ventas()).isEmpty();
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy, hoy, "01"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("No hay ventas por facturar");
        assertThat(facturacionService.facturaQueBloquea(b.getId())).map(Factura::getId).contains(g.getId());
    }

    @Test
    void validaPeriodicidadYRango() {
        venta(10);
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy.minusDays(1), hoy, "01"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("un solo día");
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy.minusDays(10), hoy, "02"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("7 días");
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy.minusDays(20), hoy, "03"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("16 días");
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy, hoy.plusDays(1), "01"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("futuro");
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy, hoy, "09"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("periodicidad");
        assertThatThrownBy(() -> FacturacionService.validarPeriodo(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 5), "04"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("mismo mes");
        assertThatThrownBy(() -> FacturacionService.validarPeriodo(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 5), "05"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("bimestre");
        FacturacionService.validarPeriodo(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30), "05");
        assertThat(FacturacionService.mesesDe("05", LocalDate.of(2026, 4, 30))).isEqualTo("14");
        assertThat(FacturacionService.mesesDe("05", LocalDate.of(2026, 12, 31))).isEqualTo("18");
        assertThat(FacturacionService.mesesDe("02", LocalDate.of(2026, 3, 2))).isEqualTo("03");
        assertThat(pac.timbrados).isEmpty();
        assertThat(facturaRepo.findAll()).noneMatch(Factura::esGlobal);
    }

    @Test
    void elReintentoDeLaGlobalNoDuplicaNiCambiaLasVentas() {
        Venta a = venta(20);
        pac.perderRespuesta = true;
        assertThatThrownBy(() -> facturacionService.facturarGlobal(hoy, hoy, "01")).isInstanceOf(NegocioException.class);
        Factura pendiente = facturaRepo.findAll().stream().filter(Factura::esGlobal).findFirst().orElseThrow();
        assertThat(pendiente.isPendiente()).isTrue();
        assertThat(facturacionService.facturaQueBloquea(a.getId())).isPresent();

        Venta posterior = venta(5);          // llegó después: no entra en el reintento
        Factura g = facturacionService.facturarGlobal(hoy, hoy, "01");
        assertThat(g.getId()).isEqualTo(pendiente.getId());
        assertThat(g.isVigente()).isTrue();
        assertThat(pac.timbrados).hasSize(1);
        assertThat(g.getVentas()).extracting("ventaId").contains(a.getId()).doesNotContain(posterior.getId());
        assertThat(facturacionService.ventasParaGlobal(hoy, hoy).ventas()).extracting(Venta::getId).containsExactly(posterior.getId());
    }
}
