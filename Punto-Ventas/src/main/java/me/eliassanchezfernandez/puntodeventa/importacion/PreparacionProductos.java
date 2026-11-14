package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.importacion.FilaProducto.Accion;
import me.eliassanchezfernandez.puntodeventa.importacion.OpcionesImportacion.Duplicados;
import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion.ErrorFila;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.util.CalculoCobro;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valida e interpreta las filas del archivo contra el catálogo actual, sin
 * tocar la base de datos. Replica las reglas de {@code ProductoService.validar}
 * (código y descripción obligatorios, precio de venta > 0, sin negativos, granel
 * solo si está habilitado, decimales solo a granel, claves SAT con formato) para
 * que al aplicar no haya sorpresas. Lo que se puede corregir sin inventar datos
 * (recortar una descripción larga, redondear una existencia de un producto por
 * pieza) se corrige y se anota como aviso; lo demás es error y la fila se omite.
 */
public final class PreparacionProductos {

    /** Lo que hay hoy en Caja Libre, cargado una sola vez por el servicio */
    public record Catalogo(Map<String, Producto> productosPorCodigo, Map<String, Departamento> departamentosPorClave,
                           List<Unidad> unidades, boolean permitirGranel) { }

    private static final int LARGO_CODIGO = 50;
    private static final int LARGO_DESCRIPCION = 200;
    private static final Set<String> SIN_DEPARTAMENTO = Set.of("", "sindepartamento", "sindepto", "ninguno", "ninguna",
            "na", "nd", "null", "none", "sincategoria", "sinasignar", "0");
    private static final Set<String> VERDADERO = Set.of("si", "s", "1", "true", "verdadero", "x", "activo", "yes", "y",
            "habilitado", "alta", "disponible", "ok");
    private static final Set<String> FALSO = Set.of("no", "n", "0", "false", "falso", "inactivo", "descontinuado", "baja",
            "deshabilitado", "nodisponible");
    /** Encabezados cuyo "Sí" significa INACTIVO */
    private static final Set<String> ENCABEZADOS_INVERTIDOS = Set.of("descontinuado", "inactivo", "baja", "deshabilitado");

    private PreparacionProductos() { }

    public static PlanImportacion preparar(TablaImportada tabla, Origen origen, Mapeo mapeo, OpcionesImportacion opciones,
                                           Catalogo catalogo) {
        List<FilaProducto> filas = new ArrayList<>();
        List<ErrorFila> errores = new ArrayList<>();
        Set<String> departamentosNuevos = new LinkedHashSet<>();
        Map<String, Integer> codigosVistos = new HashMap<>();
        Map<String, String> departamentosNuevosPorClave = new HashMap<>();
        boolean requiereInventario = false;
        boolean requierePrecios = false;

        boolean encabezado = opciones.primeraFilaEsEncabezado();
        List<String> encabezados = tabla.encabezados(encabezado);
        boolean activoInvertido = encabezadoInvertido(mapeo, encabezados);
        List<List<String>> datos = tabla.datos(encabezado);

        for (int i = 0; i < datos.size(); i++) {
            List<String> fila = datos.get(i);
            int numero = TablaImportada.numeroDeFila(i, encabezado);
            String codigo = normalizarCodigo(mapeo.valor(fila, CampoProducto.CODIGO));
            try {
                FilaProducto f = interpretar(numero, fila, codigo, mapeo, opciones, catalogo, activoInvertido);
                Integer repetida = codigosVistos.putIfAbsent(f.codigo.toLowerCase(), numero);
                if (repetida != null) {
                    throw new FilaInvalida("El código ya apareció en la fila " + repetida + " de este archivo; se toma solo la primera.");
                }
                if (f.departamento != null && f.departamentoNuevo) {
                    String clave = Texto.clave(f.departamento);
                    String yaPropuesto = departamentosNuevosPorClave.putIfAbsent(clave, f.departamento);
                    if (yaPropuesto != null) f.departamento = yaPropuesto;   // misma mayúscula para todos
                    departamentosNuevos.add(f.departamento);
                }
                if (f.accion != Accion.OMITIR) {
                    requiereInventario |= f.cambiaExistencia;
                    requierePrecios |= f.accion == Accion.ACTUALIZAR && f.cambiaPrecios;
                }
                filas.add(f);
            } catch (FilaInvalida e) {
                errores.add(new ErrorFila(numero, codigo, e.getMessage()));
            }
        }
        return new PlanImportacion(origen, tabla, mapeo, opciones, filas, errores, departamentosNuevos,
                requiereInventario, requierePrecios);
    }

    // ── Una fila ─────────────────────────────────────────────────────────

