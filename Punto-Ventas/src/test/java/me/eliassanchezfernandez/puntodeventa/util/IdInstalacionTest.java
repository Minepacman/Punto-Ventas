package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Id de instalación: aleatorio, estable entre lecturas, tolerante a archivos rotos. */
class IdInstalacionTest {

    @Test
    void seCreaUnaVezYSeConserva(@TempDir Path carpeta) throws Exception {
        String id = IdInstalacion.obtener(carpeta.resolve("app"));
        assertThat(id).matches("[0-9A-F]{4}-[0-9A-F]{4}");
        assertThat(IdInstalacion.obtener(carpeta.resolve("app"))).isEqualTo(id);
        assertThat(Files.readString(carpeta.resolve("app").resolve(IdInstalacion.ARCHIVO)).trim()).isEqualTo(id);
        // Otra instalación tiene otro id
        assertThat(IdInstalacion.obtener(carpeta.resolve("otra"))).isNotEqualTo(id);
    }

    @Test
    void unArchivoRotoSeReemplazaYSinPermisosSeInformaSinId(@TempDir Path carpeta) throws Exception {
        Files.writeString(carpeta.resolve(IdInstalacion.ARCHIVO), "basura\n");
        assertThat(IdInstalacion.obtener(carpeta)).matches("[0-9A-F]{4}-[0-9A-F]{4}");
        // Si la "carpeta" es un archivo no se puede crear nada: sin id, sin excepción
        Path archivo = carpeta.resolve("archivo.txt");
        Files.writeString(archivo, "x");
        assertThat(IdInstalacion.obtener(archivo)).isEqualTo(IdInstalacion.SIN_ID);
    }

    @Test
    void normalizaLoLeido() {
        assertThat(IdInstalacion.normalizar(" 7f3a91c2 ")).isEqualTo("7F3A-91C2");
        assertThat(IdInstalacion.normalizar("7F3A-91C2\n")).isEqualTo("7F3A-91C2");
        assertThat(IdInstalacion.normalizar("zz")).isNull();
        assertThat(IdInstalacion.generar()).matches("[0-9A-F]{4}-[0-9A-F]{4}");
    }
}
