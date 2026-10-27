package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Receptor de facturas (CFDI 4.0): los datos fiscales de quien pide factura,
 * tal como aparecen en su Constancia de Situación Fiscal (el SAT rechaza el
 * timbrado si el nombre, el régimen o el código postal no coinciden).
 *
 * NO es el módulo de clientes a crédito ({@link Cliente}); es un catálogo
 * mínimo y aparte, solo para facturar.
 */
@Entity
@Table(name = "clientes_fiscales")
@Getter
@Setter
@NoArgsConstructor
public class ClienteFiscal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** RFC en mayúsculas, sin espacios (único) */
    @Column(nullable = false, unique = true, length = 13)
    private String rfc;

    /** Nombre o razón social EXACTAMENTE como en la CSF (sin régimen societario, ej. sin "S.A. DE C.V.") */
    @Column(nullable = false, length = 254)
    private String nombre;

    /** c_RegimenFiscal, ej. 612, 601, 616 */
    @Column(name = "regimen_fiscal", nullable = false, length = 3)
    private String regimenFiscal;

    /** Código postal del domicilio fiscal (5 dígitos) */
    @Column(name = "codigo_postal", nullable = false, length = 5)
    private String codigoPostal;

    /** c_UsoCFDI preferido, ej. G03, G01, S01 */
    @Column(name = "uso_cfdi", nullable = false, length = 4)
    private String usoCfdi;

    @Column(length = 254)
    private String email;

    @Column(name = "creado")
    private LocalDateTime creado = LocalDateTime.now();

    @Column(name = "modificado")
    private LocalDateTime modificado;

    public String getEmail() {
        return email == null || email.isBlank() ? null : email;
    }

    @Override
    public String toString() {
        return rfc + " — " + nombre;
    }
}
