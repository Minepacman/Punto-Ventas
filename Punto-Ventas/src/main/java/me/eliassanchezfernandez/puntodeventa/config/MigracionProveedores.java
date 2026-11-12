package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.service.ProveedorService;
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
 */
@Component
public class MigracionProveedores {

    @Autowired private ProveedorService proveedorService;

    @Value("${app.proveedores.migrar:true}")
    private boolean migrar;

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!migrar) return;
        try {
            int creados = proveedorService.migrarDesdeTextosDeEntradas();
            if (creados > 0) System.out.println("ℹ️  Proveedores: " + creados + " creado(s) desde las entradas de inventario.");
        } catch (RuntimeException e) {
            System.err.println("No se pudieron migrar los proveedores de entradas anteriores: " + e.getMessage());
        }
    }
}
