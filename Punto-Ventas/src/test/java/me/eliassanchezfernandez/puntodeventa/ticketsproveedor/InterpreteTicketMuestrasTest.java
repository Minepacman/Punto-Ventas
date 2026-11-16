package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El intérprete contra las 16 fotos reales de tickets de proveedor (texto transcrito a mano, anonimizado).
 * Con el texto limpio debe leer el 100 % de los campos; con ruido típico del OCR se mide cuánto aguanta.
 */
class InterpreteTicketMuestrasTest {

    @Test
    void leeTodosLosCamposDeLasDieciseisMuestras() throws Exception {
        List<String> reporte = new ArrayList<>();
        int campos = 0, aciertos = 0;
        List<String> fallas = new ArrayList<>();
        for (MuestrasTickets.Muestra m : MuestrasTickets.todas()) {
            List<TicketLeido> leidos = InterpreteTicket.interpretar(m.texto());
            MuestrasTickets.Marcador r = MuestrasTickets.comparar(leidos, m.esperado(), false);
            campos += r.campos;
            aciertos += r.aciertos;
            reporte.add(String.format("%s  %s  (%d/%d campos)  cuadre: %s", m.nombre(), MuestrasTickets.porcentaje(r.precision()),
                    r.aciertos, r.campos, leidos.isEmpty() || leidos.get(0).getCuadre() == null ? "-" : leidos.get(0).getCuadre().mensaje()));
            for (String d : r.diferencias) fallas.add(m.nombre() + " " + d);
        }
        reporte.add(String.format("TOTAL  %s  (%d/%d campos)", MuestrasTickets.porcentaje((double) aciertos / campos), aciertos, campos));
        System.out.println("── Precisión del intérprete con el texto limpio ──");
        reporte.forEach(System.out::println);
        fallas.forEach(System.out::println);
        assertThat(fallas).isEmpty();
    }

    @Test
    void todasLasMuestrasCuadranConSuTotal() throws Exception {
        for (MuestrasTickets.Muestra m : MuestrasTickets.todas()) {
            for (TicketLeido t : InterpreteTicket.interpretar(m.texto())) {
                assertThat(t.getCuadre().cuadra()).as(m.nombre() + ": " + t.getCuadre().mensaje()).isTrue();
                for (LineaTicket l : t.getLineas()) {
                    assertThat(l.getEstado()).as(m.nombre() + " " + l).isEqualTo(LineaTicket.Estado.RECONOCIDO);
                }
            }
        }
    }

    @Test
    void aguantaElRuidoTipicoDelOcr() throws Exception {
        System.out.println("── Precisión con ruido de OCR inyectado (5 semillas por muestra) ──");
        double[] intensidades = { 0.5, 1.0, 2.0 };
        for (double intensidad : intensidades) {
            int campos = 0, aciertos = 0, cuadran = 0, tickets = 0;
            List<String> filas = new ArrayList<>();
            for (MuestrasTickets.Muestra m : MuestrasTickets.todas()) {
                int c = 0, a = 0;
                for (long semilla = 1; semilla <= 5; semilla++) {
                    String ruidoso = MuestrasTickets.conRuido(m.texto(), semilla * 7919 + m.nombre().hashCode(), intensidad);
                    List<TicketLeido> leidos = InterpreteTicket.interpretar(ruidoso);
                    MuestrasTickets.Marcador r = MuestrasTickets.comparar(leidos, m.esperado(), true);
                    if (System.getenv("DEPURAR_RUIDO") != null && intensidad == 1.0 && semilla <= 2) {
                        System.out.println("=== " + m.nombre() + " semilla " + semilla + "\n" + ruidoso);
                        r.diferencias.forEach(x -> System.out.println("   ✗ " + x));
                    }
                    c += r.campos;
                    a += r.aciertos;
                    for (TicketLeido t : leidos) { tickets++; if (t.getCuadre() != null && t.getCuadre().cuadra()) cuadran++; }
                }
                campos += c;
                aciertos += a;
                filas.add(String.format("  %s %s", m.nombre(), MuestrasTickets.porcentaje((double) a / c)));
            }
            double p = (double) aciertos / campos;
            System.out.printf("Intensidad %.1f: %s de los campos; %d de %d tickets cuadran%n", intensidad,
                    MuestrasTickets.porcentaje(p), cuadran, tickets);
            filas.forEach(System.out::println);
            if (intensidad <= 1.0) assertThat(p).as("precisión con ruido %.1f", intensidad).isGreaterThan(0.85);
        }
    }
}
