package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.MovimientoInventario;

@Converter(autoApply = true)
public class TipoMovimientoConverter extends EnumStringConverter<MovimientoInventario.Tipo> {
    public TipoMovimientoConverter() { super(MovimientoInventario.Tipo.class); }
}
