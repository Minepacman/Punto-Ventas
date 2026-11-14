package me.eliassanchezfernandez.puntodeventa.importacion;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.importacion.FilaProducto.Accion;
import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion.ErrorFila;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.HistorialPrecio;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Proveedor;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.HistorialPrecioRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProveedorRepository;
import me.eliassanchezfernandez.puntodeventa.repository.UnidadRepository;
import me.eliassanchezfernandez.puntodeventa.service.AuditoriaService;
import me.eliassanchezfernandez.puntodeventa.service.Autorizacion;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Importar / migrar el catálogo desde otro punto de venta o desde Excel.
 *
 * Flujo: {@link #leer} (archivo → tabla) → el asistente propone origen y mapeo →
 * {@link #preparar} (validación completa contra el catálogo, sin escribir) →
 * {@link #aplicar} (escribe por lotes, cada lote en su transacción, con
 * progreso; si un lote falla se reintenta fila por fila para aislar la mala).
 *
 * Permisos: EDITAR_PRODUCTOS siempre; CAMBIAR_PRECIOS si se cambian precios de
 * productos existentes; AJUSTAR_INVENTARIO si se importan existencias (alta
 * inicial o ajuste). Los tres aceptan autorización de administrador, que se
 * consume al final. Cada cambio de existencia deja su movimiento en la bitácora
 * de inventario y cada cambio de precio su historial; la importación completa
 * queda en la bitácora general (IMPORTACION_PRODUCTOS / IMPORTACION_PROVEEDORES).
 *
 * Rendimiento: lotes de {@value #TAMANO_LOTE} filas con flush + clear del
 * EntityManager, para que 20 000 productos no se queden en memoria ni bloqueen
 * la base más de unos milisegundos por lote. El hilo lo decide quien llama (la
 * ventana lo corre fuera del hilo de JavaFX).
 */
@Service
public class ImportacionService {

    static final int TAMANO_LOTE = 250;

    @Autowired private ProductoRepository      productoRepo;
    @Autowired private DepartamentoRepository  departamentoRepo;
    @Autowired private UnidadRepository        unidadRepo;
    @Autowired private HistorialPrecioRepository historialPrecioRepo;
    @Autowired private ProveedorRepository     proveedorRepo;
    @Autowired private ProductoService         productoService;
    @Autowired private ProveedorService        proveedorService;
    @Autowired private SesionService           sesionService;
    @Autowired private AuditoriaService        auditoriaService;
    @Autowired private ConfiguracionService    configuracionService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    // ── Lectura y preparación ────────────────────────────────────────────

    /** Lee el archivo (CSV, XLSX o XLS). No toca la base de datos. */
    public TablaImportada leer(Path archivo) throws IOException {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        return LectorArchivo.leer(archivo);
    }

    /** Foto del catálogo actual para validar sin consultar fila por fila */
    @Transactional(readOnly = true)
    public PreparacionProductos.Catalogo catalogo() {
        Map<String, Producto> porCodigo = new HashMap<>();
        for (Producto p : productoRepo.findAll()) porCodigo.put(p.getCodigoBarras().toLowerCase(), p);
        Map<String, Departamento> deptos = new HashMap<>();
        for (Departamento d : departamentoRepo.findAll()) deptos.put(Texto.clave(d.getNombre()), d);
        return new PreparacionProductos.Catalogo(porCodigo, deptos, unidadRepo.findAllByOrderByNombreAsc(),
                configuracionService.isPermitirGranel());
    }

    public PlanImportacion preparar(TablaImportada tabla, Origen origen, Mapeo mapeo, OpcionesImportacion opciones) {
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS);
        List<Campo> faltan = mapeo.obligatoriosSinMapear(CampoProducto.values());
        if (!faltan.isEmpty()) {
            throw new NegocioException("Falta indicar qué columna es: " + String.join(", ", faltan.stream().map(Campo::etiqueta).toList()) + ".");
        }
        return PreparacionProductos.preparar(tabla, origen, mapeo, opciones, catalogo());
    }

    // ── Aplicar productos ────────────────────────────────────────────────

    /**
     * Escribe el plan. {@code progreso} recibe el número de filas ya procesadas
     * (se llama desde el hilo que ejecuta; la ventana lo pasa a Platform.runLater).
     */
    public ResumenImportacion aplicar(PlanImportacion plan, Collection<Autorizacion> autorizaciones, IntConsumer progreso) {
        long inicio = System.currentTimeMillis();
        sesionService.verificarPermiso(Permiso.EDITAR_PRODUCTOS, autorizaciones);
        if (plan.requiereCambiarPrecios()) sesionService.verificarPermiso(Permiso.CAMBIAR_PRECIOS, autorizaciones);
        if (plan.requiereAjustarInventario()) sesionService.verificarPermiso(Permiso.AJUSTAR_INVENTARIO, autorizaciones);
        Autorizacion autInventario = plan.requiereAjustarInventario()
                ? sesionService.autorizacionUsada(Permiso.AJUSTAR_INVENTARIO, autorizaciones) : null;
        Autorizacion autPrecios = plan.requiereCambiarPrecios()
                ? sesionService.autorizacionUsada(Permiso.CAMBIAR_PRECIOS, autorizaciones) : null;
        Autorizacion autEditar = sesionService.autorizacionUsada(Permiso.EDITAR_PRODUCTOS, autorizaciones);
        Cajero actor = sesionService.getCajeroActual();
        String motivo = "Importación desde " + plan.origen().getNombre() + " (" + plan.tabla().nombreArchivo() + ")";

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Contadores c = new Contadores();
        List<ErrorFila> errores = new ArrayList<>(plan.errores());

        // 1) Departamentos nuevos (una transacción): clave → id
        Map<String, Long> departamentos = tx.execute(estado -> crearDepartamentos(plan, c));

        // 2) Productos por lotes
        List<FilaProducto> filas = plan.filasAAplicar();
        int procesadas = 0;
        for (int desde = 0; desde < filas.size(); desde += TAMANO_LOTE) {
            List<FilaProducto> lote = filas.subList(desde, Math.min(filas.size(), desde + TAMANO_LOTE));
            try {
                Contadores parcial = new Contadores();
                tx.execute(estado -> {
                    for (FilaProducto f : lote) aplicarFila(f, plan, departamentos, actor, motivo, autInventario, parcial);
                    em.flush();
                    em.clear();
                    return null;
                });
                c.sumar(parcial);
            } catch (RuntimeException loteFallido) {
                // Una fila mala tiró el lote: se reintenta fila por fila para rescatar las demás
                for (FilaProducto f : lote) {
                    try {
                        Contadores parcial = new Contadores();
                        tx.execute(estado -> {
                            aplicarFila(f, plan, departamentos, actor, motivo, autInventario, parcial);
                            em.flush();
                            em.clear();
                            return null;
                        });
                        c.sumar(parcial);
                    } catch (RuntimeException e) {
                        errores.add(new ErrorFila(f.numeroFila, f.codigo, mensajeDe(e)));
                    }
                }
            }
            procesadas += lote.size();
            if (progreso != null) progreso.accept(procesadas);
        }

        // 3) Bitácora y autorizaciones
        int omitidos = (int) plan.aOmitir();
        ResumenImportacion resumen = new ResumenImportacion(c.creados, c.actualizados, omitidos, c.departamentos,
                c.movimientos, c.precios, List.copyOf(errores), System.currentTimeMillis() - inicio);
        Autorizacion autorizacionUsada = autInventario != null ? autInventario : autPrecios != null ? autPrecios : autEditar;
        tx.execute(estado -> {
            auditoriaService.registrar(actor, "IMPORTACION_PRODUCTOS", plan.origen().getNombre() + " \""
                    + plan.tabla().nombreArchivo() + "\": " + resumen.resumenCorto()
                    + (c.movimientos > 0 ? " Movimientos de inventario: " + c.movimientos + "." : "")
                    + (c.precios > 0 ? " Cambios de precio: " + c.precios + "." : ""), autorizacionUsada);
            return null;
        });
        if (autInventario != null) autInventario.consumir();
        if (autPrecios != null) autPrecios.consumir();
        if (autEditar != null) autEditar.consumir();
        return resumen;
    }

    private Map<String, Long> crearDepartamentos(PlanImportacion plan, Contadores c) {
        Map<String, Long> ids = new HashMap<>();
        for (String nombre : plan.departamentosNuevos()) {
            String clave = Texto.clave(nombre);
            Departamento existente = departamentoRepo.findByNombreIgnoreCase(nombre).orElse(null);
            if (existente == null) {
                Departamento d = new Departamento();
                d.setNombre(nombre);
                existente = departamentoRepo.save(d);
                c.departamentos++;
            }
            ids.put(clave, existente.getId());
        }
        for (Departamento d : departamentoRepo.findAll()) ids.putIfAbsent(Texto.clave(d.getNombre()), d.getId());
        return ids;
    }

    private void aplicarFila(FilaProducto f, PlanImportacion plan, Map<String, Long> departamentos, Cajero actor,
                             String motivo, Autorizacion autInventario, Contadores c) {
        if (f.accion == Accion.CREAR) {
            if (productoRepo.findByCodigoBarras(f.codigo).isPresent()) {
                throw new NegocioException("El código ya existe en Caja Libre (se agregó después de validar el archivo).");
            }
            Producto p = new Producto();
            p.setCodigoBarras(f.codigo);
            p.setDescripcion(f.descripcion);
            p.setPrecioCosto(f.precioCosto != null ? f.precioCosto : 0);
            p.setPrecioVenta(f.precioVenta);
            p.setPrecioMayoreo(f.precioMayoreo != null ? f.precioMayoreo : 0);
            p.setDepartamento(referenciaDepartamento(f, departamentos));
            p.setUnidad(f.unidad != null ? em.getReference(Unidad.class, f.unidad.getId()) : null);
            p.setTipoVenta(f.tipoVenta != null ? f.tipoVenta : Producto.TipoVenta.UNIDAD);
            p.setActivo(f.activo == null || f.activo);
            p.setUsaInventario(true);
            p.setCantidadActual(f.existencia != null ? f.existencia : 0);
            p.setCantidadMinima(f.minimo != null ? f.minimo : 0);
            p.setClaveProdServ(f.claveProdServ);
            p.setClaveUnidad(f.claveUnidad);
            Producto guardado = productoRepo.save(p);
            c.creados++;
            if (guardado.getCantidadActual() > 0) {
                productoService.registrarMovimiento(guardado, MovimientoInventario.Tipo.ALTA_INICIAL,
                        guardado.getCantidadActual(), 0, guardado.getCantidadActual(), motivo, null, null, null, autInventario);
                c.movimientos++;
            }
            return;
        }

        Producto p = productoRepo.findById(f.idExistente)
                .orElseThrow(() -> new NegocioException("El producto ya no existe en Caja Libre."));
        if (f.descripcion != null) p.setDescripcion(f.descripcion);
        if (f.precioVenta != null) c.precios += cambiarPrecio(p, "VENTA", p.getPrecioVenta(), f.precioVenta, actor);
        if (f.precioCosto != null) c.precios += cambiarPrecio(p, "COSTO", p.getPrecioCosto(), f.precioCosto, actor);
        if (f.precioMayoreo != null) c.precios += cambiarPrecio(p, "MAYOREO", p.getPrecioMayoreo(), f.precioMayoreo, actor);
        if (f.precioVenta != null) p.setPrecioVenta(f.precioVenta);
        if (f.precioCosto != null) p.setPrecioCosto(f.precioCosto);
        if (f.precioMayoreo != null) p.setPrecioMayoreo(f.precioMayoreo);
        if (f.departamentoIndicado) p.setDepartamento(referenciaDepartamento(f, departamentos));
        if (f.unidadIndicada) {
            p.setUnidad(f.unidad != null ? em.getReference(Unidad.class, f.unidad.getId()) : null);
            p.setTipoVenta(f.tipoVenta);
        }
        if (f.minimo != null) p.setCantidadMinima(f.minimo);
        if (f.activo != null) p.setActivo(f.activo);
        if (plan.mapeo().tiene(CampoProducto.CLAVE_PROD_SERV) && plan.opciones().duplicados() == OpcionesImportacion.Duplicados.ACTUALIZAR_TODO) {
            p.setClaveProdServ(f.claveProdServ);
        }
        if (plan.mapeo().tiene(CampoProducto.CLAVE_UNIDAD) && plan.opciones().duplicados() == OpcionesImportacion.Duplicados.ACTUALIZAR_TODO) {
            p.setClaveUnidad(f.claveUnidad);
        }
        if (f.existencia != null && p.isUsaInventario() && Math.abs(p.getCantidadActual() - f.existencia) > 0.0001) {
            double antes = p.getCantidadActual();
            p.setCantidadActual(f.existencia);
            productoService.registrarMovimiento(p, MovimientoInventario.Tipo.AJUSTE, f.existencia - antes, antes,
                    f.existencia, motivo, null, null, null, autInventario);
            c.movimientos++;
        }
        productoRepo.save(p);
        c.actualizados++;
    }

    private Departamento referenciaDepartamento(FilaProducto f, Map<String, Long> departamentos) {
        if (f.departamento == null) return null;
        Long id = departamentos.get(Texto.clave(f.departamento));
        return id == null ? null : em.getReference(Departamento.class, id);
    }

    private int cambiarPrecio(Producto p, String campo, double anterior, double nuevo, Cajero actor) {
        if (Math.abs(anterior - nuevo) < 0.0001) return 0;
        historialPrecioRepo.save(new HistorialPrecio(p, campo, anterior, nuevo, actor));
        return 1;
    }

    private static String mensajeDe(RuntimeException e) {
        if (e instanceof NegocioException) return e.getMessage();
        Throwable causa = e;
        while (causa.getCause() != null && causa.getCause() != causa) causa = causa.getCause();
        String msg = causa.getMessage();
        if (msg != null && (msg.contains("UNIQUE") || msg.contains("unique") || msg.contains("duplicate"))) {
            return "El código ya existe en Caja Libre.";
        }
        return "No se pudo guardar: " + (msg == null ? causa.getClass().getSimpleName() : msg);
    }

    private static final class Contadores {
        int creados, actualizados, departamentos, movimientos, precios;

        void sumar(Contadores o) {
            creados += o.creados;
            actualizados += o.actualizados;
            departamentos += o.departamentos;
            movimientos += o.movimientos;
            precios += o.precios;
        }
    }

    // ── Proveedores ──────────────────────────────────────────────────────

    /** Fila de proveedor validada; {@code omitir} = ya existe uno con ese nombre */
    public record FilaProveedor(int numeroFila, String nombre, String rfc, String telefono, String contacto, String notas,
                                boolean omitir) { }

    public record PlanProveedores(TablaImportada tabla, Mapeo mapeo, List<FilaProveedor> filas, List<ErrorFila> errores) {
        public long aCrear()  { return filas.stream().filter(f -> !f.omitir()).count(); }
        public long aOmitir() { return filas.stream().filter(FilaProveedor::omitir).count(); }
    }

    public PlanProveedores prepararProveedores(TablaImportada tabla, Mapeo mapeo, boolean primeraFilaEsEncabezado) {
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        if (!mapeo.tiene(CampoProveedor.NOMBRE)) throw new NegocioException("Falta indicar qué columna es el nombre del proveedor.");
        Map<String, Proveedor> existentes = new HashMap<>();
        for (Proveedor p : proveedorRepo.findAll()) existentes.put(ProveedorService.clave(p.getNombre()), p);
        List<FilaProveedor> filas = new ArrayList<>();
        List<ErrorFila> errores = new ArrayList<>();
        Map<String, Integer> vistos = new LinkedHashMap<>();
        List<List<String>> datos = tabla.datos(primeraFilaEsEncabezado);
        for (int i = 0; i < datos.size(); i++) {
            List<String> fila = datos.get(i);
            int numero = TablaImportada.numeroDeFila(i, primeraFilaEsEncabezado);
            String nombre = mapeo.valor(fila, CampoProveedor.NOMBRE);
            if (nombre.isEmpty()) {
                errores.add(new ErrorFila(numero, "", "Falta el nombre del proveedor."));
                continue;
            }
            if (nombre.length() > 150) nombre = nombre.substring(0, 150).trim();
            String clave = ProveedorService.clave(nombre);
            Integer antes = vistos.putIfAbsent(clave, numero);
            if (antes != null) {
                errores.add(new ErrorFila(numero, nombre, "El proveedor ya apareció en la fila " + antes + " de este archivo."));
                continue;
            }
            filas.add(new FilaProveedor(numero, nombre, vacioANull(mapeo.valor(fila, CampoProveedor.RFC)),
                    vacioANull(mapeo.valor(fila, CampoProveedor.TELEFONO)), vacioANull(mapeo.valor(fila, CampoProveedor.CONTACTO)),
                    vacioANull(Texto.recortar(mapeo.valor(fila, CampoProveedor.NOTAS), 500)), existentes.containsKey(clave)));
        }
        return new PlanProveedores(tabla, mapeo, filas, errores);
    }

    /** Cada proveedor en su transacción (ProveedorService valida RFC, teléfono y nombre duplicado) */
    public ResumenImportacion aplicarProveedores(PlanProveedores plan, IntConsumer progreso) {
        long inicio = System.currentTimeMillis();
        sesionService.verificarPermiso(Permiso.GESTIONAR_PROVEEDORES);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        List<ErrorFila> errores = new ArrayList<>(plan.errores());
        int creados = 0, omitidos = 0, procesadas = 0;
        for (FilaProveedor f : plan.filas()) {
            if (f.omitir()) {
                omitidos++;
            } else {
                try {
                    tx.execute(estado -> proveedorService.guardar(null, f.nombre(), f.rfc(), f.telefono(), f.contacto(), null, f.notas()));
                    creados++;
                } catch (RuntimeException e) {
                    errores.add(new ErrorFila(f.numeroFila(), f.nombre(), mensajeDe(e)));
                }
            }
            procesadas++;
            if (progreso != null) progreso.accept(procesadas);
        }
        ResumenImportacion resumen = new ResumenImportacion(creados, 0, omitidos, 0, 0, 0, List.copyOf(errores),
                System.currentTimeMillis() - inicio);
        final int creadosFinal = creados;
        tx.execute(estado -> {
            auditoriaService.registrar(sesionService.getCajeroActual(), "IMPORTACION_PROVEEDORES",
                    "\"" + plan.tabla().nombreArchivo() + "\": " + creadosFinal + " creados, " + resumen.omitidos()
                    + " omitidos, " + resumen.errores().size() + " con error.", (Cajero) null);
            return null;
        });
        return resumen;
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
