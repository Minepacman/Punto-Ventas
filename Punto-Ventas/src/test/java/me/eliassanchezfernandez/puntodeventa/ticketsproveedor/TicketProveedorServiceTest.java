package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.ProveedorCodigo;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorCodigoRepository;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.AutorizacionService;
import me.eliassanchezfernandez.puntodeventa.service.AvisosService;
import me.eliassanchezfernandez.puntodeventa.service.BaseServicioTest;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.PermisoRequeridoException;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.RenglonAplicar;
import me.eliassanchezfernandez.puntodeventa.ticketsproveedor.TicketProveedorService.Solicitud;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Registrar un ticket de proveedor: entrada al inventario, costo, historial, bitácora, lo aprendido,
 * avisos de costo y permisos. El texto viene de las muestras reales (anonimizadas).
 */
class TicketProveedorServiceTest extends BaseServicioTest {

    @Autowired private TicketProveedorService servicio;
    @Autowired private ProveedorService proveedorService;
    @Autowired private ProveedorCodigoRepository codigoRepo;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private AutorizacionService autorizacionService;
    @Autowired private AvisosService avisosService;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.HistorialPrecioRepository historialRepo;
    @Autowired private me.eliassanchezfernandez.puntodeventa.repository.TicketProveedorRegistroRepository registroRepo;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Proveedor jalapa;

    @BeforeEach
    void iniciar() throws Exception {
        iniciarComoAdmin();
        jalapa = proveedorService.guardar(null, "Cervezas y Refrescos de Jalapa", "CRJ010101AB1", null, null, Set.of(), null);
    }

    private TicketLeido ticket(String muestra) throws Exception {
        return InterpreteTicket.interpretar(MuestrasTickets.recurso(muestra + ".txt")).get(0);
    }

    private Producto conCosto(String nombre, double costo, double precio, double stock) {
        Producto p = producto(nombre, precio, stock);
        p.setPrecioCosto(costo);
        return productoRepo.save(p);
    }

    private Solicitud solicitud(TicketProveedorService.Revision r, boolean repetir) {
        List<RenglonAplicar> rs = new ArrayList<>();
        for (TicketProveedorService.RenglonRevision rr : r.renglones()) {
            LineaTicket l = rr.linea();
            rs.add(new RenglonAplicar(l.getCodigoProveedor(), l.getDescripcion(), rr.elegida().productoId(), null,
                    l.getCantidad(), rr.piezasPorUnidad(), l.getImporte(), true));
        }
        TicketLeido t = r.ticket();
        return new Solicitud(r.proveedorId(), t.getFolio(), t.getFecha(), t.getTotal(), t.getCuadre().factorCosto(),
                "texto escrito", t.getTexto(), rs, repetir);
    }

    // ── El caso completo ──────────────────────────────────────────────────

