package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.MotivoCancelacion;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import me.eliassanchezfernandez.puntodeventa.repository.FacturaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cancelación de facturas (motivo, sustitución, permiso/autorización, en espera) y su efecto sobre la venta. */
class FacturaCancelacionTest extends BaseServicioTest {

    @Autowired private FacturacionService facturacionService;
    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private AutorizacionService autorizacionService;
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
    void unaVentaFacturadaNoSeCancelaHastaCancelarLaFactura() {
        Venta v = venta(50);
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "me equivoqué", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("factura " + f.getSerieFolio())
                .hasMessageContaining("Cancela o sustituye primero");

        Factura cancelada = facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.NO_SE_LLEVO_A_CABO, null, null);
        assertThat(cancelada.isCancelada()).isTrue();
        assertThat(cancelada.getMotivoCancelacion()).isEqualTo("03");
        assertThat(cancelada.getUuidSustitucion()).isNull();
        assertThat(cancelada.getCanceladaPorNombre()).isEqualTo("Administrador");
        assertThat(cancelada.getFechaCancelacion()).isNotNull();
        assertThat(pac.cancelados).containsExactly("fid-1:03");
        assertThat(facturacionService.facturaQueBloquea(v.getId())).isEmpty();
        assertThat(facturacionService.facturasDeVenta(v.getId())).hasSize(1);     // el historial se conserva

        // Ahora sí: la venta se cancela, y una venta cancelada ya no se factura
        Venta vc = ventaService.cancelar(v.getId(), "me equivoqué", null);
        assertThat(vc.isCancelada()).isTrue();
        assertThatThrownBy(() -> facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.NO_SE_LLEVO_A_CABO, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("ya está cancelada");
        List<Auditoria> bitacora = auditoriaRepo.findTop200ByOrderByFechaDesc();
        assertThat(bitacora).anyMatch(a -> "FACTURA_CANCELADA".equals(a.getAccion()) && a.getDetalle().contains("motivo 03"));
    }

