package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manejador global de errores: registra todo, avisa una vez por error (el mismo error repetido
 * en menos de un minuto no vuelve a abrir la ventana) y la carpeta de registros vive en la de
 * datos de la aplicación. Sin JavaFX: el "mostrador" se sustituye por una lista.
 */
class RegistroErroresTest {

    private final Consumer<Throwable> mostradorOriginal = RegistroErrores.mostrador;

    @AfterEach
    void restaurar() {
        RegistroErrores.mostrador = mostradorOriginal;
        System.clearProperty(RegistroErrores.PROPIEDAD_CARPETA);
    }

    @Test
    void laCarpetaDeRegistrosEsLogsDentroDeLaCarpetaDeDatosSalvoPropiedadExplicita() {
        System.clearProperty(RegistroErrores.PROPIEDAD_CARPETA);
        assertThat(RegistroErrores.carpetaLogs()).isEqualTo(RutasApp.carpetaAplicacion().resolve("logs"));
        assertThat(RegistroErrores.archivoActual().getFileName().toString()).isEqualTo(RegistroErrores.ARCHIVO);

        System.setProperty(RegistroErrores.PROPIEDAD_CARPETA, "/tmp/otra/logs");
        assertThat(RegistroErrores.carpetaLogs()).isEqualTo(Path.of("/tmp/otra/logs"));
    }

    @Test
    void elMismoErrorNoVuelveAAvisarDentroDeLaVentanaPeroUnoDistintoSi() {
        RegistroErrores.Acelerador a = new RegistroErrores.Acelerador(60_000);
        assertThat(a.debeAvisar("x", 0)).isTrue();
        assertThat(a.debeAvisar("x", 1_000)).isFalse();       // repetido enseguida
        assertThat(a.debeAvisar("y", 1_000)).isTrue();        // otro error sí avisa
        assertThat(a.debeAvisar("x", 59_999)).isFalse();
        assertThat(a.debeAvisar("x", 60_000)).isTrue();       // pasó la ventana
        assertThat(a.debeAvisar("x", 60_001)).isFalse();
    }

    @Test
    void laClaveDelErrorEsLaCausaRaizConSuMensajeYSuPrimeraLinea() {
        IllegalStateException raiz = new IllegalStateException("se rompió");
        RuntimeException envuelta = new RuntimeException("envoltura", raiz);
        assertThat(RegistroErrores.claveDe(envuelta)).isEqualTo(RegistroErrores.claveDe(raiz))
                .startsWith("java.lang.IllegalStateException|se rompió|");
        // la misma excepción lanzada desde otra línea es "otro" error
        assertThat(RegistroErrores.claveDe(new IllegalStateException("se rompió")))
                .isNotEqualTo(RegistroErrores.claveDe(raiz));
    }

    @Test
    void manejarRegistraYAvisaUnaSolaVezPorErrorRepetido() {
        List<Throwable> avisados = new CopyOnWriteArrayList<>();
        RegistroErrores.mostrador = avisados::add;
        // Misma excepción (misma pila) lanzada varias veces seguidas: una ventana
        IllegalArgumentException e = new IllegalArgumentException("precio negativo");
        RegistroErrores.manejar(Thread.currentThread(), e);
        RegistroErrores.manejar(Thread.currentThread(), e);
        RegistroErrores.manejar(Thread.currentThread(), e);
        assertThat(avisados).hasSize(1);
        // Si el aviso falla (sin JavaFX, por ejemplo) no se propaga
        RegistroErrores.mostrador = t -> { throw new IllegalStateException("Toolkit not initialized"); };
        RegistroErrores.manejar(Thread.currentThread(), new ArithmeticException("división"));
        // ThreadDeath y null se ignoran sin tocar nada
        RegistroErrores.mostrador = avisados::add;
        RegistroErrores.manejar(Thread.currentThread(), null);
        assertThat(avisados).hasSize(1);
    }

    @Test
    void elMensajeAlUsuarioNoTraePilaPeroSiLaCausaYLaCarpeta() {
        System.setProperty(RegistroErrores.PROPIEDAD_CARPETA, "/tmp/cl/logs");
        String msg = RegistroErrores.mensajeAviso(new RuntimeException("afuera", new IllegalStateException("la causa real")));
        assertThat(msg).contains("la causa real").contains(java.nio.file.Path.of("/tmp/cl/logs").toString()).contains("Pedir soporte")
                .doesNotContain("at ").doesNotContain("Exception");
    }

    @Test
    void loQueSeEscribeEnSystemErrSigueSaliendoYSeRegistraLineaPorLinea() throws Exception {
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        RegistroErrores.SalidaRegistrada salida = new RegistroErrores.SalidaRegistrada(original, StandardCharsets.UTF_8);
        try (PrintStream ps = new PrintStream(salida, true, StandardCharsets.UTF_8)) {
            ps.println("Respaldo automático fallido: disco lleno");
            ps.print("sin salto");
            ps.print("\r\n");
            ps.println("");    // una línea vacía no se registra, pero sí sale
        }
        assertThat(original.toString(StandardCharsets.UTF_8))
                .isEqualTo("Respaldo automático fallido: disco lleno" + System.lineSeparator() + "sin salto\r\n" + System.lineSeparator());   // println usa el salto del sistema (\r\n en Windows)
    }
}
