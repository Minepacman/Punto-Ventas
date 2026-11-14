package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.importacion.FilaProducto.Accion;
import me.eliassanchezfernandez.puntodeventa.importacion.OpcionesImportacion.Duplicados;
import me.eliassanchezfernandez.puntodeventa.importacion.PlanImportacion.ErrorFila;
import me.eliassanchezfernandez.puntodeventa.importacion.PreparacionProductos.Catalogo;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Validación e interpretación de filas sin base de datos (catálogo simulado) */
class PreparacionProductosTest {

    static final Path RECURSOS = Path.of("src/test/resources/importacion");

    private static Unidad unidad(long id, String nombre, String abrev, boolean decimales) {
        Unidad u = new Unidad(nombre, abrev, decimales);
        u.setId(id);
        return u;
    }

    static final List<Unidad> UNIDADES = List.of(unidad(1, "Pieza", "pza", false), unidad(2, "Kilogramo", "kg", true),
            unidad(3, "Litro", "L", true), unidad(4, "Caja", "caja", false));

    private static Producto existente(long id, String codigo, double venta, boolean usaInventario, double stock) {
        Producto p = new Producto();
        p.setId(id);
        p.setCodigoBarras(codigo);
        p.setDescripcion("Existente " + codigo);
        p.setPrecioCosto(1);
        p.setPrecioVenta(venta);
        p.setPrecioMayoreo(0);
        p.setUsaInventario(usaInventario);
        p.setCantidadActual(stock);
        return p;
    }

    private static Catalogo catalogo(boolean granel, Producto... existentes) {
        Map<String, Producto> porCodigo = new HashMap<>();
        for (Producto p : existentes) porCodigo.put(p.getCodigoBarras().toLowerCase(), p);
        Departamento refrescos = new Departamento();
        refrescos.setId(10L);
        refrescos.setNombre("Refrescos");
        return new Catalogo(porCodigo, Map.of("refrescos", refrescos), UNIDADES, granel);
    }

