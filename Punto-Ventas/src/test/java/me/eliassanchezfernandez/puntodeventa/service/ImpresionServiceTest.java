package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.DocumentoImpresion;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresionException;
import me.eliassanchezfernandez.puntodeventa.impresion.ImpresoraTickets;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ModoImpresora;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.ImpresionService.Resultado;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Impresión de tickets y corte, cajón y su configuración: con una impresora falsa
 * (ninguna prueba toca una impresora real). Lo central: una impresora que falla
 * NUNCA afecta la venta ya cobrada.
 */
class ImpresionServiceTest extends BaseServicioTest {

    @Autowired private ImpresionService impresionService;
    @Autowired private DispositivosService dispositivosService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.VentaRepository ventaRepo;
    @Autowired private AuditoriaService auditoriaService;

    /** Impresora que guarda lo que se le manda (o falla a propósito) */
    static class ImpresoraFalsa implements ImpresoraTickets {
        final List<DocumentoImpresion> documentos = new ArrayList<>();
        final List<Boolean> cajonConImpresion = new ArrayList<>();
        int copias;
        int aperturasDeCajon;
        boolean fallar;

        @Override public void imprimir(DocumentoImpresion d, int c, boolean cajon) throws ImpresionException {
            if (fallar) throw new ImpresionException("sin papel");
            documentos.add(d);
            copias += c;
            cajonConImpresion.add(cajon);
        }
        @Override public void abrirCajon() throws ImpresionException {
            if (fallar) throw new ImpresionException("sin papel");
            aperturasDeCajon++;
        }
        @Override public boolean soportaCajon() { return true; }
        @Override public String descripcion() { return "falsa"; }
    }

    private ImpresoraFalsa impresora;

    @BeforeEach
    void preparar() throws Exception {
        iniciarComoAdmin();
        impresora = new ImpresoraFalsa();
        impresionService.setFabrica(c -> impresora);
    }

    @AfterEach
    void restaurar() {
        impresionService.setFabrica(null);
    }

    private void configurarTermica(boolean automatico, boolean cajon, int copias) {
        dispositivosService.guardarImpresora(ModoImpresora.TERMICA, "Impresora de prueba", "CP850",
                automatico, copias, cajon, 80);
    }

    private Venta cobrarEfectivo(Producto p) {
        return ventaService.cobrar(List.of(ventaService.crearDetalle(p, 2)), List.of(
                new PagoVenta(Venta.FormaPago.EFECTIVO, 500)), null);
    }

    // ── Aislamiento: la impresora jamás afecta la venta ──────────────────

    @Test
    void siLaImpresoraFallaLaVentaYaCobradaNoSeAfecta() {
        configurarTermica(true, true, 1);
        Producto p = producto("Refresco", 20, 10);
        Venta v = cobrarEfectivo(p);
        impresora.fallar = true;

        Resultado r = impresionService.despuesDeCobrar(v.getId());   // no lanza

        assertThat(r.esFallo()).isTrue();
        assertThat(r.mensaje()).contains("La venta se guardó").contains("sin papel");
        assertThat(ventaRepo.findById(v.getId())).isPresent();      // la venta sigue ahí
        assertThat(stockActual(p)).isEqualTo(8);                    // y el inventario descontado
    }

    @Test
    void unaFallaInesperadaDeLaImpresoraTambienSeContiene() {
        configurarTermica(true, false, 1);
        Venta v = cobrarEfectivo(producto("Galletas", 15, 10));
        impresionService.setFabrica(c -> { throw new IllegalStateException("el controlador explotó"); });

        Resultado r = impresionService.despuesDeCobrar(v.getId());

        assertThat(r.esFallo()).isTrue();
        assertThat(r.mensaje()).contains("explotó");
    }

    @Test
    void laVersionAsincronaTampocoLanzaAunqueLaVentaNoExista() throws Exception {
        configurarTermica(true, false, 1);
        Resultado r = impresionService.despuesDeCobrarAsync(987654321L).get();
        assertThat(r.esFallo()).isTrue();
    }

    // ── Qué se imprime y cuándo ──────────────────────────────────────────

    @Test
    void conImpresionAutomaticaImprimeElTicketConLosDatosDeLaVenta() {
        configurarTermica(true, false, 2);
        Producto p = producto("Refresco cola", 20, 10);
        Venta v = cobrarEfectivo(p);

        Resultado r = impresionService.despuesDeCobrar(v.getId());

        assertThat(r.estado()).isEqualTo(Resultado.Estado.IMPRESO);
        assertThat(impresora.documentos).hasSize(1);
        assertThat(impresora.copias).isEqualTo(2);
        String ticket = impresora.documentos.get(0).aTexto(48);
        assertThat(ticket).contains("Refresco cola").contains("$40.00").contains("Folio: " + v.getFolioMostrado())
                .contains("Efectivo").contains("$500.00").contains("Cambio").contains("$460.00")
                .doesNotContain("REIMPRESION");
        assertThat(impresora.cajonConImpresion).containsExactly(false);
    }

