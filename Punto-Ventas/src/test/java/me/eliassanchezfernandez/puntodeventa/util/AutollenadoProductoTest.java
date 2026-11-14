package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Descripción propuesta al estilo abarrotes y mapeo de categoría del catálogo a departamento de la tienda */
class AutollenadoProductoTest {

    private static CatalogoCodigo ficha(String nombre, String marca, Double cantidad, String unidad, String contenido, String categoria) {
        CatalogoCodigo f = new CatalogoCodigo();
        f.setCodigo("7501055300075");
        f.setNombre(nombre);
        f.setMarca(marca);
        f.setCantidad(cantidad);
        f.setUnidad(unidad);
        f.setContenido(contenido);
        f.setCategoria(categoria);
        return f;
    }

    private static Departamento depto(long id, String nombre) {
        Departamento d = new Departamento();
        d.setId(id);
        d.setNombre(nombre);
        return d;
    }

    // ── Descripción ───────────────────────────────────────────────────────

    @Test
    void nombreMasContenidoCuandoLaMarcaYaVieneEnElNombre() {
        assertThat(AutollenadoProducto.descripcion(ficha("Coca-Cola", "Coca-Cola", 355.0, "ml", "355 ml", "Bebidas")))
                .isEqualTo("Coca-Cola 355 ml");
    }

    @Test
    void laMarcaSeAntecedeSiElNombreNoLaTrae() {
        assertThat(AutollenadoProducto.descripcion(ficha("Leche Extra Proteína baja en grasa", "Alpura", null, null, null, "Lácteos")))
                .isEqualTo("Alpura Leche Extra Proteína baja en grasa");
        assertThat(AutollenadoProducto.descripcion(ficha("Tortillas", "Azucena", null, null, "", "Panadería")))
                .isEqualTo("Azucena Tortillas");
    }

    @Test
    void laMarcaNoSeRepiteAunqueCambienGuionesOAcentos() {
        assertThat(AutollenadoProducto.descripcion(ficha("Coca Cola Light 600", "Coca-Cola", 600.0, "ml", "600 ml", "Bebidas")))
                .as("el nombre ya trae la marca (sin guion) y la cantidad (600)")
                .isEqualTo("Coca Cola Light 600");
        assertThat(AutollenadoProducto.descripcion(ficha("Jugo Jumex de mango", "JUMEX", 1.0, "l", "1 L", "Bebidas")))
                .isEqualTo("Jugo Jumex de mango 1 L");
    }

    @Test
    void elContenidoNoSeRepiteSiElNombreYaLoDice() {
        assertThat(AutollenadoProducto.descripcion(ficha("Agua fresca con chía 360 ml", "", 360.0, "ml", "360 ml", "Bebidas")))
                .isEqualTo("Agua fresca con chía 360 ml");
        assertThat(AutollenadoProducto.descripcion(ficha("Sabritas 45g", "Sabritas", 45.0, "g", "45 g", "Botanas")))
                .as("45g pegado no es la palabra 45, pero el contenido normalizado '45 g' sí está")
                .isEqualTo("Sabritas 45g");
    }

    @Test
    void sinTextoDeContenidoSeArmaConCantidadYUnidad() {
        assertThat(AutollenadoProducto.descripcion(ficha("Aceite vegetal", "Nutrioli", 1.0, "l", null, "Abarrotes")))
                .isEqualTo("Nutrioli Aceite vegetal 1 l");
        assertThat(AutollenadoProducto.descripcion(ficha("Yogur", "Danone", 1.5, "kg", null, "Lácteos")))
                .isEqualTo("Danone Yogur 1.5 kg");
        assertThat(AutollenadoProducto.descripcion(ficha("Chucrut", "Ferment Freaks", null, null, null, "Otros")))
                .isEqualTo("Ferment Freaks Chucrut");
    }

