package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;

@Converter(autoApply = true)
public class PermisoConverter extends EnumStringConverter<Permiso> {
    public PermisoConverter() { super(Permiso.class); }
}
