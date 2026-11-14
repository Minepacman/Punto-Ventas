package me.eliassanchezfernandez.puntodeventa.importacion;

/**
 * Decisiones del usuario en el asistente.
 *
 * @param duplicados               qué hacer con los códigos que ya existen en Caja Libre
 * @param crearDepartamentos       crear los departamentos que no existan (si no, el producto queda sin departamento)
 * @param importarExistencias      tomar la columna de existencia (alta inicial / ajuste auditado; exige AJUSTAR_INVENTARIO)
 * @param primeraFilaEsEncabezado  la primera fila del archivo son los nombres de columna
 */
public record OpcionesImportacion(Duplicados duplicados, boolean crearDepartamentos, boolean importarExistencias,
                                  boolean primeraFilaEsEncabezado) {

    public static OpcionesImportacion porDefecto() {
        return new OpcionesImportacion(Duplicados.OMITIR, true, true, true);
    }

    public enum Duplicados {
        OMITIR("Omitir: dejar el producto como está en Caja Libre"),
        ACTUALIZAR_PRECIOS("Actualizar solo los precios (costo, venta y mayoreo)"),
        ACTUALIZAR_TODO("Actualizar todo (descripción, precios, departamento, unidad, mínimo y existencia)");

        private final String etiqueta;

        Duplicados(String etiqueta) { this.etiqueta = etiqueta; }

        public String getEtiqueta() { return etiqueta; }

        @Override public String toString() { return etiqueta; }
    }
}
