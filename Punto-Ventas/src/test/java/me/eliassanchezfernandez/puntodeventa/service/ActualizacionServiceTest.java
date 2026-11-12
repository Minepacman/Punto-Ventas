package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.util.VersionSemantica;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Análisis puro de la respuesta de GitHub (sin red): elige la versión más alta
 * que supere a la instalada, respeta borradores y prelanzamientos, y escoge el
 * instalador del sistema.
 */
class ActualizacionServiceTest {

    private static final String JSON = """
        [
          {"tag_name": "v1.2.0-beta.1", "name": "1.2.0 beta 1", "draft": false, "prerelease": true,
           "html_url": "https://github.com/Minepacman/Punto-Ventas/releases/tag/v1.2.0-beta.1",
           "body": "Prueba de lotes y caducidades.",
           "assets": [
             {"name": "PuntoDeVenta-1.2.0-windows-x64.exe", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.2.0-beta.1/PuntoDeVenta-1.2.0-windows-x64.exe"},
             {"name": "PuntoDeVenta-1.2.0-macos-arm64.dmg", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.2.0-beta.1/PuntoDeVenta-1.2.0-macos-arm64.dmg"}
           ]},
          {"tag_name": "v1.1.0", "name": "Versión 1.1.0", "draft": false, "prerelease": false,
           "html_url": "https://github.com/Minepacman/Punto-Ventas/releases/tag/v1.1.0",
           "body": "## Novedades\\r\\n- Clientes con crédito\\r\\n- Arreglos",
           "assets": [
             {"name": "PuntoDeVenta-1.1.0-windows-x64.exe", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-windows-x64.exe"},
             {"name": "PuntoDeVenta-1.1.0-windows-x86.exe", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-windows-x86.exe"},
             {"name": "PuntoDeVenta-1.1.0-macos-arm64.dmg", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-macos-arm64.dmg"},
             {"name": "PuntoDeVenta-1.1.0-macos-x64.dmg", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-macos-x64.dmg"}
           ]},
          {"tag_name": "v1.10.0", "name": "Borrador", "draft": true, "prerelease": false, "html_url": "", "body": "", "assets": []},
          {"tag_name": "v1.0.0", "name": "Primera versión", "draft": false, "prerelease": false,
           "html_url": "https://github.com/Minepacman/Punto-Ventas/releases/tag/v1.0.0", "body": "Inicial", "assets": []},
          {"tag_name": "nightly", "name": "sin versión", "draft": false, "prerelease": false, "html_url": "", "body": "", "assets": []}
        ]
        """;

    private static VersionSemantica v(String s) {
        return VersionSemantica.parsear(s).orElseThrow();
    }

    @Test
    void usuarioNormalRecibeLaFinalMasAltaConElInstaladorDeSuSistema() {
        Optional<ActualizacionService.Actualizacion> a = ActualizacionService.analizar(JSON, v("1.0.0"), false, "Windows 10", "amd64");
        assertThat(a).isPresent();
        assertThat(a.get().version()).isEqualTo(v("1.1.0"));
        assertThat(a.get().prelanzamiento()).isFalse();
        assertThat(a.get().urlDescarga()).contains("https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-windows-x64.exe");
        assertThat(a.get().urlPagina()).endsWith("/tag/v1.1.0");
        assertThat(a.get().notas()).contains("Clientes con crédito").doesNotContain("\r");
        assertThat(a.get().nombre()).isEqualTo("Versión 1.1.0");
    }

    @Test
    void elDonadorRecibeTambienLosPrelanzamientos() {
        Optional<ActualizacionService.Actualizacion> a = ActualizacionService.analizar(JSON, v("1.0.0"), true, "Mac OS X", "aarch64");
        assertThat(a).isPresent();
        assertThat(a.get().version()).isEqualTo(v("1.2.0-beta.1"));
        assertThat(a.get().prelanzamiento()).isTrue();
        assertThat(a.get().urlDescarga()).contains("https://github.com/Minepacman/Punto-Ventas/releases/download/v1.2.0-beta.1/PuntoDeVenta-1.2.0-macos-arm64.dmg");
    }

