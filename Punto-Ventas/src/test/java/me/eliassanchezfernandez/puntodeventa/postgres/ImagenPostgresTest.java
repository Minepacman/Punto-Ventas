package me.eliassanchezfernandez.puntodeventa.postgres;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.ProductoImagen;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenService;
import me.eliassanchezfernandez.puntodeventa.service.ProductoImagenServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.awt.Color;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Imágenes de producto en PostgreSQL: columna bytea (no "large object") e ida y vuelta íntegra. */
class ImagenPostgresTest extends BasePostgresTest {

    @Autowired private ProductoImagenService imagenService;
    @Autowired private DataSource dataSource;

    @Test
    void guardaYLeeLaImagenComoBytea() throws Exception {
        iniciarComoAdmin();
        Producto p = new Producto();
        p.setCodigoBarras("IMG-" + sufijo());
        p.setDescripcion("Con foto " + sufijo());
        p.setPrecioVenta(10);
        p.setUsaInventario(false);
        p = productoService.guardar(p);

        ProductoImagen g = imagenService.guardar(p.getId(), ProductoImagenServiceTest.png(1024, 768), "prueba pg");
        assertThat(imagenService.imagen(p.getId())).get().isEqualTo(g.getImagen());
        assertThat(imagenService.miniatura(p.getId())).get().isEqualTo(g.getMiniatura());
        assertThat(imagenService.idsConImagen(Set.of(p.getId()))).containsExactly(p.getId());
        assertThat(ProductoImagenService.dimensiones(imagenService.imagen(p.getId()).orElseThrow())).containsExactly(512, 384);

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns "
                     + "WHERE table_name = 'producto_imagen' AND column_name IN ('imagen', 'miniatura')")) {
            int columnas = 0;
            while (rs.next()) {
                columnas++;
                assertThat(rs.getString(1)).isEqualTo("bytea");
            }
            assertThat(columnas).isEqualTo(2);
        }

        imagenService.guardar(p.getId(), ProductoImagenServiceTest.jpg(200, 200, Color.PINK), "otra");
        assertThat(ProductoImagenService.dimensiones(imagenService.imagen(p.getId()).orElseThrow())).containsExactly(200, 200);
        imagenService.quitar(p.getId());
        assertThat(imagenService.tieneImagen(p.getId())).isFalse();
        productoService.eliminar(p.getId());
    }
}
