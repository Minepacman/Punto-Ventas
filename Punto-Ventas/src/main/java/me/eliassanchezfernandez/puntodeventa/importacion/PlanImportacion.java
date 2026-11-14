package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.List;
import java.util.Set;

/**
 * Resultado de validar el archivo contra el catálogo: lo que se haría al
 * aplicar. Se muestra al usuario antes de tocar la base de datos.
 *
 * @param filas                     filas válidas (CREAR / ACTUALIZAR / OMITIR)
 * @param errores                   filas que no se importarán, con el motivo
 * @param departamentosNuevos       departamentos que se crearán
 * @param requiereAjustarInventario alguna fila cambia existencias (alta con existencia o ajuste)
 * @param requiereCambiarPrecios    alguna fila cambia precios de un producto existente
 */
public record PlanImportacion(Origen origen, TablaImportada tabla, Mapeo mapeo, OpcionesImportacion opciones,
                              List<FilaProducto> filas, List<ErrorFila> errores, Set<String> departamentosNuevos,
                              boolean requiereAjustarInventario, boolean requiereCambiarPrecios) {

    public record ErrorFila(int numeroFila, String codigo, String mensaje) {
        @Override public String toString() {
            return "Fila " + numeroFila + (codigo == null || codigo.isBlank() ? "" : " (" + codigo + ")") + ": " + mensaje;
        }
    }

    public long aCrear()      { return filas.stream().filter(f -> f.accion == FilaProducto.Accion.CREAR).count(); }
    public long aActualizar() { return filas.stream().filter(f -> f.accion == FilaProducto.Accion.ACTUALIZAR).count(); }
    public long aOmitir()     { return filas.stream().filter(f -> f.accion == FilaProducto.Accion.OMITIR).count(); }
    public long conAvisos()   { return filas.stream().filter(f -> !f.avisos.isEmpty()).count(); }

    /** Filas que realmente escriben algo */
    public List<FilaProducto> filasAAplicar() {
        return filas.stream().filter(f -> f.accion != FilaProducto.Accion.OMITIR).toList();
    }

    public boolean hayAlgoQueAplicar() {
        return aCrear() + aActualizar() > 0;
    }
}