    @Test
    void siYaTieneLaUltimaNoHayActualizacion() {
        assertThat(ActualizacionService.analizar(JSON, v("1.1.0"), false, "Windows 10", "amd64")).isEmpty();
        assertThat(ActualizacionService.analizar(JSON, v("1.2.0-beta.1"), true, "Windows 10", "amd64")).isEmpty();
        // Con una beta instalada, la final 1.1.0 es menor que 1.2.0-beta.1: tampoco
        assertThat(ActualizacionService.analizar(JSON, v("1.2.0-beta.1"), false, "Windows 10", "amd64")).isEmpty();
        // Los borradores (1.10.0) nunca cuentan aunque sean la versión más alta
        assertThat(ActualizacionService.analizar(JSON, v("1.1.0"), true, "Windows 10", "amd64"))
                .map(a -> a.version()).contains(v("1.2.0-beta.1"));
    }

    @Test
    void sinInstaladorParaElSistemaSeOfreceLaPagina() {
        Optional<ActualizacionService.Actualizacion> a = ActualizacionService.analizar(JSON, v("1.0.0"), false, "Linux", "amd64");
        assertThat(a).isPresent();
        assertThat(a.get().urlDescarga()).isEmpty();
        assertThat(a.get().urlParaAbrir()).isEqualTo(a.get().urlPagina());
    }

