package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.Promocion;

@Converter(autoApply = true)
public class TipoPromocionConverter extends EnumStringConverter<Promocion.Tipo> {
    public TipoPromocionConverter() { super(Promocion.Tipo.class); }
}
