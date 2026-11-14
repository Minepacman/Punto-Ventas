package me.eliassanchezfernandez.puntodeventa.importacion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reconocimiento del programa de origen y propuesta de mapeo por encabezados reales */
class OrigenYMapeoTest {

    static final Path RECURSOS = Path.of("src/test/resources/importacion");

    private static Mapeo mapear(String archivo, Origen esperado) throws IOException {
        TablaImportada t = LectorArchivo.leer(RECURSOS.resolve(archivo));
        List<String> enc = t.encabezados(true);
        Origen o = Origen.detectar(enc);
        assertThat(o).as(archivo).isEqualTo(esperado);
        return o.mapeoAutomatico(enc, CampoProducto.values());
    }

    @Test
    void eleventaExportacionActual() throws IOException {
        Mapeo m = mapear("eleventa-export.csv", Origen.ELEVENTA);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).isEqualTo(0);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).as("\"Producto\" es la descripción").isEqualTo(1);
        assertThat(m.columnaDe(CampoProducto.PRECIO_COSTO)).isEqualTo(2);
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).isEqualTo(3);
        assertThat(m.columnaDe(CampoProducto.PRECIO_MAYOREO)).isEqualTo(4);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).isEqualTo(5);
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).isEqualTo(6);
        assertThat(m.columnaDe(CampoProducto.MINIMO)).isEqualTo(7);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).as("Tipo de Venta").isEqualTo(9);
        assertThat(m.columnaDe(CampoProducto.CLAVE_PROD_SERV)).isEqualTo(11);
        assertThat(m.columnaDe(CampoProducto.CLAVE_UNIDAD)).isEqualTo(12);
        assertThat(m.campoDeColumna(8)).as("Inv. Máximo no existe en Caja Libre").isNull();
        assertThat(m.campoDeColumna(10)).as("Proveedor no se importa por producto").isNull();
        assertThat(m.campoDeColumna(13)).as("IVA").isNull();
    }

    @Test
    void eleventaPlantillaOficialXls() throws IOException {
        Mapeo m = mapear("eleventa-plantilla.xls", Origen.ELEVENTA);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).isEqualTo(0);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).isEqualTo(1);
        assertThat(m.columnaDe(CampoProducto.PRECIO_MAYOREO)).isEqualTo(4);
        assertThat(m.columnaDe(CampoProducto.MINIMO)).isEqualTo(7);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).isEqualTo(9);
        assertThat(m.columnaDe(CampoProducto.CLAVE_PROD_SERV)).isEqualTo(12);
        assertThat(m.columnaDe(CampoProducto.CLAVE_UNIDAD)).as("\"Unidad de Medida (CFDI 3.3)\" es la clave SAT, no la unidad de venta").isEqualTo(13);
        assertThat(m.campoDeColumna(10)).as("Impuesto 1 (IVA)").isNull();
    }

    @Test
    void abarrotesPuntoDeVenta() throws IOException {
        Mapeo m = mapear("abarrotes-pdv.csv", Origen.ABARROTES_PDV);
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).as("\"Inventario\"").isEqualTo(5);
        assertThat(m.columnaDe(CampoProducto.MINIMO)).isEqualTo(6);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).isEqualTo(7);
        assertThat(m.tiene(CampoProducto.UNIDAD)).isFalse();
    }

    @Test
    void sicar() throws IOException {
        Mapeo m = mapear("sicar.csv", Origen.SICAR);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).as("Clave").isEqualTo(0);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).isEqualTo(2);
        assertThat(m.columnaDe(CampoProducto.MINIMO)).isEqualTo(4);
        assertThat(m.columnaDe(CampoProducto.PRECIO_COSTO)).as("Precio de Compra").isEqualTo(6);
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).as("Precio 1").isEqualTo(7);
        assertThat(m.columnaDe(CampoProducto.PRECIO_MAYOREO)).as("Precio 2").isEqualTo(8);
        assertThat(m.campoDeColumna(11)).as("\"Mayoreo 2\" es una cantidad, no se importa").isNull();
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).isEqualTo(14);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).isEqualTo(17);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).as("Granel (S/N)").isEqualTo(19);
    }

    @Test
    void myBusinessPos() throws IOException {
        Mapeo m = mapear("mybusiness.csv", Origen.MYBUSINESS);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).as("Código de barras manda sobre Código del producto").isEqualTo(12);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).isEqualTo(1);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).as("Linea").isEqualTo(2);
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).isEqualTo(3);
        assertThat(m.columnaDe(CampoProducto.PRECIO_COSTO)).as("Costo ultimo").isEqualTo(4);
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).isEqualTo(5);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).isEqualTo(6);
        assertThat(m.columnaDe(CampoProducto.PRECIO_MAYOREO)).as("Precio2").isEqualTo(7);
    }

    @Test
    void loyverse() throws IOException {
        Mapeo m = mapear("loyverse.csv", Origen.LOYVERSE);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).as("Barcode, no SKU ni Handle").isEqualTo(16);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).as("Name, no Description").isEqualTo(2);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).isEqualTo(3);
        assertThat(m.campoDeColumna(4)).as("Description (texto largo) no se importa").isNull();
        assertThat(m.columnaDe(CampoProducto.PRECIO_COSTO)).isEqualTo(5);
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).isEqualTo(6);
        assertThat(m.columnaDe(CampoProducto.ACTIVO)).as("Available for sale").isEqualTo(8);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).as("Sold by weight").isEqualTo(9);
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).as("In stock").isEqualTo(23);
        assertThat(m.columnaDe(CampoProducto.MINIMO)).as("Low stock").isEqualTo(24);
    }

    @Test
    void genericoPorAliasYPlantillaDeCajaLibre() throws IOException {
        Mapeo m = mapear("generico-errores.csv", Origen.GENERICO);
        assertThat(m.columnaDe(CampoProducto.CODIGO)).isEqualTo(0);
        assertThat(m.columnaDe(CampoProducto.DESCRIPCION)).as("Nombre").isEqualTo(1);
        assertThat(m.columnaDe(CampoProducto.PRECIO_COSTO)).isEqualTo(2);
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).isEqualTo(3);
        assertThat(m.columnaDe(CampoProducto.DEPARTAMENTO)).as("Depto").isEqualTo(4);
        assertThat(m.columnaDe(CampoProducto.EXISTENCIA)).as("Cantidad").isEqualTo(5);
        assertThat(m.columnaDe(CampoProducto.UNIDAD)).as("Granel").isEqualTo(6);

        List<String> plantilla = PlantillaImportacion.encabezadosProductos();
        assertThat(Origen.detectar(plantilla)).isEqualTo(Origen.CAJA_LIBRE);
        Mapeo mp = Origen.CAJA_LIBRE.mapeoAutomatico(plantilla, CampoProducto.values());
        for (CampoProducto c : CampoProducto.values()) {
            assertThat(mp.columnaDe(c)).as(c.name()).isEqualTo(c.ordinal());
        }
        Mapeo prov = Mapeo.automatico(PlantillaImportacion.encabezadosProveedores(), CampoProveedor.values());
        for (CampoProveedor c : CampoProveedor.values()) assertThat(prov.columnaDe(c)).isEqualTo(c.ordinal());
    }

    @Test
    void unaColumnaSoloAlimentaUnCampoYElUsuarioPuedeReasignar() {
        Mapeo m = Mapeo.automatico(List.of("Precio", "Precio"), CampoProducto.values());
        assertThat(m.columnaDe(CampoProducto.PRECIO_VENTA)).isEqualTo(0);
        assertThat(m.campoDeColumna(1)).isNull();
        m.asignar(CampoProducto.PRECIO_MAYOREO, 0);
        assertThat(m.tiene(CampoProducto.PRECIO_VENTA)).as("la columna 0 cambió de campo").isFalse();
        assertThat(m.columnaDe(CampoProducto.PRECIO_MAYOREO)).isEqualTo(0);
        assertThat(m.obligatoriosSinMapear(CampoProducto.values()))
                .containsExactly(CampoProducto.CODIGO, CampoProducto.DESCRIPCION, CampoProducto.PRECIO_VENTA);
        m.asignar(CampoProducto.PRECIO_MAYOREO, null);
        assertThat(m.comoMapa()).isEmpty();
    }

    @Test
    void losAliasNoSeRepitenEntreCampos() {
        java.util.Map<String, CampoProducto> vistos = new java.util.HashMap<>();
        for (CampoProducto c : CampoProducto.values()) {
            for (String a : c.alias()) {
                assertThat(a).as("alias normalizado").isEqualTo(Texto.clave(a));
                CampoProducto otro = vistos.put(a, c);
                assertThat(otro).as("alias \"" + a + "\" repetido en " + c + " y " + otro).isNull();
            }
        }
        assertThat(Texto.clave("Precio de Venta ($)")).isEqualTo("preciodeventa");
        assertThat(Texto.clave("Inv. Mínimo")).isEqualTo("invminimo");
    }
}