    private static PlanImportacion plan(String archivo, Duplicados dup, Catalogo cat) throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve(archivo));
        Origen o = Origen.detectar(t.encabezados(true));
        Mapeo m = o.mapeoAutomatico(t.encabezados(true), CampoProducto.values());
        return PreparacionProductos.preparar(t, o, m, new OpcionesImportacion(dup, true, true, true), cat);
    }

    @Test
    void archivoConErroresReportaCadaFilaYConservaLasBuenas() throws IOException {
        PlanImportacion plan = plan("generico-errores.csv", Duplicados.OMITIR, catalogo(true));
        List<String> errores = plan.errores().stream().map(ErrorFila::toString).toList();
        assertThat(errores).hasSize(6);
        assertThat(errores.get(0)).startsWith("Fila 3").contains("Falta el código");
        assertThat(errores.get(1)).startsWith("Fila 4 (ABC-1)").contains("Falta el precio de venta");
        assertThat(errores.get(2)).startsWith("Fila 5 (ABC-2)").contains("no es un número válido").contains("caro");
        assertThat(errores.get(3)).startsWith("Fila 6 (ABC-3)").contains("mayor a cero");
        assertThat(errores.get(4)).startsWith("Fila 7 (7501055300136)").contains("ya apareció en la fila 2");
        assertThat(errores.get(5)).startsWith("Fila 9 (ABC-5)").contains("negativos");

        assertThat(plan.filas()).extracting(f -> f.codigo).containsExactly("7501055300136", "ABC-4", "2003", "7501050000000");
        FilaProducto coca = plan.filas().get(0);
        assertThat(coca.accion).isEqualTo(Accion.CREAR);
        assertThat(coca.precioCosto).as("12,50 a la mexicana").isEqualTo(12.5);
        assertThat(coca.precioVenta).isEqualTo(18);
        assertThat(coca.departamento).isEqualTo("Refrescos");
        assertThat(coca.departamentoNuevo).isFalse();
        assertThat(coca.tipoVenta).isEqualTo(Producto.TipoVenta.UNIDAD);
        assertThat(coca.existencia).isEqualTo(24);
        assertThat(coca.cambiaExistencia).isTrue();

        FilaProducto caro = plan.filas().get(1);
        assertThat(caro.precioCosto).as("1,000 = mil").isEqualTo(1000);
        assertThat(caro.precioVenta).as("1,500 = mil quinientos").isEqualTo(1500);
        assertThat(caro.existencia).as("2,5 piezas se redondea").isEqualTo(3);
        assertThat(caro.avisos).anyMatch(a -> a.contains("redonde"));
        assertThat(caro.departamento).isEqualTo("Varios");
        assertThat(caro.departamentoNuevo).isTrue();

        FilaProducto frijol = plan.filas().get(2);
        assertThat(frijol.tipoVenta).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(frijol.unidad.getNombre()).isEqualTo("Kilogramo");
        assertThat(frijol.existencia).as("12,75 con coma decimal (una coma y 2 dígitos)").isEqualTo(12.75);
        assertThat(frijol.avisos).isEmpty();

        assertThat(plan.filas().get(3).codigo).as("7.50105E+12 se expande").isEqualTo("7501050000000");
        assertThat(plan.departamentosNuevos()).containsExactly("Varios", "Granos");
        assertThat(plan.requiereAjustarInventario()).isTrue();
        assertThat(plan.requiereCambiarPrecios()).isFalse();
    }

    @Test
    void granelDeshabilitadoRechazaProductosAGranelNuevos() throws IOException {
        PlanImportacion plan = plan("generico-errores.csv", Duplicados.OMITIR, catalogo(false));
        assertThat(plan.errores()).anyMatch(e -> e.codigo().equals("2003") && e.mensaje().contains("granel"));
    }

    @Test
    void duplicadosOmitirActualizarPreciosYActualizarTodo() throws IOException {
        Producto coca = existente(1, "7501055300136", 17, true, 10);
        Producto pan = existente(2, "7501000111112", 52, false, 0);

        PlanImportacion omitir = plan("abarrotes-pdv.csv", Duplicados.OMITIR, catalogo(true, coca, pan));
        assertThat(omitir.aOmitir()).isEqualTo(2);
        assertThat(omitir.aCrear()).isEqualTo(3);
        assertThat(omitir.requiereCambiarPrecios()).isFalse();
        FilaProducto omitida = omitir.filas().stream().filter(f -> f.codigo.equals("7501055300136")).findFirst().orElseThrow();
        assertThat(omitida.accion).isEqualTo(Accion.OMITIR);
        assertThat(omitida.idExistente).isEqualTo(1L);

        PlanImportacion precios = plan("abarrotes-pdv.csv", Duplicados.ACTUALIZAR_PRECIOS, catalogo(true, coca, pan));
        FilaProducto cocaP = precios.filas().stream().filter(f -> f.codigo.equals("7501055300136")).findFirst().orElseThrow();
        assertThat(cocaP.accion).isEqualTo(Accion.ACTUALIZAR);
        assertThat(cocaP.precioVenta).isEqualTo(18);
        assertThat(cocaP.cambiaPrecios).isTrue();
        assertThat(cocaP.descripcion).as("solo precios: la descripción no se toca").isNull();
        assertThat(cocaP.existencia).isNull();
        assertThat(cocaP.departamentoIndicado).isFalse();
        FilaProducto panP = precios.filas().stream().filter(f -> f.codigo.equals("7501000111112")).findFirst().orElseThrow();
        assertThat(panP.cambiaPrecios).as("mismo precio de venta pero costo distinto").isTrue();
        assertThat(precios.requiereCambiarPrecios()).isTrue();

        PlanImportacion todo = plan("abarrotes-pdv.csv", Duplicados.ACTUALIZAR_TODO, catalogo(true, coca, pan));
        FilaProducto cocaT = todo.filas().stream().filter(f -> f.codigo.equals("7501055300136")).findFirst().orElseThrow();
        assertThat(cocaT.descripcion).isEqualTo("Coca-Cola 600 ml");
        assertThat(cocaT.existencia).isEqualTo(24);
        assertThat(cocaT.cambiaExistencia).as("de 10 a 24").isTrue();
        assertThat(cocaT.minimo).isEqualTo(6);
        FilaProducto panT = todo.filas().stream().filter(f -> f.codigo.equals("7501000111112")).findFirst().orElseThrow();
        assertThat(panT.existencia).as("el producto no maneja inventario: no se toca").isNull();
        assertThat(panT.avisos).anyMatch(a -> a.contains("no maneja inventario"));
        assertThat(panT.departamentoIndicado).isTrue();
        assertThat(panT.departamento).as("\"- Sin Departamento -\" = sin departamento").isNull();
        FilaProducto jugo = todo.filas().stream().filter(f -> f.codigo.equals("7501055363506")).findFirst().orElseThrow();
        assertThat(jugo.existencia).as("existencia negativa se deja en 0").isZero();
        assertThat(jugo.cambiaExistencia).isFalse();
        assertThat(jugo.avisos).anyMatch(a -> a.contains("negativa"));
    }

    @Test
    void eleventaPreciosConSignoDePesosTipoDeVentaYClavesSat() throws IOException {
        PlanImportacion plan = plan("eleventa-export.csv", Duplicados.OMITIR, catalogo(true));
        assertThat(plan.errores()).isEmpty();
        FilaProducto coca = plan.filas().get(0);
        assertThat(coca.precioCosto).isEqualTo(12.5);
        assertThat(coca.precioVenta).isEqualTo(18);
        assertThat(coca.precioMayoreo).isEqualTo(16.5);
        assertThat(coca.tipoVenta).isEqualTo(Producto.TipoVenta.UNIDAD);
        assertThat(coca.unidad.getNombre()).isEqualTo("Pieza");
        assertThat(coca.claveProdServ).isEqualTo("50202306");
        assertThat(coca.claveUnidad).isEqualTo("H87");
        FilaProducto jitomate = plan.filas().get(1);
        assertThat(jitomate.tipoVenta).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(jitomate.unidad.getNombre()).isEqualTo("Kilogramo");
        assertThat(jitomate.existencia).isEqualTo(7.5);
        assertThat(jitomate.departamento).isEqualTo("Frutas y Verduras");
        assertThat(plan.filas().get(2).departamento).isNull();
        assertThat(plan.filas().get(3).existencia).isZero();
        assertThat(plan.filas().get(3).cambiaExistencia).isFalse();
    }

    @Test
    void sicarGranelSNyLoyverseSoldByWeightYAvailableForSale() throws IOException {
        PlanImportacion sicar = plan("sicar.csv", Duplicados.OMITIR, catalogo(true));
        assertThat(sicar.errores()).isEmpty();
        assertThat(sicar.filas().get(0).tipoVenta).isEqualTo(Producto.TipoVenta.UNIDAD);
        assertThat(sicar.filas().get(0).precioMayoreo).isEqualTo(16.5);
        assertThat(sicar.filas().get(1).tipoVenta).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(sicar.filas().get(1).existencia).isEqualTo(15.25);
        assertThat(sicar.filas().get(1).precioMayoreo).isEqualTo(0);

        PlanImportacion loy = plan("loyverse.csv", Duplicados.OMITIR, catalogo(true));
        assertThat(loy.errores()).as("la fila sin Barcode falla por código").hasSize(1);
        assertThat(loy.errores().get(0).numeroFila()).isEqualTo(3);
        assertThat(loy.filas().get(0).descripcion).isEqualTo("Coca-Cola 600 ml");
        assertThat(loy.filas().get(0).activo).isTrue();
        assertThat(loy.filas().get(1).codigo).isEqualTo("7501000111112");
        assertThat(loy.filas().get(1).activo).as("Available for sale = N").isFalse();
        assertThat(loy.filas().get(1).minimo).isEqualTo(2);
    }

    @Test
    void myBusinessConCodigoDeBarrasVacioElUsuarioRemapeaAlCodigoDelProducto() throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve("mybusiness.csv"));
        Origen o = Origen.detectar(t.encabezados(true));
        Mapeo m = o.mapeoAutomatico(t.encabezados(true), CampoProducto.values());
        OpcionesImportacion op = OpcionesImportacion.porDefecto();
        PlanImportacion conBarras = PreparacionProductos.preparar(t, o, m, op, catalogo(true));
        assertThat(conBarras.errores()).hasSize(1);
        m.asignar(CampoProducto.CODIGO, 0);
        PlanImportacion conClave = PreparacionProductos.preparar(t, o, m, op, catalogo(true));
        assertThat(conClave.errores()).isEmpty();
        assertThat(conClave.filas()).extracting(f -> f.codigo).containsExactly("COCA600", "JIT", "BIMBOG");
        assertThat(conClave.filas().get(1).tipoVenta).as("Unidad KG").isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(conClave.filas().get(2).departamento).isEqualTo("PANADERÍA");
    }

    @Test
    void interpretacionDeUnidadesYActivo() {
        assertThat(PreparacionProductos.interpretarUnidad("Pieza", UNIDADES).unidad().getNombre()).isEqualTo("Pieza");
        assertThat(PreparacionProductos.interpretarUnidad("KG", UNIDADES).tipo()).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(PreparacionProductos.interpretarUnidad("Lt", UNIDADES).unidad().getNombre()).isEqualTo("Litro");
        assertThat(PreparacionProductos.interpretarUnidad("caja", UNIDADES).unidad().getNombre()).as("la unidad del catálogo manda").isEqualTo("Caja");
        assertThat(PreparacionProductos.interpretarUnidad("cartón", UNIDADES).tipo()).isEqualTo(Producto.TipoVenta.PAQUETE);
        assertThat(PreparacionProductos.interpretarUnidad("Paquete", UNIDADES).tipo()).isEqualTo(Producto.TipoVenta.PAQUETE);
        assertThat(PreparacionProductos.interpretarUnidad("GRANEL", UNIDADES).unidad().getNombre()).isEqualTo("Kilogramo");
        assertThat(PreparacionProductos.interpretarUnidad("S", UNIDADES).tipo()).isEqualTo(Producto.TipoVenta.GRANEL);
        assertThat(PreparacionProductos.interpretarUnidad("N", UNIDADES).tipo()).isEqualTo(Producto.TipoVenta.UNIDAD);
        assertThat(PreparacionProductos.interpretarUnidad("docena", UNIDADES)).isNull();
        assertThat(PreparacionProductos.interpretarUnidad("", UNIDADES)).isNull();

        assertThat(PreparacionProductos.activo("Sí", false, true)).isTrue();
        assertThat(PreparacionProductos.activo("0", false, true)).isFalse();
        assertThat(PreparacionProductos.activo("Sí", true, true)).as("columna \"Descontinuado\" = Sí → inactivo").isFalse();
        assertThat(PreparacionProductos.activo("quizás", false, true)).isNull();
        assertThat(PreparacionProductos.activo("Sí", false, false)).isNull();
        assertThat(PreparacionProductos.normalizarCodigo(" 7501055300136.0 ")).isEqualTo("7501055300136");
        assertThat(PreparacionProductos.normalizarCodigo("ABC-1")).isEqualTo("ABC-1");
        assertThat(PreparacionProductos.normalizarCodigo("1.5")).isEqualTo("1.5");
    }

    @Test
    void descripcionLargaSeRecortaConAvisoYMayoreoMayorQueVentaAvisa() {
        String larga = "x".repeat(250);
        TablaImportada t = LectorCsv.leerTexto("a.csv", "Codigo,Descripcion,Precio,Mayoreo\n1," + larga + ",10,12\n", "UTF-8");
        Mapeo m = Mapeo.automatico(t.encabezados(true), CampoProducto.values());
        PlanImportacion plan = PreparacionProductos.preparar(t, Origen.GENERICO, m, OpcionesImportacion.porDefecto(), catalogo(true));
        assertThat(plan.errores()).isEmpty();
        FilaProducto f = plan.filas().get(0);
        assertThat(f.descripcion).hasSize(200);
        assertThat(f.avisos).anyMatch(a -> a.contains("recortada")).anyMatch(a -> a.contains("mayoreo"));
        assertThat(plan.conAvisos()).isEqualTo(1);
        assertThat(ReporteImportacion.previo(plan)).contains("Avisos").contains("recortada");
    }

    @Test
    void sinEncabezadoLasColumnasSeNombranPorLetraYLaFilaUnoCuenta() {
        TablaImportada t = LectorCsv.leerTexto("a.csv", "1,Uno,10\n2,Dos,20\n", "UTF-8");
        assertThat(t.encabezados(false)).containsExactly("Columna A", "Columna B", "Columna C");
        Mapeo m = new Mapeo();
        m.asignar(CampoProducto.CODIGO, 0);
        m.asignar(CampoProducto.DESCRIPCION, 1);
        m.asignar(CampoProducto.PRECIO_VENTA, 2);
        PlanImportacion plan = PreparacionProductos.preparar(t, Origen.GENERICO, m,
                new OpcionesImportacion(Duplicados.OMITIR, true, true, false), catalogo(true));
        assertThat(plan.filas()).hasSize(2);
        assertThat(plan.filas().get(0).numeroFila).isEqualTo(1);
    }
}
