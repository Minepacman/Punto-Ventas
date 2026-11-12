package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.Periodo;

/**
 * Lo que la pantalla le pasa a cada sección al cargarla: el periodo y la caja
 * elegidos arriba y la madurez de los datos (para acotar la confianza).
 *
 * @param caja    caja elegida en el filtro (null = todas)
 * @param madurez madurez de los datos al momento de la carga (puede ser null si no se pudo leer)
 */
public record ContextoEstadisticas(Periodo periodo, Integer caja, MadurezDatos madurez) { }