    @Test
    void reconoceProveedorYProductoYRegistraLaEntradaConCostoPorPieza() throws Exception {
        Producto pinada = conCosto("Piñada 2 L", 25, 35, 3);
        TicketProveedorService.Revision r = servicio.preparar(ticket("t11"), null);

        assertThat(r.proveedorId()).isEqualTo(jalapa.getId());
        assertThat(r.motivoProveedor()).contains("RFC");
        TicketProveedorService.RenglonRevision rr = r.renglones().get(0);
        assertThat(rr.elegida().productoId()).isEqualTo(pinada.getId());
        assertThat(rr.piezasPorUnidad()).isEqualTo(8);                 // "8/2L" a $232 = 8 botellas, no una
        assertThat(rr.preguntarEmpaque()).isTrue();
        assertThat(rr.costoPorPieza()).isEqualTo(29.0);                 // 232 / 8

        TicketProveedorService.Resultado res = servicio.aplicar(solicitud(r, false), null);

        Producto p = productoRepo.findById(pinada.getId()).orElseThrow();
        assertThat(p.getCantidadActual()).isEqualTo(11);
        assertThat(p.getPrecioCosto()).isEqualTo(29.0);
        assertThat(p.getProveedor().getId()).isEqualTo(jalapa.getId());
        List<MovimientoInventario> movs = productoService.movimientosDe(p.getId());
        MovimientoInventario entrada = movs.stream().filter(m -> m.getTipo() == MovimientoInventario.Tipo.ENTRADA).findFirst().orElseThrow();
        assertThat(entrada.getCantidad()).isEqualTo(8);
        assertThat(entrada.getCostoUnitario()).isEqualTo(29.0);
        assertThat(entrada.getProveedorId()).isEqualTo(jalapa.getId());
        assertThat(entrada.getMotivo()).contains("9515-102460");
        List<HistorialPrecio> hist = historialRepo.findByProductoIdOrderByFechaDesc(p.getId());
        assertThat(hist).anyMatch(h -> h.getCampo().equals("COSTO") && h.getPrecioAnterior() == 25 && h.getPrecioNuevo() == 29);
        assertThat(auditoriaService.ultimas()).anyMatch(a -> a.getAccion().equals("TICKET_PROVEEDOR_REGISTRADO"));

        // Aviso de costo: subió 16 %, se sugiere conservar la ganancia del 40 % → 29 × 1.4 = 40.60 → 41.00
        assertThat(res.subieron()).hasSize(1);
        TicketProveedorService.CambioCosto c = res.subieron().get(0);
        assertThat(c.costoAntes()).isEqualTo(25);
        assertThat(c.costoAhora()).isEqualTo(29);
        assertThat(c.porcentaje()).isCloseTo(0.16, within(1e-9));
        assertThat(c.gananciaAntes()).isCloseTo(0.40, within(1e-9));
        assertThat(c.gananciaAhora()).isCloseTo(6.0 / 29, within(1e-9));
        assertThat(c.precioSugerido()).isEqualTo(41.0);
        assertThat(res.bajaron()).isEmpty();

        // Lo aprendió: código del proveedor → producto, 8 piezas por unidad
        ProveedorCodigo aprendido = codigoRepo.findByProveedorIdAndClave(jalapa.getId(), "C:10001360").orElseThrow();
        assertThat(aprendido.getProducto().getId()).isEqualTo(p.getId());
        assertThat(aprendido.getPiezasPorUnidad()).isEqualTo(8);
        assertThat(aprendido.getUltimoCosto()).isEqualTo(29.0);
    }

    @Test
    void laSegundaVezLoReconoceSoloConLoAprendido() throws Exception {
        // Un nombre que no se parece nada: solo lo aprendido lo puede encontrar
        Producto pinada = conCosto("Refresco sabor piña familiar", 25, 35, 0);
        TicketProveedorService.Revision r1 = servicio.preparar(ticket("t11"), jalapa.getId());
        assertThat(r1.renglones().get(0).elegida()).isNull();
        List<RenglonAplicar> rs = List.of(new RenglonAplicar("10001360", "PIÑ ADA 8/2L NR", pinada.getId(), null, 1, 8, 232, true));
        servicio.aplicar(new Solicitud(jalapa.getId(), "A-1", null, 232.0, 1, null, null, rs, false), null);

        TicketProveedorService.Revision r2 = servicio.preparar(ticket("t11"), null);
        TicketProveedorService.RenglonRevision rr = r2.renglones().get(0);
        assertThat(rr.elegida().productoId()).isEqualTo(pinada.getId());
        assertThat(rr.elegida().origen()).isEqualTo("APRENDIDO");
        assertThat(rr.dudoso()).isFalse();
        assertThat(rr.piezasPorUnidad()).isEqualTo(8);
        assertThat(rr.preguntarEmpaque()).isFalse();
        assertThat(codigoRepo.findByProveedorIdAndClave(jalapa.getId(), "C:10001360").orElseThrow().getVeces()).isEqualTo(1);
    }

