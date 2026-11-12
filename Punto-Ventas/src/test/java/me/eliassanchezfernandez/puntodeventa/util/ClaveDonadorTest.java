package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Claves de donador firmadas con Ed25519: se validan sin conexión, una clave
 * alterada o firmada por otro se rechaza, el formato nuevo (PDV2: kid,
 * vencimiento, nivel cerrado, banderas) convive con el anterior (PDV1), y la
 * pública oficial incrustada es una llave válida. Las pruebas usan su propio
 * par (la privada oficial no está en el repositorio).
 */
class ClaveDonadorTest {

    private static KeyPair par;
    private static KeyPair otroPar;
    private static ClaveDonador verificador;
    private static String kid;

    @BeforeAll
    static void llaves() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO);
        par = g.generateKeyPair();
        otroPar = g.generateKeyPair();
        verificador = new ClaveDonador(par.getPublic());
        kid = ClaveDonador.kidDe(par.getPublic());
    }

    /** Formato anterior */
    private static ClaveDonador.Datos datosV1() {
        return new ClaveDonador.Datos("ABCD2345", "Abarrotes Lupita", LocalDate.of(2026, 11, 12), "Oro");
    }

    private static ClaveDonador.Datos datosV2() {
        return new ClaveDonador.Datos(kid, "QRST6789", "Abarrotes Lupita", LocalDate.of(2026, 11, 12),
                LocalDate.of(2027, 11, 12), "plus", List.of());
    }

    // ── PDV2 ─────────────────────────────────────────────────────────────

    @Test
    void emiteYValidaPdv2IdaYVuelta() {
        String clave = ClaveDonador.emitir(datosV2(), par.getPrivate());
        assertThat(clave).startsWith("PDV2.").doesNotContain(" ", "\n", "=");
        ClaveDonador.Datos d = verificador.validar(clave);
        assertThat(d).isEqualTo(datosV2());
        assertThat(d.esPdv2()).isTrue();
        assertThat(d.kid()).isEqualTo(kid);
        assertThat(d.nivelApoyo()).isEqualTo(ClaveDonador.Nivel.PLUS);
        assertThat(d.permiteBeta()).isTrue();
        assertThat(d.permiteSoporte()).isTrue();
        assertThat(d.vigenciaTexto()).isEqualTo("vigente hasta 12/11/2027");
        assertThat(ClaveDonador.huella(clave)).hasSize(ClaveDonador.LARGO_HUELLA).isEqualTo(clave.split("\\.")[2].substring(0, 12));
    }

    @Test
    void vigenciaVencimientoYRevocacion() {
        ClaveDonador.Datos d = datosV2();
        assertThat(d.vigente(LocalDate.of(2027, 11, 12))).isTrue();     // el último día todavía vale
        assertThat(d.vigente(LocalDate.of(2027, 11, 13))).isFalse();
        assertThat(d.estado(LocalDate.of(2027, 1, 1), Set.of(), Set.of())).isEqualTo(ClaveDonador.Estado.VIGENTE);
        assertThat(d.estado(LocalDate.of(2028, 1, 1), Set.of(), Set.of())).isEqualTo(ClaveDonador.Estado.VENCIDA);
        // Revocada por id, o por retiro de la llave que la firmó; la revocación gana a la vigencia
        assertThat(d.estado(LocalDate.of(2027, 1, 1), Set.of("QRST6789"), Set.of())).isEqualTo(ClaveDonador.Estado.REVOCADA);
        assertThat(d.estado(LocalDate.of(2027, 1, 1), Set.of(), Set.of(kid))).isEqualTo(ClaveDonador.Estado.REVOCADA);
        assertThat(d.estado(LocalDate.of(2027, 1, 1), Set.of("OTRA2345"), Set.of("deadbeef"))).isEqualTo(ClaveDonador.Estado.VIGENTE);

        // Sin vencimiento
        ClaveDonador.Datos eterna = new ClaveDonador.Datos(kid, "QRST6789", "X", LocalDate.of(2026, 1, 1), null, "basico", List.of());
        assertThat(eterna.vigente(LocalDate.of(2099, 1, 1))).isTrue();
        assertThat(eterna.vigenciaTexto()).isEqualTo("sin vencimiento");
        assertThat(verificador.validar(ClaveDonador.emitir(eterna, par.getPrivate())).vence()).isNull();
    }

    @Test
    void lasBanderasRestringenBetaYSoporte() {
        ClaveDonador.Datos soloSoporte = new ClaveDonador.Datos(kid, "QRST6789", "X", LocalDate.of(2026, 1, 1), null,
                "basico", List.of(ClaveDonador.FLAG_SOPORTE));
        ClaveDonador.Datos leida = verificador.validar(ClaveDonador.emitir(soloSoporte, par.getPrivate()));
        assertThat(leida.flags()).containsExactly("soporte");
        assertThat(leida.permiteBeta()).isFalse();
        assertThat(leida.permiteSoporte()).isTrue();
        ClaveDonador.Datos ambas = new ClaveDonador.Datos(kid, "QRST6789", "X", LocalDate.of(2026, 1, 1), null,
                "patrocinador", List.of("beta", "soporte"));
        assertThat(verificador.validar(ClaveDonador.emitir(ambas, par.getPrivate())).flags()).containsExactly("beta", "soporte");
    }

    @Test
    void unKidDesconocidoSeRechazaConMensajeClaro() {
        // Firmada por otra llave y con SU kid: el verificador no la conoce
        ClaveDonador.Datos ajena = new ClaveDonador.Datos(ClaveDonador.kidDe(otroPar.getPublic()), "QRST6789", "X",
                LocalDate.of(2026, 1, 1), null, "basico", List.of());
        String clave = ClaveDonador.emitir(ajena, otroPar.getPrivate());
        assertThatThrownBy(() -> verificador.validar(clave))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class).hasMessageContaining("llave");
        // Con varias públicas se acepta la de cada una
        ClaveDonador ambas = new ClaveDonador(List.of(par.getPublic(), otroPar.getPublic()));
        assertThat(ambas.kids()).containsExactly(kid, ClaveDonador.kidDe(otroPar.getPublic()));
        assertThat(ambas.validar(clave).nombre()).isEqualTo("X");
        // Un kid "correcto" pero firma de otra llave: no pasa
        ClaveDonador.Datos suplantada = new ClaveDonador.Datos(kid, "QRST6789", "X", LocalDate.of(2026, 1, 1), null, "basico", List.of());
        assertThatThrownBy(() -> verificador.validar(ClaveDonador.emitir(suplantada, otroPar.getPrivate())))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class).hasMessageContaining("firma");
    }

    @Test
    void elKidSeDerivaDeLaPublicaYEsEstable() {
        assertThat(kid).matches("[0-9a-f]{8}").isEqualTo(ClaveDonador.kidDe(par.getPublic()));
        assertThat(kid).isNotEqualTo(ClaveDonador.kidDe(otroPar.getPublic()));
        assertThat(ClaveDonador.oficial().kids()).hasSize(1).allMatch(k -> k.matches("[0-9a-f]{8}"));
    }

    @Test
    void elNivelEsCerradoYLoDesconocidoCuentaComoBasico() {
        assertThat(ClaveDonador.Nivel.desde("plus")).isEqualTo(ClaveDonador.Nivel.PLUS);
        assertThat(ClaveDonador.Nivel.desde(" Patrocinador ")).isEqualTo(ClaveDonador.Nivel.PATROCINADOR);
        assertThat(ClaveDonador.Nivel.desde("Oro")).isEqualTo(ClaveDonador.Nivel.BASICO);
        assertThat(ClaveDonador.Nivel.desde(null)).isEqualTo(ClaveDonador.Nivel.BASICO);
        assertThat(ClaveDonador.Nivel.esValido("basico")).isTrue();
        assertThat(ClaveDonador.Nivel.esValido("oro")).isFalse();
        assertThat(datosV1().nivelApoyo()).isEqualTo(ClaveDonador.Nivel.BASICO);
    }

    // ── PDV1 (formato anterior) ──────────────────────────────────────────

    @Test
    void emiteYValidaPdv1IdaYVuelta() {
        String clave = ClaveDonador.emitir(datosV1(), par.getPrivate());
        assertThat(clave).startsWith(ClaveDonador.PREFIJO_V1 + ".").doesNotContain(" ", "\n", "=");
        ClaveDonador.Datos d = verificador.validar(clave);
        assertThat(d).isEqualTo(datosV1());
        assertThat(d.esPdv2()).isFalse();
        assertThat(d.kid()).isNull();
        assertThat(d.vence()).isNull();
        assertThat(d.vigente(LocalDate.of(2099, 1, 1))).isTrue();   // sin vencimiento
        assertThat(d.descripcion()).isEqualTo("Abarrotes Lupita (desde 12/11/2026)");
        assertThat(d.tieneNivel()).isTrue();
        assertThat(d.permiteBeta()).isTrue();
    }

    @Test
    void toleraEspaciosSaltosDeLineaYComillasDelMensajero() {
        String clave = ClaveDonador.emitir(datosV2(), par.getPrivate());
        String pegada = "  \"" + clave.substring(0, 20) + "\n " + clave.substring(20, 50) + " \r\n" + clave.substring(50) + "\"  ";
        assertThat(verificador.validar(pegada)).isEqualTo(datosV2());
    }

    @Test
    void acentosYNombresLargosSobrevivenLaCodificacion() {
        ClaveDonador.Datos d = new ClaveDonador.Datos(kid, "Q2W3E4R5", "Miscelánea \"La Güera\" & Hnos. Ñuñoa",
                LocalDate.of(2026, 1, 31), LocalDate.of(2027, 1, 31), "", List.of());
        ClaveDonador.Datos v = verificador.validar(ClaveDonador.emitir(d, par.getPrivate()));
        assertThat(v.nombre()).isEqualTo("Miscelánea \"La Güera\" & Hnos. Ñuñoa");
        assertThat(v.tieneNivel()).isFalse();
        assertThat(v.nivelApoyo()).isEqualTo(ClaveDonador.Nivel.BASICO);
    }

    @Test
    void rechazaClavesAlteradas() {
        String clave = ClaveDonador.emitir(datosV1(), par.getPrivate());
        String[] partes = clave.split("\\.");

        // Carga cambiada (otro nombre) con la firma original
        String otraCarga = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "PDV1|ABCD2345|Otro Negocio|2026-11-12|Oro".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verificador.validar(partes[0] + "." + otraCarga + "." + partes[2]))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class).hasMessageContaining("firma");

        // Un carácter de la firma cambiado (a la mitad: el último solo lleva bits de relleno)
        int medio = partes[2].length() / 2;
        char c = partes[2].charAt(medio);
        String firmaRota = partes[2].substring(0, medio) + (c == 'A' ? 'B' : 'A') + partes[2].substring(medio + 1);
        assertThatThrownBy(() -> verificador.validar(partes[0] + "." + partes[1] + "." + firmaRota))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class);

        // Clave recortada
        assertThatThrownBy(() -> verificador.validar(clave.substring(0, clave.length() - 10)))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class);

        // Vencimiento "alargado" en la carga de una PDV2 con la firma original
        String v2 = ClaveDonador.emitir(datosV2(), par.getPrivate());
        String[] p2 = v2.split("\\.");
        String alargada = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("PDV2|" + kid + "|QRST6789|Abarrotes Lupita|2026-11-12|2099-11-12|plus|").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> verificador.validar(p2[0] + "." + alargada + "." + p2[2]))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class).hasMessageContaining("firma");

        // Prefijo cambiado: PDV1 con carga PDV2 (o al revés) no pasa
        assertThatThrownBy(() -> verificador.validar("PDV1." + p2[1] + "." + p2[2]))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class);
    }

    @Test
    void rechazaClavesFirmadasPorOtraLlave() {
        String ajena = ClaveDonador.emitir(datosV1(), otroPar.getPrivate());
        assertThatThrownBy(() -> verificador.validar(ajena)).isInstanceOf(ClaveDonador.ClaveInvalidaException.class);
        // y la oficial tampoco acepta la de pruebas
        assertThatThrownBy(() -> ClaveDonador.oficial().validar(ClaveDonador.emitir(datosV1(), par.getPrivate())))
                .isInstanceOf(ClaveDonador.ClaveInvalidaException.class);
    }

    @Test
    void rechazaTextosQueNoSonClaves() {
        for (String t : new String[] {"", "   ", "hola", "PDV1.abc", "PDV9.YQ.YQ", "PDV1.%%%.###", "PDV1..", "PDV2..", null}) {
            assertThatThrownBy(() -> verificador.validar(t)).as("texto: " + t)
                    .isInstanceOf(ClaveDonador.ClaveInvalidaException.class);
        }
        assertThat(ClaveDonador.huella("hola")).isEmpty();
    }

    @Test
    void laClavePublicaOficialEsUnaLlaveEd25519Valida() {
        assertThat(ClaveDonador.clavePublicaDesde(ClaveDonador.CLAVE_PUBLICA_OFICIAL).getAlgorithm())
                .isEqualToIgnoringCase("EdDSA");
        assertThat(ClaveDonador.oficial()).isNotNull();
        assertThat(ClaveDonador.CLAVES_PUBLICAS_OFICIALES).contains(ClaveDonador.CLAVE_PUBLICA_OFICIAL);
    }

    @Test
    void losDatosSeValidanAlConstruirlos() {
        assertThatThrownBy(() -> new ClaveDonador.Datos("ID", "", LocalDate.now(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaveDonador.Datos("ID", "a|b", LocalDate.now(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaveDonador.Datos("ID", "x".repeat(81), LocalDate.now(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaveDonador.Datos("", "Tienda", LocalDate.now(), "")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClaveDonador.Datos("ID", "  Tienda  ", LocalDate.now(), null).nombre()).isEqualTo("Tienda");
        // PDV2: kid con forma de 8 hex, vencimiento no anterior a la emisión, banderas simples
        assertThatThrownBy(() -> new ClaveDonador.Datos("zz", "ID", "T", LocalDate.now(), null, "basico", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaveDonador.Datos(kid, "ID", "T", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), "basico", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClaveDonador.Datos(kid, "ID", "T", LocalDate.now(), null, "basico", List.of("a|b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ClaveDonador.Datos(kid.toUpperCase(), "ID", "T", LocalDate.now(), null, "basico", List.of(" Beta ")).flags())
                .containsExactly("beta");
    }

    @Test
    void elIdNuevoEsLegibleYSinCaracteresAmbiguos() {
        for (int i = 0; i < 50; i++) {
            String id = ClaveDonador.nuevoId();
            assertThat(id).hasSize(ClaveDonador.LARGO_ID).matches("[A-HJ-NP-Z2-9]+");
            assertThat(ClaveDonador.esId(id)).isTrue();
        }
        assertThat(ClaveDonador.esId("ABCD1234")).isFalse();   // 1 no está en el alfabeto
        assertThat(ClaveDonador.esId(null)).isFalse();
    }
}
