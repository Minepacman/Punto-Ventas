package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoComun;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Producto del sistema al que apuntan los renglones de "producto común"
 * ({@link ProductoComun}). Se crea la primera vez que hace falta y nunca se
 * lista ni se edita desde las pantallas (ProductoRepository lo excluye de las
 * consultas de catálogo). Sin inventario, sin departamento ni unidad, con las
 * claves genéricas del SAT (01010101 "No existe en el catálogo", H87 Pieza):
 * así la factura de una venta con estos renglones sale sin configurar nada.
 */
@Service
public class ProductoComunService {

    @Autowired private ProductoRepository productoRepo;

    /** El producto del sistema, creándolo si aún no existe (siempre activo y sin inventario) */
    @Transactional
    public Producto obtenerOCrear() {
        return productoRepo.findByCodigoBarras(ProductoComun.CODIGO).map(this::sanear).orElseGet(this::crear);
    }

    private Producto crear() {
        Producto p = new Producto();
        p.setCodigoBarras(ProductoComun.CODIGO);
        p.setDescripcion(ProductoComun.NOMBRE_POR_DEFECTO);
        p.setTipoVenta(Producto.TipoVenta.UNIDAD);
        p.setPrecioCosto(0);
        p.setPrecioVenta(0);
        p.setPrecioMayoreo(0);
        p.setUsaInventario(false);
        p.setCantidadActual(0);
        p.setCantidadMinima(0);
        p.setActivo(true);
        p.setClaveProdServ(CatalogoSat.PROD_SERV_COMODIN);
        p.setClaveUnidad(CatalogoSat.UNIDAD_PIEZA);
        p.setObjetoImp(CatalogoSat.OBJETO_IMP_SI);
        return productoRepo.save(p);
    }

    /**
     * Si alguien tocó la fila por fuera (importación, SQL), se deja como debe estar:
     * activa y sin inventario, para que los renglones comunes nunca se bloqueen.
     */
    private Producto sanear(Producto p) {
        boolean cambio = false;
        if (!p.isActivo()) { p.setActivo(true); cambio = true; }
        if (p.isUsaInventario()) { p.setUsaInventario(false); cambio = true; }
        if (p.getPrecioMayoreo() != 0) { p.setPrecioMayoreo(0); cambio = true; }
        return cambio ? productoRepo.save(p) : p;
    }
}
