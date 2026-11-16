package me.eliassanchezfernandez.puntodeventa.ticketsproveedor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Fallas vistas con fotos reales (sesión 2026-11-16), reproducidas con renglones sintéticos sin datos de nadie:
 * ceros de matriz de puntos leídos como 8, números partidos por un espacio, signos sueltos en vez del 1,
 * columnas revueltas por el papel ondulado, descripciones que el OCR se comió, el total un renglón arriba de
 * su etiqueta, etiquetas con letras cambiadas, fechas con el año mal leído y "S9.31" contra "59.31".
 */
class InterpreteTicketCorreccionesTest {

    private static TicketLeido uno(String texto) {
        List<TicketLeido> ts = InterpreteTicket.interpretar(texto);
        assertThat(ts).hasSize(1);
        return ts.get(0);
    }

    @Test
    void cerosDeMatrizDePuntosLeidosComoOchoSeCorrigenConLasCuentas() {
        TicketLeido t = uno("""
                Prod      Descripcion         Cant Precio   Desc
                SubTot
                25281988  AMPER REG 12/.4      1.8 215.08   8.00
                215.88
                25284882  AMPER MANGO 12/      1.0 215.00   0.00
                215.00
                SUBTOTAL :          430.00
                DESCUENTO:            8.88
                IMPUESTOS:           59.31
                TOTAL $    430.00
                """);
        assertThat(t.getLineas()).hasSize(2);
        LineaTicket l = t.getLineas().get(0);
        assertThat(l.getCantidad()).isEqualTo(1.0);
        assertThat(l.getPrecioUnitario()).isEqualTo(215.0);
        assertThat(l.getDescuento()).isEqualTo(0.0);
        assertThat(l.getImporte()).isEqualTo(215.0);
        assertThat(l.getEstado()).isEqualTo(LineaTicket.Estado.REVISAR);
        assertThat(l.getMotivo()).contains("matriz");
        assertThat(t.getDescuento()).isEqualTo(0.0);
        assertThat(t.getTotal()).isEqualTo(430.0);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void numerosPartidosPorUnEspacioSeUnen() {
        assertThat(InterpreteTicket.unirNumerosPartidos("Total: 324. 00 MXN")).isEqualTo("Total: 324.00 MXN");
        assertThat(InterpreteTicket.unirNumerosPartidos("0.05 MlbRcp20CD 468 .81")).isEqualTo("0.05 MlbRcp20CD 468.81");
        assertThat(InterpreteTicket.unirNumerosPartidos("Ruta 12. 300 piezas")).isEqualTo("Ruta 12. 300 piezas");
        TicketLeido t = uno("""
                ORBIT SURTID 89.00 89.00
                MAZAPAN GIGA109.00 109.00
                MZ CHOCO GT 126.00 126.00
                Tota]: 324. 00 MXN
                """);
        assertThat(t.getLineas()).hasSize(3);
        assertThat(t.getTotal()).isEqualTo(324.0);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void unSignoSueltoEntreCifrasEsUnUno() {
        TicketLeido t = uno("""
                NO. CLAVE CANT PRECIO IMPORTE
                UHT Lala 100 Vainilla 330ml Pet
                1 021442 ] 23.70 23.70
                UHT Lala 100 Fresa 330ml Pet
                2 021441 | 23.70 23.70
                Subtotal $47.40
                """);
        assertThat(t.getLineas()).hasSize(2);
        for (LineaTicket l : t.getLineas()) {
            assertThat(l.getCantidad()).isEqualTo(1.0);
            assertThat(l.getPrecioUnitario()).isEqualTo(23.70);
            assertThat(l.getEstado()).isEqualTo(LineaTicket.Estado.REVISAR);
        }
        assertThat(t.getLineas().get(0).getCodigoProveedor()).isEqualTo("021442");
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void claveAlFinalDeLaDescripcionYPrecioEImporteEnElRenglonDeAbajo() {
        TicketLeido t = uno("""
                NS CLAVE CANT P.UNIT SUBTOTAL
                MPERADOR SENZO DTS G 117GRX48X1 300062064
                $17.26 $ 34.52
                GIRO DTS NI RF 114GRX44X1
                2 300062346 $17.26 $ 17.26
                TOTAL= 3 $ 51.78
                """);
        assertThat(t.getLineas()).hasSize(2);
        LineaTicket l = t.getLineas().get(0);
        assertThat(l.getCodigoProveedor()).isEqualTo("300062064");
        assertThat(l.getCantidad()).isEqualTo(2.0);
        assertThat(l.getPrecioUnitario()).isEqualTo(17.26);
        assertThat(l.getImporte()).isEqualTo(34.52);
        assertThat(l.getDescripcion()).doesNotContain("300062064");
        assertThat(t.getLineas().get(1).getCodigoProveedor()).isEqualTo("300062346");
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void columnasRevueltasPorElPapelOndulado() {
        // El OCR puso precio e importe en el renglón de la descripción y la cantidad en el de la clave
        TicketLeido t = uno("""
                NS CLAVE CANT P.UNIT SUBTOTAL
                * EMPERADOR SENZO DTS RG3 117GRX48X1 34.52
                1 300062064 2 $17.26 $
                GIRO DTS NIRF 114GRX44X1 $17.26 $ 17.26
                2 300062346
                TOTAL= 3 $ 51.78
                """);
        assertThat(t.getLineas()).hasSize(2);
        assertThat(t.getLineas().get(0).getCantidad()).isEqualTo(2.0);
        assertThat(t.getLineas().get(0).getImporte()).isEqualTo(34.52);
        assertThat(t.getLineas().get(0).getCodigoProveedor()).isEqualTo("300062064");
        assertThat(t.getLineas().get(1).getCodigoProveedor()).isEqualTo("300062346");
        assertThat(t.getLineas().get(1).getImporte()).isEqualTo(17.26);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void renglonSinDescripcionPeroConCuentasExactasEsArticuloPorRevisar() {
        TicketLeido t = uno("""
                Prod Descripcion
                Cant Precio Desc. SubTotal
                2 26.00 0 51.99
                5909 WKS NOVA Pouch Adult Salmon 24/
                2 10.50 0 21.00
                3 300058269 $17.26 $ 17.26
                TOTAL 90.25
                """);
        assertThat(t.getLineas()).hasSize(3);
        LineaTicket sin = t.getLineas().get(0);
        assertThat(sin.getDescripcion()).isEmpty();
        assertThat(sin.getCantidad()).isEqualTo(2.0);
        assertThat(sin.getPrecioUnitario()).isEqualTo(26.0);
        assertThat(sin.getImporte()).isEqualTo(51.99);
        assertThat(sin.getEstado()).isEqualTo(LineaTicket.Estado.REVISAR);
        assertThat(sin.getMotivo()).contains("descripción");
        assertThat(t.getLineas().get(1).getDescripcion()).startsWith("WKS NOVA");
        assertThat(t.getLineas().get(2).getCodigoProveedor()).isEqualTo("300058269");
        assertThat(t.getLineas().get(2).getImporte()).isEqualTo(17.26);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void unRenglonDeNumerosDebajoDeSuDescripcionSigueSiendoUnSoloArticulo() {
        TicketLeido t = uno("""
                GoldenNuts Enchilado 1p 78
                1 8.0% 15.56 $15.56
                TOTAL A PAGAR 15.56
                """);
        assertThat(t.getLineas()).hasSize(1);
        assertThat(t.getLineas().get(0).getDescripcion()).isEqualTo("GoldenNuts Enchilado 1p 78");
        assertThat(t.getLineas().get(0).getImporte()).isEqualTo(15.56);
    }

    @Test
    void elTotalUnRenglonArribaDeSuEtiquetaSeRescataConLasCuentas() {
        TicketLeido t = uno("""
                NS CLAVE CANT P.UNIT SUBTOTAL
                * EMPERADOR SENZO DTS RG3 117GRX48X1
                1 300062064 2 $17.26 $ 34.52
                * GIRO DTS NI RF 114GRX44X1
                2 300062346 1 $17.26 $ 17.26
                * CHOKIS CLASICA FU NF 76GRX45X1
                3 300058269 1 $17.26 $ 17.26
                * MARIAS AZU RG2 144GRX22X1
                4 300060502 3 $14.68 $ 44.04
                * RICANELAS DTS 113GRX34X1
                5 300062421 1 $15.69 $ 15.69
                * MARIAS DORADAS 199GRX15X1
                6 300061649 2 $25.11 $ 50.22
                * PT REGALO= 0 $ 0.00
                * SUBTOTAL= $ 178.99
                * DESCUENTO= $ 0.00
                $ 14.32
                * I.E.P.S.= $ 0.00
                * I.V.A.= 193.31
                TOTAL= 10 $
                """);
        assertThat(t.getLineas()).hasSize(6);
        assertThat(t.getTotal()).isEqualTo(193.31);
        assertThat(t.getImpuestos()).isEqualTo(14.32);
        assertThat(t.getCuadre().cuadra()).isTrue();
        assertThat(t.getAvisos()).anyMatch(a -> a.contains("193.31"));
    }

    @Test
    void elRescateDelTotalNoTomaConteosDePiezasNiPaquetes() {
        TicketLeido t = uno("""
                Cantidad Descripcion Total
                0.05 MlbRcp20CD 468.81
                Costo Distribucion: 11.60
                Total Paquetes 0.05
                Total cajetillas: 5
                """);
        assertThat(t.getLineas()).hasSize(1);
        assertThat(t.getTotal()).isNull();
        assertThat(t.getCuadre().cuadra()).isFalse();
    }

    @Test
    void etiquetasConLetrasCambiadasPorElOcr() {
        assertThat(InterpreteTicket.etiquetas("Tota]: 324.00")).startsWith("TOTAL");
        assertThat(InterpreteTicket.etiquetas("IUA : 30.35")).startsWith("IVA");
        assertThat(InterpreteTicket.etiquetas("TOTALAPAGAR 267.10")).isEqualTo("TOTAL A PAGAR 267.10");
        assertThat(InterpreteTicket.etiquetas("DESCRIBCIOM")).isEqualTo("DESCRIPCION");    // dos letras en una etiqueta larga
        assertThat(InterpreteTicket.etiquetaParecida("TOTEL")).isEqualTo("TOTAL");
        assertThat(InterpreteTicket.etiquetaParecida("TOTES")).isNull();                   // dos letras en una corta: no
        TicketLeido t = uno("""
                Takis Ranch 1p 70g PC FLOW
                2 8.0% 15.56 $31.12
                IUA : $0
                TOTALAPAGAR 31.12
                """);
        assertThat(t.getTotal()).isEqualTo(31.12);
        assertThat(t.getIva()).isEqualTo(0.0);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void fechaConGuionYEspacioAntesDelAnioYAnioMalLeido() {
        TicketLeido t = uno("""
                Impresion:11-13 2026 13:30:07
                Folio: 9544-54738
                25281900  AMPER REG 12/.4      1.0 215.00   0.00
                215.00
                TOTAL $    215.00
                """);
        assertThat(t.getFecha()).isEqualTo(LocalDate.of(2026, 11, 13));
        int hoy = LocalDate.now().getYear();
        assertThat(InterpreteTicket.anioPlausible("2028")).isNotNull().isLessThanOrEqualTo(hoy + 1);
        assertThat(InterpreteTicket.anioPlausible("2925")).isNotNull().isBetween(hoy - 3, hoy + 1);
        assertThat(InterpreteTicket.anioPlausible(String.valueOf(hoy))).isEqualTo(hoy);
        assertThat(InterpreteTicket.anioPlausible("1999")).isNull();
    }

    @Test
    void impuestosLeidosConEseAlInicioSeDecidenConLasCuentas() {
        TicketLeido t = uno("""
                25281900  AMPER REG 12/.4      1.0 215.00   0.00
                215.00
                25284802  AMPER MANGO 12/      1.0 215.00   0.00
                215.00
                SUBTOTAL :          430.00
                DESCUENTO:            0.00
                IMPUESTOS:           S9.31
                TOTAL $    430.00
                """);
        assertThat(t.getImpuestos()).isCloseTo(59.31, within(0.001));   // 430 − 430 / 1.16, no $9.31
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void claveConElNumeroDeRenglonPegadoSeLimpia() {
        TicketLeido t = uno("""
                NO. CLAVE CANT PRECIO IMPORTE
                UHT Lala 100 Vainilla 330ml Pet
                1 021442 1 23.70 23.70
                UHT Lala Latte Des 330ml Tpr
                2 020422 1 22.40 22.40
                Nutriyoghurt Beb NuezCereal 450 gr CN
                3025927 2 16.00 32.00
                Nutriyoghurt Beb FresaCereal 450 gr CN
                4 025928 Ls 16.00 32.00
                Subtotal $110.10
                """);
        assertThat(t.getLineas()).hasSize(4);
        assertThat(t.getLineas().get(2).getCodigoProveedor()).isEqualTo("025927");
        assertThat(t.getLineas().get(2).getEstado()).isEqualTo(LineaTicket.Estado.REVISAR);
        // "Ls" era un 2: la cantidad sale del importe entre el precio
        assertThat(t.getLineas().get(3).getCantidad()).isEqualTo(2.0);
        assertThat(t.getLineas().get(3).getPrecioUnitario()).isEqualTo(16.0);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void unidadPegadaAlPrecioYBasuraNumericaAlFinalDelRenglon() {
        TicketLeido t = uno("""
                Fecha de Emisión. 12/11/2026 ed e 7
                8-ROCHER T2 $20.52c/u $164.16 79
                $10.4112 o/ a.
                10-K MAXI T1 u $104.11 Na
                Total ventas: $268.27
                Total final: $268.27 74
                """);
        assertThat(t.getLineas()).hasSize(2);
        assertThat(t.getLineas().get(0).getCantidad()).isEqualTo(8.0);
        assertThat(t.getLineas().get(0).getPrecioUnitario()).isEqualTo(20.52);
        assertThat(t.getLineas().get(0).getImporte()).isEqualTo(164.16);
        assertThat(t.getLineas().get(1).getCantidad()).isEqualTo(10.0);
        assertThat(t.getLineas().get(1).getPrecioUnitario()).isEqualTo(10.4112);
        assertThat(t.getLineas().get(1).getImporte()).isEqualTo(104.11);
        assertThat(t.getTotal()).isEqualTo(268.27);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void basuraConDosPuntosAlInicioNoConvierteUnArticuloEnEncabezado() {
        TicketLeido t = uno("""
                oducto Descripción Desc. Importe
                ZA 4 $ $15.50 Folios Desc:
                )134020 DANONE FRESA 220G DS ASS $0.00 $62.00
                VA 2 $ $15.50 Folios Desc:
                pr: 126 DANONE MANZANA 220G DS EVO $0.00 $31.00
                Importe: $93.00
                Total a pagar: $93.00
                """);
        assertThat(t.getLineas()).hasSize(2);
        assertThat(t.getLineas().get(0).getCodigoProveedor()).isEqualTo("134020");
        assertThat(t.getLineas().get(0).getDescripcion()).startsWith("DANONE FRESA");
        assertThat(t.getLineas().get(1).getDescripcion()).contains("DANONE MANZANA");
        assertThat(t.getLineas().get(1).getCantidad()).isEqualTo(2.0);
        assertThat(t.getLineas().get(1).getImporte()).isEqualTo(31.0);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void paqueteDeCigarrosConGuionYBasuraAlrededor() {
        TicketLeido t = uno("""
                ©) Cantidad Descripcion Total
                - 0.05 MIbRep200D 468 .81 pa
                Costo Distribucion: 11.60
                hs
                Total 480.41
                Total Paquetes 0.05 | ae
                Total cajetillas: 5
                """);
        assertThat(t.getLineas()).hasSize(1);
        assertThat(t.getLineas().get(0).getCantidad()).isEqualTo(0.05);
        assertThat(t.getLineas().get(0).getImporte()).isEqualTo(468.81);
        assertThat(t.getLineas().get(0).getEmpaque()).isEqualTo(100);
        assertThat(t.getCargos()).isEqualTo(11.60);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }

    @Test
    void elValorDelPieEsElUltimoConCentavosNoLaBasuraDeDespues() {
        TicketLeido t = uno("""
                Cantidad Descripcion Total
                0.05 MlbRcp20CD 468.81
                Costo Distribucion: 11.60 1 j 8
                Total 480.41
                """);
        assertThat(t.getCargos()).isEqualTo(11.60);
        assertThat(t.getTotal()).isEqualTo(480.41);
        assertThat(t.getCuadre().cuadra()).isTrue();
    }
}
