package me.eliassanchezfernandez.puntodeventa.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;

/**
 * Crea los repositorios de Spring Data DESPUÉS de mostrar el login, en el hilo diferido del arranque.
 *
 * <p>Con Spring Boot 3.5 crear cada repositorio analiza sus consultas {@code @Query} con el analizador de
 * HQL de Spring Data y el de Hibernate (ANTLR): con 39 repositorios eran ~2-3 s de CPU con dos núcleos,
 * todos ANTES de que apareciera el login (modo {@code deferred}, ver rendimiento-1.0.5.md). Ahora
 * {@code application.properties} usa {@code bootstrap-mode=lazy} (cada repositorio se crea al primer uso)
 * y esta tarea los crea todos mientras el cajero escribe su contraseña, así la ventana principal no los
 * paga en el hilo de JavaFX. Si alguien entra antes de que termine, el que falte se crea al usarse
 * (Spring serializa la creación: nunca dos veces el mismo).</p>
 *
 * <p>Va primero en la cola de {@link ArranqueDiferido}; con {@code app.arranque.diferido=false} (pruebas,
 * calentador del archivo CDS) corre en el acto, como el modo {@code deferred} de antes. Con cualquier otro
 * {@code bootstrap-mode} los repositorios ya existen y esto no cuesta nada.</p>
 */
@Component
public class PrecalentarRepositorios {

    @Autowired private ArranqueDiferido arranqueDiferido;
    @Autowired private ListableBeanFactory beanFactory;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void alArrancar() {
        arranqueDiferido.ejecutar("repositorios", this::precalentar);
    }

    /** Crea (si faltan) todos los repositorios; devuelve cuántos hay */
    int precalentar() {
        return beanFactory.getBeansOfType(Repository.class).size();
    }
}
