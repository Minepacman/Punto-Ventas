package me.eliassanchezfernandez.puntodeventa;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Caducidad;
import me.eliassanchezfernandez.puntodeventa.model.Cliente;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Devolucion;
import me.eliassanchezfernandez.puntodeventa.model.PagoVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.TipoIva;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.service.AgregadosService;
import me.eliassanchezfernandez.puntodeventa.service.CajaService;
import me.eliassanchezfernandez.puntodeventa.service.CatalogoCodigosService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.CuentaClienteService;
import me.eliassanchezfernandez.puntodeventa.service.DevolucionService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.TutorialesService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import org.springframework.context.ApplicationContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tienda de ejemplo para las capturas del sitio («Abarrotes Doña Lupita»): departamentos con su IVA,
 * productos con existencia, cinco semanas de ventas, ventas de hoy (una a cuenta, una con devolución
 * pagada con vale), clientes con fiado y abonos, proveedores con días de entrega y preventa y
 * productos por caducar. Solo nombres inventados; nunca datos de una tienda real. Corre sobre la BD
 * desechable de pruebas (create-drop): no hace falta borrar nada al terminar.
 */
public final class DatosDemoCapturas {

    public static final String TIENDA = "Abarrotes Doña Lupita";

    /** Lo que las capturas necesitan encontrar después */
    public record Sembrado(Map<String, Producto> productos, Long ventaConDevolucionId, Long ventaParaDevolverId) { }

    /** Lo sembrado en este proceso (las clases de capturas pueden compartir el contexto de Spring y su BD) */
    private static volatile Sembrado ultimo;

    private DatosDemoCapturas() { }

