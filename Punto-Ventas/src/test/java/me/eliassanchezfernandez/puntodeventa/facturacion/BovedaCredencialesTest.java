package me.eliassanchezfernandez.puntodeventa.facturacion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cifrado de credenciales con llave por instalación: ida y vuelta, llave distinta, enmascarado. */
class BovedaCredencialesTest {

    @TempDir Path carpeta;

    @Test
    void cifraYDescifraConLaLlaveDeLaInstalacion() throws Exception {
        BovedaCredenciales b = new BovedaCredenciales(carpeta);
        assertThat(b.hayLlave()).isFalse();

        String cifrado = b.cifrar("secreto-ñ-ü-123");
        assertThat(b.hayLlave()).isTrue();
        assertThat(Files.isRegularFile(carpeta.resolve(BovedaCredenciales.NOMBRE_ARCHIVO_LLAVE))).isTrue();
        assertThat(cifrado).startsWith("v1:").doesNotContain("secreto");
        assertThat(b.descifrar(cifrado)).isEqualTo("secreto-ñ-ü-123");

        // Dos cifrados del mismo texto son distintos (IV aleatorio) pero descifran igual
        String otro = b.cifrar("secreto-ñ-ü-123");
        assertThat(otro).isNotEqualTo(cifrado);
        assertThat(b.descifrar(otro)).isEqualTo("secreto-ñ-ü-123");

        // Otra instancia con la MISMA carpeta lee la misma llave
        assertThat(new BovedaCredenciales(carpeta).descifrar(cifrado)).isEqualTo("secreto-ñ-ü-123");

        assertThat(b.cifrar(null)).isNull();
        assertThat(b.cifrar("")).isNull();
        assertThat(b.descifrar(null)).isNull();
    }

    @Test
    void conOtraLlaveNoSeDescifraYElErrorEsClaro() throws Exception {
        BovedaCredenciales a = new BovedaCredenciales(carpeta.resolve("a"));
        BovedaCredenciales b = new BovedaCredenciales(carpeta.resolve("b"));
        String cifrado = a.cifrar("clave");
        assertThatThrownBy(() -> b.descifrar(cifrado))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Vuelve a capturarlas");
        assertThatThrownBy(() -> b.descifrar("texto-plano"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("formato");
    }

    @Test
    void enmascaraParaLaPantalla() {
        assertThat(BovedaCredenciales.enmascarar("elias.sanchez@correo.com")).isEqualTo("el••••••@correo.com");
        assertThat(BovedaCredenciales.enmascarar("usuario")).isEqualTo("us••••••");
        assertThat(BovedaCredenciales.enmascarar("ab")).isEqualTo("a••••••");
        assertThat(BovedaCredenciales.enmascarar("")).isEmpty();
        assertThat(BovedaCredenciales.enmascarar(null)).isEmpty();
        assertThat(new CredencialesPac("usuario@x.com", "secreta", true).toString()).doesNotContain("secreta");
    }
}
