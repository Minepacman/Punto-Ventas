package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.List;

/**
 * Campos de producto que se pueden importar. Los alias son encabezados reales
 * de otros sistemas (eleventa, SICAR, Abarrotes PDV, MyBusiness POS, Loyverse,
 * Excel genérico) ya normalizados con {@link Texto#clave}: sin acentos,
 * minúsculas y sin signos. Orden = orden de la plantilla de Caja Libre.
 */
public enum CampoProducto implements Campo {

    CODIGO("Código de barras", true, "Obligatorio y único. Hasta 50 caracteres.",
            "codigodebarras", "codigobarras", "codbarras", "barcode", "ean", "upc", "upcean", "codigo",
            "codigodelproducto", "codigoproducto", "clave", "clavedelproducto", "claveproducto", "codigointerno",
            "cve", "cveart", "cvearticulo", "codigoarticulo", "articulo", "idproducto", "sku", "code", "handle",
            "numero", "noparte", "numeroparte", "id"),
    DESCRIPCION("Descripción", true, "Obligatoria. Hasta 200 caracteres.",
            "descripcion", "descripcionproducto", "descripciondelproducto", "nombre", "nombredelproducto",
            "nombreproducto", "producto", "nombrearticulo", "descripcionarticulo", "articulodescripcion",
            "descripcioncorta", "name", "itemname", "description", "descr", "concepto", "detalle"),
    PRECIO_COSTO("Precio de costo", false, "Lo que pagas al proveedor. Vacío = 0.",
            "preciodecosto", "preciocosto", "costo", "precioconiva", "costoconiva", "costounitario", "costodecompra",
            "preciodecompra", "preciocompra", "compra", "costototal", "ultimocosto", "costopromedio", "cost", "costprice",
            "purchaseprice", "pcosto", "pcompra", "costounit", "costoultimo", "ultimocostounitario"),
    PRECIO_VENTA("Precio de venta", true, "Obligatorio, mayor que cero. Precio al público.",
            "preciodeventa", "precioventa", "precio", "preciopublico", "precioalpublico", "preciounitario", "venta",
            "precio1", "preciomenudeo", "menudeo", "preciodetalle", "preciodevental", "precioventaconiva", "precioconivaventa",
            "pventa", "pv", "price", "saleprice", "sellingprice", "preciofinal", "precionormal", "preciobase",
            "preciodeventanormal"),
    PRECIO_MAYOREO("Precio de mayoreo", false, "Opcional. Vacío = sin mayoreo.",
            "preciodemayoreo", "preciomayoreo", "mayoreo", "preciomayorista", "preciomayor", "precioalmayoreo",
            "precio2", "preciodeventa2", "precioventa2", "pmayoreo", "wholesaleprice", "mayorista", "preciodemayoreo1",
            "preciomayoreo1", "preciomedio"),
    DEPARTAMENTO("Departamento", false, "Se crea si no existe (opción del asistente).",
            "departamento", "depto", "dpto", "categoria", "categoriadelproducto", "categoriaproducto", "familia",
            "linea", "lineadeproducto", "grupo", "clasificacion", "seccion", "rubro", "category", "categories", "department",
            "departamentoycategorias",
            "tipodeproducto", "giro", "sublinea"),
    UNIDAD("Unidad / se vende por", false, "Pieza, Kilogramo, Litro, Paquete... o \"Granel\" / \"Pieza\".",
            "unidadsevendepor", "unidad", "unidaddemedida", "unidadmedida", "unidadventa", "unidaddeventa", "sevendepor", "vendepor",
            "sevendea", "ventapor", "tipodeventa", "tipoventa", "tipo", "medida", "um", "udm", "presentacion",
            "granel", "granelsn", "esgranel", "agranel", "unit", "unidaddevental", "soldby", "soldbyweight", "pesable",
            "sevendeporpeso"),
    EXISTENCIA("Existencia", false, "Cantidad actual en tienda. Se registra como movimiento de inventario.",
            "existencia", "existencias", "existenciaactual", "cantidad", "cantidadactual", "stock", "inventario",
            "cantidadeninventario", "cantidadinventario", "disponible", "disponibles", "exist", "existencialactual",
            "enstock", "instock", "quantity", "qty", "currentquantity", "cantidaddisponible", "existenciatotal", "piezas", "almacen",
            "existenciaalmacen"),
    MINIMO("Mínimo", false, "Existencia mínima para avisar reabastecimiento.",
            "minimo", "stockminimo", "existenciaminima", "cantidadminima", "invmin", "invminimo", "inventariominimo", "min",
            "minstock", "lowstock", "lowstockalert", "reorderpoint", "puntodereorden", "minimoinventario", "stockmin",
            "existenciamin", "alerta", "alertastock", "cantidadminimadealerta"),
    ACTIVO("Activo", false, "Sí/No. Vacío = activo. \"No\" = descontinuado (no aparece en Ventas).",
            "activo", "estatus", "estado", "status", "habilitado", "visible", "disponibleventa", "sevende", "active",
            "available", "availableforsale", "descontinuado", "inactivo", "baja"),
    CLAVE_PROD_SERV("Clave SAT (producto)", false, "c_ClaveProdServ, 8 dígitos. Opcional, solo para facturar.",
            "clavesat", "claveprodserv", "clavedelsat", "clavesatproducto", "claveproductosat", "claveproductoservicio",
            "claveprodservsat", "cveprodserv", "clavesatprodserv", "cprodserv", "productosat",
            "claveproductoserviciocfdi33", "claveproductoserviciocfdi40"),
    CLAVE_UNIDAD("Clave SAT (unidad)", false, "c_ClaveUnidad (H87, KGM...). Opcional, solo para facturar.",
            "claveunidad", "claveunidadsat", "clavesatunidad", "unidadsat", "cveunidad", "claveunidadmedida",
            "cunidad", "claveum", "claveunidadcfdi", "unidadmedidacfdi", "unidaddemedidacfdi33", "unidaddemedidacfdi40");

    private final String etiqueta;
    private final boolean obligatorio;
    private final String ayuda;
    private final List<String> alias;

    CampoProducto(String etiqueta, boolean obligatorio, String ayuda, String... alias) {
        this.etiqueta = etiqueta;
        this.obligatorio = obligatorio;
        this.ayuda = ayuda;
        this.alias = List.of(alias);
    }

    @Override public String etiqueta()     { return etiqueta; }
    @Override public List<String> alias()  { return alias; }
    @Override public boolean obligatorio() { return obligatorio; }
    @Override public String ayuda()        { return ayuda; }

    @Override public String toString() { return etiqueta; }
}
