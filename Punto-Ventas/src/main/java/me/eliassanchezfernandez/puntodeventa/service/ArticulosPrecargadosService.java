package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catálogo de ejemplo de abarrotes (Configuración → Artículos precargados).
 * Solo se ofrece con el catálogo vacío. Los productos entran por
 * ProductoService.guardar, así valen las mismas validaciones y cada existencia
 * inicial deja su movimiento ALTA_INICIAL. Todo o nada (una transacción).
 * Los códigos son internos (PRE-0001...), no códigos de barras reales.
 */
@Service
public class ArticulosPrecargadosService {

    @Autowired private ProductoRepository productoRepo;
    @Autowired private ProductoService    productoService;
    @Autowired private UnidadService      unidadService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService      sesionService;
    @Autowired private AuditoriaService   auditoriaService;

    /** departamento, descripción, precio de venta, costo, unidad ("Kilogramo" = a granel) */
    private record Muestra(String depto, String descripcion, double precio, double costo, String unidad) { }

    private static final Muestra[] CATALOGO = {
        new Muestra("Abarrotes", "Arroz 1 kg", 32, 24, "Pieza"),
        new Muestra("Abarrotes", "Frijol negro 1 kg", 42, 32, "Pieza"),
        new Muestra("Abarrotes", "Azúcar estándar 1 kg", 34, 27, "Pieza"),
        new Muestra("Abarrotes", "Aceite vegetal 1 L", 45, 36, "Pieza"),
        new Muestra("Abarrotes", "Sal de mesa 1 kg", 16, 11, "Pieza"),
        new Muestra("Abarrotes", "Pasta para sopa 200 g", 10, 7, "Pieza"),
        new Muestra("Abarrotes", "Atún en agua 140 g", 24, 17, "Pieza"),
        new Muestra("Abarrotes", "Frijol a granel (kg)", 38, 29, "Kilogramo"),
        new Muestra("Bebidas", "Refresco de cola 600 ml", 19, 14, "Pieza"),
        new Muestra("Bebidas", "Agua purificada 1 L", 12, 8, "Pieza"),
        new Muestra("Bebidas", "Jugo de naranja 1 L", 30, 22, "Pieza"),
        new Muestra("Lácteos y huevo", "Leche entera 1 L", 27, 22, "Pieza"),
        new Muestra("Lácteos y huevo", "Huevo blanco (kg)", 54, 44, "Kilogramo"),
        new Muestra("Lácteos y huevo", "Queso panela 400 g", 68, 52, "Pieza"),
        new Muestra("Botanas y dulces", "Papas fritas 45 g", 18, 13, "Pieza"),
        new Muestra("Botanas y dulces", "Galletas María 170 g", 16, 11, "Pieza"),
        new Muestra("Botanas y dulces", "Chocolate en barra", 14, 10, "Pieza"),
        new Muestra("Limpieza y hogar", "Detergente en polvo 1 kg", 40, 31, "Pieza"),
        new Muestra("Limpieza y hogar", "Papel higiénico 4 rollos", 38, 29, "Pieza"),
        new Muestra("Limpieza y hogar", "Cloro 1 L", 22, 15, "Pieza"),
    };

    /** Solo se ofrece mientras no haya ningún producto */
    public boolean puedeCargar() {
        return productoRepo.count() == 0;
    }

    public int cantidadDeMuestra() {
        return CATALOGO.length;
    }

    /**
     * Solo ADMIN y solo con el catálogo vacío. Con la venta a granel
     * deshabilitada se omiten los productos a granel.
     * @return cuántos productos se crearon
     */
    @Transactional
    public int cargar() {
        sesionService.verificarAdmin();
        if (!puedeCargar()) {
            throw new NegocioException("Ya hay productos en el catálogo: los artículos precargados solo se cargan con el catálogo vacío.");
        }
        boolean granel = configuracionService.isPermitirGranel();
        Map<String, Departamento> departamentos = new LinkedHashMap<>();
        int creados = 0;
        for (Muestra m : CATALOGO) {
            Unidad unidad = unidadService.buscarPorNombre(m.unidad()).orElse(null);
            boolean esGranel = unidad != null && unidad.isPermiteDecimales();
            if (esGranel && !granel) continue;

            Departamento d = departamentos.computeIfAbsent(m.depto(), nombre ->
                    productoService.listarDepartamentos().stream()
                            .filter(x -> x.getNombre().equalsIgnoreCase(nombre)).findFirst()
                            .orElseGet(() -> {
                                Departamento nuevo = new Departamento();
                                nuevo.setNombre(nombre);
                                return productoService.guardarDepartamento(nuevo);
                            }));
            Producto p = new Producto();
            p.setCodigoBarras(String.format("PRE-%04d", creados + 1));
            p.setDescripcion(m.descripcion());
            p.setPrecioVenta(m.precio());
            p.setPrecioCosto(m.costo());
            p.setDepartamento(d);
            p.setUnidad(unidad);
            p.setTipoVenta(esGranel ? Producto.TipoVenta.GRANEL : Producto.TipoVenta.UNIDAD);
            p.setUsaInventario(true);
            p.setCantidadActual(esGranel ? 20 : 24);
            p.setCantidadMinima(esGranel ? 5 : 6);
            productoService.guardar(p);
            creados++;
        }
        auditoriaService.registrar(sesionService.getCajeroActual(), "CATALOGO_PRECARGADO",
                creados + " productos de ejemplo en " + departamentos.size() + " departamentos", (Cajero) null);
        return creados;
    }
}
