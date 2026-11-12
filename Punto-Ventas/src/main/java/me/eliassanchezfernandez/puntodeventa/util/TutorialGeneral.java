package me.eliassanchezfernandez.puntodeventa.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Contenido del recorrido general por el programa (Java puro, sin JavaFX):
 * un paso por pestaña de main.fxml más una bienvenida y un cierre. Los
 * atajos se toman del catálogo {@link Atajo} para no duplicar teclas a mano.
 * La ventana que lo muestra es {@code controller/TutorialGeneralDialogo}.
 */
public final class TutorialGeneral {

    private TutorialGeneral() { }

    /**
     * @param tabId  fx:id de la pestaña de main.fxml que se resalta (null = ninguna)
     * @param atajos atajos que se listan bajo el texto
     */
    public record Paso(String tabId, String titulo, String texto, List<Atajo> atajos) {

        /** "F1 · Ctrl+1 — Ir a Ventas" por renglón (vacío si el paso no tiene atajos) */
        public String textoAtajos(boolean mac) {
            StringBuilder sb = new StringBuilder();
            for (Atajo a : atajos) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(a.etiquetaTeclas(mac)).append("  —  ").append(a.getDescripcion());
            }
            return sb.toString();
        }
    }

    /** Todos los pasos, en orden (sin filtrar por permisos) */
    public static List<Paso> pasos() {
        List<Paso> p = new ArrayList<>();
        p.add(new Paso(null, "Bienvenido al Punto de Venta",
                "Este recorrido corto te muestra qué hay en cada pestaña y cómo moverte rápido con el teclado. "
                + "Dura un minuto y puedes verlo otra vez cuando quieras desde «Tutorial», en la barra de abajo.\n\n"
                + "Avanza con Enter o →, regresa con ← y sal con Esc.",
                List.of()));
        p.add(new Paso("tabVentas", "Ventas",
                "La pantalla de todos los días. Teclea o escanea el código, ajusta cantidades, aplica mayoreo o "
                + "descuentos (si tienes permiso) y cobra en efectivo, tarjeta, transferencia o pago dividido. "
                + "Puedes dejar un ticket en espera para atender a otro cliente, y abajo aparecen sugerencias "
                + "de productos que suelen comprarse juntos.",
                List.of(Atajo.IR_VENTAS, Atajo.BUSCAR_PRODUCTO, Atajo.CANTIDAD, Atajo.PONER_EN_ESPERA,
                        Atajo.RECUPERAR_ESPERA, Atajo.COBRAR)));
        p.add(new Paso("tabClientes", "Clientes",
                "Aquí vivirá el directorio de clientes con crédito y estado de cuenta. En esta versión el "
                + "módulo todavía no está disponible: los botones solo lo avisan.",
                List.of(Atajo.IR_CLIENTES)));
        p.add(new Paso("tabProductos", "Productos",
                "Tu catálogo: códigos de barras, descripción, precio de costo y de venta, departamento, "
                + "unidad, venta a granel y precio de mayoreo. También las promociones (NxM, por departamento, "
                + "por horario o hasta agotar) y los departamentos.",
                List.of(Atajo.IR_PRODUCTOS)));
        p.add(new Paso("tabInventario", "Inventario",
                "Existencias y movimientos: entradas de mercancía, ajustes, productos bajos de stock, "
                + "proveedores y órdenes de compra. Cada venta descuenta existencias automáticamente.",
                List.of(Atajo.IR_INVENTARIO)));
        p.add(new Paso("tabEstadisticas", "Estadísticas",
                "Qué se vende, cuánto y a qué horas: ventas por día, por cajero y por hora, productos más "
                + "vendidos, canasta de compra y recomendaciones. Incluye el clima, si lo activas. "
                + "Al abrirla por primera vez tiene su propio recorrido.",
                List.of(Atajo.IR_ESTADISTICAS)));
        p.add(new Paso("tabCorte", "Corte",
                "El cierre del día de esta caja: fondo inicial, ventas por forma de pago, cancelaciones y "
                + "ganancias. Puedes imprimirlo o exportarlo.",
                List.of()));
        p.add(new Paso("tabConfig", "Configuración",
                "Todo lo demás: usuarios y permisos, datos del negocio y logotipo, ticket e impresora, "
                + "formas de pago, moneda, folios, respaldos, báscula, cajón, acceso desde el celular, "
                + "ubicación y clima, y facturación electrónica.",
                List.of()));
        p.add(new Paso(null, "Barra superior y bloqueo",
                "Arriba ves la hora, la caja y el usuario en sesión. «Bloquear» regresa al inicio de sesión "
                + "sin cerrar el programa (el ticket sin cobrar se conserva): úsalo al dejar la caja o para "
                + "cambiar de cajero. «Cerrar sesión» y «Salir» te preguntan antes.",
                List.of(Atajo.BLOQUEAR)));
        p.add(new Paso(null, "Listo para vender",
                "Eso es todo. En la barra de abajo siempre están los atajos principales; con la tecla de "
                + "ayuda ves la lista completa. Si quieres repetir este recorrido, pulsa «Tutorial» ahí mismo.",
                List.of(Atajo.AYUDA_ATAJOS)));
        return p;
    }

    /**
     * Pasos para un usuario: se quitan los de las pestañas que no puede ver
     * (MainController las retira del TabPane según permisos).
     */
    public static List<Paso> pasosPara(Collection<String> tabsVisibles) {
        Set<String> visibles = Set.copyOf(tabsVisibles);
        return pasos().stream()
                .filter(paso -> paso.tabId() == null || visibles.contains(paso.tabId()))
                .toList();
    }
}
