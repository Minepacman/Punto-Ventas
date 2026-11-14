package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.model.CatalogoCodigo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lector del catalogo-mx.csv: columnas por nombre, comillas RFC 4180, filas inválidas descartadas, recortes */
class CatalogoCsvTest {

    static final String ENCABEZADO = "codigo,nombre,marca,cantidad,unidad,contenido,categoria,imagen,fuente,actualizado\n";

    @Test
    void leeLasColumnasPorNombreYConvierteLaCantidad() throws IOException {
        String csv = ENCABEZADO
                + "7501055300075,Coca-Cola,Coca-Cola,355,ml,355 ml,Bebidas,https://images.openfoodfacts.org/x/front.200.jpg,off,2026-03-13\n"
                + "7500326303234,Agua fresca con chía,,,,,Bebidas,,off,2024-05-31\n";
        List<CatalogoCodigo> filas = CatalogoCsv.leer(new StringReader(csv));
        assertThat(filas).hasSize(2);
        CatalogoCodigo coca = filas.get(0);
        assertThat(coca.getCodigo()).isEqualTo("7501055300075");
        assertThat(coca.getNombre()).isEqualTo("Coca-Cola");
        assertThat(coca.getMarca()).isEqualTo("Coca-Cola");
        assertThat(coca.getCantidad()).isEqualTo(355.0);
        assertThat(coca.getUnidad()).isEqualTo("ml");
        assertThat(coca.getContenido()).isEqualTo("355 ml");
        assertThat(coca.getCategoria()).isEqualTo("Bebidas");
        assertThat(coca.getImagen()).startsWith("https://");
        assertThat(coca.tieneImagen()).isTrue();
        assertThat(coca.getFuente()).isEqualTo("off");
        assertThat(coca.getActualizado()).isEqualTo("2026-03-13");
        CatalogoCodigo agua = filas.get(1);
        assertThat(agua.getMarca()).isNull();
        assertThat(agua.getCantidad()).isNull();
        assertThat(agua.getUnidad()).isNull();
        assertThat(agua.getContenido()).isNull();
        assertThat(agua.getImagen()).isNull();
        assertThat(agua.tieneImagen()).isFalse();
    }

    @Test
    void comillasComasYSaltosDentroDelCampo() throws IOException {
        String csv = ENCABEZADO
                + "7501055305339,\"Coca Cola \"\"Light\"\", sin azúcar\",\"Coca-Cola\",600,ml,\"600 ml\",Bebidas,,off,2026-01-01\r\n"
                + "96385074,\"Nombre en\ndos líneas\",Marca,,,,Otros,,manual,\r\n";
        List<CatalogoCodigo> filas = CatalogoCsv.leer(new StringReader(csv));
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0).getNombre()).isEqualTo("Coca Cola \"Light\", sin azúcar");
        assertThat(filas.get(0).getCantidad()).isEqualTo(600.0);
        assertThat(filas.get(1).getNombre()).isEqualTo("Nombre en dos líneas");   // el salto se vuelve espacio
        assertThat(filas.get(1).getActualizado()).isNull();
    }

    @Test
    void elOrdenDeLasColumnasNoImportaYSeIgnoranLasDesconocidas() throws IOException {
        String csv = "﻿nombre,extra,codigo,categoria\n"
                + "Sabritas Original,xyz,7501000111114,Botanas\n";
        List<CatalogoCodigo> filas = CatalogoCsv.leer(new StringReader(csv));
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).getCodigo()).isEqualTo("7501000111114");
        assertThat(filas.get(0).getNombre()).isEqualTo("Sabritas Original");
        assertThat(filas.get(0).getCategoria()).isEqualTo("Botanas");
        assertThat(filas.get(0).getMarca()).isNull();
    }

    @Test
    void filasSinCodigoValidoOSinNombreSeDescartanYSeCuentan() throws IOException {
        String csv = ENCABEZADO
                + "7501055300076,Dígito de control malo,,,,,Otros,,off,\n"     // check digit inválido
                + "PRE-0001,Código interno,,,,,Otros,,off,\n"
                + "7501055300075,,Sin nombre,,,,Otros,,off,\n"
                + "744886534234,UPC-A de 12 dígitos,Aga,600,ml,600 ml,Bebidas,http://inseguro/x.jpg,off,2026-04-02\n"
                + "\n";
        List<CatalogoCodigo> filas = new ArrayList<>();
        CatalogoCsv.Resultado r = CatalogoCsv.leer(new StringReader(csv), filas::add);
        assertThat(r.leidas()).isEqualTo(4);
        assertThat(r.validas()).isEqualTo(1);
        assertThat(r.descartadas()).isEqualTo(3);
        assertThat(filas.get(0).getCodigo()).isEqualTo("0744886534234");   // normalizado
        assertThat(filas.get(0).getImagen()).as("imagen que no es https se descarta").isNull();
    }

    @Test
    void losTextosSeRecortanALasLongitudesDeLaTabla() throws IOException {
        String largo = "x".repeat(500);
        String csv = ENCABEZADO + "7501055300075," + largo + "," + largo + ",1,kg," + largo + "," + largo + ",https://a/" + largo + "," + largo + "," + largo + "\n";
        CatalogoCodigo f = CatalogoCsv.leer(new StringReader(csv)).get(0);
        assertThat(f.getNombre()).hasSize(CatalogoCodigo.NOMBRE_MAX);
        assertThat(f.getMarca()).hasSize(CatalogoCodigo.MARCA_MAX);
        assertThat(f.getContenido()).hasSize(CatalogoCodigo.CONTENIDO_MAX);
        assertThat(f.getCategoria()).hasSize(CatalogoCodigo.CATEGORIA_MAX);
        assertThat(f.getImagen()).as("una URL que no cabe no se recorta: se descarta").isNull();
        assertThat(f.getFuente()).hasSize(CatalogoCodigo.FUENTE_MAX);
        assertThat(f.getActualizado()).hasSize(CatalogoCodigo.ACTUALIZADO_MAX);
    }

    @Test
    void cantidadesRarasSonNull() {
        assertThat(CatalogoCsv.parsearCantidad("600")).isEqualTo(600.0);
        assertThat(CatalogoCsv.parsearCantidad("1.5")).isEqualTo(1.5);
        assertThat(CatalogoCsv.parsearCantidad("")).isNull();
        assertThat(CatalogoCsv.parsearCantidad("null")).isNull();
        assertThat(CatalogoCsv.parsearCantidad("abc")).isNull();
        assertThat(CatalogoCsv.parsearCantidad("-3")).isNull();
        assertThat(CatalogoCsv.parsearCantidad("0")).isNull();
    }

    @Test
    void archivoVacioOSinColumnasEsError() {
        assertThatThrownBy(() -> CatalogoCsv.leer(new StringReader(""))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CatalogoCsv.leer(new StringReader("a,b,c\n1,2,3\n"))).isInstanceOf(IOException.class);
    }
}
