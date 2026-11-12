package me.eliassanchezfernandez.puntodeventa.controller.estadisticas;

import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService;
import me.eliassanchezfernandez.puntodeventa.service.EstadisticasService.MadurezDatos;
import me.eliassanchezfernandez.puntodeventa.service.ReabastecimientoService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Textos de ayuda de la pestaña Estadísticas: uno por sección (botón "?") y
 * las páginas del tutorial de la primera vez. Todo en un solo lugar para que
 * al agregar una sección baste con dar de alta su clave aquí.
 */
public final class AyudaEstadisticas {

    private AyudaEstadisticas() { }

    /** Ayuda de una sección: la abre el botón "?" de su tarjeta */
    public record Tema(String clave, String titulo, String texto) { }

    /** Una página del tutorial */
    public record Pagina(String titulo, String texto) { }

    private static final Map<String, Tema> TEMAS = new LinkedHashMap<>();

    private static void tema(String clave, String titulo, String texto) {
        TEMAS.put(clave, new Tema(clave, titulo, texto));
    }

    static {
        tema("nivel", "Nivel y madurez de los datos",
                "Cuenta los días desde la primera venta registrada y en cuántos de ellos hubo ventas.\n\n"
                + "• Entrenamiento (menos de " + EstadisticasService.DIAS_ENTRENAMIENTO + " días): el modelo apenas aprende; "
                + "las recomendaciones salen de reglas generales de abarrotes.\n"
                + "• Datos parciales (" + EstadisticasService.DIAS_ENTRENAMIENTO + " a " + (EstadisticasService.DIAS_CONFIABLES - 1)
                + " días): ya hay tendencias básicas; las recomendaciones mejoran cada día.\n"
                + "• Datos suficientes (" + EstadisticasService.DIAS_CONFIABLES + " días o más): el historial cubre un mes completo "
                + "y las estadísticas y recomendaciones son confiables.\n\n"
                + "La confianza máxima de las recomendaciones sube día con día: 35 % el primer día y 100 % a partir de "
                + EstadisticasService.DIAS_CONFIABLES + " días. Mientras tanto, tómalas como una guía, no como una regla.");

        tema("resumen", "Resumen del periodo",
                "Venta total, número de tickets, ticket promedio (total ÷ tickets) y piezas vendidas en el periodo "
                + "elegido arriba (Hoy, Semana, Mes, Año o uno personalizado). Las ventas canceladas no cuentan.\n\n"
                + "Los datos de días pasados salen de los agregados que el programa calcula cada noche; el día de hoy "
                + "se calcula al momento.");

        tema("comparativos", "Comparativos",
                "Comparan siempre a la fecha de hoy: hoy contra el mismo día de la semana pasada; semana, mes y año "
                + "«a la fecha» contra el mismo tramo del periodo anterior (un martes se compara con el lunes y martes "
                + "de la semana pasada, no con la semana completa).\n\n"
                + "La variación es (actual − anterior) ÷ anterior. Si el periodo anterior no tuvo ventas aparece "
                + "«Sin referencia».");

        tema("serie", "Ventas por día y por día de la semana",
                "La gráfica de línea muestra el total vendido cada día del periodo (por mes si el periodo es mayor a dos "
                + "meses). La de barras suma las ventas por día de la semana para ver qué días son más fuertes.");

        tema("horarios", "Horarios pico",
                "Las ventas del periodo se reparten en franjas: mañana (6–11), mediodía (12–14), tarde (15–18), noche "
                + "(19–23) y madrugada (0–5, solo si hubo ventas). El mapa de calor cruza día de la semana × franja: "
                + "más intenso = más ventas. Sirve para decidir horarios de personal y surtido.");

        tema("productos", "Productos más vendidos",
                "Ranking de productos del periodo elegido, por importe (lo que dejan en dinero) o por piezas (lo que "
                + "más se mueve). «% del total» es la parte que representa cada producto del total del periodo según "
                + "el criterio elegido. Se muestran los primeros 25; con «Exportar…» salen a CSV, Excel o PDF.");

        tema("cajeros", "Cajas, cajeros y horas",
                "Total, número de ventas y ticket promedio por cajero y por hora del día en el periodo. Si hay varias "
                + "cajas contra un servidor, también por caja, y el filtro de caja aplica a estas tablas.");

        tema("rezagados", "Productos rezagados (inventario inmovilizado)",
                "Productos que tienen existencia pero no se venden:\n\n"
                + "• Sin venta en N días (N se elige en la tarjeta; por defecto " + ReabastecimientoService.DIAS_SIN_VENTA + "). "
                + "Un producto que nunca se ha vendido cuenta los días desde la primera venta de la tienda.\n"
                + "• Rotación baja: la existencia alcanza para más de " + ReabastecimientoService.COBERTURA_EXCESIVA_DIAS
                + " días al ritmo de venta de los últimos " + ReabastecimientoService.VENTANA_DIAS + " días.\n\n"
                + "«Valor» es existencia × precio de venta (y × costo si puedes ver costos): es dinero detenido en el "
                + "anaquel. Conviene promocionarlos, bajarles el precio o dejar de pedirlos.");

        tema("surtido", "Frecuencia de surtido y pedido sugerido",
                "Para cada producto con inventario:\n\n"
                + "• Venta diaria = piezas vendidas en los últimos " + ReabastecimientoService.VENTANA_DIAS
                + " días ÷ días (en las primeras semanas se divide entre los días que realmente hay).\n"
                + "• Cobertura = existencia ÷ venta diaria: para cuántos días alcanza lo que hay.\n"
                + "• Objetivo = lo que conviene tener después de surtir: el mayor entre el doble del mínimo y "
                + "«mínimo + venta diaria × días hasta el próximo surtido».\n"
                + "• Surtir cada N días = cada cuánto se consume lo que hay entre el objetivo y el mínimo.\n"
                + "• Pedido = objetivo − existencia, solo si el producto está agotado, bajo el mínimo o se acaba "
                + "antes del próximo surtido.\n\n"
                + "Los productos se agrupan por el proveedor que los surtió por última vez (entradas de inventario). "
                + "Si el proveedor tiene días de visita, «próximo surtido» es el intervalo entre visitas; si no, "
                + ReabastecimientoService.HORIZONTE_DIAS + " días. Sin proveedor conocido se agrupan por departamento.\n\n"
                + "«Crear orden de compra…» guarda un borrador con lo que hay que pedir (se revisa y envía desde "
                + "Inventario → Órdenes de compra; requiere el permiso de proveedores).");

        tema("juntos", "Se compran juntos",
                "Pares de productos que aparecen en el mismo ticket con frecuencia (últimas 4 semanas). «Confianza» es "
                + "qué tan seguido el segundo acompaña al primero; «lift» cuántas veces más de lo esperado por azar "
                + "(1.0 = nada especial, 2.0 = el doble). Sirven para acomodar el anaquel y para las sugerencias en Ventas.");

        tema("momento", "Para este momento",
                "Lo que el motor sugeriría ahora mismo con el ticket vacío, según el día de la semana, la franja horaria "
                + "y el clima. Es lo mismo que ve el cajero en la tira de sugerencias de Ventas. La confianza (Alta, "
                + "Media, Baja) está acotada por la madurez de los datos.");

        tema("clima", "Según el clima y correlación producto × clima",
                "Con el clima registrado cada hora (Configuración → Ubicación y clima) el programa aprende qué productos "
                + "pesan más de lo normal con cada condición o banda de temperatura. Índice 1.0 = igual que siempre; "
                + "2.0 = el producto pesa el doble en las ventas con ese clima. Solo se consultan las coordenadas del "
                + "negocio; ningún dato de ventas sale del equipo.");

        tema("clima-ventas", "Ventas por condición climática",
                "Ventas del periodo agrupadas por la condición del clima de su hora (despejado, lluvia, nublado…). "
                + "Las ventas sin clima registrado aparecen como «Sin datos de clima».");

        tema("recalcular", "Agregados y botón Recalcular",
                "Las estadísticas no recorren todas las ventas cada vez: cada noche a las 3:00 (y al arrancar, si hace "
                + "falta) el programa guarda resúmenes por día y por producto. «Recalcular» rehace todo desde la primera "
                + "venta; úsalo si restauraste un respaldo o algo no cuadra. Tarda según el tamaño del historial.");
    }