    private static FilaProducto interpretar(int numero, List<String> fila, String codigo, Mapeo mapeo,
                                            OpcionesImportacion opciones, Catalogo catalogo, boolean activoInvertido) {
        FilaProducto f = new FilaProducto(numero);
        if (codigo.isEmpty()) throw new FilaInvalida("Falta el código de barras.");
        if (codigo.length() > LARGO_CODIGO) throw new FilaInvalida("El código tiene más de " + LARGO_CODIGO + " caracteres.");
        f.codigo = codigo;

        Producto existente = catalogo.productosPorCodigo().get(codigo.toLowerCase());
        boolean nuevo = existente == null;
        Duplicados modo = opciones.duplicados();
        if (!nuevo) {
            f.idExistente = existente.getId();
            f.accion = modo == Duplicados.OMITIR ? Accion.OMITIR : Accion.ACTUALIZAR;
        } else {
            f.accion = Accion.CREAR;
        }
        if (f.accion == Accion.OMITIR) return f;          // se deja como está: no hay nada que validar
        boolean todo = nuevo || modo == Duplicados.ACTUALIZAR_TODO;

        // Descripción
        String descripcion = mapeo.valor(fila, CampoProducto.DESCRIPCION);
        if (nuevo && descripcion.isEmpty()) throw new FilaInvalida("Falta la descripción.");
        if (todo && !descripcion.isEmpty()) {
            if (descripcion.length() > LARGO_DESCRIPCION) {
                descripcion = descripcion.substring(0, LARGO_DESCRIPCION).trim();
                f.aviso("Descripción recortada a " + LARGO_DESCRIPCION + " caracteres.");
            }
            f.descripcion = descripcion;
        }

        // Precios
        Double venta = numero(f, fila, mapeo, CampoProducto.PRECIO_VENTA);
        Double costo = numero(f, fila, mapeo, CampoProducto.PRECIO_COSTO);
        Double mayoreo = numero(f, fila, mapeo, CampoProducto.PRECIO_MAYOREO);
        if (nuevo) {
            if (venta == null) throw new FilaInvalida("Falta el precio de venta.");
            if (costo == null) costo = 0.0;
            if (mayoreo == null) mayoreo = 0.0;
        }
        if (venta != null && venta <= 0) throw new FilaInvalida("El precio de venta debe ser mayor a cero.");
        if ((costo != null && costo < 0) || (mayoreo != null && mayoreo < 0)) {
            throw new FilaInvalida("Los precios no pueden ser negativos.");
        }
        f.precioVenta = venta;
        f.precioCosto = costo;
        f.precioMayoreo = mayoreo;
        double ventaFinal = venta != null ? venta : (existente != null ? existente.getPrecioVenta() : 0);
        double mayoreoFinal = mayoreo != null ? mayoreo : (existente != null ? existente.getPrecioMayoreo() : 0);
        if (mayoreoFinal > 0 && mayoreoFinal > ventaFinal + 0.0001) {
            f.aviso("El precio de mayoreo es mayor que el de venta.");
        }
        if (existente != null && f.accion == Accion.ACTUALIZAR) {
            f.cambiaPrecios = distinto(venta, existente.getPrecioVenta()) || distinto(costo, existente.getPrecioCosto())
                    || distinto(mayoreo, existente.getPrecioMayoreo());
        }

        // Departamento, unidad, mínimo, activo, claves SAT: solo en alta o "actualizar todo"
        if (todo) {
            departamento(f, fila, mapeo, opciones, catalogo);
            unidad(f, fila, mapeo, catalogo, existente);
            Double minimo = numero(f, fila, mapeo, CampoProducto.MINIMO);
            if (minimo != null && minimo < 0) {
                f.aviso("Mínimo negativo: se deja en 0.");
                minimo = 0.0;
            }
            f.minimo = minimo;
            f.activo = activo(mapeo.valor(fila, CampoProducto.ACTIVO), activoInvertido, mapeo.tiene(CampoProducto.ACTIVO));
            clavesSat(f, fila, mapeo);
        }

        // Existencia: alta inicial (nuevo) o ajuste (actualizar todo), solo si el usuario lo pidió
        if (todo && opciones.importarExistencias() && mapeo.tiene(CampoProducto.EXISTENCIA)) {
            existencia(f, fila, mapeo, existente);
        }
        return f;
    }

    private static void departamento(FilaProducto f, List<String> fila, Mapeo mapeo, OpcionesImportacion opciones,
                                     Catalogo catalogo) {
        if (!mapeo.tiene(CampoProducto.DEPARTAMENTO)) return;
        f.departamentoIndicado = true;
        String texto = mapeo.valor(fila, CampoProducto.DEPARTAMENTO);
        String clave = Texto.clave(texto);
        if (SIN_DEPARTAMENTO.contains(clave) || clave.matches("^sindepartamento.*")) return;
        Departamento existente = catalogo.departamentosPorClave().get(clave);
        if (existente != null) {
            f.departamento = existente.getNombre();
            return;
        }
        if (texto.length() > 100) texto = texto.substring(0, 100).trim();
        if (opciones.crearDepartamentos()) {
            f.departamento = texto;
            f.departamentoNuevo = true;
        } else {
            f.aviso("El departamento \"" + texto + "\" no existe: el producto queda sin departamento.");
        }
    }