    @Test
    void elMotivo01ExigeSustitucionYLaVentaSePuedeRefacturar() {
        Venta v = venta(80);
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThatThrownBy(() -> facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_CON_RELACION, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("UUID");
        assertThatThrownBy(() -> facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_CON_RELACION, "no-es-uuid", null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("formato");
        assertThatThrownBy(() -> facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_CON_RELACION, f.getUuid(), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("a sí misma");
        assertThat(pac.cancelados).isEmpty();
        assertThat(facturaRepo.findById(f.getId()).orElseThrow().isVigente()).isTrue();

        // Flujo de sustitución: se cancela la primera con 01 y se emite la nueva (la venta queda libre al cancelar)
        String nueva = "12345678-ABCD-4EF0-9A1B-ABCDEF123456";
        Factura c = facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_CON_RELACION, nueva.toLowerCase(), null);
        assertThat(c.isCancelada()).isTrue();
        assertThat(c.getUuidSustitucion()).isEqualTo(nueva);
        assertThat(pac.cancelados).containsExactly("fid-1:01:" + nueva);
        Factura f2 = facturacionService.facturarVenta(v.getId(), cliente.getId(), "G01", null);
        assertThat(f2.isVigente()).isTrue();
        assertThat(f2.getFolio()).isEqualTo("2");
        assertThat(facturacionService.facturaQueBloquea(v.getId())).map(Factura::getId).contains(f2.getId());
        // Un motivo distinto de 01 ignora el UUID capturado
        Factura c2 = facturacionService.cancelarFactura(f2.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, nueva, null);
        assertThat(c2.getUuidSustitucion()).isNull();
    }

    @Test
    void cancelacionEnEsperaDelReceptorMantieneBloqueadaLaVenta() {
        Venta v = venta(30);
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        pac.cancelacionEnEspera = true;
        Factura r = facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, null, null);
        assertThat(r.getEstadoEnum()).isEqualTo(Factura.Estado.CANCELACION_EN_PROCESO);
        assertThat(r.bloqueaVenta()).isTrue();
        assertThat(facturacionService.facturaQueBloquea(v.getId())).isPresent();
        assertThatThrownBy(() -> ventaService.cancelar(v.getId(), "x", null)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null)).isInstanceOf(NegocioException.class);
        assertThat(facturacionService.ventasParaGlobal(LocalDate.now(), LocalDate.now()).ventas()).extracting(Venta::getId).doesNotContain(v.getId());
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc()).anyMatch(a -> "FACTURA_CANCELACION_SOLICITADA".equals(a.getAccion()));
    }

    @Test
    void cancelarExigePermisoOAutorizacionYQuedaRegistrado() throws Exception {
        Venta v = venta(30);
        Factura f = facturacionService.facturarVenta(v.getId(), cliente.getId(), null, null);
        assertThat(Permiso.porDefecto(me.eliassanchezfernandez.puntodeventa.model.Cajero.Rol.GERENTE)).doesNotContain(Permiso.CANCELAR_FACTURA);

        iniciarComoCajero("facturador", EnumSet.of(Permiso.FACTURAR));
        assertThatThrownBy(() -> facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, null, null))
                .isInstanceOf(PermisoRequeridoException.class)
                .satisfies(e -> assertThat(((PermisoRequeridoException) e).getPermiso()).isEqualTo(Permiso.CANCELAR_FACTURA));
        assertThat(pac.cancelados).isEmpty();

        Autorizacion aut = autorizacionService.autorizar("admin", "admin123", Permiso.CANCELAR_FACTURA);
        Factura c = facturacionService.cancelarFactura(f.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, null, List.of(aut));
        assertThat(c.isCancelada()).isTrue();
        assertThat(c.getCanceladaPorNombre()).contains("Usuario facturador").contains("autorizó Administrador");
        assertThat(aut.vigente()).isFalse();        // se consume
        assertThat(auditoriaRepo.findTop200ByOrderByFechaDesc())
                .anyMatch(a -> "FACTURA_CANCELADA".equals(a.getAccion()) && "admin".equals(a.getAutorizadoPorLogin()));
    }

    @Test
    void laGlobalCanceladaLiberaSusVentasYLaPendienteSeResuelve() {
        Venta a = venta(10);
        Venta b = venta(20);
        Factura g = facturacionService.facturarGlobal(LocalDate.now(), LocalDate.now(), "01");
        assertThatThrownBy(() -> ventaService.cancelar(a.getId(), "x", null)).isInstanceOf(NegocioException.class).hasMessageContaining("global");
        facturacionService.cancelarFactura(g.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, null, null);
        assertThat(facturacionService.ventasParaGlobal(LocalDate.now(), LocalDate.now()).ventas()).extracting(Venta::getId).contains(a.getId(), b.getId());
        ventaService.cancelar(a.getId(), "x", null);

        // Pendiente que el PAC nunca recibió → se descarta; pendiente que sí timbró → queda vigente
        pac.fallaTimbrar = new me.eliassanchezfernandez.puntodeventa.facturacion.PacException(
                me.eliassanchezfernandez.puntodeventa.facturacion.PacException.Tipo.RED, "sin internet");
        assertThatThrownBy(() -> facturacionService.facturarVenta(b.getId(), cliente.getId(), null, null)).isInstanceOf(NegocioException.class);
        Factura pendiente = facturaRepo.findDeVenta(b.getId()).get(0);
        assertThatThrownBy(() -> facturacionService.cancelarFactura(pendiente.getId(), MotivoCancelacion.ERRORES_SIN_RELACION, null, null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Resolver pendiente");
        pac.fallaTimbrar = null;
        assertThat(facturacionService.resolverPendiente(pendiente.getId())).isEmpty();
        assertThat(facturaRepo.findById(pendiente.getId())).isEmpty();
        assertThat(facturacionService.facturaQueBloquea(b.getId())).isEmpty();

        pac.perderRespuesta = true;
        assertThatThrownBy(() -> facturacionService.facturarVenta(b.getId(), cliente.getId(), null, null)).isInstanceOf(NegocioException.class);
        Factura pendiente2 = facturaRepo.findDeVenta(b.getId()).get(0);
        Factura resuelta = facturacionService.resolverPendiente(pendiente2.getId()).orElseThrow();
        assertThat(resuelta.isVigente()).isTrue();
        assertThat(resuelta.getUuid()).isEqualTo(pac.porOrden.get("V-" + b.getId()).uuid());
    }
}
