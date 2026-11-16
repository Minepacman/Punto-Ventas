package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import me.eliassanchezfernandez.puntodeventa.ocr.MotorOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.Renglon;
import me.eliassanchezfernandez.puntodeventa.ocr.ResultadoOcr;
import me.eliassanchezfernandez.puntodeventa.service.BaseServicioTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * De la foto al ticket con un motor OCR falso que "lee" el texto de la muestra con la posición
 * vertical de cada renglón (como lo daría el motor real tras enderezar la foto).
 */
@Import(LeerFotoConMotorFalsoTest.Config.class)
class LeerFotoConMotorFalsoTest extends BaseServicioTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MotorOcr motorFalso() {
            return new MotorOcr() {
                @Override public ResultadoOcr leer(byte[] imagen) throws IOException {
                    String texto = MuestrasTickets.recurso(new String(imagen) + ".txt");
                    List<Renglon> rs = new ArrayList<>();
                    String[] ls = texto.split("\n");
                    for (int i = 0; i < ls.length; i++) rs.add(new Renglon(ls[i], 0.9, 40 + i * 22));
                    return new ResultadoOcr(rs, texto);
                }
                @Override public String nombre() { return "Motor de prueba"; }
            };
        }
    }

    @Autowired private TicketProveedorService servicio;

    @Test
    void leeLaFotoYConservaLaPosicionDeCadaRenglon() throws Exception {
        iniciarComoAdmin();
        assertThat(servicio.hayLectorDeFotos()).isTrue();
        TicketProveedorService.Lectura l = servicio.leerFoto("t14".getBytes());
        assertThat(l.origen()).isEqualTo("Motor de prueba");
        assertThat(l.tickets()).hasSize(1);
        TicketLeido t = l.tickets().get(0);
        assertThat(t.getLineas()).hasSize(8);
        assertThat(t.getCuadre().cuadra()).isTrue();
        // La posición de cada artículo sirve para mostrar su recorte de la foto
        assertThat(t.getLineas().get(0).getY()).isGreaterThan(0);
        assertThat(t.getLineas().get(1).getY()).isGreaterThan(t.getLineas().get(0).getY());
    }

    @Test
    void dosTicketsEnLaMismaFotoSalenSeparados() throws Exception {
        iniciarComoAdmin();
        List<TicketLeido> ts = servicio.leerFoto("t13".getBytes()).tickets();
        assertThat(ts).hasSize(2);
        assertThat(ts.get(0).getFolio()).isEqualTo("9544-49822");
        assertThat(ts.get(1).getFolio()).isEqualTo("9544-49823");
    }
}