    @Test
    void unTicketSinNombreSeReconocePorLosProductosQueYaConoce() throws Exception {
        Proveedor mars = proveedorService.guardar(null, "Mars", null, null, null, Set.of(), null);
        TicketLeido t = ticket("t12");
        assertThat(servicio.preparar(t, null).proveedorId()).isNull();          // el ticket no trae el nombre
        List<RenglonAplicar> rs = new ArrayList<>();
        for (LineaTicket l : t.getLineas()) {
            Producto p = conCosto(l.getDescripcion(), 9, 14, 0);
            rs.add(new RenglonAplicar(l.getCodigoProveedor(), l.getDescripcion(), p.getId(), null, l.getCantidad(), 1, l.getImporte(), true));
        }
        servicio.aplicar(new Solicitud(mars.getId(), t.getFolio(), t.getFecha(), t.getTotal(), 1, null, null, rs, false), null);

        TicketProveedorService.Revision r = servicio.preparar(ticket("t12"), null);
        assertThat(r.proveedorId()).isEqualTo(mars.getId());
        assertThat(r.motivoProveedor()).contains("conoce");
    }

    @Test
    void elMismoFolioNoSeRegistraDosVecesSalvoQueSeConfirme() throws Exception {
        Producto pinada = conCosto("Piñada 2 L", 29, 35, 0);
        TicketProveedorService.Revision r = servicio.preparar(ticket("t11"), null);
        servicio.aplicar(solicitud(r, false), null);

        TicketProveedorService.Revision otra = servicio.preparar(ticket("t11"), null);
        assertThat(otra.avisoRepetido()).contains("ya se registró");
        assertThatThrownBy(() -> servicio.aplicar(solicitud(otra, false), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("Registrar de todos modos");
        assertThat(stockActual(pinada)).isEqualTo(8);
        servicio.aplicar(solicitud(otra, true), null);
        assertThat(stockActual(pinada)).isEqualTo(16);
        // Cada registro del mismo folio lleva su número de repetición (1, 2…): es la llave única en la base
        var registros = registroRepo.findByProveedorIdAndFolioOrderByRegistradoEnDesc(r.proveedorId(), r.ticket().getFolio());
        assertThat(registros).extracting(me.eliassanchezfernandez.puntodeventa.model.TicketProveedorRegistro::getRepeticion)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void siOtraCajaRegistraElMismoFolioEnMedioLaEntradaSeDeshaceConAvisoAmable() throws Exception {
        Producto pinada = conCosto("Piñada 2 L", 29, 35, 0);
        TicketProveedorService.Revision r = servicio.preparar(ticket("t11"), null);
        servicio.aplicar(solicitud(r, false), null);
        assertThat(stockActual(pinada)).isEqualTo(8);
        // "Otra caja" alcanzó a guardar este folio con el número de repetición que esta caja va a calcular
        // (previos + 1 = 3): el insert de aquí choca con la llave única, como pasaría en la carrera real
        var ajeno = new me.eliassanchezfernandez.puntodeventa.model.TicketProveedorRegistro();
        ajeno.setProveedor(proveedorService.listarActivos().stream().filter(p -> p.getId().equals(r.proveedorId())).findFirst().orElseThrow());
        ajeno.setFolio(r.ticket().getFolio());
        ajeno.setRepeticion(3);
        ajeno.setRenglones(1);
        ajeno.setTotalRegistrado(232);
        registroRepo.saveAndFlush(ajeno);

        TicketProveedorService.Revision otra = servicio.preparar(ticket("t11"), null);
        boolean postgres;
        try (var con = jdbc.getDataSource().getConnection()) {
            postgres = con.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
        }
        if (postgres) {
            // Varias cajas (PostgreSQL): la llave única uk_tickets_proveedor_folio_rep detiene el segundo insert
            assertThatThrownBy(() -> servicio.aplicar(solicitud(otra, true), null))
                    .isInstanceOf(NegocioException.class).hasMessageContaining("Otra caja").hasMessageContaining("No se registró nada");
            assertThat(stockActual(pinada)).isEqualTo(8);     // la entrada de mercancía se deshizo completa
        } else {
            // Una caja (SQLite): un solo proceso y escrituras en serie (BEGIN IMMEDIATE), la carrera no existe; Hibernate
            // no crea la llave única en SQLite. Aquí solo se comprueba que la repetición se numera como la llave espera.
            servicio.aplicar(solicitud(otra, true), null);
            var registros = registroRepo.findByProveedorIdAndFolioOrderByRegistradoEnDesc(r.proveedorId(), r.ticket().getFolio());
            assertThat(registros).extracting(me.eliassanchezfernandez.puntodeventa.model.TicketProveedorRegistro::getRepeticion)
                    .containsExactlyInAnyOrder(1, 3, 3);
        }
    }

    @Test
    void repartirImpuestosNoIncluidosEnElCostoDeCadaPieza() throws Exception {
        Proveedor gamesa = proveedorService.guardar(null, "Gamesa", null, null, null, Set.of(), null);
        TicketLeido t = ticket("t02");            // precios sin IEPS: 178.99 + 14.32 = 193.31
        TicketProveedorService.Revision r = servicio.preparar(t, null);
        assertThat(r.proveedorId()).isEqualTo(gamesa.getId());
        assertThat(t.getCuadre().impuestosIncluidos()).isFalse();
        double factor = t.getCuadre().factorCosto();
        assertThat(factor).isCloseTo(193.31 / 178.99, within(1e-9));

        Producto emperador = conCosto("Galletas Emperador Senzo", 9, 14, 0);
        servicio.aplicar(new Solicitud(gamesa.getId(), t.getFolio(), t.getFecha(), t.getTotal(), factor, null, null,
                List.of(new RenglonAplicar("300062064", "EMPERADOR SENZO DTS RG3 117GRX48X1", emperador.getId(), null, 2, 1, 34.52, true)),
                false), null);
        // 34.52 × 1.08 / 2 = 18.64 (con IEPS), no 17.26
        assertThat(productoRepo.findById(emperador.getId()).orElseThrow().getPrecioCosto()).isEqualTo(18.64);
    }

    @Test
    void creaElProductoQueNoExisteConElCostoDelTicketYLoRecuerda() throws Exception {
        TicketLeido t = ticket("t11");
        TicketProveedorService.Revision r = servicio.preparar(t, null);
        assertThat(r.renglones().get(0).elegida()).isNull();
        TicketProveedorService.ProductoNuevo nuevo = new TicketProveedorService.ProductoNuevo("Piñada 2 litros", "", null, 39.5);
        TicketProveedorService.Resultado res = servicio.aplicar(new Solicitud(jalapa.getId(), t.getFolio(), t.getFecha(), t.getTotal(), 1,
                null, null, List.of(new RenglonAplicar("10001360", "PIÑ ADA 8/2L NR", null, nuevo, 1, 8, 232, true)), false), null);

        assertThat(res.productosNuevos()).hasSize(1);
        Producto p = productoRepo.findById(res.productosNuevos().get(0)).orElseThrow();
        assertThat(p.getDescripcion()).isEqualTo("Piñada 2 litros");
        assertThat(p.getCodigoBarras()).startsWith("PRV" + jalapa.getId() + "-10001360");
        assertThat(p.getPrecioCosto()).isEqualTo(29.0);
        assertThat(p.getPrecioVenta()).isEqualTo(39.5);
        assertThat(p.getCantidadActual()).isEqualTo(8);
        assertThat(res.subieron()).isEmpty();                      // un producto nuevo no "sube de costo"
        assertThat(servicio.preparar(ticket("t11"), null).renglones().get(0).elegida().productoId()).isEqualTo(p.getId());
    }

    @Test
    void piezasConDecimalesEnUnProductoPorPiezaSeRechazanConExplicacion() throws Exception {
        Producto lata = conCosto("Amper energy lata 473 ml", 15, 22, 0);
        List<RenglonAplicar> rs = List.of(new RenglonAplicar(null, "PAQUETE AMPER ENERGY 473ML 24PZS", lata.getId(), null, 0.5, 1, 215, true));
        assertThatThrownBy(() -> servicio.aplicar(new Solicitud(jalapa.getId(), null, null, null, 1, null, null, rs, false), null))
                .isInstanceOf(NegocioException.class).hasMessageContaining("se vende por pieza");
        // Con 24 piezas por paquete, medio paquete = 12 latas a 215 / 12 = 17.92
        servicio.aplicar(new Solicitud(jalapa.getId(), null, null, null, 1, null, null,
                List.of(new RenglonAplicar(null, "PAQUETE AMPER ENERGY 473ML 24PZS", lata.getId(), null, 0.5, 24, 215, true)), false), null);
        Producto p = productoRepo.findById(lata.getId()).orElseThrow();
        assertThat(p.getCantidadActual()).isEqualTo(12);
        assertThat(p.getPrecioCosto()).isEqualTo(17.92);
    }

    @Test
    void siUnRenglonFallaNoEntraNingunoDelTicket() throws Exception {
        Producto a = conCosto("Producto A", 10, 15, 0);
        Producto b = conCosto("Producto B", 10, 15, 0);
        b.setActivo(false);
        productoRepo.save(b);
        List<RenglonAplicar> rs = List.of(
                new RenglonAplicar(null, "A", a.getId(), null, 3, 1, 30, true),
                new RenglonAplicar(null, "B", b.getId(), null, 3, 1, 30, true));
        assertThatThrownBy(() -> servicio.aplicar(new Solicitud(jalapa.getId(), "X1", null, null, 1, null, null, rs, false), null))
                .isInstanceOf(NegocioException.class);
        // (la transacción de la prueba sigue abierta; lo que importa es que el servicio no deja la mitad: ver la anotación)
        assertThat(TicketProveedorService.class.getMethod("aplicar", Solicitud.class, java.util.Collection.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    // ── Permisos ──────────────────────────────────────────────────────────

    @Test
    void sinAjustarInventarioPideAutorizacionYUnaSolaCubreTodoElTicket() throws Exception {
        Producto a = conCosto("Producto A", 10, 15, 0);
        Producto b = conCosto("Producto B", 10, 15, 0);
        iniciarComoCajero("cajero1", EnumSet.noneOf(Permiso.class));
        List<RenglonAplicar> rs = List.of(
                new RenglonAplicar(null, "A", a.getId(), null, 3, 1, 33, true),
                new RenglonAplicar(null, "B", b.getId(), null, 2, 1, 22, true));
        Solicitud s = new Solicitud(jalapa.getId(), "F-1", null, null, 1, null, null, rs, false);
        assertThatThrownBy(() -> servicio.aplicar(s, null)).isInstanceOf(PermisoRequeridoException.class);

        Autorizacion aut = autorizacionService.autorizar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA, Permiso.AJUSTAR_INVENTARIO);
        servicio.aplicar(s, List.of(aut));
        assertThat(stockActual(a)).isEqualTo(3);
        assertThat(stockActual(b)).isEqualTo(2);
        assertThat(aut.isConsumida()).isTrue();
        // Sin ver ganancias, no se le enseñan costos (pero sí se registraron)
        assertThat(productoRepo.findById(a.getId()).orElseThrow().getPrecioCosto()).isEqualTo(11);
    }

    @Test
    void quienNoVeCostosNoRecibeLaListaDeCambiosDeCosto() throws Exception {
        Producto a = conCosto("Producto A", 10, 15, 0);
        iniciarComoCajero("almacen", EnumSet.of(Permiso.AJUSTAR_INVENTARIO));
        TicketProveedorService.Resultado r = servicio.aplicar(new Solicitud(jalapa.getId(), null, null, null, 1, null, null,
                List.of(new RenglonAplicar(null, "A", a.getId(), null, 2, 1, 30, true)), false), null);
        assertThat(r.costosVisibles()).isFalse();
        assertThat(r.subieron()).isEmpty();
        assertThat(r.cambiosOcultos()).isEqualTo(1);
    }

    @Test
    void crearProductoExigeEditarProductos() throws Exception {
        iniciarComoCajero("almacen2", EnumSet.of(Permiso.AJUSTAR_INVENTARIO));
        TicketProveedorService.ProductoNuevo nuevo = new TicketProveedorService.ProductoNuevo("Algo nuevo", null, null, 20);
        assertThatThrownBy(() -> servicio.aplicar(new Solicitud(jalapa.getId(), null, null, null, 1, null, null,
                List.of(new RenglonAplicar(null, "ALGO", null, nuevo, 1, 1, 10, true)), false), null))
                .isInstanceOf(PermisoRequeridoException.class);
    }

    @Test
    void actualizarPreciosPideEditarYCambiarPreciosYQuedaEnElHistorial() throws Exception {
        Producto a = conCosto("Producto A", 10, 15, 0);
        iniciarComoCajero("almacen3", EnumSet.of(Permiso.AJUSTAR_INVENTARIO));
        assertThatThrownBy(() -> servicio.actualizarPreciosVenta(List.of(new TicketProveedorService.NuevoPrecio(a.getId(), 16))))
                .isInstanceOf(PermisoRequeridoException.class);
        iniciarComoAdmin();
        assertThat(servicio.actualizarPreciosVenta(List.of(new TicketProveedorService.NuevoPrecio(a.getId(), 16.5)))).isEqualTo(1);
        assertThat(productoRepo.findById(a.getId()).orElseThrow().getPrecioVenta()).isEqualTo(16.5);
        assertThat(historialRepo.findByProductoIdOrderByFechaDesc(a.getId()))
                .anyMatch(h -> h.getCampo().equals("VENTA") && h.getPrecioAnterior() == 15 && h.getPrecioNuevo() == 16.5);
        assertThat(auditoriaService.ultimas()).anyMatch(x -> x.getAccion().equals("PRECIOS_ACTUALIZADOS_POR_COSTO"));
    }

    // ── Cuentas y avisos ──────────────────────────────────────────────────

    @Test
    void cuentasDeCostoYPrecioSugerido() {
        assertThat(TicketProveedorService.redondearArriba50(17.03)).isEqualTo(17.5);
        assertThat(TicketProveedorService.redondearArriba50(18.0)).isEqualTo(18.0);
        assertThat(TicketProveedorService.redondearArriba50(18.51)).isEqualTo(19.0);
        assertThat(TicketProveedorService.costoPorPieza(468.81, 480.41 / 468.81, 0.05, 100)).isEqualTo(96.08);
        TicketProveedorService.CambioCosto baja = TicketProveedorService.cambioCosto(1L, "x", 20, 18, 26);
        assertThat(baja.porcentaje()).isCloseTo(-0.10, within(1e-9));
        assertThat(baja.precioSugerido()).isEqualTo(23.5);              // 18 × 1.3 = 23.40 → 23.50
        assertThat(TicketProveedorService.cambioCosto(1L, "x", 20, 20.001, 26)).isNull();
    }

    @Test
    void margenHabitualDelDepartamentoParaElPrecioSugerido() throws Exception {
        Departamento d = departamento("Botanas");
        for (int i = 0; i < 3; i++) {
            Producto p = producto("Botana " + i, 12.5, 0, Producto.TipoVenta.UNIDAD, d);
            p.setPrecioCosto(10);
            productoRepo.save(p);
        }
        assertThat(servicio.margenHabitual(d.getId())).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void unProductoQueQuedaGanandoPocoSaleEnAvisos() throws Exception {
        Producto a = conCosto("Refresco con poca ganancia", 10, 12, 0);
        servicio.aplicar(new Solicitud(jalapa.getId(), null, null, null, 1, null, null,
                List.of(new RenglonAplicar(null, "REFRESCO", a.getId(), null, 2, 1, 23, true)), false), null);   // costo 11.50
        assertThat(servicio.gananciaBajaTrasEntradas(7)).anyMatch(g -> g.productoId().equals(a.getId()));
        avisosService.invalidarCache();
        assertThat(avisosService.avisos().avisos()).anyMatch(x -> x.tipo() == AvisosService.Tipo.GANANCIA && x.titulo().equals(a.getDescripcion()));
    }
}
