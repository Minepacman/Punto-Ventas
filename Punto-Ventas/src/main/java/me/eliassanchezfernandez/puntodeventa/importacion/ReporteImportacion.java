package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion.ErrorFila;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Texto plano del reporte (para copiar al portapapeles o guardar como .txt) */
public final class ReporteImportacion {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private ReporteImportacion() { }

    /** Antes de aplicar: qué se haría y qué filas tienen problemas */
    public static String previo(PlanImportacion plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Caja Libre - validación de importación (").append(LocalDateTime.now().format(FECHA)).append(")\n");
        sb.append("Archivo: ").append(plan.tabla().nombreArchivo()).append(" (").append(plan.tabla().detalle()).append(")\n");
        sb.append("Origen: ").append(plan.origen().getNombre()).append('\n');
        sb.append("Se crearán: ").append(plan.aCrear()).append("   Se actualizarán: ").append(plan.aActualizar())
          .append("   Se omitirán (ya existen): ").append(plan.aOmitir()).append("   Con error: ").append(plan.errores().size()).append('\n');
        if (!plan.departamentosNuevos().isEmpty()) {
            sb.append("Departamentos nuevos: ").append(String.join(", ", plan.departamentosNuevos())).append('\n');
        }
        agregarErrores(sb, plan.errores());
        agregarAvisos(sb, plan.filas());
        return sb.toString().trim();
    }

    /** Después de aplicar */
    public static String resultado(PlanImportacion plan, ResumenImportacion resumen) {
        StringBuilder sb = new StringBuilder();
        sb.append("Caja Libre - importación (").append(LocalDateTime.now().format(FECHA)).append(")\n");
        sb.append("Archivo: ").append(plan.tabla().nombreArchivo()).append("   Origen: ").append(plan.origen().getNombre()).append('\n');
        sb.append(resumen.resumenCorto()).append('\n');
        if (resumen.movimientosInventario() > 0) sb.append("Movimientos de inventario registrados: ").append(resumen.movimientosInventario()).append('\n');
        if (resumen.cambiosDePrecio() > 0) sb.append("Cambios de precio registrados: ").append(resumen.cambiosDePrecio()).append('\n');
        sb.append("Tiempo: ").append(resumen.duracionMs() / 1000.0).append(" s\n");
        agregarErrores(sb, resumen.errores());
        agregarAvisos(sb, plan.filas());
        return sb.toString().trim();
    }

    private static void agregarErrores(StringBuilder sb, List<ErrorFila> errores) {
        if (errores.isEmpty()) return;
        sb.append("\nFilas con error (no se importan):\n");
        for (ErrorFila e : errores) sb.append("  ").append(e).append('\n');
    }

    private static void agregarAvisos(StringBuilder sb, List<FilaProducto> filas) {
        boolean hay = false;
        for (FilaProducto f : filas) {
            if (f.avisos.isEmpty()) continue;
            if (!hay) {
                sb.append("\nAvisos (la fila sí se importa):\n");
                hay = true;
            }
            sb.append("  Fila ").append(f.numeroFila).append(" (").append(f.codigo).append("): ")
              .append(String.join(" ", f.avisos)).append('\n');
        }
    }
}