    /** Ayuda de la clave dada, o un tema genérico si no existe */
    public static Tema de(String clave) {
        Tema t = TEMAS.get(clave);
        return t != null ? t : new Tema(clave, "Estadísticas", "Sin ayuda para esta sección todavía.");
    }

    public static List<Tema> temas() {
        return List.copyOf(TEMAS.values());
    }

    /** Páginas del tutorial de la primera vez (con la madurez actual en la primera página) */
    public static List<Pagina> paginasTutorial(MadurezDatos madurez) {
        List<Pagina> p = new ArrayList<>();
        String estado = madurez == null || !madurez.hayVentas()
                ? "Hoy todavía no hay ventas registradas."
                : "Hoy hay datos de " + madurez.diasDesdePrimeraVenta() + " día(s) (" + madurez.diasConVentas()
                  + " con ventas); confianza de las recomendaciones: " + madurez.porcentajeConfianza() + " %.";
        p.add(new Pagina("Bienvenido a Estadísticas",
                "Esta pestaña te muestra cómo va la tienda y te ayuda a decidir qué surtir, cuándo y a quién pedírselo.\n\n"
                + "IMPORTANTE: esta pestaña mejora con el tiempo. El modelo se va «entrenando» con tus ventas: las "
                + "primeras semanas los datos y las recomendaciones pueden no ser realistas. Con alrededor de un mes de "
                + "ventas (" + EstadisticasService.DIAS_CONFIABLES + " días) ya da estadísticas más realistas y "
                + "recomendaciones confiables.\n\n" + estado + "\n\n"
                + "Arriba siempre verás el indicador «Nivel de datos» con cuántos días de ventas hay. Puedes pasar las "
                + "páginas con Enter o las flechas ← →, y cerrar con Esc."));
        p.add(new Pagina("Resumen",
                "Venta total, tickets, ticket promedio y piezas del periodo que elijas arriba (Hoy, Semana, Mes, Año o "
                + "personalizado). El periodo aplica a todas las pestañas.\n\n"
                + "Los comparativos te dicen si vas mejor o peor que el mismo tramo del periodo anterior (hoy contra "
                + "el mismo día de la semana pasada; la semana, el mes y el año «a la fecha»). Las gráficas muestran "
                + "las ventas por día y por día de la semana."));
        p.add(new Pagina("Horarios pico",
                "Las ventas se reparten en franjas (mañana, mediodía, tarde, noche) y en un mapa de calor de día de la "
                + "semana × franja: más intenso = más ventas.\n\n"
                + "Úsalo para saber a qué horas necesitas más gente en caja y cuándo conviene recibir mercancía."));
        p.add(new Pagina("Productos más vendidos",
                "El ranking del periodo, por importe (lo que más dinero deja) o por piezas (lo que más se mueve), con el "
                + "porcentaje que representa cada producto. Se puede exportar a CSV, Excel o PDF."));
        p.add(new Pagina("Reabastecimiento",
                "Aquí está lo más útil para surtir:\n\n"
                + "• Productos rezagados: tienen existencia pero no se venden (sin venta en N días o con existencia para "
                + "más de " + ReabastecimientoService.COBERTURA_EXCESIVA_DIAS + " días). Es dinero detenido: promociónalos o deja de pedirlos.\n\n"
                + "• Frecuencia de surtido y pedido sugerido: con la venta diaria promedio, la existencia y el mínimo de "
                + "cada producto se calcula para cuántos días alcanza, cada cuántos días conviene surtirlo y cuánto pedir. "
                + "Todo agrupado por proveedor (el que lo surtió por última vez), con un botón para crear la orden de "
                + "compra en borrador.\n\n"
                + "Mientras haya pocas semanas de ventas, la venta diaria se calcula con los días que realmente hay, así "
                + "que las cantidades sugeridas pueden quedar cortas o largas: revísalas antes de pedir."));
        p.add(new Pagina("Recomendaciones y clima",
                "«Se compran juntos» muestra pares de productos que suelen ir en el mismo ticket; «Para este momento» lo "
                + "que el motor sugeriría ahora (lo mismo que ve el cajero en Ventas); «Según el clima» los productos que "
                + "se venden más con el clima de ahora.\n\n"
                + "Si activas el clima en Configuración → Ubicación y clima, el programa aprende qué productos pesan más "
                + "con lluvia, calor o frío. Solo se envían las coordenadas del negocio; ningún dato de ventas sale del equipo."));
        p.add(new Pagina("Cajeros, exportar y ayuda",
                "«Cajeros y horas» muestra el total y el ticket promedio por cajero y por hora (y por caja si hay varias).\n\n"
                + "Cada tarjeta tiene un botón «?» que explica esa métrica, y el botón «? Ayuda» de arriba vuelve a abrir "
                + "este tutorial cuando quieras. «Recalcular» rehace los agregados desde la primera venta (útil después "
                + "de restaurar un respaldo).\n\n"
                + "Esta pestaña se irá enriqueciendo con nuevas secciones; cuando aparezcan, su «?» te dirá qué muestran."));
        return p;
    }
}
