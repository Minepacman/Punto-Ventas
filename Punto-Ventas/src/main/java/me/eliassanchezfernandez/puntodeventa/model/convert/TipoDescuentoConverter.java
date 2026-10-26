package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;

@Converter(autoApply = true)
public class TipoDescuentoConverter extends EnumStringConverter<DetalleVenta.TipoDescuento> {
    public TipoDescuentoConverter() { super(DetalleVenta.TipoDescuento.class); }
}