    @Test
    void sinImpresionAutomaticaNoImprimeNada() {
        configurarTermica(false, false, 1);
        Venta v = cobrarEfectivo(producto("Agua", 10, 10));

        Resultado r = impresionService.despuesDeCobrar(v.getId());

        assertThat(r.estado()).isEqualTo(Resultado.Estado.OMITIDO);
        assertThat(impresora.documentos).isEmpty();
        assertThat(impresora.aperturasDeCajon).isZero();
    }

    @Test
    void sinImpresoraConfiguradaSeOmiteSinAvisoDeError() {
        Venta v = cobrarEfectivo(producto("Jugo", 12, 10));
        assertThat(impresionService.despuesDeCobrar(v.getId()).estado()).isEqualTo(Resultado.Estado.OMITIDO);
    }

    @Test
    void elCajonSeAbreSoloSiHuboEfectivo() {
        configurarTermica(true, true, 1);
        Producto p = producto("Pan", 20, 10);

        Venta conEfectivo = cobrarEfectivo(p);
        impresionService.despuesDeCobrar(conEfectivo.getId());
        assertThat(impresora.cajonConImpresion).containsExactly(true);

        dispositivosService.guardarTerminales("Terminal 1", false);
        Venta soloTarjeta = ventaService.cobrar(List.of(ventaService.crearDetalle(p, 1)), List.of(
                new PagoVenta(Venta.FormaPago.TARJETA, 20)), null);
        impresionService.despuesDeCobrar(soloTarjeta.getId());
        assertThat(impresora.cajonConImpresion).containsExactly(true, false);
    }

    @Test
    void conImpresionApagadaPeroCajonActivoSoloAbreElCajon() {
        configurarTermica(false, true, 1);
        Venta v = cobrarEfectivo(producto("Leche", 25, 10));

        Resultado r = impresionService.despuesDeCobrar(v.getId());

        assertThat(r.estado()).isEqualTo(Resultado.Estado.IMPRESO);
        assertThat(impresora.documentos).isEmpty();
        assertThat(impresora.aperturasDeCajon).isEqualTo(1);
    }

