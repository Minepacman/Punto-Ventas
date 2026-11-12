package me.eliassanchezfernandez.puntodeventa;

import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.Platform;
import me.eliassanchezfernandez.puntodeventa.util.CalentadorCds;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Proceso hijo del {@link CalentadorCds} (paso 1): carga las mismas clases
 * que un arranque normal (Spring + Hibernate contra una base SQLite
 * temporal, JavaFX con el tema, app.css y los controles habituales) SIN
 * tocar la base real ni mostrar ventanas, y sale. La JVM, arrancada con
 * {@code -XX:DumpLoadedClassList}, deja la lista de clases con la que se
 * vuelca el archivo CDS.
 *
 * Todo es "lo mejor posible": cualquier fallo solo deja una lista más corta.
 */
final class CalentadorArranque {

    private CalentadorArranque() { }

    static void calentarYSalir(String[] args) {
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);   // no estorbar a la caja
        Path bd = null;
        ConfigurableApplicationContext ctx = null;
        try {
            bd = Files.createTempFile("pos-calentar-", ".db");
            Files.deleteIfExists(bd);                 // SQLite lo crea; solo queremos una ruta libre
            System.setProperty("spring.datasource.url", "jdbc:sqlite:" + bd.toAbsolutePath());
            System.setProperty("spring.datasource.driver-class-name", "org.sqlite.JDBC");
            System.setProperty("spring.jpa.database-platform", "org.hibernate.community.dialect.SQLiteDialect");
            System.setProperty("spring.jpa.hibernate.ddl-auto", "update");
            System.setProperty("app.movil.autoarranque", "false");
            System.setProperty("app.estadisticas.jobs", "false");
            System.setProperty("app.proveedores.migrar", "false");
            System.setProperty("app.arranque.diferido", "false");
            System.setProperty("app.datos-dir", bd.toAbsolutePath() + "-datos");

            SpringApplication app = new SpringApplication(PuntoDeVentaApp.class);
            app.setHeadless(true);
            ctx = app.run(args);
            calentarJavaFx();
        } catch (Throwable t) {
            System.err.println("Calentador CDS: " + t);
        } finally {
            try {
                if (ctx != null) ctx.close();
            } catch (RuntimeException ignorada) { /* salimos igual */ }
            borrar(bd);
            System.exit(0);
        }
    }

    /** Toolkit, tema, app.css, FXML y los controles de las pantallas; nada se muestra */
    private static void calentarJavaFx() throws InterruptedException {
        CountDownLatch listo = new CountDownLatch(1);
        try {
            Platform.setImplicitExit(false);
            Platform.startup(() -> {
                try {
                    Application.setUserAgentStylesheet(new NordLight().getUserAgentStylesheet());
                    Ventanas.precalentar();
                    javafx.fxml.FXMLLoader cargador = new javafx.fxml.FXMLLoader(
                            Ventanas.class.getResource("/fxml/arranque.fxml"));
                    cargador.load();
                } catch (Throwable t) {
                    System.err.println("Calentador CDS (JavaFX): " + t);
                } finally {
                    listo.countDown();
                }
            });
        } catch (Throwable t) {       // sin pantalla / sin JavaFX: la lista queda sin esas clases
            System.err.println("Calentador CDS (toolkit): " + t);
            return;
        }
        listo.await(60, TimeUnit.SECONDS);
        try {
            Platform.exit();
        } catch (RuntimeException ignorada) { /* ya salimos */ }
    }

    private static void borrar(Path bd) {
        if (bd == null) return;
        for (String sufijo : new String[] {"", "-wal", "-shm", "-journal"}) {
            try {
                Files.deleteIfExists(Path.of(bd + sufijo));
            } catch (Exception ignorada) { /* temporal */ }
        }
        try {
            Path datos = Path.of(bd + "-datos");
            if (Files.isDirectory(datos)) {
                try (var s = Files.walk(datos)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignorada) { /* temporal */ }
                    });
                }
            }
        } catch (Exception ignorada) { /* temporal */ }
    }
}
