package me.eliassanchezfernandez.puntodeventa;


import atlantafx.base.theme.PrimerDark;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Punto de arranque principal.
 *
 * Patrón: Spring Boot arranca primero y levanta el contexto (base de datos,
 * servicios, seguridad). Luego JavaFX toma el hilo de la UI.
 */

@SpringBootApplication
public class PuntoDeVentaApp extends Application {

    private ConfigurableApplicationContext springContext;

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
        // Aplicar tema moderno AtlantaFX (modo oscuro estilo GitHub)
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/main.fxml"));

        // Inyectar el contexto de Spring en los controladores FXML
        loader.setControllerFactory(springContext::getBean);

        Scene scene = new Scene(loader.load(), 1200, 720);

        primaryStage.setTitle("Punto de Venta");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(640);
        primaryStage.show();
    }

    // ── Cierre limpio ─────────────────────────────────────────────────────
    @Override
    public void stop() {
        springContext.close();
        Platform.exit();
    }
}