    public static Sembrado sembrar(ApplicationContext ctx) throws Exception {
        SesionService sesion = ctx.getBean(SesionService.class);
        CajeroRepository cajeros = ctx.getBean(CajeroRepository.class);
        ConfiguracionService configuracion = ctx.getBean(ConfiguracionService.class);
        ConfiguracionRepository configuracionRepo = ctx.getBean(ConfiguracionRepository.class);
        DepartamentoRepository departamentos = ctx.getBean(DepartamentoRepository.class);
        ProductoRepository productoRepo = ctx.getBean(ProductoRepository.class);
        ProductoService productoService = ctx.getBean(ProductoService.class);
        VentaRepository ventaRepo = ctx.getBean(VentaRepository.class);
        VentaService ventaService = ctx.getBean(VentaService.class);
        CuentaClienteService cuentas = ctx.getBean(CuentaClienteService.class);
        DevolucionService devoluciones = ctx.getBean(DevolucionService.class);
        ProveedorService proveedores = ctx.getBean(ProveedorService.class);

        Cajero admin = cajeros.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        admin.setDebeCambiarContrasena(false);
        admin.setBloqueadoHasta(null);
        admin.setNombreCompleto("Guadalupe Hernández");
        admin.setTutorialGeneralVisto(true);
        cajeros.save(admin);
        sesion.cerrarSesion();
        sesion.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
        ctx.getBean(TutorialesService.class).marcarEstadisticasVisto();

        var cfg = configuracion.obtener();
        cfg.setNombreTienda(TIENDA);
        cfg.setAsistenteInicialHecho(true);
        configuracionRepo.save(cfg);
        if (ultimo != null && productoRepo.findByCodigoBarras("7501055300778").isPresent()) {
            return ultimo;   // otra clase de capturas ya sembró en este mismo contexto de Spring
        }
        ctx.getBean(CajaService.class).registrarFondo(sesion.getCajeroActual(), 500);

        // ── Proveedores ──
        Proveedor coca = proveedores.guardar(null, "Coca-Cola FEMSA", null, "55 1234 0100", "Ruta 14",
                Set.of(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), null);
        Proveedor sabritas = proveedores.guardar(null, "Sabritas", null, null, "Don Toño",
                Set.of(DayOfWeek.WEDNESDAY), Set.of(DayOfWeek.MONDAY), null);
        Proveedor lala = proveedores.guardar(null, "Lala", null, null, null,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY), Set.of(), null);
        Proveedor bimbo = proveedores.guardar(null, "Bimbo", null, null, null,
                Set.of(DayOfWeek.values()), Set.of(), null);
        proveedores.guardar(null, "Central de Abastos (fruta y verdura)", null, null, null,
                Set.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), Set.of(), null);

        // ── Departamentos con su IVA (básicos en 0 %) ──
        Departamento bebidas = depto(departamentos, "Bebidas", TipoIva.TASA_16);
        Departamento botanas = depto(departamentos, "Botanas y dulces", TipoIva.TASA_16);
        Departamento lacteos = depto(departamentos, "Lácteos y huevo", TipoIva.TASA_0);
        Departamento abarrotes = depto(departamentos, "Abarrotes", TipoIva.TASA_0);
        Departamento frutas = depto(departamentos, "Frutas y verduras", TipoIva.TASA_0);
        Departamento limpieza = depto(departamentos, "Limpieza", TipoIva.TASA_16);

        // código, descripción, costo, venta, existencia, mínimo, departamento, tipo, proveedor
        Object[][] catalogo = {
                {"7501055300778", "Coca-Cola 600 ml", 13.5, 20.0, 46, 24, bebidas, Producto.TipoVenta.UNIDAD, coca},
                {"7501055310883", "Coca-Cola 2 L", 27.0, 38.0, 18, 12, bebidas, Producto.TipoVenta.UNIDAD, coca},
                {"7501086801046", "Agua Ciel 1 L", 7.5, 12.0, 30, 12, bebidas, Producto.TipoVenta.UNIDAD, coca},
                {"7501011115644", "Sabritas Sal 45 g", 13.0, 20.0, 9, 12, botanas, Producto.TipoVenta.UNIDAD, sabritas},
                {"7501011104112", "Ruffles Queso 50 g", 13.5, 21.0, 14, 10, botanas, Producto.TipoVenta.UNIDAD, sabritas},
                {"7501020511116", "Leche Lala entera 1 L", 24.0, 29.0, 22, 12, lacteos, Producto.TipoVenta.UNIDAD, lala},
                {"7501020540666", "Yoghurt Lala fresa 1 kg", 31.0, 42.0, 6, 6, lacteos, Producto.TipoVenta.UNIDAD, lala},
                {"7501073200145", "Huevo blanco 12 pz", 38.0, 48.0, 15, 8, lacteos, Producto.TipoVenta.UNIDAD, null},
                {"7501030426806", "Pan Bimbo blanco grande", 44.0, 52.0, 8, 5, abarrotes, Producto.TipoVenta.UNIDAD, bimbo},
                {"7501058611086", "Frijol negro 1 kg", 28.0, 36.0, 20, 6, abarrotes, Producto.TipoVenta.UNIDAD, null},
                {"7501003300088", "Arroz Morelos 1 kg", 24.0, 32.0, 2, 6, abarrotes, Producto.TipoVenta.UNIDAD, null},
                {"7501026005688", "Jabón Roma 1 kg", 31.0, 39.0, 11, 4, limpieza, Producto.TipoVenta.UNIDAD, null},
                {"2004", "Aguacate", 52.0, 65.0, 7.5, 3, frutas, Producto.TipoVenta.GRANEL, null},
                {"2007", "Jitomate", 22.0, 32.0, 12.2, 4, frutas, Producto.TipoVenta.GRANEL, null},
                {"2011", "Plátano", 15.0, 24.0, 0, 3, frutas, Producto.TipoVenta.GRANEL, null},
        };
        Map<String, Producto> productos = new LinkedHashMap<>();
        for (Object[] c : catalogo) {
            Producto p = new Producto();
            p.setCodigoBarras((String) c[0]);
            p.setDescripcion((String) c[1]);
            p.setPrecioCosto((Double) c[2]);
            p.setPrecioVenta((Double) c[3]);
            p.setCantidadActual(((Number) c[4]).doubleValue());
            p.setCantidadMinima(((Number) c[5]).doubleValue());
            p.setDepartamento((Departamento) c[6]);
            p.setTipoVenta((Producto.TipoVenta) c[7]);
            p.setProveedor((Proveedor) c[8]);
            p.setUsaInventario(true);
            p.setIva(TipoIva.TIENDA);
            productos.put(p.getDescripcion(), productoRepo.save(p));
        }

        // ── Caducidades: la leche y el yoghurt controlan fecha ──
        LocalDate hoy = LocalDate.now();
        for (String nombre : List.of("Leche Lala entera 1 L", "Yoghurt Lala fresa 1 kg", "Pan Bimbo blanco grande")) {
            Producto p = productos.get(nombre);
            p.setControlaCaducidad(true);
            productos.put(nombre, productoRepo.save(p));
        }
        productoService.registrarEntradaDeProveedor(productos.get("Leche Lala entera 1 L").getId(), 12, lala.getId(),
                null, 24.0, List.of(), hoy.plusDays(3));
        productoService.registrarEntradaDeProveedor(productos.get("Yoghurt Lala fresa 1 kg").getId(), 4, lala.getId(),
                null, 31.0, List.of(), hoy.plusDays(6));
        productoService.registrarEntradaDeProveedor(productos.get("Pan Bimbo blanco grande").getId(), 6, bimbo.getId(),
                null, 44.0, List.of(), hoy.plusDays(2));
        productos.replaceAll((k, v) -> productoRepo.findById(v.getId()).orElseThrow());

        // ── Cinco semanas de ventas (sin tocar existencias): gráficas, ranking y horas pico ──
        List<Producto> lista = new ArrayList<>(productos.values());
        for (int d = 1; d <= 35; d++) {
            int n = 6 + (d * 7) % 5 + (hoy.minusDays(d).getDayOfWeek() == DayOfWeek.SATURDAY ? 6 : 0);
            for (int k = 0; k < n; k++) {
                Producto p = lista.get((d * 3 + k * 5) % lista.size());
                double cantidad = p.getTipoVenta() == Producto.TipoVenta.GRANEL ? 0.5 + (k % 3) * 0.25 : 1 + k % 3;
                ventaHistorica(ventaRepo, p, hoy.minusDays(d).atTime(8 + (k * 5 + d) % 13, (k * 13 + d) % 60), cantidad);
            }
        }

        // ── Ventas de hoy, con el flujo real del cobro ──
        for (int i = 0; i < 6; i++) {
            Producto p = lista.get((i * 4) % lista.size());
            double cantidad = p.getTipoVenta() == Producto.TipoVenta.GRANEL ? 1 : 1 + i % 2;
            cobrarEfectivo(ventaService, List.of(ventaService.crearDetalle(p, cantidad, true)));
        }

        // ── Clientes con fiado ──
        Cliente juan = cuentas.guardar(null, "Juan Pérez (taller)", "55 1234 0001", "Paga los viernes", 1500, true, List.of());
        Cliente rosa = cuentas.guardar(null, "Rosa Martínez", null, null, 800, true, List.of());
        Cliente maestra = cuentas.guardar(null, "Maestra Carmen", "55 1234 0002", null, 1000, true, List.of());
        aCuenta(ventaService, juan, List.of(
                ventaService.crearDetalle(productos.get("Coca-Cola 2 L"), 2, true),
                ventaService.crearDetalle(productos.get("Huevo blanco 12 pz"), 1, true),
                ventaService.crearDetalle(productos.get("Frijol negro 1 kg"), 2, true)));
        aCuenta(ventaService, rosa, List.of(
                ventaService.crearDetalle(productos.get("Leche Lala entera 1 L"), 2, true),
                ventaService.crearDetalle(productos.get("Pan Bimbo blanco grande"), 1, true)));
        aCuenta(ventaService, maestra, List.of(
                ventaService.crearDetalle(productos.get("Jabón Roma 1 kg"), 1, true),
                ventaService.crearDetalle(productos.get("Agua Ciel 1 L"), 6, true)));
        cuentas.registrarAbono(juan.getId(), 100, Venta.FormaPago.EFECTIVO, null);

        // ── Devolución con vale y una venta lista para devolver ──
        Venta conDevolucion = cobrarEfectivo(ventaService, List.of(
                ventaService.crearDetalle(productos.get("Yoghurt Lala fresa 1 kg"), 1, true),
                ventaService.crearDetalle(productos.get("Ruffles Queso 50 g"), 2, true)));
        DetalleVenta ruffles = conDevolucion.getDetalles().stream()
                .filter(d -> d.getProducto() != null && d.getProducto().getDescripcion().startsWith("Ruffles"))
                .findFirst().orElseThrow();
        devoluciones.devolver(new DevolucionService.Solicitud(conDevolucion.getId(),
                List.of(new DevolucionService.Linea(ruffles.getId(), 1, true)), "Producto equivocado",
                Devolucion.FormaReembolso.VALE), List.of());
        Venta paraDevolver = cobrarEfectivo(ventaService, List.of(
                ventaService.crearDetalle(productos.get("Coca-Cola 600 ml"), 2, true),
                ventaService.crearDetalle(productos.get("Sabritas Sal 45 g"), 1, true),
                ventaService.crearDetalle(productos.get("Leche Lala entera 1 L"), 1, true),
                ventaService.crearDetalle(productos.get("Huevo blanco 12 pz"), 1, true)));

        ctx.getBean(CatalogoCodigosService.class).cargarIncluidoSiFalta();   // alta por código de barras sin internet
        ctx.getBean(AgregadosService.class).recalcularManual();
        productos.replaceAll((k, v) -> productoRepo.findById(v.getId()).orElseThrow());
        ultimo = new Sembrado(productos, conDevolucion.getId(), paraDevolver.getId());
        return ultimo;
    }

    private static Departamento depto(DepartamentoRepository repo, String nombre, TipoIva iva) {
        Departamento d = repo.findByNombreIgnoreCase(nombre).orElseGet(Departamento::new);
        d.setNombre(nombre);
        d.setIvaPorDefecto(iva);
        return repo.save(d);
    }

    private static Venta cobrarEfectivo(VentaService ventas, List<DetalleVenta> detalles) {
        double total = ventas.calcularTotal(detalles);
        return ventas.cobrar(new ArrayList<>(detalles), List.of(new PagoVenta(Venta.FormaPago.EFECTIVO, Math.ceil(total / 50) * 50)), List.of());
    }

    private static void aCuenta(VentaService ventas, Cliente cliente, List<DetalleVenta> detalles) {
        double total = ventas.calcularTotal(detalles);
        ventas.cobrar(new ArrayList<>(detalles), List.of(PagoVenta.aCuenta(cliente.getId(), total)), List.of());
    }

    private static void ventaHistorica(VentaRepository repo, Producto p, LocalDateTime fecha, double cantidad) {
        Venta v = new Venta();
        v.setFecha(fecha);
        DetalleVenta d = new DetalleVenta();
        d.setVenta(v);
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(p.getPrecioVenta());
        v.getDetalles().add(d);
        v.setTotal(VentaService.redondear(p.getPrecioVenta() * cantidad));
        v.setPagoCon(v.getTotal());
        repo.save(v);
    }
}
