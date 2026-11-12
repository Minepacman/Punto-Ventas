package me.eliassanchezfernandez.puntodeventa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regla absoluta del programa de apoyo (software libre): la clave de donador
 * es una constancia, no un candado. Ningún servicio de negocio —ventas, corte,
 * inventario, impresión, respaldos, facturación, servidor móvil…— puede
 * depender de DonadorService. Solo la bienvenida (ventana de donación), el
 * buscador de actualizaciones (betas) y las pantallas lo consultan. Si alguien
 * inyecta DonadorService en otro servicio, esta prueba falla.
 */
@SpringBootTest
class ApoyoNoBloqueaTest {

    /** Los únicos servicios que pueden saber si el negocio es donador */
    private static final Set<String> PERMITIDOS = Set.of("BienvenidaService", "ActualizacionService", "DonadorService");

    @Autowired private ApplicationContext contexto;

    @Test
    void ningunServicioDeNegocioInyectaDonadorService() {
        List<String> infractores = new ArrayList<>();
        for (String nombre : contexto.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = contexto.getBean(nombre);
            } catch (RuntimeException e) {
                continue;
            }
            Class<?> tipo = org.springframework.aop.support.AopUtils.getTargetClass(bean);
            if (!tipo.getName().startsWith("me.eliassanchezfernandez.puntodeventa.service")
                    && !tipo.getName().startsWith("me.eliassanchezfernandez.puntodeventa.servidor")
                    && !tipo.getName().startsWith("me.eliassanchezfernandez.puntodeventa.facturacion")
                    && !tipo.getName().startsWith("me.eliassanchezfernandez.puntodeventa.impresion")) continue;
            if (PERMITIDOS.contains(tipo.getSimpleName())) continue;
            for (Class<?> c = tipo; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (DonadorService.class.isAssignableFrom(f.getType())) infractores.add(tipo.getSimpleName() + "." + f.getName());
                }
            }
        }
        assertThat(infractores).as("servicios de negocio que consultan la clave de donador").isEmpty();
    }
}
