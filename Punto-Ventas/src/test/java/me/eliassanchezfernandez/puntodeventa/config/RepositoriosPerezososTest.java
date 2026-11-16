package me.eliassanchezfernandez.puntodeventa.config;

import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionService;
import me.eliassanchezfernandez.puntodeventa.service.PromocionService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.repository.Repository;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El programa arranca con {@code spring.data.jpa.repositories.bootstrap-mode=lazy} (los repositorios se crean al
 * primer uso, ver rendimiento-1.0.5.md) y {@link PrecalentarRepositorios} crea los demás en el hilo diferido.
 * Las demás pruebas usan el modo de siempre (application.properties de pruebas): esta cubre el camino real.
 */
class RepositoriosPerezososTest {

    @TempDir Path dir;

    @Test
    void elProgramaUsaRepositoriosPerezososYElHiloDiferidoLosCreaTodos() throws Exception {
        Properties principal = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            principal.load(in);
        }
        assertThat(principal.getProperty("spring.data.jpa.repositories.bootstrap-mode")).isEqualTo("lazy");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(PuntoDeVentaApp.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:sqlite:" + dir.resolve("datos.db"),
                        "--spring.jpa.hibernate.ddl-auto=update",
                        "--spring.data.jpa.repositories.bootstrap-mode=lazy",
                        "--app.arranque.diferido=true",
                        "--app.arranque.diferido-espera-ms=0",
                        "--app.datos-dir=" + dir.resolve("datos"),
                        "--app.claves-dir=" + dir.resolve("claves"))) {
            // Mientras el hilo diferido crea repositorios, la "pantalla" ya los usa: Spring serializa la creación
            Cajero admin = ctx.getBean(SesionService.class).autenticar("admin", "admin123");
            assertThat(admin.getUsuario()).isEqualTo("admin");
            assertThat(ctx.getBean(ConfiguracionService.class).obtener()).isNotNull();
            assertThat(ctx.getBean(PromocionService.class).listarTodas()).isEmpty();

            assertThat(ctx.getBean(ArranqueDiferido.class).esperar(60_000)).as("el hilo diferido terminó").isTrue();
            int repositorios = ctx.getBean(PrecalentarRepositorios.class).precalentar();
            assertThat(repositorios).isGreaterThanOrEqualTo(30);
            assertThat(ctx.getBeansOfType(Repository.class)).hasSize(repositorios);
        }
    }
}