    @Test
    void respuestasRarasNoRevientan() {
        assertThat(ActualizacionService.analizar("[]", v("1.0.0"), false, "Windows", "amd64")).isEmpty();
        assertThat(ActualizacionService.analizar("{\"message\":\"API rate limit exceeded\"}", v("1.0.0"), false, "Windows", "amd64")).isEmpty();
        assertThatThrownBy(() -> ActualizacionService.analizar("no es json", v("1.0.0"), false, "Windows", "amd64"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eligeElInstaladorPorSistemaYArquitectura() {
        List<String> activos = List.of("PuntoDeVenta-1.1.0-windows-x64.exe", "PuntoDeVenta-1.1.0-windows-x86.exe",
                "PuntoDeVenta-1.1.0-macos-arm64.dmg", "PuntoDeVenta-1.1.0-macos-x64.dmg", "fuentes.zip");
        assertThat(ActualizacionService.elegirActivo(activos, "Windows 11", "amd64")).contains("PuntoDeVenta-1.1.0-windows-x64.exe");
        assertThat(ActualizacionService.elegirActivo(activos, "Windows 10", "x86")).contains("PuntoDeVenta-1.1.0-windows-x86.exe");
        assertThat(ActualizacionService.elegirActivo(activos, "Mac OS X", "aarch64")).contains("PuntoDeVenta-1.1.0-macos-arm64.dmg");
        assertThat(ActualizacionService.elegirActivo(activos, "Mac OS X", "x86_64")).contains("PuntoDeVenta-1.1.0-macos-x64.dmg");
        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "amd64")).isEmpty();

        // Solo hay x64 para Windows: la JVM de 32 bits no lo acepta; la de 64 sí
        List<String> soloX64 = List.of("PuntoDeVenta-1.1.0.exe");
        assertThat(ActualizacionService.elegirActivo(soloX64, "Windows 10", "amd64")).contains("PuntoDeVenta-1.1.0.exe");
        assertThat(ActualizacionService.elegirActivo(soloX64, "Windows 10", "x86")).isEmpty();
        // Solo hay x86: la de 64 bits no toma el de 32
        assertThat(ActualizacionService.elegirActivo(List.of("PuntoDeVenta-1.1.0-win-x86.exe"), "Windows 10", "amd64")).isEmpty();
        // Un solo dmg sin arquitectura en el nombre sirve para cualquier Mac
        assertThat(ActualizacionService.elegirActivo(List.of("PuntoDeVenta-1.1.0.dmg"), "Mac OS X", "aarch64")).contains("PuntoDeVenta-1.1.0.dmg");
        assertThat(ActualizacionService.elegirActivo(List.of(), "Mac OS X", "aarch64")).isEmpty();
    }

    @Test
    void enLinuxEligeDebRpmOPortableSegunElGestorDePaquetes() {
        List<String> activos = List.of("PuntoDeVenta-1.1.0-windows-x64.exe", "PuntoDeVenta-1.1.0-macos-arm64.dmg",
                "PuntoDeVenta-1.1.0-linux-x64.deb", "PuntoDeVenta-1.1.0-linux-x64.rpm", "PuntoDeVenta-1.1.0-linux-x64.tar.gz",
                "SHA256SUMS.txt");
        Set<ActualizacionService.GestorPaquetes> dpkg = EnumSet.of(ActualizacionService.GestorPaquetes.DPKG);
        Set<ActualizacionService.GestorPaquetes> rpm = EnumSet.of(ActualizacionService.GestorPaquetes.RPM);
        Set<ActualizacionService.GestorPaquetes> ambos = EnumSet.allOf(ActualizacionService.GestorPaquetes.class);
        Set<ActualizacionService.GestorPaquetes> ninguno = EnumSet.noneOf(ActualizacionService.GestorPaquetes.class);

        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "amd64", dpkg)).contains("PuntoDeVenta-1.1.0-linux-x64.deb");
        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "amd64", rpm)).contains("PuntoDeVenta-1.1.0-linux-x64.rpm");
        // Ubuntu con el paquete "rpm" instalado sigue siendo Debian: gana el .deb
        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "amd64", ambos)).contains("PuntoDeVenta-1.1.0-linux-x64.deb");
        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "x86_64", ninguno)).contains("PuntoDeVenta-1.1.0-linux-x64.tar.gz");
        // Solo hay x64: en un Linux ARM no se ofrece nada (página de la versión)
        assertThat(ActualizacionService.elegirActivo(activos, "Linux", "aarch64", dpkg)).isEmpty();
        assertThat(ActualizacionService.elegirActivo(List.of("PuntoDeVenta-1.1.0-linux-arm64.deb"), "Linux", "aarch64", dpkg))
                .contains("PuntoDeVenta-1.1.0-linux-arm64.deb");
        // Un paquete sin arquitectura en el nombre, si es el único, se ofrece
        assertThat(ActualizacionService.elegirActivo(List.of("punto-de-venta_1.1.0.deb"), "Linux", "amd64", dpkg)).contains("punto-de-venta_1.1.0.deb");
        // Con dpkg pero sin .deb publicado se cae al portable, nunca al .rpm
        assertThat(ActualizacionService.elegirActivo(List.of("PuntoDeVenta-1.1.0-linux-x64.rpm", "PuntoDeVenta-1.1.0-linux-x64.tar.gz"),
                "Linux", "amd64", dpkg)).contains("PuntoDeVenta-1.1.0-linux-x64.tar.gz");
        // Los instaladores de Windows/macOS no se ofrecen en Linux
        assertThat(ActualizacionService.elegirActivo(List.of("PuntoDeVenta-1.1.0-windows-x64.exe", "PuntoDeVenta-1.1.0-macos-x64.dmg"),
                "Linux", "amd64", ambos)).isEmpty();
        // Los gestores de paquetes no influyen fuera de Linux
        assertThat(ActualizacionService.elegirActivo(activos, "Windows 10", "amd64", dpkg)).contains("PuntoDeVenta-1.1.0-windows-x64.exe");
        assertThat(ActualizacionService.gestoresInstalados()).isNotNull();

        String json = """
            [{"tag_name": "v1.1.0", "name": "1.1.0", "draft": false, "prerelease": false,
              "html_url": "https://github.com/Minepacman/Punto-Ventas/releases/tag/v1.1.0", "body": "",
              "assets": [
                {"name": "PuntoDeVenta-1.1.0-linux-x64.deb", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-linux-x64.deb"},
                {"name": "PuntoDeVenta-1.1.0-linux-x64.tar.gz", "browser_download_url": "https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/PuntoDeVenta-1.1.0-linux-x64.tar.gz"}
              ]}]
            """;
        // analizar usa los gestores del equipo donde corre la prueba: en Linux con dpkg ofrece el .deb; en los demás el portable
        Optional<ActualizacionService.Actualizacion> a = ActualizacionService.analizar(json, v("1.0.0"), false, "Linux", "amd64");
        assertThat(a).isPresent();
        String esperado = ActualizacionService.gestoresInstalados().contains(ActualizacionService.GestorPaquetes.DPKG)
                ? "PuntoDeVenta-1.1.0-linux-x64.deb" : "PuntoDeVenta-1.1.0-linux-x64.tar.gz";
        assertThat(a.get().urlDescarga()).contains("https://github.com/Minepacman/Punto-Ventas/releases/download/v1.1.0/" + esperado);
    }

    @Test
    void lasNotasSeRecortan() {
        String largas = "x".repeat(ActualizacionService.NOTAS_MAXIMO + 500);
        assertThat(ActualizacionService.recortar(largas)).hasSize(ActualizacionService.NOTAS_MAXIMO + 2).endsWith("…");
        assertThat(ActualizacionService.recortar(null)).isEmpty();
        assertThat(ActualizacionService.recortar("  hola\r\n ")).isEqualTo("hola");
    }

    @Test
    void soloSeAbrenEnlacesDelRepositorio() {
        assertThat(ActualizacionService.enlacePermitido("https://github.com/Minepacman/Punto-Ventas/releases/tag/v1.1.0")).isTrue();
        assertThat(ActualizacionService.enlacePermitido(ActualizacionService.PAGINA_RELEASES)).isTrue();
        for (String malo : new String[] {null, "", "http://github.com/Minepacman/Punto-Ventas/releases",
                "https://github.com/Otro/Punto-Ventas/releases", "https://github.com/Minepacman/Punto-Ventas-falso/x",
                "https://evil.example/https://github.com/Minepacman/Punto-Ventas/", "https://github.com/Minepacman/Punto-Ventas/../x",
                "https://github.com/Minepacman/Punto-Ventas/@evil", "https://github.com/Minepacman/Punto-Ventas/#x",
                "javascript:alert(1)"}) {
            assertThat(ActualizacionService.enlacePermitido(malo)).as(String.valueOf(malo)).isFalse();
        }

        // Una release cuyo instalador apunta fuera del repositorio: se ofrece la página, nunca ese archivo
        String ajeno = """
            [{"tag_name": "v9.0.0", "name": "x", "draft": false, "prerelease": false,
              "html_url": "https://evil.example/descarga", "body": "",
              "assets": [{"name": "PuntoDeVenta-9.0.0-windows-x64.exe", "browser_download_url": "https://evil.example/malo.exe"}]}]
            """;
        Optional<ActualizacionService.Actualizacion> a = ActualizacionService.analizar(ajeno, v("1.0.0"), false, "Windows 10", "amd64");
        assertThat(a).isPresent();
        assertThat(a.get().urlDescarga()).isEmpty();
        assertThat(a.get().urlPagina()).isEqualTo(ActualizacionService.PAGINA_RELEASES);
        assertThat(a.get().urlParaAbrir()).isEqualTo(ActualizacionService.PAGINA_RELEASES);
        assertThat(ActualizacionService.API_RELEASES).startsWith("https://api.github.com/repos/Minepacman/Punto-Ventas/");
        assertThat(ActualizacionService.URL_REVOCACIONES).startsWith("https://raw.githubusercontent.com/Minepacman/Punto-Ventas/");
    }

    @Test
    void lasNotasSonTextoPlanoSinCaracteresDeControl() {
        assertThat(ActualizacionService.recortar("hola\u0007 <b>x</b>\r\n\u001bfin")).isEqualTo("hola <b>x</b>\nfin");
    }
}
