package me.eliassanchezfernandez.puntodeventa.importacion;

import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;

import java.util.ArrayList;
import java.util.List;

/**
 * Una fila del archivo ya interpretada y validada, lista para aplicarse.
 * Los campos opcionales en null significan "no venía en el archivo: no tocar"
 * (en una actualización) o "valor por defecto" (en un alta).
 */
public final class FilaProducto {

    public enum Accion { CREAR, ACTUALIZAR, OMITIR }

    public final int numeroFila;
    public Accion accion;
    public String codigo;
    public String descripcion;
    public Double precioCosto;
    public Double precioVenta;
    public Double precioMayoreo;
    /** Nombre canónico del departamento (el existente o el que se creará); null = sin departamento o no mapeado */
    public String departamento;
    /** true si el departamento no existe aún en Caja Libre y se creará */
    public boolean departamentoNuevo;
    /** true si la columna de departamento venía mapeada (aun con valor vacío) */
    public boolean departamentoIndicado;
    public Unidad unidad;
    public Producto.TipoVenta tipoVenta;
    /** true si la columna de unidad venía mapeada y el valor se entendió */
    public boolean unidadIndicada;
    public Double existencia;
    public Double minimo;
    public Boolean activo;
    public String claveProdServ;
    public String claveUnidad;
    /** Id del producto de Caja Libre con el mismo código (ACTUALIZAR / OMITIR) */
    public Long idExistente;
    public boolean cambiaPrecios;
    public boolean cambiaExistencia;
    public final List<String> avisos = new ArrayList<>();

    public FilaProducto(int numeroFila) {
        this.numeroFila = numeroFila;
    }

    public void aviso(String texto) {
        avisos.add(texto);
    }
}
