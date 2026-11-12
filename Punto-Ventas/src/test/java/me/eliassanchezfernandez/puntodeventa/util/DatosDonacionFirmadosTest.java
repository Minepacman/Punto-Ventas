package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Datos de donación firmados (PDVD1): ida y vuelta, pendiente sin CLABE, cotejo con donacion.properties, firma ajena. */
class DatosDonacionFirmadosTest {

    private static KeyPair par;
    private static KeyPair otroPar;
    private static ClaveDonador verificador;

    @BeforeAll
    static void llaves() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO);
        par = g.generateKeyPair();
        otroPar = g.generateKeyPair();
        verificador = new ClaveDonador(par.getPublic());
    }

    private static Donacion.Datos incrustados(String clabe, String banco, String beneficiario) {
        Properties p = new Properties();
        p.setProperty("clabe", clabe);
        p.setProperty("banco", banco);
        p.setProperty("beneficiario", beneficiario);
        p.setProperty("soporte.whatsapp", "5512345678");
        p.setProperty("soporte.correo", "hola@ejemplo.com");
        return Donacion.desde(p);
    }

    @Test
    void emiteYVerificaIdaYVuelta() {
        DatosDonacionFirmados.Datos d = new DatosDonacionFirmados.Datos(LocalDate.of(2026, 11, 20), false,
                "032 180 00011835971 9", "BBVA", "Elias Oziel Sánchez Fernández", "525512345678", "hola@ejemplo.com", Marca.SITIO_OFICIAL);
        String texto = DatosDonacionFirmados.emitir(d, par.getPrivate());
        assertThat(texto).startsWith("PDVD1.").doesNotContain(" ", "\n");
        DatosDonacionFirmados.Datos leidos = DatosDonacionFirmados.verificar(" " + texto + "\n", verificador);
        assertThat(leidos).isEqualTo(d);
        assertThat(leidos.clabe()).isEqualTo("032180000118359719");   // sin espacios
        assertThat(DatosDonacionFirmados.json(d)).contains("\"v\":1", "\"emitido\":\"2026-11-20\"", "\"pendiente\":false",
                "\"beneficiario\":\"Elias Oziel Sánchez Fernández\"");
    }

    @Test
    void losDatosPendientesNoLlevanClabeYCoincidenSoloConUnProgramaSinClabe() {
        Donacion.Datos sinClabe = incrustados("000000000000000000", "PENDIENTE", "PENDIENTE");
        DatosDonacionFirmados.Datos d = DatosDonacionFirmados.Datos.de(sinClabe, LocalDate.of(2026, 11, 12));
        assertThat(d.pendiente()).isTrue();
        assertThat(d.clabe()).isEmpty();
        assertThat(d.banco()).isEmpty();
        assertThat(d.beneficiario()).isEmpty();
        assertThat(d.whatsapp()).isEqualTo("5512345678");
        assertThat(d.sitio()).isEqualTo(Marca.SITIO_OFICIAL);
        DatosDonacionFirmados.Datos leidos = DatosDonacionFirmados.verificar(DatosDonacionFirmados.emitir(d, par.getPrivate()), verificador);
        assertThat(leidos.coincideCon(sinClabe)).isTrue();
        // Una copia que ya puso SU CLABE mientras el autor sigue "pendiente": no coincide
        assertThat(leidos.coincideCon(incrustados("032180000118359719", "BBVA", "Fulano"))).isFalse();
    }

    @Test
    void elCotejoIgnoraMayusculasYEspaciosPeroNoLaCuenta() {
        Donacion.Datos oficiales = incrustados("032180000118359719", "BBVA", "Elias Oziel Sánchez Fernández");
        DatosDonacionFirmados.Datos d = DatosDonacionFirmados.Datos.de(oficiales, LocalDate.of(2026, 11, 20));
        assertThat(d.pendiente()).isFalse();
        assertThat(d.coincideCon(oficiales)).isTrue();
        assertThat(d.coincideCon(incrustados("032 180 00011835971 9", "bbva", "ELIAS  OZIEL SÁNCHEZ FERNÁNDEZ"))).isTrue();
        assertThat(d.coincideCon(incrustados("002010077777777771", "BBVA", "Elias Oziel Sánchez Fernández"))).isFalse();   // otra CLABE
        assertThat(d.coincideCon(incrustados("032180000118359719", "BBVA", "Fulano Pérez"))).isFalse();                   // otro nombre
        assertThat(d.coincideCon(incrustados("000000000000000000", "PENDIENTE", "PENDIENTE"))).isFalse();                 // programa sin CLABE
    }

    @Test
    void rechazaClabeInvalidaFirmasAjenasYTextosRaros() {
        assertThatThrownBy(() -> new DatosDonacionFirmados.Datos(LocalDate.now(), false, "032180000118359718", "BBVA", "X", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        DatosDonacionFirmados.Datos d = new DatosDonacionFirmados.Datos(LocalDate.of(2026, 11, 20), false,
                "032180000118359719", "BBVA", "Elias Oziel Sánchez Fernández", "", "", "");
        String ajena = DatosDonacionFirmados.emitir(d, otroPar.getPrivate());
        assertThatThrownBy(() -> DatosDonacionFirmados.verificar(ajena, verificador))
                .isInstanceOf(DatosDonacionFirmados.DatosInvalidosException.class).hasMessageContaining("firma");
        for (String t : new String[] {"", "hola", "PDVD1.abc", "PDVR1.a.b", null, "PDVD1.%%.##"}) {
            assertThatThrownBy(() -> DatosDonacionFirmados.verificar(t, verificador)).as(String.valueOf(t))
                    .isInstanceOf(DatosDonacionFirmados.DatosInvalidosException.class);
        }
        // Firmado pero con CLABE inválida o versión desconocida: no se acepta
        for (String json : new String[] {
                "{\"v\":1,\"emitido\":\"2026-11-12\",\"pendiente\":false,\"clabe\":\"032180000118359718\"}",
                "{\"v\":2,\"emitido\":\"2026-11-12\",\"pendiente\":true}",
                "{\"v\":1,\"emitido\":\"ayer\",\"pendiente\":true}"}) {
            byte[] carga = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String texto = ClaveDonador.armar(DatosDonacionFirmados.PREFIJO, carga, ClaveDonador.firmar(carga, par.getPrivate()));
            assertThatThrownBy(() -> DatosDonacionFirmados.verificar(texto, verificador)).as(json)
                    .isInstanceOf(DatosDonacionFirmados.DatosInvalidosException.class);
        }
    }

    @Test
    void laHuellaDeLaLlaveOficialEsSha256Hex() {
        assertThat(DatosDonacionFirmados.huellaLlaveOficial()).matches("[0-9a-f]{64}");
        // El kid es el principio de esa misma huella
        assertThat(ClaveDonador.oficial().kids()).allMatch(k -> DatosDonacionFirmados.huellaLlaveOficial().startsWith(k));
    }
}
