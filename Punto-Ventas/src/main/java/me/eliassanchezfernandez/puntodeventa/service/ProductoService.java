package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProductoService {

    @Autowired private ProductoRepository productoRepo;

    public List<Producto> listarTodos() {
        return productoRepo.findAll();
    }

    public List<Producto> buscarPorNombre(String texto) {
        return productoRepo.findByDescripcionContainingIgnoreCase(texto);
    }

    public List<Producto> productosConStockBajo() {
        // Busca productos cuya cantidad_actual <= cantidad_minima
        return productoRepo.findByCantidadActualLessThanEqualAndUsaInventario(
                0, true); // TODO: ajustar a comparar con minimo propio
    }

    public Optional<Producto> buscarPorCodigo(String codigoBarras) {
        return productoRepo.findByCodigoBarras(codigoBarras);
    }

    @Transactional
    public Producto guardar(Producto producto) {
        return productoRepo.save(producto);
    }

    @Transactional
    public void eliminar(Long id) {
        productoRepo.deleteById(id);
    }
}
