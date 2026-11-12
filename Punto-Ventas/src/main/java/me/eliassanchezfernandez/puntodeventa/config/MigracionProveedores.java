package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
import me.eliassanchezfernandez.puntodeventa.util.VersionApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Al arrancar, convierte los proveedores que se escribían a mano en las entradas
 * de inventario en filas del catálogo de proveedores (ver
 * {@link ProveedorService#migrarDesdeTextosDeEntradas()}). Es idempotente y un
 * fallo aquí nunca impide abrir el programa. {@code app.proveedores.migrar=false} lo apaga.
 *
 * Corre en el hilo diferido ({@link ArranqueDiferido}), con la ventana ya
 * dibujada, y solo si la base no está ya al día con esta versión
 * ({@link VersionEsquema}): recorre toda la tabla de movimientos (un SELECT
 * DISTINCT y un UPDATE por proveedor), que en un disco duro cuesta segundos.
 */
@Component
public class MigracionProveedores {

    @Autowired private ProveedorService proveedorService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private ArranqueDiferido arranqueDiferido;

    @Value("${app.proveedores.migrar:true}")
    private boolean migrar;

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!migrar) return;
        arranqueDiferido.ejecutar("proveedores", this::migrarAhora);
    }

    /** La migración en sí (también desde las pruebas) */
    public void migrarAhora() {
        try {
            if (VersionEsquema.puedeOmitir()
                    && VersionApp.version().equals(configuracionService.obtener().getVersionEsquema())) {
                return;      // ya se hizo con esta misma versión
            }
            int creados = proveedorService.migrarDesdeTextosDeEntradas();
            if (creados > 0) System.out.println("ℹ️  Proveedores: " + creados + " creado(s) desde las entradas de inventario.");
        } catch (RuntimeException e) {
            System.err.println("No se pudieron migrar los proveedores de entradas anteriores: " + e.getMessage());
        }
    }
}
