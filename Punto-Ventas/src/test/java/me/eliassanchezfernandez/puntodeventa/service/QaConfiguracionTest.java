package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.util.Donacion;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA v1.0.0 — defectos (ya corregidos) en Configuración, respaldos y bienvenida.
 * Sin transacción envolvente (igual que BaseDatosServiceTest: VACUUM INTO no corre dentro de una).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QaConfiguracionTest {

    @Autowired private BaseDatosService baseDatosService;
    @Autowired private SesionService    sesionService;
    @Autowired private VentaService     ventaService;
    @Autowired private ProductoService  productoService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private RutasApp         rutas;

    @AfterEach
    void limpiar() throws Exception {
        Files.deleteIfExists(rutas.archivoRestauracionPendiente());
        sesionService.cerrarSesion();
        sesionService.reiniciarLimiteTerminalParaPruebas();
    }

    private Cajero entrarComoAdmin() throws Exception {
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow();
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();
        return sesionService.autenticar(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
    }

    /**
     * M-16 (medio). En macOS/Windows el sistema de archivos no distingue
     * mayúsculas: "DATOS.DB" es el mismo archivo que "datos.db". "Respaldar
     * ahora" comparaba rutas con equals() y aceptaba el destino, borraba la base
     * en uso y la recreaba con VACUUM INTO: lo vendido después iba a un archivo
     * huérfano. Ahora se compara con isSameFile y por nombre sin mayúsculas.
     */
    @Test
    void respaldarSobreLaBaseEnUsoConOtrasMayusculasDebeRechazarse() throws Exception {
        entrarComoAdmin();
        Path bd = rutas.archivoBd();
        Path alias = bd.resolveSibling(bd.getFileName().toString().toUpperCase());
        // Por nombre (misma carpeta, mayúsculas distintas) se rechaza en cualquier sistema de archivos
        assertThatThrownBy(() -> baseDatosService.respaldar(alias))
                .as("el respaldo no puede apuntar a la base de datos abierta, con cualquier capitalización")
                .isInstanceOf(NegocioException.class).hasMessageContaining("en uso");
        // Tampoco sus archivos auxiliares
        assertThatThrownBy(() -> baseDatosService.respaldar(bd.resolveSibling(bd.getFileName() + "-wal")))
                .isInstanceOf(NegocioException.class);
        Assumptions.assumeTrue(Files.exists(alias) && Files.isSameFile(alias, bd),
                "la comprobación por isSameFile solo aplica en sistemas de archivos sin distinción de mayúsculas");
        assertThat(baseDatosService.esLaBaseEnUso(alias)).isTrue();
    }

    /** El respaldo manual se escribe entero o no se escribe (temporal + move) y sigue funcionando a otra carpeta */
    @Test
    void elRespaldoManualAOtraCarpetaSigueFuncionando(@TempDir Path dir) throws Exception {
        entrarComoAdmin();
        Path destino = dir.resolve("respaldo-qa.db");
        assertThat(baseDatosService.respaldar(destino)).isEqualTo(destino.toAbsolutePath());
        assertThat(Files.isRegularFile(destino)).isTrue();
        assertThat(Files.exists(dir.resolve("respaldo-qa.db.tmp"))).isFalse();
    }

    /**
     * A-3 (alto). Tras "Restaurar respaldo" el programa dejaba seguir vendiendo y
     * esas ventas se perdían al reiniciar (el respaldo reemplaza la base). Ahora:
     * con una restauración pendiente, cobrar se rechaza con un mensaje claro
     * (y la pantalla ofrece cerrar el programa de inmediato).
     */
    @Test
    void conUnaRestauracionPendienteNoSePuedeCobrar() throws Exception {
        entrarComoAdmin();
        Producto p = new Producto();
        p.setCodigoBarras("QA-REST-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion("Jabón QA");
        p.setPrecioVenta(10);
        p.setUsaInventario(false);
        p = productoService.guardar(p);
        Producto producto = p;

        Files.writeString(rutas.archivoRestauracionPendiente(), "pendiente");   // lo que deja BaseDatosService.restaurar
        assertThat(rutas.hayRestauracionPendiente()).isTrue();
        assertThat(baseDatosService.hayRestauracionPendiente()).isTrue();
        assertThatThrownBy(() -> ventaService.cobrar(List.of(ventaService.crearDetalle(producto, 1)), Venta.FormaPago.EFECTIVO, 10))
                .isInstanceOf(NegocioException.class).hasMessageContaining("respaldo pendiente");

        Files.delete(rutas.archivoRestauracionPendiente());
        assertThat(rutas.hayRestauracionPendiente()).isFalse();
    }

    /**
     * A-3 (alto), segunda parte. Al aplicar el respaldo pendiente en el arranque
     * se toma una copia de seguridad de la base que se reemplaza EN ESE MOMENTO
     * (no al pulsar Restaurar): lo registrado entre tanto queda en esa copia.
     */
    @Test
    void aplicarLaRestauracionPendienteGuardaCopiaDeLaBaseQueReemplaza(@TempDir Path dir) throws Exception {
        Path bd = dir.resolve("datos.db");
        Files.writeString(bd, "SQLite format 3\0base-actual-con-ventas-posteriores");
        Path pendiente = RutasApp.hermano(bd, ".restaurar");
        Files.writeString(pendiente, "SQLite format 3\0respaldo-restaurado");

        assertThat(RutasApp.aplicarRestauracionPendiente(bd)).isTrue();

        assertThat(Files.readString(bd)).contains("respaldo-restaurado");
        assertThat(Files.exists(pendiente)).isFalse();
        try (var archivos = Files.list(dir)) {
            List<Path> copias = archivos.filter(a -> a.getFileName().toString().startsWith("datos.db.antes-de-aplicar-")).toList();
            assertThat(copias).hasSize(1);
            assertThat(Files.readString(copias.get(0))).contains("base-actual-con-ventas-posteriores");
        }
    }

    /**
     * M-19 (medio). Mientras donacion.properties siga con CLABE/banco en
     * PENDIENTE, la ventana de donación se mostraba al administrador en cada
     * entrada y desbloqueo, bloqueando la caja 40 s para pedir un donativo que no
     * se puede hacer. Ahora sin CLABE no se agrega el paso DONACION.
     */
    @Test
    void sinClabeConfiguradaNoDebeMostrarseLaVentanaDeDonacion() {
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, false, false))
                .as("sin CLABE no hay a dónde donar: el paso DONACION no debe aparecer")
                .doesNotContain(BienvenidaService.Paso.DONACION);
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, false, true))
                .containsExactly(BienvenidaService.Paso.DONACION);

        Donacion.Datos datos = Donacion.cargar();
        Assumptions.assumeFalse(datos.clabeConfigurada(), "la CLABE ya está configurada: la regla no aplica");
        assertThat(BienvenidaService.pasos(Cajero.Rol.ADMIN, true, true, false))
                .doesNotContain(BienvenidaService.Paso.DONACION);
    }
}
