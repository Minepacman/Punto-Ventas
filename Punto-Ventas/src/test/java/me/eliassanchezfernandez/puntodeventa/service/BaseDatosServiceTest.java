package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Respaldo, restauración y respaldo automático contra archivos temporales.
 * Sin transacción envolvente (VACUUM INTO no puede correr dentro de una) y con
 * contexto propio, igual que IntegridadSinTransaccionTest. Nunca toca datos.db:
 * la BD es la desechable de src/test/resources/application.properties.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BaseDatosServiceTest {

    @Autowired private BaseDatosService     baseDatosService;
    @Autowired private ConfiguracionService configuracionService;
    @Autowired private SesionService        sesionService;
    @Autowired private ProductoService      productoService;
    @Autowired private CajeroRepository     cajeroRepo;
    @Autowired private CajeroService        cajeroService;
    @Autowired private RutasApp             rutas;

    @TempDir Path temporal;

    @AfterEach
    void limpiar() throws Exception {
        sesionService.cerrarSesion();
        Files.deleteIfExists(rutas.archivoRestauracionPendiente());
        try (Stream<Path> s = Files.list(rutas.archivoBd().getParent())) {
            for (Path p : s.filter(x -> x.getFileName().toString().contains(".antes-de-restaurar-")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private Cajero entrarComo(String usuario, String clave) throws Exception {
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(usuario).orElseThrow();
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();
        return sesionService.autenticar(usuario, clave);
    }

    private static boolean esBaseDeEstePrograma(Path archivo) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + archivo);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('configuracion','productos','ventas')")) {
            return rs.next() && rs.getInt(1) == 3;
        }
    }

    private Producto nuevoProducto(String nombre) {
        Producto p = new Producto();
        p.setCodigoBarras("BK-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(nombre);
        p.setPrecioVenta(10);
        p.setPrecioCosto(5);
        p.setUsaInventario(false);
        return productoService.guardar(p);
    }

    @Test
    void muestraRutaTamanoYConteosSoloAlAdmin() throws Exception {
        entrarComo("admin", "admin123");
        nuevoProducto("Para contar");
        BaseDatosService.Informacion info = baseDatosService.informacion();

        assertThat(info.archivo()).isEqualTo(rutas.archivoBd());
        assertThat(info.bytes()).isGreaterThan(0);
        assertThat(info.registros()).containsKeys("Productos", "Ventas", "Usuarios");
        assertThat(info.registros().get("Productos")).isGreaterThanOrEqualTo(1);

        cajeroService.crear("cajeroBd", "Cajero Bd", "clave123", Cajero.Rol.CAJERO, null);
        entrarComo("cajeroBd", "clave123");
        assertThatThrownBy(() -> baseDatosService.informacion()).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> baseDatosService.respaldar(temporal.resolve("x.db"))).isInstanceOf(NegocioException.class);
    }

    @Test
    void respaldarGeneraUnaCopiaSqliteValidaYNoPisaLaBdEnUso() throws Exception {
        entrarComo("admin", "admin123");
        Path destino = temporal.resolve("respaldo.db");

        assertThat(baseDatosService.respaldar(destino)).isEqualTo(destino);
        assertThat(destino).exists();
        assertThat(esBaseDeEstePrograma(destino)).isTrue();

        // si ya existe se reemplaza (FileChooser ya pidió confirmación)
        assertThat(baseDatosService.respaldar(destino)).exists();
        assertThatThrownBy(() -> baseDatosService.respaldar(rutas.archivoBd()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("en uso");
    }

    @Test
    void respaldarExigeExtensionDbYNoSobrescribeArchivosQueNoSonSqlite() throws Exception {
        entrarComo("admin", "admin123");
        assertThatThrownBy(() -> baseDatosService.respaldar(temporal.resolve("respaldo.txt")))
                .isInstanceOf(NegocioException.class).hasMessageContaining(".db");
        assertThatThrownBy(() -> baseDatosService.respaldar(temporal.resolve("sin-extension")))
                .isInstanceOf(NegocioException.class);
        Path ajeno = java.nio.file.Files.writeString(temporal.resolve("documento.db"), "importante, no es una base de datos");
        assertThatThrownBy(() -> baseDatosService.respaldar(ajeno))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una base de datos SQLite");
        assertThat(java.nio.file.Files.readString(ajeno)).isEqualTo("importante, no es una base de datos");
        assertThat(baseDatosService.respaldar(temporal.resolve("OK.DB"))).exists();
    }

    @Test
    void restaurarValidaElArchivoPideLaClaveYGuardaCopiaDeSeguridad() throws Exception {
        entrarComo("admin", "admin123");
        Path respaldo = baseDatosService.respaldar(temporal.resolve("bueno.db"));

        // no es SQLite
        Path texto = Files.writeString(temporal.resolve("texto.db"), "esto no es una base de datos");
        assertThatThrownBy(() -> baseDatosService.restaurar(texto, "admin123"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es una base de datos SQLite");
        // SQLite pero de otra aplicación
        Path ajena = temporal.resolve("ajena.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + ajena); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cosas (id integer)");
        }
        assertThatThrownBy(() -> baseDatosService.restaurar(ajena, "admin123"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("no es un respaldo de este Punto de Venta");
        // contraseña incorrecta
        assertThatThrownBy(() -> baseDatosService.restaurar(respaldo, "mala"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("contraseña");
        assertThat(baseDatosService.hayRestauracionPendiente()).isFalse();

        // válido: queda pendiente y se guardó una copia de seguridad de la base actual
        Path seguridad = baseDatosService.restaurar(respaldo, "admin123");
        assertThat(baseDatosService.hayRestauracionPendiente()).isTrue();
        assertThat(rutas.archivoRestauracionPendiente()).exists();
        assertThat(seguridad).exists();
        assertThat(seguridad.getFileName().toString()).contains(".antes-de-restaurar-");
        assertThat(esBaseDeEstePrograma(seguridad)).isTrue();
        assertThat(rutas.archivoBd()).exists();   // la base en uso no se tocó
    }

    @Test
    void soloElAdminRestauraYConSuPropiaClave() throws Exception {
        entrarComo("admin", "admin123");
        Path respaldo = baseDatosService.respaldar(temporal.resolve("otro.db"));
        cajeroService.crear("cajeroRes", "Cajero Res", "clave123", Cajero.Rol.CAJERO, null);
        entrarComo("cajeroRes", "clave123");
        assertThatThrownBy(() -> baseDatosService.restaurar(respaldo, "clave123")).isInstanceOf(NegocioException.class);
        assertThat(baseDatosService.hayRestauracionPendiente()).isFalse();
    }

    @Test
    void alReiniciarSeAplicaElRespaldoPendiente() throws Exception {
        Path bd = temporal.resolve("datos.db");
        Files.writeString(bd, "base vieja");
        Files.writeString(RutasApp.hermano(bd, "-journal"), "residuo");

        assertThat(RutasApp.aplicarRestauracionPendiente(bd)).isFalse();   // nada pendiente

        Files.writeString(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR), "SQLite format 3\0 base restaurada");
        assertThat(RutasApp.aplicarRestauracionPendiente(bd)).isTrue();
        assertThat(Files.readString(bd)).isEqualTo("SQLite format 3\0 base restaurada");
        assertThat(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR)).doesNotExist();
        assertThat(RutasApp.hermano(bd, "-journal")).doesNotExist();
    }

    // ── Respaldo automático ───────────────────────────────────────────────

    private static Path falso(Path carpeta, LocalDate dia) throws Exception {
        return Files.writeString(carpeta.resolve("respaldo-auto-" + dia.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".db"), "x");
    }

    @Test
    void alReiniciarUnPendienteQueYaNoEsSqliteNoReemplazaLaBase() throws Exception {
        Path bd = temporal.resolve("datos.db");
        Files.writeString(bd, "base actual");
        Files.writeString(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR), "esto ya no es una base de datos");

        assertThat(RutasApp.aplicarRestauracionPendiente(bd)).isFalse();

        assertThat(Files.readString(bd)).isEqualTo("base actual");
        assertThat(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR)).doesNotExist();
        assertThat(RutasApp.hermano(bd, RutasApp.SUFIJO_RESTAURAR + ".rechazado")).exists();
    }

    @Test
    void restaurarRechazaRespaldosConDisparadoresVistasOSinAdministrador() throws Exception {
        entrarComo("admin", "admin123");
        Path con = baseDatosService.respaldar(temporal.resolve("con.db"));
        // Un respaldo manipulado: copia con un disparador
        Path trigger = java.nio.file.Files.copy(con, temporal.resolve("trigger.db"));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + trigger);
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER t AFTER INSERT ON ventas BEGIN SELECT 1; END");
        }
        assertThatThrownBy(() -> baseDatosService.restaurar(trigger, "admin123"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("disparadores");

        Path vista = java.nio.file.Files.copy(con, temporal.resolve("vista.db"));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + vista);
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE VIEW v AS SELECT * FROM cajeros");
        }
        assertThatThrownBy(() -> baseDatosService.restaurar(vista, "admin123"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("vistas");

        Path sinAdmin = java.nio.file.Files.copy(con, temporal.resolve("sinadmin.db"));
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sinAdmin);
             java.sql.Statement st = c.createStatement()) {
            st.execute("UPDATE cajeros SET activo = 0 WHERE rol = 'ADMIN'");
        }
        assertThatThrownBy(() -> baseDatosService.restaurar(sinAdmin, "admin123"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("administrador");
        assertThat(baseDatosService.hayRestauracionPendiente()).isFalse();
    }

    @Test
    void conservarUltimasBorraLosMasViejosYSoloLosRespaldosAutomaticos() throws Exception {
        for (int i = 1; i <= 5; i++) falso(temporal, LocalDate.of(2026, 1, i));
        Path ajeno = Files.writeString(temporal.resolve("notas.txt"), "no tocar");
        Path manual = Files.writeString(temporal.resolve("respaldo-20260101-1200.db"), "no tocar");

        baseDatosService.conservarUltimas(temporal, 3);

        try (Stream<Path> s = Files.list(temporal)) {
            assertThat(s.map(p -> p.getFileName().toString()).sorted().toList()).containsExactly(
                    "notas.txt", "respaldo-20260101-1200.db",
                    "respaldo-auto-20260103.db", "respaldo-auto-20260104.db", "respaldo-auto-20260105.db");
        }
        assertThat(ajeno).exists();
        assertThat(manual).exists();
    }

    @Test
    void elRespaldoAutomaticoSoloCorreActivoYConservaNCopias() throws Exception {
        entrarComo("admin", "admin123");
        Path carpeta = temporal.resolve("auto");

        // apagado: no hace nada
        assertThat(baseDatosService.respaldoAutomaticoAlCerrar()).isEmpty();
        assertThat(baseDatosService.respaldoAutomaticoDelDia()).isEmpty();

        // validaciones de la configuración
        assertThatThrownBy(() -> configuracionService.guardarRespaldoAutomatico(true, "", 3)).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> configuracionService.guardarRespaldoAutomatico(true, carpeta.toString(), 0)).isInstanceOf(NegocioException.class);

        configuracionService.guardarRespaldoAutomatico(true, carpeta.toString(), 2);
        for (int i = 1; i <= 4; i++) falso(carpeta, LocalDate.now().minusDays(i));   // 4 días anteriores

        // primer inicio del día: crea el de hoy y deja solo 2 en total (hoy + ayer)
        Path hoy = baseDatosService.respaldoAutomaticoDelDia().orElseThrow();
        assertThat(hoy.getFileName().toString()).isEqualTo("respaldo-auto-"
                + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".db");
        assertThat(esBaseDeEstePrograma(hoy)).isTrue();
        try (Stream<Path> s = Files.list(carpeta)) {
            assertThat(s.count()).isEqualTo(2);
        }
        assertThat(configuracionService.obtener().getRespaldoUltimo()).isEqualTo(LocalDate.now());

        // un segundo inicio de sesión el mismo día no repite el respaldo...
        assertThat(baseDatosService.respaldoAutomaticoDelDia()).isEmpty();
        // ...pero al cerrar se actualiza el de hoy, sin acumular archivos
        assertThat(baseDatosService.respaldoAutomaticoAlCerrar()).contains(hoy);
        try (Stream<Path> s = Files.list(carpeta)) {
            assertThat(s.count()).isEqualTo(2);
        }
        configuracionService.guardarRespaldoAutomatico(false, carpeta.toString(), 2);
    }

    @Test
    void unRespaldoAutomaticoFallidoNoLanzaExcepcion() throws Exception {
        entrarComo("admin", "admin123");
        Path archivoEnLugarDeCarpeta = Files.writeString(temporal.resolve("no-es-carpeta"), "x");
        // la configuración se guarda válida y luego la carpeta "desaparece" (se vuelve un archivo)
        Path carpeta = temporal.resolve("destino");
        configuracionService.guardarRespaldoAutomatico(true, carpeta.toString(), 3);
        Files.delete(carpeta);
        Files.move(archivoEnLugarDeCarpeta, carpeta);

        assertThat(baseDatosService.respaldoAutomaticoAlCerrar()).isEmpty();   // no lanza
        configuracionService.guardarRespaldoAutomatico(false, "", 3);
    }
}
