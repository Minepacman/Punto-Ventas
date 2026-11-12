package me.eliassanchezfernandez.puntodeventa;


import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import me.eliassanchezfernandez.puntodeventa.config.ArranqueDiferido;
import me.eliassanchezfernandez.puntodeventa.service.BaseDatosService;
import me.eliassanchezfernandez.puntodeventa.util.CalentadorCds;
import me.eliassanchezfernandez.puntodeventa.util.ConexionLocal;
import me.eliassanchezfernandez.puntodeventa.util.Navegador;
import me.eliassanchezfernandez.puntodeventa.util.PantallaConexion;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;



@SpringBootApplication

public class PuntoDeVentaApp extends Application {

    /** Cuánto esperar tras el login para generar el archivo CDS (no competir con el inicio de sesión) */
    static final long DEMORA_CDS_MS = Long.getLong("app.cds.demora-ms", 60_000L);

    private volatile ConfigurableApplicationContext springContext;

    /** Por qué no arrancó Spring (p. ej. el servidor PostgreSQL no respondió); null si arrancó */
    private Throwable errorArranque;

    /** El usuario cerró la ventana mientras Spring arrancaba: al terminar se cierra el contexto y listo */
    private boolean cerrando;

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

    /**
     * Abre el enlace con el comando del sistema ({@link Navegador}); HostServices solo como respaldo, porque
     * en el programa instalado en macOS no hace nada. Si nada funciona, muestra la dirección para copiarla.
     */
    public static void abrirEnlace(String url) {
        if (!Navegador.esSegura(url)) return;
        if (Navegador.abrir(url)) return;
        if (servicios != null) {
            try {
                servicios.showDocument(url);
                return;
            } catch (RuntimeException ignorada) {
                // sigue: se muestra la dirección
            }
        }
        Runnable aviso = () -> {
            javafx.scene.control.TextField campo = new javafx.scene.control.TextField(url);
            campo.setEditable(false);
            campo.setPrefColumnCount(40);
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION);
            a.setTitle("Abrir enlace");
            a.setHeaderText("No se pudo abrir el navegador. Copia esta dirección y pégala en tu navegador:");
            a.getDialogPane().setContent(campo);
            a.show();
        };
        if (Platform.isFxApplicationThread()) aviso.run(); else Platform.runLater(aviso);
    }

    // ── Arranque ─────────────────────────────────────────────────────────
    public static void main(String[] args) {
        launch(args); // delega a JavaFX que llama a init() → start()
    }

    /**
     * Spring NO arranca aquí. Antes lo hacía (en el hilo JavaFX-Launcher, antes de start()) y el
     * usuario no veía ninguna ventana durante todo el arranque (3 s en un equipo rápido, 10-15 s en
     * una PC vieja con disco duro). Ahora start() muestra una ventana ligera de inmediato y Spring
     * arranca en un hilo aparte (arrancarEnSegundoPlano), igual que ya se hacía la primera vez.
     */
    @Override
    public void init() {
        // nada
    }

    /**
     * Arranca el contexto. Si falla (lo normal: el servidor no responde) no se
     * relanza: se guarda la causa y la pantalla de error ofrece "Reintentar",
     * en lugar de un programa que se cierra sin explicación.
     */
    private void arrancarSpring() {
        try {
            ConfigurableApplicationContext ctx = SpringApplication.run(PuntoDeVentaApp.class);
            boolean cerrar;
            synchronized (this) {
                cerrar = cerrando;
                if (!cerrar) springContext = ctx;
            }
            if (cerrar) {
                ctx.close();         // la ventana se cerró mientras arrancaba
                return;
            }
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
        // Ventana inmediata ("Abriendo la tienda…") mientras Spring arranca en segundo plano
        Ventanas.mostrarArranque(primaryStage);
        primaryStage.setOnCloseRequest(e -> {
            synchronized (this) { cerrando = true; }
            Platform.exit();
        });
        // Mientras tanto el hilo de JavaFX precalienta los controles y las hojas de estilo del login
        Platform.runLater(Ventanas::precalentar);
        arrancarEnSegundoPlano(primaryStage);
    }

    /** Login si Spring arrancó; si no, la pantalla de "sin conexión" */
    private void mostrarSegunArranque(Stage stage) throws Exception {
        stage.setOnCloseRequest(null);
        if (springContext != null) {
            // La pantalla de login se abre con el contexto de Spring como
            // fábrica de controladores (ver Ventanas)
            Ventanas.mostrarLogin(stage, springContext);
            // Con la ventana dibujada arrancan las tareas diferidas (acceso móvil, migraciones, clima)
            Platform.runLater(ArranqueDiferido::senalarVentanaLista);
            CalentadorCds.programar(DEMORA_CDS_MS);
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
            synchronized (this) {
                if (cerrando) return;
            }
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
        synchronized (this) { cerrando = true; }
        // Guardar el ticket sin cobrar también al cerrar con la X o Cmd+Q (USR-15)
        Ventanas.ejecutarAccionAlCerrar();
        // Primero se oculta la ventana: el respaldo de cierre puede tardar en un disco lento
        // y el usuario ya no debe ver la aplicación "colgada".
        try {
            for (javafx.stage.Window w : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) w.hide();
        } catch (RuntimeException e) {
            System.err.println("No se pudieron ocultar las ventanas al cerrar: " + e.getMessage());
        }
        ConfigurableApplicationContext ctx = springContext;
        if (ctx == null) return;   // Spring aún no terminó: arrancarSpring() cerrará el contexto al acabar
        // Respaldo automático al cerrar (si está activo); un fallo no impide cerrar
        try {
            ctx.getBean(BaseDatosService.class).respaldoAutomaticoAlCerrar();
        } catch (Exception e) {
            System.err.println("Respaldo al cerrar: " + e.getMessage());
        }
        // stop() ya corre dentro del cierre de JavaFX: volver a llamar Platform.exit()
        // aquí provocaba un SIGSEGV en libglass (macOS) al cerrar la ventana.
        ctx.close();
    }
}
