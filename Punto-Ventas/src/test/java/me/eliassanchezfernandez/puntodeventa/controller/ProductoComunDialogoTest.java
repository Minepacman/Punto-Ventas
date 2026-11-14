package me.eliassanchezfernandez.puntodeventa.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Validación de la ventana "Producto común" (Java puro, sin toolkit): precio y cantidad a la mexicana. */
class ProductoComunDialogoTest {

    @Test
    void precioYCantidadValidosPasan() {
        assertThat(ProductoComunDialogo.validar("25", "1")).isNull();
        assertThat(ProductoComunDialogo.validar("12,50", "1.5")).isNull();     // coma decimal
        assertThat(ProductoComunDialogo.validar("$1,500", "2")).isNull();      // miles con coma y signo de pesos
        assertThat(ProductoComunDialogo.validar(" 0.5 ", "0.250")).isNull();
    }

    @Test
    void precioVacioCeroONegativoSeRechaza() {
        assertThat(ProductoComunDialogo.validar("", "1")).contains("precio");
        assertThat(ProductoComunDialogo.validar("0", "1")).contains("precio");
        assertThat(ProductoComunDialogo.validar("-3", "1")).contains("precio");
        assertThat(ProductoComunDialogo.validar("abc", "1")).contains("precio");
        assertThat(ProductoComunDialogo.validar("2000000", "1")).contains("demasiado grande");
    }

    @Test
    void cantidadVaciaCeroONegativaSeRechaza() {
        assertThat(ProductoComunDialogo.validar("10", "")).contains("cantidad");
        assertThat(ProductoComunDialogo.validar("10", "0")).contains("cantidad");
        assertThat(ProductoComunDialogo.validar("10", "-1")).contains("cantidad");
        assertThat(ProductoComunDialogo.validar("10", "x")).contains("cantidad");
        assertThat(ProductoComunDialogo.validar("10", "200000")).contains("demasiado grande");
    }
}