    @Test
    void laReimpresionSeMarcaYQuedaEnLaBitacora() throws Exception {
        configurarTermica(false, false, 1);
        Venta v = cobrarEfectivo(producto("Café", 30, 10));

        // El hilo de impresión no ve la venta de esta prueba (aún sin confirmar): se valida la impresión directa
        Resultado directo = impresionService.reimprimirVenta(v.getId());
        assertThat(directo.estado()).isEqualTo(Resultado.Estado.IMPRESO);
        String ticket = impresora.documentos.get(0).aTexto(48);
        assertThat(ticket).contains("*** REIMPRESION ***").contains("Café");

        // La solicitud (permiso + bitácora) se atiende en el hilo que llama
        impresionService.reimprimirVentaAsync(v.getId()).get();
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "TICKET_REIMPRESO".equals(a.getAccion())
                && a.getDetalle().contains("#" + v.getId()));
    }

    @Test
    void reimprimirExigeVerHistorial() throws Exception {
        configurarTermica(false, false, 1);
        Venta v = cobrarEfectivo(producto("Dulce", 5, 10));
        iniciarComoCajero("sinhist", EnumSet.noneOf(Permiso.class));

        assertThatThrownBy(() -> impresionService.reimprimirVentaAsync(v.getId()))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void reimprimirSinImpresoraDaUnAvisoClaro() {
        Venta v = cobrarEfectivo(producto("Chicle", 3, 10));
        Resultado r = impresionService.reimprimirVenta(v.getId());
        assertThat(r.esFallo()).isTrue();
        assertThat(r.mensaje()).contains("No hay impresora configurada");
    }

    // ── Corte ─────────────────────────────────────────────────────────────

    @Test
    void elCorteImpresoIncluyeVentasYOcultaLaGananciaSinPermiso() throws Exception {
        configurarTermica(false, false, 1);
        cobrarEfectivo(producto("Torta", 50, 10));

        DocumentoImpresion conGanancia = impresionService.documentoDeCorte();
        assertThat(conGanancia.aTexto(48)).contains("CORTE DE CAJA").contains("Ventas en efectivo")
                .contains("Efectivo esperado").contains("Ganancia");

        crearUsuario("cortista", Cajero.Rol.CAJERO, EnumSet.of(Permiso.HACER_CORTE));
        iniciarComo("cortista", CLAVE_PRUEBA);
        DocumentoImpresion sinGanancia = impresionService.documentoDeCorte();
        assertThat(sinGanancia.aTexto(48)).contains("CORTE DE CAJA").doesNotContain("Ganancia");
    }

    @Test
    void imprimirElCorteExigeElPermiso() throws Exception {
        iniciarComoCajero("sincorte", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> impresionService.imprimirCorteAsync()).isInstanceOf(PermisoRequeridoException.class);
    }

    // ── Cajón de dinero ───────────────────────────────────────────────────

    @Test
    void abrirElCajonExigePermisoYQuedaEnLaBitacora() throws Exception {
        iniciarComoCajero("cajeroX", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> impresionService.abrirCajonAsync(null)).isInstanceOf(PermisoRequeridoException.class);
        assertThat(impresora.aperturasDeCajon).isZero();

        iniciarComoAdmin();
        Resultado r = impresionService.abrirCajonAsync(null).get();
        assertThat(r.estado()).isEqualTo(Resultado.Estado.IMPRESO);
        assertThat(impresora.aperturasDeCajon).isEqualTo(1);
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CAJON_ABIERTO".equals(a.getAccion()));
    }

    @Test
    void unGerenteTieneElPermisoDelCajonPorDefectoYUnCajeroNo() throws Exception {
        assertThat(Permiso.porDefecto(Cajero.Rol.GERENTE)).contains(Permiso.ABRIR_CAJON, Permiso.GESTIONAR_PROVEEDORES);
        assertThat(Permiso.porDefecto(Cajero.Rol.CAJERO)).doesNotContain(Permiso.ABRIR_CAJON, Permiso.GESTIONAR_PROVEEDORES);

        crearUsuario("gerenteC", Cajero.Rol.GERENTE, EnumSet.copyOf(Permiso.porDefecto(Cajero.Rol.GERENTE)));
        iniciarComo("gerenteC", CLAVE_PRUEBA);
        assertThat(impresionService.abrirCajonAsync(null).get().estado()).isEqualTo(Resultado.Estado.IMPRESO);
    }

    @Test
    void siLaImpresoraFallaAlAbrirElCajonSoloHayUnAviso() throws ExecutionException, InterruptedException {
        impresora.fallar = true;
        Resultado r = impresionService.abrirCajonAsync(null).get();
        assertThat(r.esFallo()).isTrue();
        assertThat(r.mensaje()).contains("No se pudo abrir el cajón");
    }

    // ── Configuración ─────────────────────────────────────────────────────

    @Test
    void guardarLaImpresoraEsSoloDelAdminYQuedaAuditado() throws Exception {
        configurarTermica(true, true, 2);
        assertThat(dispositivosService.obtener().getModoImpresora()).isEqualTo(ModoImpresora.TERMICA);
        assertThat(dispositivosService.obtener().getCopias()).isEqualTo(2);
        assertThat(auditoriaService.ultimas()).anyMatch(a -> "CONFIGURACION_MODIFICADA".equals(a.getAccion())
                && a.getDetalle().contains("[Impresora]"));

        iniciarComoCajero("intruso", EnumSet.allOf(Permiso.class));
        assertThatThrownBy(() -> configurarTermica(false, false, 1)).isInstanceOf(NegocioException.class);
    }

    @Test
    void elAnchoDelPapelDeLaImpresoraEsElDelTicket() {
        dispositivosService.guardarImpresora(ModoImpresora.SISTEMA, null, "CP437", false, 1, false, 58);
        assertThat(configuracionService.obtener().getTicketAnchoMm()).isEqualTo(58);
        assertThat(dispositivosService.obtener().getImpresoraCodePage()).isEqualTo("CP437");
    }

    @Test
    void laConfiguracionValidaModoCopiasAnchoYImpresora() {
        assertThatThrownBy(() -> dispositivosService.guardarImpresora(ModoImpresora.TERMICA, null, "CP850", false, 1, false, 80))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Elige la impresora");
        assertThatThrownBy(() -> dispositivosService.guardarImpresora(ModoImpresora.NINGUNA, null, "CP850", false, 9, false, 80))
                .isInstanceOf(NegocioException.class).hasMessageContaining("copias");
        assertThatThrownBy(() -> dispositivosService.guardarImpresora(ModoImpresora.NINGUNA, null, "CP850", false, 1, false, 70))
                .isInstanceOf(NegocioException.class).hasMessageContaining("58 u 80");
        assertThatThrownBy(() -> dispositivosService.guardarImpresora(null, null, "CP850", false, 1, false, 80))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void laImpresionDePruebaEsDelAdmin() throws Exception {
        configurarTermica(false, false, 1);
        Resultado r = impresionService.imprimirPruebaAsync().get();
        assertThat(r.estado()).isEqualTo(Resultado.Estado.IMPRESO);
        assertThat(impresora.documentos.get(0).aTexto(48)).contains("PRUEBA DE IMPRESION").contains("á é í ó ú ñ");

        iniciarComoCajero("probador", EnumSet.noneOf(Permiso.class));
        assertThatThrownBy(() -> impresionService.imprimirPruebaAsync()).isInstanceOf(NegocioException.class);
    }
}
