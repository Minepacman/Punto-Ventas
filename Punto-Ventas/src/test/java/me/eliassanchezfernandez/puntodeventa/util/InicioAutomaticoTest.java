package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InicioAutomaticoTest {

    @Test
    void sistemaPorNombre() {
        assertThat(InicioAutomatico.Sistema.de("Windows 10")).isEqualTo(InicioAutomatico.Sistema.WINDOWS);
        assertThat(InicioAutomatico.Sistema.de("Mac OS X")).isEqualTo(InicioAutomatico.Sistema.MAC);
        assertThat(InicioAutomatico.Sistema.de("Linux")).isEqualTo(InicioAutomatico.Sistema.LINUX);
        assertThat(InicioAutomatico.Sistema.de(null)).isEqualTo(InicioAutomatico.Sistema.OTRO);
    }

    @Test
    void decisionDeFabricaVaciaYLuegoLaGuardada(@TempDir Path dir) {
        assertThat(InicioAutomatico.decidido(dir)).isEmpty();
        assertThat(InicioAutomatico.guardarDecision(dir, false)).isTrue();
        assertThat(InicioAutomatico.decidido(dir)).contains(false);
        InicioAutomatico.guardarDecision(dir, true);
        assertThat(InicioAutomatico.decidido(dir)).contains(true);
    }

    @Test
    void macEscribeYBorraElLaunchAgentConElPaqueteApp(@TempDir Path casa) throws Exception {
        Path lanzador = Path.of("/Applications/PuntoDeVenta.app/Contents/MacOS/PuntoDeVenta");
        var e = new InicioAutomatico.Entorno(InicioAutomatico.Sistema.MAC, casa, null);
        assertThat(InicioAutomatico.aplicar(true, lanzador, e)).isNull();
        Path plist = casa.resolve("Library/LaunchAgents/me.eliassanchezfernandez.puntodeventa.plist");
        String contenido = Files.readString(plist);
        assertThat(contenido).contains("<string>/Applications/PuntoDeVenta.app</string>").contains("<key>RunAtLoad</key>")
                .contains("/usr/bin/open");
        assertThat(InicioAutomatico.registrado(e)).isTrue();
        assertThat(InicioAutomatico.aplicar(false, lanzador, e)).isNull();
        assertThat(plist).doesNotExist();
        assertThat(InicioAutomatico.aplicar(true, Path.of("/sin/paquete/PuntoDeVenta"), e)).contains(".app");
    }

    @Test
    void linuxUsaXdgAutostart(@TempDir Path casa) throws Exception {
        Path lanzador = Path.of("/opt/punto-de-venta/bin/PuntoDeVenta");
        var e = new InicioAutomatico.Entorno(InicioAutomatico.Sistema.LINUX, casa, null);
        assertThat(InicioAutomatico.aplicar(true, lanzador, e)).isNull();
        Path desktop = casa.resolve(".config/autostart/caja-libre.desktop");
        assertThat(Files.readString(desktop)).contains("Exec=\"/opt/punto-de-venta/bin/PuntoDeVenta\"")
                .contains("Name=" + Marca.NOMBRE).startsWith("[Desktop Entry]");
        var xdg = new InicioAutomatico.Entorno(InicioAutomatico.Sistema.LINUX, casa, casa.resolve("cfg").toString());
        InicioAutomatico.aplicar(true, lanzador, xdg);
        assertThat(casa.resolve("cfg/autostart/caja-libre.desktop")).exists();
        InicioAutomatico.aplicar(false, lanzador, e);
        assertThat(desktop).doesNotExist();
    }

    @Test
    void windowsRegistraLaRutaEntreComillasEnRunDelUsuario() {
        var cmd = InicioAutomatico.comandoRegistrar(Path.of("C:\\Users\\Juan Perez\\AppData\\Local\\PuntoDeVenta\\PuntoDeVenta.exe"));
        assertThat(cmd).startsWith("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "CajaLibre");
        assertThat(cmd).contains("\\\"C:\\Users\\Juan Perez\\AppData\\Local\\PuntoDeVenta\\PuntoDeVenta.exe\\\"", "/f");
        assertThat(InicioAutomatico.comandoQuitar()).containsExactly("reg", "delete", InicioAutomatico.CLAVE_REGISTRO, "/v", "CajaLibre", "/f");
    }

    @Test
    void sinProgramaInstaladoNoHaceNada(@TempDir Path dir) {
        // en las pruebas no existe jpackage.app-path
        InicioAutomatico.alArrancar(dir);
        assertThat(InicioAutomatico.decidido(dir)).isEmpty();
    }
}
