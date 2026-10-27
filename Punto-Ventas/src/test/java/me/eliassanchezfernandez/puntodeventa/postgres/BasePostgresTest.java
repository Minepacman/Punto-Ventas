package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.IdentidadCaja;
import me.eliassanchezfernandez.puntodeventa.service.ProductoService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.service.VentaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;
import java.util.UUID;

/**
 * Base de las pruebas contra PostgreSQL (etiqueta "postgres", tarea
 * {@code ./gradlew testPostgres}): el contexto de Spring arranca contra el
 * PostgreSQL embebido con el mismo ddl-auto que producción (update), y las
 * pruebas NO van envueltas en una transacción: cada operación se confirma de
 * verdad, como en la tienda, para poder probar ventas simultáneas desde varios
 * hilos. Por eso los nombres de usuario y códigos llevan un sufijo aleatorio.
 */
@Tag("postgres")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "app.caja.numero=1",
        "app.caja.nombre=Caja 1",
        "app.bd.modo=POSTGRESQL"
})
public abstract class BasePostgresTest {

    public static final String CLAVE_PRUEBA = "clave123";

    @DynamicPropertySource
    static void propiedadesPostgres(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> PostgresEmbebido.url(PostgresEmbebido.BASE_APP));
        registro.add("spring.datasource.username", () -> PostgresEmbebido.USUARIO);
        registro.add("spring.datasource.password", () -> PostgresEmbebido.CONTRASENA);
        registro.add("spring.datasource.driver-class-name", PerfilBaseDatos.POSTGRESQL::driver);
        registro.add("spring.jpa.database-platform", PerfilBaseDatos.POSTGRESQL::dialecto);
    }

    @Autowired protected SesionService      sesionService;
    @Autowired protected CajeroService      cajeroService;
    @Autowired protected ProductoService    productoService;
    @Autowired protected VentaService       ventaService;
    @Autowired protected CajeroRepository   cajeroRepo;
    @Autowired protected ProductoRepository productoRepo;
    @Autowired protected IdentidadCaja      identidadCaja;

    @AfterEach
    void cerrarSesion() {
        sesionService.cerrarSesion();
    }

    protected static String sufijo() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected Cajero iniciarComoAdmin() throws Exception {
        return iniciarComo(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
    }

    protected Cajero iniciarComo(String usuario, String clave) throws Exception {
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(usuario).orElseThrow();
        if (c.isDebeCambiarContrasena()) {
            c.setDebeCambiarContrasena(false);
            cajeroRepo.save(c);
        }
        sesionService.cerrarSesion();
        return sesionService.autenticar(usuario, clave);
    }

    /** Crea un cajero con esos permisos, ya sin el cambio de contraseña obligatorio (requiere sesión de admin) */
    protected Cajero crearCajero(String usuario, Set<Permiso> permisos) {
        Cajero c = cajeroService.crear(usuario, "Usuario " + usuario, CLAVE_PRUEBA, Cajero.Rol.CAJERO, permisos);
        c.setDebeCambiarContrasena(false);
        return cajeroRepo.save(c);
    }

    protected Producto producto(String descripcion, double precio, double stock) {
        Producto p = new Producto();
        p.setCodigoBarras("PG-" + sufijo());
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    protected double stockActual(Producto p) {
        return productoRepo.findById(p.getId()).orElseThrow().getCantidadActual();
    }
}
