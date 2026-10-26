package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.Converter;
import me.eliassanchezfernandez.puntodeventa.model.Venta;

@Converter(autoApply = true)
public class FormaPagoConverter extends EnumStringConverter<Venta.FormaPago> {
    public FormaPagoConverter() { super(Venta.FormaPago.class); }
}
