package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.List;

/**
 * Lo que pasó al aplicar una importación.
 *
 * @param errores filas que no se importaron: las que falló la validación más las que fallaron al escribir
 */
public record ResumenImportacion(int creados, int actualizados, int omitidos, int departamentosCreados,
                                 int movimientosInventario, int cambiosDePrecio, List<PlanImportacion.ErrorFila> errores,
                                 long duracionMs) {

    public int importados() {
        return creados + actualizados;
    }

    public String resumenCorto() {
        StringBuilder sb = new StringBuilder();
        sb.append(creados).append(creados == 1 ? " producto nuevo" : " productos nuevos");
        sb.append(", ").append(actualizados).append(actualizados == 1 ? " actualizado" : " actualizados");
        if (omitidos > 0) sb.append(", ").append(omitidos).append(omitidos == 1 ? " omitido (ya existía)" : " omitidos (ya existían)");
        if (!errores.isEmpty()) sb.append(", ").append(errores.size()).append(errores.size() == 1 ? " fila con error" : " filas con error");
        if (departamentosCreados > 0) sb.append(", ").append(departamentosCreados).append(departamentosCreados == 1 ? " departamento nuevo" : " departamentos nuevos");
        return sb.append('.').toString();
    }
}
