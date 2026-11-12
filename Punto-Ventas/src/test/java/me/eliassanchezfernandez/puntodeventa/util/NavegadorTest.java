package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NavegadorTest {

    @Test
    void soloAceptaHttpsYMailto() {
        assertThat(Navegador.esSegura("https://github.com/Minepacman/Punto-Ventas/releases/download/v1.0.2/x.dmg")).isTrue();
        assertThat(Navegador.esSegura("https://wa.me/525512345678?text=hola%20mundo")).isTrue();
        assertThat(Navegador.esSegura("mailto:soporte@example.com?subject=Hola")).isTrue();
        assertThat(Navegador.esSegura("http://github.com")).isFalse();
        assertThat(Navegador.esSegura("file:///etc/passwd")).isFalse();
        assertThat(Navegador.esSegura("javascript:alert(1)")).isFalse();
        assertThat(Navegador.esSegura("/Applications/Calculator.app")).isFalse();
        assertThat(Navegador.esSegura("https://github.com/\nrm -rf")).isFalse();
        assertThat(Navegador.esSegura(null)).isFalse();
        assertThat(Navegador.esSegura("https:///sinhost")).isFalse();
    }

    @Test
    void usaElComandoDeCadaSistemaConLaUrlComoArgumentoAparte() {
        String url = "https://github.com/a b";
        assertThat(Navegador.comando("Mac OS X", url)).contains(List.of("open", url));
        assertThat(Navegador.comando("Windows 10", url)).contains(List.of("rundll32", "url.dll,FileProtocolHandler", url));
        assertThat(Navegador.comando("Linux", url)).contains(List.of("xdg-open", url));
        assertThat(Navegador.comando("SunOS", url)).isEmpty();
    }
}
