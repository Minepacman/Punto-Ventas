package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sistema del que viene el archivo, reconocido por sus encabezados. Cada origen
 * tiene una o varias "huellas" (conjuntos de encabezados normalizados que deben
 * estar TODOS presentes) y, si hace falta, un mapeo explícito que manda sobre
 * los alias genéricos (p. ej. en Loyverse "Name" es el nombre y "Description"
 * un texto largo que no nos sirve).
 *
 * Las huellas salen de los archivos y manuales reales (noviembre 2026):
 *  - eleventa 5: exporta .xls con "Código, Producto, P. Costo, P. Venta, P. Mayoreo, Departamento, Existencia,
 *    Inv. Mínimo, Inv. Máximo, Tipo de Venta, Proveedor, Clave Producto / Servicio, Unidad Medida CFDI, IVA, IEPS"
 *    (eleventa.com/aprender/importar-productos). Su plantilla oficial plantilla.xls: "Codigo, Descripcion, Precio Costo,
 *    Precio Venta, Precio Mayoreo, Departamento, Existencia, Inv. Minimo, Inv. Máximo, Tipo de Venta, Impuesto 1 (IVA),
 *    Impuesto 2 (Otro), Clave Producto / Servicio (CFDI 3.3), Unidad de Medida (CFDI 3.3)".
 *  - Abarrotes PDV: "Codigo, Descripcion, Precio Costo, Precio Venta, Precio Mayoreo, Inventario,
 *    Inv. Minimo, Departamento" (eleventa.com/aprender/migrar-abarrotes-punto-de-venta).
 *  - SICAR: plantilla "Clave, Clave Alterna, Descripción, Servicio (S/N), Inventario Mínimo, Inventario Máximo,
 *    Precio de Compra, Precio 1..4, Mayoreo 2..4, Existencia, Peso, Características, Departamento y Categorías,
 *    Receta (S/N), Granel (S/N), Impuesto (S/N)" (manual.sicar.mx/articulos). "Mayoreo N" es una CANTIDAD, no un precio.
 *  - MyBusiness POS: "Código del producto, Descripción, Linea, Precio de venta, Costo ultimo, Existencia, Unidad,
 *    Precio2..5, Impuesto, Código de barras" (helpmybusinesspos.info/prodsexcel).
 *  - Loyverse: CSV "Handle, SKU, Name, Category, Description, Cost, Price, ..., Barcode, ..., Sold by weight, ...,
 *    In stock, Low stock, Available for sale" (help.loyverse.com/help/importing-and-exporting).
 *  - Square: "Token, Item Name, Variation Name, Description, SKU, Categories, Price, New Quantity, Current Quantity..."
 */
public enum Origen {

    CAJA_LIBRE("Plantilla de Caja Libre", null,
            List.of(Set.of("codigodebarras", "descripcion", "preciodeventa", "clavesatproducto")),
            Map.of()),

    ELEVENTA("eleventa", "Los precios vienen con \"$\" (se limpian). \"- Sin Departamento -\" se toma como sin departamento. "
            + "\"Inv. Máximo\", impuestos y proveedor no se importan (Caja Libre no los maneja por producto).",
            List.of(Set.of("codigo", "producto", "pcosto", "pventa", "tipodeventa"),
                    Set.of("codigo", "descripcion", "preciocosto", "precioventa", "tipodeventa", "invminimo")),
            Map.of("producto", CampoProducto.DESCRIPCION, "unidadmedidacfdi", CampoProducto.CLAVE_UNIDAD,
                    "unidaddemedidacfdi33", CampoProducto.CLAVE_UNIDAD,
                    "claveproductoservicio", CampoProducto.CLAVE_PROD_SERV,
                    "claveproductoserviciocfdi33", CampoProducto.CLAVE_PROD_SERV)),

    ABARROTES_PDV("Abarrotes PDV", "\"Inventario\" es la existencia actual. No trae tipo de venta: todo se "
            + "importa como pieza salvo que cambies la unidad después.",
            List.of(Set.of("codigo", "descripcion", "preciocosto", "precioventa", "inventario", "invminimo")),
            Map.of()),

    SICAR("SICAR", "\"Precio 1\" es el precio de venta y \"Precio 2\" se toma como mayoreo (SICAR maneja "
            + "hasta 4 precios por cantidad; \"Mayoreo N\" es una cantidad y no se importa). \"Granel (S/N)\" decide la unidad.",
            List.of(Set.of("clave", "descripcion", "precio1", "existencia", "granelsn"),
                    Set.of("clave", "clavealterna", "descripcion", "preciodecompra", "precio1")),
            Map.of("precio1", CampoProducto.PRECIO_VENTA, "precio2", CampoProducto.PRECIO_MAYOREO,
                    "granelsn", CampoProducto.UNIDAD, "departamentoycategorias", CampoProducto.DEPARTAMENTO,
                    "preciodecompra", CampoProducto.PRECIO_COSTO, "inventariominimo", CampoProducto.MINIMO)),

