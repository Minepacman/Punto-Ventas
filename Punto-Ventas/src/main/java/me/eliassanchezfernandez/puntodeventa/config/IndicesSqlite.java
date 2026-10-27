package me.eliassanchezfernandez.puntodeventa.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Índices sobre las tablas que ya existían en datos.db antes de Estadísticas
 * (ventas y detalle_ventas). Hibernate crea los índices de las tablas nuevas
 * con la tabla; en las existentes se agregan aquí con CREATE INDEX IF NOT
 * EXISTS, que es idempotente y seguro con la aplicación recién arrancada.
 * Corre después de ddl-auto=update (ContextRefreshedEvent), igual que las
 * siembras de SesionService y UnidadService.
 *
 * El nombre es histórico: la misma sentencia vale en PostgreSQL, así que los
 * índices se crean en los dos motores (en el servidor también hacen falta).
 */
@Component
public class IndicesSqlite {

    public static final List<String> INDICES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_ventas_fecha ON ventas (fecha)",
            "CREATE INDEX IF NOT EXISTS idx_ventas_fecha_cancelacion ON ventas (fecha_cancelacion)",
            "CREATE INDEX IF NOT EXISTS idx_detalle_ventas_venta ON detalle_ventas (venta_id)",
            "CREATE INDEX IF NOT EXISTS idx_detalle_ventas_producto ON detalle_ventas (producto_id)",
            // SQLite no indexa las claves foráneas por sí solo: estas consultas (pagos de un ticket,
            // bitácoras por producto, historial reciente, tickets de un cajero) recorrerían la tabla entera
            "CREATE INDEX IF NOT EXISTS idx_pagos_venta_venta ON pagos_venta (venta_id)",
            "CREATE INDEX IF NOT EXISTS idx_movimientos_producto_fecha ON movimientos_inventario (producto_id, fecha)",
            "CREATE INDEX IF NOT EXISTS idx_movimientos_fecha ON movimientos_inventario (fecha)",
            "CREATE INDEX IF NOT EXISTS idx_historial_precios_producto_fecha ON historial_precios (producto_id, fecha)",
            "CREATE INDEX IF NOT EXISTS idx_auditoria_fecha ON auditoria (fecha)",
            "CREATE INDEX IF NOT EXISTS idx_auditoria_accion_fecha ON auditoria (accion, fecha)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_pendientes_cajero ON tickets_pendientes (cajero_id)",
            "CREATE INDEX IF NOT EXISTS idx_tickets_pendientes_lineas_ticket ON tickets_pendientes_lineas (ticket_id)",
            "CREATE INDEX IF NOT EXISTS idx_productos_activo_descripcion ON productos (activo, descripcion)",
            "CREATE INDEX IF NOT EXISTS idx_ventas_cajero_fecha ON ventas (cajero_id, fecha)",
            // Varias cajas: el corte y el fondo se consultan por caja y día
            "CREATE INDEX IF NOT EXISTS idx_ventas_caja_fecha ON ventas (caja, fecha)",
            "CREATE INDEX IF NOT EXISTS idx_cortes_fecha ON cortes (fecha)",
            "CREATE INDEX IF NOT EXISTS idx_fondos_caja_fecha ON fondos_caja (fecha)");

    @Autowired private DataSource dataSource;

    @EventListener(ContextRefreshedEvent.class)
    public void crearIndices() {
        try {
            crear(dataSource);
        } catch (SQLException e) {
            // Un índice que no se pudo crear solo afecta la velocidad, nunca los datos
            System.err.println("No se pudieron crear los índices de ventas: " + e.getMessage());
        }
    }

    /** Punto de entrada reutilizable (también desde las pruebas) */
    public static void crear(DataSource dataSource) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            crear(c);
        }
    }

    /** Igual, sobre una conexión abierta (la migración al servidor los crea en la base nueva) */
    public static void crear(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            SQLException primero = null;
            for (String sql : INDICES) {
                try {
                    st.execute(sql);
                } catch (SQLException e) {
                    // Un índice que falle (p. ej. una tabla aún sin esa columna) no debe impedir los demás
                    if (primero == null) primero = e;
                }
            }
            if (primero != null) throw primero;
        }
    }
}
