package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rutas derivadas de la URL de SQLite (BD, copias y restauración pendiente). */
class RutasAppTest {

    @Test
    void laRutaSalDeLaUrlJdbc() {
        assertThat(RutasApp.rutaDeUrl("jdbc:sqlite:datos.db")).isEqualTo(Path.of("datos.db").toAbsolutePath());
        assertThat(RutasApp.rutaDeUrl("jdbc:sqlite:/tmp/x/datos.db?foo=bar")).isEqualTo(Path.of("/tmp/x/datos.db"));
        assertThatThrownBy(() -> RutasApp.rutaDeUrl("jdbc:sqlite::memory:")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RutasApp.rutaDeUrl("jdbc:h2:mem:x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void lasCopiasQuedanJuntoALaBd() {
        Path bd = Path.of("/tmp/x/datos.db");
        assertThat(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR)).isEqualTo(Path.of("/tmp/x/datos.db.restaurar"));
    }

    @Test
    void laCarpetaDeDatosDependeDelSistema() {
        Path casa = Path.of("/home/ana");
        assertThat(RutasApp.carpetaAplicacion("Windows 10", Map.of("APPDATA", "C:\\Users\\Ana\\AppData\\Roaming"), casa))
                .isEqualTo(Path.of("C:\\Users\\Ana\\AppData\\Roaming").resolve("PuntoDeVenta"));
        assertThat(RutasApp.carpetaAplicacion("Windows 11", Map.of(), casa))
                .isEqualTo(casa.resolve("AppData").resolve("Roaming").resolve("PuntoDeVenta"));
        assertThat(RutasApp.carpetaAplicacion("Mac OS X", Map.of(), casa))
                .isEqualTo(casa.resolve("Library").resolve("Application Support").resolve("PuntoDeVenta"));
        assertThat(RutasApp.carpetaAplicacion("Linux", Map.of(), casa)).isEqualTo(casa.resolve(".puntodeventa"));
    }

    @Test
    void sinDatosEnLaCarpetaDeTrabajoLaBdVaALaCarpetaDelUsuario(@TempDir Path trabajo, @TempDir Path usuario) {
        String url = RutasApp.urlInicial(trabajo, usuario);
        assertThat(url).isEqualTo("jdbc:sqlite:" + usuario.resolve("datos.db"));
        assertThat(RutasApp.rutaDeUrl(url)).isEqualTo(usuario.resolve("datos.db"));
    }

    @Test
    void unDatosDbExistenteEnLaCarpetaDeTrabajoSeSigueUsandoSinMoverNiCopiarNada(@TempDir Path trabajo, @TempDir Path usuario) throws Exception {
        Path existente = Files.writeString(trabajo.resolve("datos.db"), "contenido original");

        String url = RutasApp.urlInicial(trabajo, usuario);

        assertThat(RutasApp.rutaDeUrl(url)).isEqualTo(existente.toAbsolutePath());
        assertThat(Files.readString(existente)).isEqualTo("contenido original");   // intacto
        assertThat(usuario.resolve("datos.db")).doesNotExist();                    // y nada se copió
    }

    @Test
    void unaUrlExplicitaSiempreManda() {
        assertThat(RutasApp.hayUrlExplicita(new String[0], Map.of("SPRING_DATASOURCE_URL", "jdbc:sqlite:/x/y.db"))).isTrue();
        assertThat(RutasApp.hayUrlExplicita(new String[] {"--spring.datasource.url=jdbc:sqlite:/z.db"}, Map.of())).isTrue();
        assertThat(RutasApp.hayUrlExplicita(new String[] {"--otra=1"}, Map.of())).isFalse();
        assertThat(RutasApp.hayUrlExplicita(null, Map.of("SPRING_DATASOURCE_URL", " "))).isFalse();
    }
}
