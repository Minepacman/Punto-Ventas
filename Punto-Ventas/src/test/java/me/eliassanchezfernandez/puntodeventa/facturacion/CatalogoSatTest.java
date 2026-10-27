package me.eliassanchezfernandez.puntodeventa.facturacion;

import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Catálogos SAT incluidos: carga, búsqueda, formato de claves y clave de unidad por defecto. */
class CatalogoSatTest {

    @Test
    void cargaLosCatalogosIncluidos() {
        assertThat(CatalogoSat.clavesProdServ()).hasSizeGreaterThan(40);
        assertThat(CatalogoSat.clavesUnidad()).hasSizeGreaterThan(15);
        assertThat(CatalogoSat.clavesProdServ()).allSatisfy(e -> assertThat(e.clave()).matches("\\d{8}"));
        assertThat(CatalogoSat.clavesUnidad()).allSatisfy(e -> assertThat(e.clave()).matches("[A-Z0-9]{2,3}"));
        assertThat(CatalogoSat.prodServ("01010101")).isPresent();
        assertThat(CatalogoSat.prodServ(CatalogoSat.PROD_SERV_ABARROTES)).isPresent();
        assertThat(CatalogoSat.unidad("H87")).map(CatalogoSat.Entrada::descripcion).contains("Pieza");
    }

    @Test
    void buscaPorClaveOPorDescripcionSinAcentos() {
        assertThat(CatalogoSat.buscarProdServ("refres")).extracting(CatalogoSat.Entrada::clave).contains("50202306");
        assertThat(CatalogoSat.buscarProdServ("502023")).extracting(CatalogoSat.Entrada::clave).contains("50202301", "50202306");
        assertThat(CatalogoSat.buscarProdServ("LACTEOS")).isNotEmpty();      // "lácteos" sin acento
        assertThat(CatalogoSat.buscarUnidad("kilo")).extracting(CatalogoSat.Entrada::clave).containsExactly("KGM");
        assertThat(CatalogoSat.buscarUnidad("")).hasSameSizeAs(CatalogoSat.clavesUnidad());
        assertThat(CatalogoSat.buscarProdServ("zzzz")).isEmpty();
    }

    @Test
    void normalizaYValidaFormatos() {
        assertThat(CatalogoSat.normalizarProdServ(" 50000000 ")).isEqualTo("50000000");
        assertThat(CatalogoSat.normalizarProdServ("")).isNull();
        assertThatThrownBy(() -> CatalogoSat.normalizarProdServ("5000")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CatalogoSat.normalizarUnidad("kgm")).isEqualTo("KGM");
        assertThat(CatalogoSat.normalizarUnidad("ea")).isEqualTo("EA");
        assertThatThrownBy(() -> CatalogoSat.normalizarUnidad("KILO")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CatalogoSat.normalizarCodigoPostal("78240")).isEqualTo("78240");
        assertThatThrownBy(() -> CatalogoSat.normalizarCodigoPostal("7824")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CatalogoSat.esRegimen("626")).isTrue();
        assertThat(CatalogoSat.esRegimen("999")).isFalse();
        assertThat(CatalogoSat.esUsoCfdi("G03")).isTrue();
        assertThat(CatalogoSat.esFormaPago("28")).isTrue();
    }

    @Test
    void claveDeUnidadPorDefecto() {
        assertThat(CatalogoSat.claveUnidadPara(null)).isEqualTo("H87");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Pieza", "pza", false))).isEqualTo("H87");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Kilogramo", "kg", true))).isEqualTo("KGM");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Litro", "L", true))).isEqualTo("LTR");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Bolsa", null, false))).isEqualTo("XBG");
        // Desconocida: por su abreviatura; si tampoco, Pieza o Kilogramo según decimales
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Kilo neto", "kg", true))).isEqualTo("KGM");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Rollo", "rll", false))).isEqualTo("H87");
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Manojo", "mjo", true))).isEqualTo("KGM");
        // La clave capturada manda
        assertThat(CatalogoSat.claveUnidadPara(new Unidad("Pieza", "pza", false, "XBX"))).isEqualTo("XBX");
    }

    @Test
    void regimenesSegunTipoDeRfc() {
        assertThat(CatalogoSat.regimenesPara("ABC010101AB1")).extracting(CatalogoSat.Entrada::clave).contains("601", "626").doesNotContain("612", "605");
        assertThat(CatalogoSat.regimenesPara("GODE561231GR8")).extracting(CatalogoSat.Entrada::clave).contains("612", "626", "605").doesNotContain("601");
        assertThat(CatalogoSat.regimenesPara(Rfc.PUBLICO_EN_GENERAL)).isEqualTo(CatalogoSat.REGIMENES);
    }
}