    private static void unidad(FilaProducto f, List<String> fila, Mapeo mapeo, Catalogo catalogo, Producto existente) {
        if (!mapeo.tiene(CampoProducto.UNIDAD)) {
            if (existente == null) f.tipoVenta = Producto.TipoVenta.UNIDAD;
            return;
        }
        String texto = mapeo.valor(fila, CampoProducto.UNIDAD);
        Interpretacion u = interpretarUnidad(texto, catalogo.unidades());
        if (u == null) {
            if (!texto.isEmpty()) f.aviso("Unidad \"" + texto + "\" no reconocida: se deja como pieza.");
            if (existente == null) f.tipoVenta = Producto.TipoVenta.UNIDAD;
            return;
        }
        boolean granel = u.tipo == Producto.TipoVenta.GRANEL;
        boolean yaEraGranel = existente != null && existente.permiteDecimales();
        if (granel && !catalogo.permitirGranel() && !yaEraGranel) {
            throw new FilaInvalida("Es un producto a granel y la venta a granel está deshabilitada en Configuración → Opciones habilitadas.");
        }
        f.unidadIndicada = true;
        f.unidad = u.unidad;
        f.tipoVenta = u.tipo;
    }

    private static void existencia(FilaProducto f, List<String> fila, Mapeo mapeo, Producto existente) {
        Double cantidad = numero(f, fila, mapeo, CampoProducto.EXISTENCIA);
        if (cantidad == null) return;
        if (existente != null && !existente.isUsaInventario()) {
            f.aviso("El producto no maneja inventario en Caja Libre: la existencia no se tocó.");
            return;
        }
        if (cantidad < 0) {
            f.aviso("Existencia negativa (" + CalculoCobro.formatoCampo(cantidad) + "): se deja en 0.");
            cantidad = 0.0;
        }
        boolean permiteDecimales = f.unidadIndicada ? f.tipoVenta == Producto.TipoVenta.GRANEL
                || (f.unidad != null && f.unidad.isPermiteDecimales())
                : existente != null ? existente.permiteDecimales() : false;
        if (!permiteDecimales && cantidad != Math.floor(cantidad)) {
            double redondeada = Math.floor(cantidad + 0.5);   // 2.5 → 3 (no redondeo bancario)
            f.aviso("Existencia con decimales (" + CalculoCobro.formatoCampo(cantidad) + ") en un producto por pieza: se redondeó a "
                    + (long) redondeada + ".");
            cantidad = redondeada;
        }
        f.existencia = cantidad;
        f.cambiaExistencia = existente == null ? cantidad > 0 : Math.abs(existente.getCantidadActual() - cantidad) > 0.0001;
    }

    private static void clavesSat(FilaProducto f, List<String> fila, Mapeo mapeo) {
        try {
            if (mapeo.tiene(CampoProducto.CLAVE_PROD_SERV)) {
                String v = mapeo.valor(fila, CampoProducto.CLAVE_PROD_SERV);
                f.claveProdServ = v.isEmpty() ? null : CatalogoSat.normalizarProdServ(v);
            }
            if (mapeo.tiene(CampoProducto.CLAVE_UNIDAD)) {
                String v = mapeo.valor(fila, CampoProducto.CLAVE_UNIDAD);
                f.claveUnidad = v.isEmpty() ? null : CatalogoSat.normalizarUnidad(v);
            }
        } catch (IllegalArgumentException e) {
            // Las claves SAT son opcionales: una clave mal formada no debe tirar la fila
            f.aviso("Clave SAT ignorada: " + e.getMessage());
            f.claveProdServ = null;
            f.claveUnidad = null;
        }
    }

    // ── Interpretación de valores ────────────────────────────────────────

    /** null si la columna no está mapeada o la celda está vacía; error si no es número */
    private static Double numero(FilaProducto f, List<String> fila, Mapeo mapeo, CampoProducto campo) {
        if (!mapeo.tiene(campo)) return null;
        String texto = mapeo.valor(fila, campo);
        if (texto.isEmpty()) return null;
        double v = CalculoCobro.parsearNumero(texto);
        if (Double.isNaN(v)) {
            throw new FilaInvalida("\"" + campo.etiqueta() + "\" no es un número válido: \"" + texto + "\".");
        }
        return v;
    }

    private static boolean distinto(Double nuevo, double actual) {
        return nuevo != null && Math.abs(nuevo - actual) > 0.0001;
    }