    MYBUSINESS("MyBusiness POS", "Se usa \"Código de barras\" como código; si viene vacío, cambia el mapeo a "
            + "\"Código del producto\". \"Linea\" se toma como departamento.",
            List.of(Set.of("codigodelproducto", "descripcion", "linea", "preciodeventa", "costoultimo")),
            Map.of("codigodebarras", CampoProducto.CODIGO, "linea", CampoProducto.DEPARTAMENTO,
                    "costoultimo", CampoProducto.PRECIO_COSTO, "precio2", CampoProducto.PRECIO_MAYOREO)),

    LOYVERSE("Loyverse", "\"Name\" es la descripción; \"Barcode\" el código (si está vacío, cambia el mapeo a \"SKU\"). "
            + "\"Sold by weight\" = granel. Los artículos con variantes (Option 1...) se importan una fila por variante.",
            List.of(Set.of("handle", "sku", "name", "price", "soldbyweight")),
            Map.of("name", CampoProducto.DESCRIPCION, "barcode", CampoProducto.CODIGO, "category", CampoProducto.DEPARTAMENTO,
                    "cost", CampoProducto.PRECIO_COSTO, "price", CampoProducto.PRECIO_VENTA,
                    "soldbyweight", CampoProducto.UNIDAD, "instock", CampoProducto.EXISTENCIA,
                    "lowstock", CampoProducto.MINIMO, "availableforsale", CampoProducto.ACTIVO)),

    SQUARE("Square", "\"Item Name\" es la descripción y \"SKU\" el código. \"Current Quantity\" es la existencia.",
            List.of(Set.of("token", "itemname", "sku", "price")),
            Map.of("itemname", CampoProducto.DESCRIPCION, "sku", CampoProducto.CODIGO, "categories", CampoProducto.DEPARTAMENTO,
                    "price", CampoProducto.PRECIO_VENTA, "currentquantity", CampoProducto.EXISTENCIA)),

    GENERICO("Archivo genérico (Excel / CSV)", "Revisa el mapeo: se propuso por el nombre de cada columna.",
            List.of(), Map.of());

    private final String nombre;
    private final String nota;
    private final List<Set<String>> huellas;
    private final Map<String, Campo> mapeoExplicito;

    Origen(String nombre, String nota, List<Set<String>> huellas, Map<String, Campo> mapeoExplicito) {
        this.nombre = nombre;
        this.nota = nota;
        this.huellas = huellas;
        this.mapeoExplicito = mapeoExplicito;
    }

    public String getNombre()  { return nombre; }
    public String getNota()    { return nota; }

    /** Encabezado normalizado → campo que manda sobre los alias genéricos */
    public Map<String, Campo> mapeoExplicito() { return mapeoExplicito; }

    /** El origen cuya huella completa aparece en los encabezados; GENERICO si ninguno */
    public static Origen detectar(List<String> encabezados) {
        Set<String> claves = new HashSet<>();
        for (String h : encabezados) claves.add(Texto.clave(h));
        for (Origen o : values()) {
            for (Set<String> huella : o.huellas) {
                if (claves.containsAll(huella)) return o;
            }
        }
        return GENERICO;
    }

    /** Mapeo propuesto: primero lo explícito del origen, después los alias genéricos para lo que falte */
    public Mapeo mapeoAutomatico(List<String> encabezados, Campo[] campos) {
        Mapeo m = Mapeo.automatico(encabezados, campos);
        if (mapeoExplicito.isEmpty()) return m;
        Mapeo conOrigen = new Mapeo();
        for (int c = 0; c < encabezados.size(); c++) {
            Campo campo = mapeoExplicito.get(Texto.clave(encabezados.get(c)));
            if (campo != null && !conOrigen.tiene(campo)) conOrigen.asignar(campo, c);
        }
        for (Map.Entry<Campo, Integer> e : m.comoMapa().entrySet()) {
            if (!conOrigen.tiene(e.getKey()) && conOrigen.campoDeColumna(e.getValue()) == null) {
                conOrigen.asignar(e.getKey(), e.getValue());
            }
        }
        return conOrigen;
    }

    @Override public String toString() { return nombre; }
}
