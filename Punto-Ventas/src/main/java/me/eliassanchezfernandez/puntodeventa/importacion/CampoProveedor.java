package me.eliassanchezfernandez.puntodeventa.importacion;

import java.util.List;

/** Campos de proveedor importables (Proveedores → catálogo). Mismo criterio de alias que {@link CampoProducto}. */
public enum CampoProveedor implements Campo {

    NOMBRE("Nombre", true, "Obligatorio y único. Hasta 150 caracteres.",
            "nombre", "proveedor", "nombredelproveedor", "nombreproveedor", "razonsocial", "empresa", "compania",
            "name", "supplier", "suppliername", "vendor", "descripcion"),
    RFC("RFC", false, "Opcional, 12 o 13 caracteres.",
            "rfc", "rfcproveedor", "taxid"),
    TELEFONO("Teléfono", false, "Opcional. Números, espacios y + ( ) - .",
            "telefono", "tel", "telefonos", "celular", "movil", "whatsapp", "phone", "phonenumber", "telefono1"),
    CONTACTO("Contacto", false, "Persona de contacto o repartidor.",
            "contacto", "persona", "personadecontacto", "vendedor", "representante", "agente", "repartidor", "contact",
            "contactname", "atencion"),
    NOTAS("Notas", false, "Texto libre (correo, dirección, condiciones...).",
            "notas", "nota", "observaciones", "comentarios", "direccion", "correo", "email", "notes", "comments",
            "address");

    private final String etiqueta;
    private final boolean obligatorio;
    private final String ayuda;
    private final List<String> alias;

    CampoProveedor(String etiqueta, boolean obligatorio, String ayuda, String... alias) {
        this.etiqueta = etiqueta;
        this.obligatorio = obligatorio;
        this.ayuda = ayuda;
        this.alias = List.of(alias);
    }

    @Override public String etiqueta()     { return etiqueta; }
    @Override public List<String> alias()  { return alias; }
    @Override public boolean obligatorio() { return obligatorio; }
    @Override public String ayuda()        { return ayuda; }

    @Override public String toString() { return etiqueta; }
}
