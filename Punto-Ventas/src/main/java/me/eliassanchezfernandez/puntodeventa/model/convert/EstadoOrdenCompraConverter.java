package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.OrdenCompra;

@Converter(autoApply = true)
public class EstadoOrdenCompraConverter extends EnumStringConverter<OrdenCompra.Estado> {
    public EstadoOrdenCompraConverter() { super(OrdenCompra.Estado.class); }
}
