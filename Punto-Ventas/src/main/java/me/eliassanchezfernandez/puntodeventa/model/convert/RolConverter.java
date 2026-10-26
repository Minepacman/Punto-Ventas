package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;

@Converter(autoApply = true)
public class RolConverter extends EnumStringConverter<Cajero.Rol> {
    public RolConverter() { super(Cajero.Rol.class); }
}
