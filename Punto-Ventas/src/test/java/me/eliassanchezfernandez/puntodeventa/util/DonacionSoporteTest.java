package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Contacto de soporte del desarrollador (WhatsApp / correo) en donacion.properties. */
class DonacionSoporteTest {

    @Test
    void mientrasEstePendienteNoHaySoporteConfigurado() {
        Donacion.Datos d = Donacion.cargar();
        // El recurso del repositorio trae PENDIENTE hasta que el dueño ponga sus datos
        assertThat(d.soporteWhatsapp()).isNotBlank();
        assertThat(d.soporteCorreo()).isNotBlank();
        assertThat(Donacion.whatsappValido("PENDIENTE")).isFalse();
        assertThat(Donacion.correoValido("PENDIENTE")).isFalse();
        assertThat(Donacion.desde(new Properties()).soporteConfigurado()).isFalse();
    }

    @Test
    void conDatosRealesSeArmanLosEnlaces() {
        Properties p = new Properties();
        p.setProperty("soporte.whatsapp", "55 1234 5678");
        p.setProperty("soporte.correo", "soporte@ejemplo.mx");
        Donacion.Datos d = Donacion.desde(p);
        assertThat(d.whatsappConfigurado()).isTrue();
        assertThat(d.correoConfigurado()).isTrue();
        assertThat(d.soporteConfigurado()).isTrue();

        assertThat(Donacion.whatsappUrl(d.soporteWhatsapp(), null)).isEqualTo("https://wa.me/525512345678");
        assertThat(Donacion.whatsappUrl("+52 1 55 1234 5678", "Hola, ¿qué tal?"))
                .isEqualTo("https://wa.me/5215512345678?text=Hola%2C%20%C2%BFqu%C3%A9%20tal%3F");
        assertThat(Donacion.correoUrl(d.soporteCorreo(), "Soporte PDV")).isEqualTo("mailto:soporte@ejemplo.mx?subject=Soporte%20PDV");
    }

    @Test
    void validaNumerosYCorreosRazonables() {
        assertThat(Donacion.whatsappValido("5512345678")).isTrue();
        assertThat(Donacion.whatsappValido("525512345678")).isTrue();
        assertThat(Donacion.whatsappValido("12345")).isFalse();
        assertThat(Donacion.whatsappValido("")).isFalse();
        assertThat(Donacion.whatsappValido(null)).isFalse();
        assertThat(Donacion.correoValido("a@b.co")).isTrue();
        assertThat(Donacion.correoValido("sin-arroba")).isFalse();
        assertThat(Donacion.correoValido(null)).isFalse();
    }

    @Test
    void elConstructorCortoSigueFuncionando() {
        Donacion.Datos d = new Donacion.Datos("000000000000000000", "Banco", "Alguien");
        assertThat(d.soporteConfigurado()).isFalse();
        assertThat(d.soporteWhatsapp()).isEqualTo(Donacion.PENDIENTE);
    }

    @Test
    void elCorreoPuedeLlevarCuerpoCodificado() {
        assertThat(Donacion.correoUrl("s@e.mx", "Soporte", "Hola\nlínea 2"))
                .isEqualTo("mailto:s@e.mx?subject=Soporte&body=Hola%0Al%C3%ADnea%202");
        assertThat(Donacion.correoUrl("s@e.mx", null, "x")).isEqualTo("mailto:s@e.mx?body=x");
        assertThat(Donacion.correoUrl("s@e.mx", "", "")).isEqualTo("mailto:s@e.mx");
    }
}
