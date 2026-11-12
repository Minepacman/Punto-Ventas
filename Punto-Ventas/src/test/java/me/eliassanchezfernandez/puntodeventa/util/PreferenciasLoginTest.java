package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Último usuario del inicio de sesión: se recuerda por computadora, nunca la contraseña, y tolera fallos. */
class PreferenciasLoginTest {

    @TempDir Path carpeta;

    @Test
    void recuerdaYOlvidaAlUltimoUsuario() throws Exception {
        Path sub = carpeta.resolve("todavia-no-existe");
        assertThat(PreferenciasLogin.ultimoUsuario(sub)).isEmpty();

        PreferenciasLogin.guardarUltimoUsuario(sub, "  Lupita ");
        assertThat(PreferenciasLogin.ultimoUsuario(sub)).contains("Lupita");
        assertThat(Files.readString(PreferenciasLogin.archivo(sub))).doesNotContainIgnoringCase("contrasena=");

        PreferenciasLogin.olvidarUltimoUsuario(sub);
        assertThat(PreferenciasLogin.ultimoUsuario(sub)).isEmpty();
    }

    @Test
    void ignoraArchivosDanadosOValoresAbsurdos() throws Exception {
        Files.writeString(PreferenciasLogin.archivo(carpeta), "ultimo.usuario=" + "x".repeat(500));
        assertThat(PreferenciasLogin.ultimoUsuario(carpeta)).isEmpty();

        Files.writeString(PreferenciasLogin.archivo(carpeta), "basura sin formato \u0000");
        assertThat(PreferenciasLogin.ultimoUsuario(carpeta)).isEmpty();

        // Guardar sobre un archivo dañado lo reemplaza sin lanzar
        PreferenciasLogin.guardarUltimoUsuario(carpeta, "juan");
        assertThat(PreferenciasLogin.ultimoUsuario(carpeta)).contains("juan");
    }
}
