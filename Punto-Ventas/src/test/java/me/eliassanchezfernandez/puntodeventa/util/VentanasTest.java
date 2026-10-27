package me.eliassanchezfernandez.puntodeventa.util;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Las ventanas modales nunca deben ser más grandes que el área visible (1024×768 con barra de tareas). */
class VentanasTest {

    @Test
    void recortaLasVentanasGrandesALaPantallaDe1024x768() {
        Rectangle2D visible = new Rectangle2D(0, 40, 1024, 728);   // 768 menos la barra de tareas

        double[] movimientos = Ventanas.ajustarAPantalla(1050, 600, visible);
        assertThat(movimientos[0]).isLessThanOrEqualTo(1024).isEqualTo(983);
        assertThat(movimientos[1]).isEqualTo(600);

        double[] ventas = Ventanas.ajustarAPantalla(1000, 640, visible);
        assertThat(ventas[0]).isEqualTo(983);
        assertThat(ventas[1]).isEqualTo(640);

        double[] movil = Ventanas.ajustarAPantalla(820, 680, visible);
        assertThat(movil[0]).isEqualTo(820);
        assertThat(movil[1]).isEqualTo(669);      // 92 % de 728
    }

    @Test
    void enPantallasGrandesNoAgrandaNada() {
        double[] r = Ventanas.ajustarAPantalla(560, 460, new Rectangle2D(0, 0, 1920, 1040));
        assertThat(r).containsExactly(560, 460);
    }
}
