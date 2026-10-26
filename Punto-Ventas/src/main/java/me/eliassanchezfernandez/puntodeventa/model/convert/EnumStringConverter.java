package me.eliassanchezfernandez.puntodeventa.model.convert;

import jakarta.persistence.AttributeConverter;

/**
 * Base para guardar enums como texto SIN la restricción CHECK que Hibernate
 * genera con @Enumerated(STRING). En SQLite esa restricción no se puede
 * modificar después, así que agregar un valor nuevo al enum (p. ej. un rol)
 * obligaría a reconstruir la tabla. Con un converter el campo es un varchar
 * normal y los valores nuevos entran sin migración.
 */
public abstract class EnumStringConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> tipo;

    protected EnumStringConverter(Class<E> tipo) {
        this.tipo = tipo;
    }

    @Override
    public String convertToDatabaseColumn(E valor) {
        return valor == null ? null : valor.name();
    }

    @Override
    public E convertToEntityAttribute(String texto) {
        if (texto == null || texto.isBlank()) return null;
        return Enum.valueOf(tipo, texto.trim());
    }
}
