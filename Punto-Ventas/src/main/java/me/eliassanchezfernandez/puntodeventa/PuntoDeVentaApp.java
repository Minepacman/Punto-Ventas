package me.eliassanchezfernandez.puntodeventa;


import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.PantallaConexion;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;



@SpringBootApplication

public class PuntoDeVentaApp extends Application {

    private ConfigurableApplicationContext springContext;

    /** Por qué no arrancó Spring (p. ej. el servidor PostgreSQL no respondió); null si arrancó */
    private Throwable errorArranque;

    /** true cuando no existe conexion.properties: la primera vez se pregunta cómo se usará esta caja */
    private static volatile boolean primeraVez;

    /** Lo marca el Launcher antes de arrancar JavaFX */
    public static void marcarPrimeraVez() {
        primeraVez = true;
    }

    public static boolean esPrimeraVez() {
        return primeraVez;
    }

    /** Para abrir enlaces en el navegador (Configuración → Actualizaciones) */
    private static volatile javafx.application.HostServices servicios;

    public static void abrirEnlace(String url) {
        if (servicios != null) servicios.showDocument(url);
    }

    // ── Spring Boot: arranque antes de JavaFX ─────────────────────────────
    public static void main(String[] args) {
        launch(args); // delega a JavaFX que llama a init() → start()
    }

    @Override
    public void init() {
        // Se ejecuta en el hilo de JavaFX Launcher, ANTES de start().
        // La primera vez todavía no se sabe contra qué base trabajar: se pregunta en start().
        if (primeraVez) return;
        arrancarSpring();
    }

    /**
     * Arranca el contexto. Si falla (lo normal: el servidor no responde) no se
     * relanza: se guarda la causa y start() muestra la pantalla de error con
     * "Reintentar", en lugar de un programa que se cierra sin explicación.
     */
    private void arrancarSpring() {
        try {
            springContext = SpringApplication.run(PuntoDeVentaApp.class);
            errorArranque = null;
        } catch (Throwable t) {
            springContext = null;
            errorArranque = t;
            System.err.println("No se pudo iniciar la base de datos: " + PantallaConexion.causaRaiz(t));
        }
    }

    // ── JavaFX: construir la ventana principal ────────────────────────────
    @Override
    public void start(Stage primaryStage) throws Exception {
        servicios = getHostServices();

        // Aplicar tema moderno AtlantaFX (modo oscuro estilo GitHub)
        //Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        //Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        //Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()); //agregar funcion para que el usuario pueda elegir el tema (detalles del codigo extras)

        if (primeraVez) {
            // ¿Una sola caja o varias cajas? Se guarda y se arranca con esa configuración
            Path carpeta = RutasApp.carpetaAplicacion();
            ConexionLocal elegida = PantallaConexion.elegirPrimeraVez(primaryStage, carpeta);
            primeraVez = false;
            RutasApp.prepararUrl(new String[0], elegida);
            arrancarEnSegundoPlano(primaryStage);
            return;
        }
        mostrarSegunArranque(primaryStage);
    }

    /** Login si Spring arrancó; si no, la pantalla de "sin conexión" */
    private void mostrarSegunArranque(Stage stage) throws Exception {
        if (springContext != null) {
            // La pantalla de login se abre con el contexto de Spring como
            // fábrica de controladores (ver Ventanas)
            Ventanas.mostrarLogin(stage, springContext);
            return;
        }
        ConexionLocal conexion = ConexionLocal.cargar(RutasApp.carpetaAplicacion());
        if (!conexion.esPostgresql()) {
            // Con el archivo local un fallo es raro (disco lleno, archivo dañado): se informa y se cierra
            Ventanas.alerta(javafx.scene.control.Alert.AlertType.ERROR, "No se pudo abrir la base de datos",
                    "El programa no pudo abrir su base de datos:\n" + PantallaConexion.causaRaiz(errorArranque)
                    + "\n\nRevisa el espacio en disco y los permisos de la carpeta de datos, o restaura un respaldo.");
            Platform.exit();
            return;
        }
        PantallaConexion.mostrarSinConexion(stage, conexion, errorArranque,
                () -> arrancarEnSegundoPlano(stage),
                nueva -> {
                    // Los datos nuevos del servidor se aplican sin reiniciar: Spring aún no ha arrancado
                    RutasApp.prepararUrl(new String[0], nueva);
                    arrancarEnSegundoPlano(stage);
                });
    }

    /** Arranca Spring fuera del hilo de JavaFX (puede tardar) y luego muestra el login o el error */
    private void arrancarEnSegundoPlano(Stage stage) {
        // Mientras Spring arranca puede no haber ninguna ventana visible (p. ej. al cerrar la
        // pregunta de la primera vez): sin esto JavaFX daría la aplicación por terminada.
        Platform.setImplicitExit(false);
        Thread t = new Thread(() -> {
            arrancarSpring();
            Platform.runLater(() -> {
                try {
                    mostrarSegunArranque(stage);
                } catch (Exception e) {
                    e.printStackTrace();
                    Ventanas.alerta(javafx.scene.control.Alert.AlertType.ERROR, "Error",
                            "No se pudo abrir la pantalla de inicio de sesión: " + e.getMessage());
                } finally {
                    Platform.setImplicitExit(true);
                }
            });
        }, "arranque-spring");
        t.setDaemon(true);
        t.start();
    }

    // ── Cierre limpio ─────────────────────────────────────────────────────
    @Override
    public void stop() {
        // Guardar el ticket sin cobrar también al cerrar con la X o Cmd+Q (USR-15)
        Ventanas.ejecutarAccionAlCerrar();
        // Primero se oculta la ventana: el respaldo de cierre puede tardar en un disco lento
        // y el usuario ya no debe ver la aplicación "colgada".
        try {
            for (javafx.stage.Window w : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) w.hide();
        } catch (RuntimeException e) {
            System.err.println("No se pudieron ocultar las ventanas al cerrar: " + e.getMessage());
        }
        if (springContext == null) return;
        // Respaldo automático al cerrar (si está activo); un fallo no impide cerrar
        try {
            springContext.getBean(BaseDatosService.class).respaldoAutomaticoAlCerrar();
        } catch (Exception e) {
            System.err.println("Respaldo al cerrar: " + e.getMessage());
        }
        // stop() ya corre dentro del cierre de JavaFX: volver a llamar Platform.exit()
        // aquí provocaba un SIGSEGV en libglass (macOS) al cerrar la ventana.
        springContext.close();
    }
}
