package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLABE: largo, dígitos, verificador (pesos 3-7-1), banco real; y la lectura de donacion.properties. */
class DonacionTest {

    @Test
    void aceptaClabesConVerificadorCorrecto() {
        assertThat(Donacion.clabeValida("032180000118359719")).isTrue();
        assertThat(Donacion.clabeValida("002010077777777771")).isTrue();
        assertThat(Donacion.clabeValida("646180110400000007")).isTrue();
        // con espacios o guiones también
        assertThat(Donacion.clabeValida("032 180 00011835971 9")).isTrue();
        assertThat(Donacion.clabeValida("032-180-00011835971-9")).isTrue();
    }

    @Test
    void rechazaVerificadorIncorrectoLargoMaloYLetras() {
        assertThat(Donacion.clabeValida("032180000118359718")).isFalse();   // verificador cambiado
        assertThat(Donacion.clabeValida("03218000011835971")).isFalse();    // 17 dígitos
        assertThat(Donacion.clabeValida("0321800001183597199")).isFalse();  // 19 dígitos
        assertThat(Donacion.clabeValida("03218000011835971X")).isFalse();
        assertThat(Donacion.clabeValida("")).isFalse();
        assertThat(Donacion.clabeValida(null)).isFalse();
    }

    @Test
    void laClabeDeEjemploConBancoCeroNoCuentaComoConfigurada() {
        // Todo ceros pasa el verificador (suma 0), pero el banco 000 no existe
        assertThat(Donacion.digitoVerificador("00000000000000000")).isZero();
        assertThat(Donacion.clabeValida("000000000000000000")).isFalse();
    }

    @Test
    void calculaElDigitoVerificador() {
        assertThat(Donacion.digitoVerificador("03218000011835971")).isEqualTo(9);
        assertThat(Donacion.digitoVerificador("00201007777777777")).isEqualTo(1);
        assertThatThrownBy(() -> Donacion.digitoVerificador("123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Donacion.digitoVerificador("0321800001183597A")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formateaEnBloquesBancoPlazaCuentaVerificador() {
        assertThat(Donacion.formatear("032180000118359719")).isEqualTo("032 180 00011835971 9");
        assertThat(Donacion.formatear("123")).isEqualTo("123");   // sin largo correcto no se toca
    }

    @Test
    void elRecursoDelProyectoCargaYSigueMarcadoComoPendiente() {
        Donacion.Datos d = Donacion.cargar();
        assertThat(d.clabe()).hasSize(18);
        assertThat(d.banco()).isNotBlank();
        assertThat(d.beneficiario()).isNotBlank();
        // Mientras el dueño no entregue su CLABE, la ventana debe decir "CLABE no configurada"
        assertThat(d.clabeConfigurada()).isFalse();
    }

    @Test
    void propiedadesVaciasOFaltantesQuedanComoPendiente() {
        Donacion.Datos vacio = Donacion.desde(new Properties());
        assertThat(vacio.clabe()).isEmpty();
        assertThat(vacio.banco()).isEqualTo("PENDIENTE");
        assertThat(vacio.beneficiario()).isEqualTo("PENDIENTE");
        assertThat(vacio.clabeConfigurada()).isFalse();

        Properties p = new Properties();
        p.setProperty("clabe", "032 180 00011835971 9");
        p.setProperty("banco", " BBVA ");
        p.setProperty("beneficiario", "Elías Sánchez");
        Donacion.Datos real = Donacion.desde(p);
        assertThat(real.clabe()).isEqualTo("032180000118359719");
        assertThat(real.banco()).isEqualTo("BBVA");
        assertThat(real.clabeConfigurada()).isTrue();
    }

    @Test
    void laVentanaSeBloqueaCuarentaSegundos() {
        assertThat(Donacion.SEGUNDOS_BLOQUEO).isEqualTo(40);
    }
}
