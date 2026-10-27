package me.eliassanchezfernandez.puntodeventa;


import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;



@SpringBootApplication

public class PuntoDeVentaApp extends Application {

    private ConfigurableApplicationContext springContext;

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
        // Se ejecuta en el hilo de JavaFX Launcher, ANTES de start()
        springContext = SpringApplication.run(PuntoDeVentaApp.class);

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

        // La pantalla de login se abre con el contexto de Spring como
        // fábrica de controladores (ver Ventanas)
        Ventanas.mostrarLogin(primaryStage, springContext);
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
