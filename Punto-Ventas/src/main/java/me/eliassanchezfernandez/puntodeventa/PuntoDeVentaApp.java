package me.eliassanchezfernandez.puntodeventa;


import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;



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
        //Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        //Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        //Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());
        Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet()); //agregar funcion para que el usuario pueda elegir el tema (detalles del codigo extras)

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/fxml/login.fxml"));

        // Inyectar el contexto de Spring en los controladores FXML
        loader.setControllerFactory(springContext::getBean);

        Scene scene = new Scene(loader.load(), 300, 400);

        primaryStage.setTitle("Punto de Venta");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    // ── Cierre limpio ─────────────────────────────────────────────────────
    @Override
    public void stop() {
        springContext.close();
        Platform.exit();
    }
}