    /**
     * Códigos: espacios fuera; "7.50104E+12" (lo que Excel hace con un código largo
     * al guardar CSV) se expande; "7501000123.0" pierde el ".0".
     */
    static String normalizarCodigo(String texto) {
        String t = Texto.limpiar(texto);
        if (t.isEmpty()) return "";
        if (t.matches("^[0-9]+(\\.[0-9]+)?[eE]\\+?[0-9]+$") || t.matches("^[0-9]+\\.0+$")) {
            try {
                BigDecimal bd = new BigDecimal(t);
                if (bd.stripTrailingZeros().scale() <= 0) return bd.toBigInteger().toString();
            } catch (NumberFormatException ignorado) { /* se deja tal cual */ }
        }
        return t;
    }

    record Interpretacion(Unidad unidad, Producto.TipoVenta tipo) { }

    /**
     * "Granel", "KG", "Kilogramo", "Sí" (columna Granel S/N) → granel; "Pieza", "UNIDAD", "No" → pieza;
     * "Paquete"/"Caja" → paquete; el nombre o abreviatura de una unidad del catálogo → esa unidad.
     * null = no se entendió.
     */
    static Interpretacion interpretarUnidad(String texto, List<Unidad> unidades) {
        String clave = Texto.clave(texto);
        if (clave.isEmpty()) return null;
        for (Unidad u : unidades) {
            if (clave.equals(Texto.clave(u.getNombre())) || (u.getAbreviatura() != null && clave.equals(Texto.clave(u.getAbreviatura())))) {
                return new Interpretacion(u, u.isPermiteDecimales() ? Producto.TipoVenta.GRANEL : Producto.TipoVenta.UNIDAD);
            }
        }
        switch (clave) {
            case "granel", "agranel", "peso", "porpeso", "pesable", "si", "s", "1", "true", "verdadero", "y", "yes", "kg", "kgs",
                 "kilo", "kilos", "kilogramo", "kilogramos", "kgm", "g", "gr", "grs", "gramo", "gramos", "grm" -> {
                Unidad u = buscar(unidades, clave.startsWith("g") && !clave.equals("granel") ? "gramo" : "kilogramo");
                return new Interpretacion(u, Producto.TipoVenta.GRANEL);
            }
            case "lt", "l", "lts", "litro", "litros", "ltr", "ml", "mililitro", "mililitros" -> {
                return new Interpretacion(buscar(unidades, "litro"), Producto.TipoVenta.GRANEL);
            }
            case "m", "mt", "mts", "metro", "metros", "mtr" -> {
                return new Interpretacion(buscar(unidades, "metro"), Producto.TipoVenta.GRANEL);
            }
            case "pza", "pz", "pzas", "pzs", "pieza", "piezas", "unidad", "unidades", "u", "un", "und", "pc", "pcs", "each", "ea",
                 "h87", "no", "n", "0", "false", "falso", "porpieza", "porunidad", "normal" -> {
                return new Interpretacion(buscar(unidades, "pieza"), Producto.TipoVenta.UNIDAD);
            }
            case "paquete", "paq", "paquetes", "pack", "pq", "six", "sixpack", "bolsa", "bolsas", "xpk" -> {
                return new Interpretacion(buscar(unidades, "pieza"), Producto.TipoVenta.PAQUETE);
            }
            case "caja", "cajas", "cj", "xbx", "carton", "cartones" -> {
                Unidad u = buscar(unidades, "caja");
                return new Interpretacion(u != null ? u : buscar(unidades, "pieza"), Producto.TipoVenta.PAQUETE);
            }
            default -> {
                return null;
            }
        }
    }

    private static Unidad buscar(List<Unidad> unidades, String nombreClave) {
        for (Unidad u : unidades) if (Texto.clave(u.getNombre()).equals(nombreClave)) return u;
        return null;
    }

    /** null = no mapeado o vacío (se queda como está / activo en alta) */
    static Boolean activo(String texto, boolean invertido, boolean mapeado) {
        if (!mapeado) return null;
        String clave = Texto.clave(texto);
        if (clave.isEmpty()) return null;
        Boolean valor = VERDADERO.contains(clave) ? Boolean.TRUE : FALSO.contains(clave) ? Boolean.FALSE : null;
        if (valor == null) return null;
        return invertido ? !valor : valor;
    }

    private static boolean encabezadoInvertido(Mapeo mapeo, List<String> encabezados) {
        Integer col = mapeo.columnaDe(CampoProducto.ACTIVO);
        if (col == null || col >= encabezados.size()) return false;
        return ENCABEZADOS_INVERTIDOS.contains(Texto.clave(encabezados.get(col)));
    }

    /** Error de una fila: la fila se omite y se reporta; las demás siguen */
    static final class FilaInvalida extends RuntimeException {
        FilaInvalida(String mensaje) { super(mensaje); }
    }
}