    @Test
    void seLimpianEspaciosYSeRecortaA200() {
        assertThat(AutollenadoProducto.descripcion(ficha("  Pan   blanco ", " Bimbo ", null, null, " 680 g ", "Panadería")))
                .isEqualTo("Bimbo Pan blanco 680 g");
        String largo = "a".repeat(190);
        assertThat(AutollenadoProducto.descripcion(ficha(largo, "Marca", 600.0, "ml", "600 ml", "Bebidas")))
                .hasSizeLessThanOrEqualTo(AutollenadoProducto.DESCRIPCION_MAX);
        assertThat(AutollenadoProducto.descripcion(ficha("", "Marca", null, null, null, null))).isEmpty();
    }

    // ── Departamento ──────────────────────────────────────────────────────

    private static final List<Departamento> TIENDA = List.of(
            depto(1, "Abarrotes"), depto(2, "Refrescos y Jugos"), depto(3, "Frituras"), depto(4, "Lacteos"),
            depto(5, "Pan y Tortillas"), depto(6, "Dulcería"), depto(7, "Limpieza del hogar"), depto(8, "Farmacia"),
            depto(9, "Carnes frías"), depto(10, "Vinos y licores"));

    @Test
    void coincidenciaExactaIgnorandoAcentosYMayusculas() {
        assertThat(AutollenadoProducto.departamento("Lácteos", TIENDA)).map(Departamento::getNombre).contains("Lacteos");
        assertThat(AutollenadoProducto.departamento("abarrotes", TIENDA)).map(Departamento::getNombre).contains("Abarrotes");
    }

    @Test
    void sinonimosDeLaTienda() {
        assertThat(AutollenadoProducto.departamento("Bebidas", TIENDA)).map(Departamento::getNombre).contains("Refrescos y Jugos");
        assertThat(AutollenadoProducto.departamento("Botanas", TIENDA)).map(Departamento::getNombre).contains("Frituras");
        assertThat(AutollenadoProducto.departamento("Panadería", TIENDA)).map(Departamento::getNombre).contains("Pan y Tortillas");
        assertThat(AutollenadoProducto.departamento("Dulces", TIENDA)).map(Departamento::getNombre).contains("Dulcería");
        assertThat(AutollenadoProducto.departamento("Limpieza", TIENDA)).map(Departamento::getNombre).contains("Limpieza del hogar");
        assertThat(AutollenadoProducto.departamento("Higiene personal", TIENDA)).map(Departamento::getNombre).contains("Farmacia");
        assertThat(AutollenadoProducto.departamento("Carnes y embutidos", TIENDA)).map(Departamento::getNombre).contains("Carnes frías");
    }

    @Test
    void otrosYCategoriasSinEquivalenteNoProponenNada() {
        assertThat(AutollenadoProducto.departamento("Otros", TIENDA)).isEmpty();
        assertThat(AutollenadoProducto.departamento("Mascotas", TIENDA)).isEmpty();
        assertThat(AutollenadoProducto.departamento("Congelados", TIENDA)).isEmpty();
        assertThat(AutollenadoProducto.departamento(null, TIENDA)).isEmpty();
        assertThat(AutollenadoProducto.departamento("Bebidas", List.of())).isEmpty();
        assertThat(AutollenadoProducto.departamento("Bebidas", null)).isEmpty();
    }

    @Test
    void elNombreExactoGanaSobreElSinonimo() {
        List<Departamento> dos = List.of(depto(1, "Refrescos"), depto(2, "Bebidas"));
        Optional<Departamento> d = AutollenadoProducto.departamento("Bebidas", dos);
        assertThat(d).map(Departamento::getId).contains(2L);
    }

    @Test
    void unSinonimoNoCoincideDentroDeOtraPalabra() {
        // "pan" no debe casar con "Panales" ni "Hispano"; "Pan dulce" sí
        List<Departamento> deptos = List.of(depto(1, "Pañales"), depto(2, "Productos Hispanos"), depto(3, "Pan dulce"));
        assertThat(AutollenadoProducto.departamento("Panadería", deptos)).map(Departamento::getNombre).contains("Pan dulce");
        assertThat(AutollenadoProducto.departamento("Bebés", List.of(depto(1, "Pañales y bebé")))).map(Departamento::getNombre)
                .contains("Pañales y bebé");
    }
}
