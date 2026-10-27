package me.eliassanchezfernandez.puntodeventa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Tareas programadas (agregados de Estadísticas a las 03:00 y clima cada
 * hora) y el reloj único de la aplicación. Los servicios reciben el Clock
 * en lugar de llamar a LocalDate.now() para que las pruebas puedan fijar
 * "hoy" sin esperar.
 *
 * app.estadisticas.jobs=false (pruebas) desactiva los trabajos en segundo
 * plano: los métodos siguen disponibles para llamarlos a mano.
 */
@Configuration
@EnableScheduling
public class ProgramacionConfig {

    @Bean
    public Clock reloj() {
        return Clock.systemDefaultZone();
    }
}
