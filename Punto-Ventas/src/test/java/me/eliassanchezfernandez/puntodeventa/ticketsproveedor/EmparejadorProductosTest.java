package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parecido entre la descripción abreviada del ticket y la del catálogo de la tienda */
class EmparejadorProductosTest {

    private static final List<EmparejadorProductos.ProductoRef> CATALOGO = List.of(
            ref(1, "Yoghurt Danone fresa 220 g"),
            ref(2, "Yoghurt Danone fresa 1 kg"),
            ref(3, "Yoghurt Danone manzana 220 g"),
            ref(4, "Activia ciruela pasa 225 g"),
            ref(5, "Mazapán de chocolate gigante"),
            ref(6, "Chicle Orbit surtido"),
            ref(7, "Galletas Marías doradas 199 g"),
            ref(8, "Galletas Marías azucaradas 144 g"),
            ref(9, "Takis Fuego 70 g"),
            ref(10, "Coca Cola 600 ml"));

    private static EmparejadorProductos.ProductoRef ref(long id, String d) {
        return new EmparejadorProductos.ProductoRef(id, d, "C" + id, null);
    }

    private static long primero(String ticket) {
        List<EmparejadorProductos.Candidato> c = EmparejadorProductos.candidatos(ticket, CATALOGO, 3);
        return c.isEmpty() ? -1 : c.get(0).producto().id();
    }

    @Test
    void laMedidaMandaSobreLasPalabras() {
        assertThat(primero("DANONE FRESA 220G DS ASS")).isEqualTo(1);
        assertThat(primero("DANONE MANZANA 220G DS EVO")).isEqualTo(3);
        List<EmparejadorProductos.Candidato> c = EmparejadorProductos.candidatos("DANONE FRESA 220G DS ASS", CATALOGO, 3);
        double kilo = c.stream().filter(x -> x.producto().id() == 2).mapToDouble(EmparejadorProductos.Candidato::puntaje).findFirst().orElse(0);
        assertThat(c.get(0).puntaje() - kilo).isGreaterThan(0.3);
    }

    @Test
    void entiendeAbreviaturasYPlurales() {
        assertThat(primero("MZ CHOCO GT")).isEqualTo(5);
        assertThat(primero("ORBIT SURTID")).isEqualTo(6);
        assertThat(primero("MARIAS DORADAS 199GRX15X1")).isEqualTo(7);
        assertThat(primero("MARIAS AZU RG2 144GRX22X1")).isEqualTo(8);
        assertThat(primero("ACTIVIA CIRUELA PASA 225G")).isEqualTo(4);
        assertThat(EmparejadorProductos.candidatos("ACTIVIA CIRUELA PASA 225G", CATALOGO, 1).get(0).puntaje())
                .isGreaterThanOrEqualTo(EmparejadorProductos.CLARO);
    }

    @Test
    void loQueNoSeAsemejaNoSePropone() {
        List<EmparejadorProductos.Candidato> c = EmparejadorProductos.candidatos("PEDIGREE RP PURPOSE RES 24/100G", CATALOGO, 3);
        assertThat(c.isEmpty() || c.get(0).puntaje() < EmparejadorProductos.DUDOSO).isTrue();
    }
}
