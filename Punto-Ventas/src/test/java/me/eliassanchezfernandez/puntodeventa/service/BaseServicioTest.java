package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Base común: BD desechable (src/test/resources/application.properties),
 * cada prueba en su transacción (se revierte al final) y helpers para
 * iniciar sesión, crear usuarios, productos y promociones.
 */
@SpringBootTest
@Transactional
public abstract class BaseServicioTest {

    public static final String CLAVE_PRUEBA = "clave123";

    @Autowired protected SesionService      sesionService;
    @Autowired protected CajeroService      cajeroService;
    @Autowired protected ProductoService    productoService;
    @Autowired protected PromocionService   promocionService;
    @Autowired protected VentaService       ventaService;
    @Autowired protected CajeroRepository   cajeroRepo;
    @Autowired protected ProductoRepository productoRepo;
    @Autowired protected PasswordEncoder    passwordEncoder;

    @AfterEach
    void cerrarSesionAlTerminar() {
        sesionService.cerrarSesion();
    }

    /** Inicia sesión como el admin sembrado, sin la obligación de cambiar contraseña */
    protected Cajero iniciarComoAdmin() throws Exception {
        return iniciarComo(SesionService.ADMIN_USUARIO, SesionService.ADMIN_CONTRASENA);
    }

    /** Inicia sesión como cualquier usuario quitándole antes el cambio obligatorio */
    protected Cajero iniciarComo(String usuario, String clave) throws Exception {
        Cajero c = cajeroRepo.findByUsuarioIgnoreCase(usuario).orElseThrow();
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();
        return sesionService.autenticar(usuario, clave);
    }

    /** Crea un usuario (requiere sesión de admin) con la contraseña CLAVE_PRUEBA */
    protected Cajero crearUsuario(String usuario, Cajero.Rol rol, Set<Permiso> permisos) {
        return cajeroService.crear(usuario, "Usuario " + usuario, CLAVE_PRUEBA, rol, permisos);
    }

    /** Crea un cajero sin permisos y deja la sesión iniciada como él */
    protected Cajero iniciarComoCajero(String usuario, Set<Permiso> permisos) throws Exception {
        iniciarComoAdmin();
        crearUsuario(usuario, Cajero.Rol.CAJERO, permisos);
        return iniciarComo(usuario, CLAVE_PRUEBA);
    }

    protected Producto producto(String descripcion, double precio, double stock) {
        return producto(descripcion, precio, stock, Producto.TipoVenta.UNIDAD, null);
    }

    protected Producto producto(String descripcion, double precio, double stock,
                                Producto.TipoVenta tipo, Departamento depto) {
        Producto p = new Producto();
        p.setCodigoBarras("T-" + UUID.randomUUID().toString().substring(0, 8));
        p.setDescripcion(descripcion);
        p.setPrecioCosto(precio / 2);
        p.setPrecioVenta(precio);
        p.setTipoVenta(tipo);
        p.setDepartamento(depto);
        p.setUsaInventario(true);
        p.setCantidadActual(stock);
        p.setCantidadMinima(1);
        return productoService.guardar(p);
    }

    protected Departamento departamento(String nombre) {
        Departamento d = new Departamento();
        d.setNombre(nombre + " " + UUID.randomUUID().toString().substring(0, 4));
        return productoService.guardarDepartamento(d);
    }

    protected Promocion promocion(Producto p, Promocion.Tipo tipo, double valor) {
        Promocion promo = new Promocion();
        promo.setProducto(p);
        promo.setTipo(tipo);
        promo.setValor(valor);
        promo.setFechaInicio(LocalDate.now().minusDays(1));
        promo.setFechaFin(LocalDate.now().plusDays(1));
        promo.setActiva(true);
        return promocionService.guardar(promo);
    }

    protected Promocion promocionNxM(Producto p, int n, int m) {
        Promocion promo = new Promocion();
        promo.setProducto(p);
        promo.setTipo(Promocion.Tipo.NXM);
        promo.setCantidadN(n);
        promo.setCantidadM(m);
        promo.setFechaInicio(LocalDate.now().minusDays(1));
        promo.setActiva(true);
        return promocionService.guardar(promo);
    }

    /** Renglón armado "a mano", como si viniera de una UI que no validó nada */
    protected DetalleVenta detalleCrudo(Producto p, double cantidad, double precio, double descuento) {
        DetalleVenta d = new DetalleVenta();
        d.setProducto(p);
        d.setCantidad(cantidad);
        d.setPrecioVenta(precio);
        d.setDescuento(descuento);
        return d;
    }

    protected double stockActual(Producto p) {
        return productoRepo.findById(p.getId()).orElseThrow().getCantidadActual();
    }
}
