package me.eliassanchezfernandez.puntodeventa.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Con aunque sea un producto en el catálogo, los artículos precargados no se ofrecen. */
class ArticulosPrecargadosConProductosTest extends BaseServicioTest {

    @Autowired private ArticulosPrecargadosService articulosService;

    @Test
    void conUnProductoYaNoSeOfreceNiSeCarga() throws Exception {
        iniciarComoAdmin();
        producto("Ya existe", 10, 5);

        assertThat(articulosService.puedeCargar()).isFalse();
        long antes = productoRepo.count();
        assertThatThrownBy(() -> articulosService.cargar()).isInstanceOf(NegocioException.class);
        assertThat(productoRepo.count()).isEqualTo(antes);
    }
}
