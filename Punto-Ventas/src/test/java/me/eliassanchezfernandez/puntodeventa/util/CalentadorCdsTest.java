package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Decisiones del generador del archivo CDS, sin lanzar procesos */
class CalentadorCdsTest {

    @Test
    void fueraDelInstaladorNoAplica() {
        assertThat(CalentadorCds.aplica()).isFalse();
        assertThat(CalentadorCds.esProcesoCalentador()).isFalse();
    }

    @Test
    void laCarpetaDeLaAppEsLaDelPrimerJarDelClasspath(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("app").resolve("PuntoDeVenta.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "x");
        String cp = jar + File.pathSeparator + dir.resolve("otro.jar");
        assertThat(CalentadorCds.carpetaApp(cp)).contains(jar.getParent().toAbsolutePath());
        assertThat(CalentadorCds.carpetaApp("")).isEmpty();
        assertThat(CalentadorCds.carpetaApp(null)).isEmpty();
    }

    @Test
    void generaSoloSiFaltaElArchivoOCambioLaVersionYNoSeAgotaronLosIntentos(@TempDir Path dir) throws Exception {
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.0")).isTrue();

        new CalentadorCds.Marca("1.0.0", 1).escribir(dir.resolve(CalentadorCds.MARCA));
        Files.writeString(dir.resolve(CalentadorCds.ARCHIVO), "archivo");
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.0")).isFalse();     // ya está
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.1")).isTrue();      // versión nueva

        Files.delete(dir.resolve(CalentadorCds.ARCHIVO));
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.0")).isTrue();      // falló una vez: se reintenta
        new CalentadorCds.Marca("1.0.0", CalentadorCds.INTENTOS_MAXIMOS).escribir(dir.resolve(CalentadorCds.MARCA));
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.0")).isFalse();     // sin más intentos en esta versión
        assertThat(CalentadorCds.necesitaGenerar(dir, "1.0.1")).isTrue();
    }

    @Test
    void unaMarcaIlegibleCuentaComoSinMarca(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(CalentadorCds.MARCA), "version=1.0.0\nintentos=muchos\n");
        assertThat(CalentadorCds.Marca.leer(dir.resolve(CalentadorCds.MARCA))).isEqualTo(new CalentadorCds.Marca("1.0.0", 0));
        assertThat(CalentadorCds.Marca.leer(dir.resolve("no-existe"))).isEqualTo(new CalentadorCds.Marca("", 0));
    }

    @Test
    void fueraDelInstaladorLanzaElJavaDelRuntimeConElClasspathActual() {
        var inv = CalentadorCds.invocacion(java.util.List.of("-Xshare:off", "-XX:DumpLoadedClassList=x"), true);
        assertThat(inv.comando().get(0)).isEqualTo(CalentadorCds.javaEjecutable().toString());
        assertThat(Files.isRegularFile(CalentadorCds.javaEjecutable())).isTrue();
        assertThat(inv.comando()).contains("-Xshare:off", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-cp",
                        System.getProperty("java.class.path"))
                .last().isEqualTo("me.eliassanchezfernandez.puntodeventa.Launcher");
        assertThat(inv.entorno()).isEmpty();
        assertThat(CalentadorCds.invocacion(java.util.List.of("-Xshare:dump"), false).comando())
                .doesNotContain("me.eliassanchezfernandez.puntodeventa.Launcher");
    }

    @Test
    void instaladoVuelveALanzarElLanzadorDeJpackageConLasOpcionesEnElEntorno(@TempDir Path dir) throws Exception {
        Path lanzador = dir.resolve("PuntoDeVenta");
        Files.writeString(lanzador, "x");
        System.setProperty("jpackage.app-path", lanzador.toString());
        try {
            var inv = CalentadorCds.invocacion(java.util.List.of("-Xshare:dump", "-XX:SharedClassListFile=" + dir.resolve("con espacio.lst")), false);
            assertThat(inv.comando()).containsExactly(lanzador.toString());
            assertThat(inv.entorno().get("JAVA_TOOL_OPTIONS"))
                    .isEqualTo("-Xshare:dump \"-XX:SharedClassListFile=" + dir.resolve("con espacio.lst") + "\"");
        } finally {
            System.clearProperty("jpackage.app-path");
        }
    }
}
