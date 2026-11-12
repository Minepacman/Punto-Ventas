package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity @Table(name = "departamentos") @Data @NoArgsConstructor
public class Departamento {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String nombre;

    /**
     * Clave de producto/servicio del SAT (c_ClaveProdServ) por defecto para
     * facturar los productos de este departamento que no tengan la suya.
     * Se captura en Configuración → Facturación. Nullable (= 50000000 Alimentos, bebidas y tabaco).
     */
    @Column(name = "clave_prod_serv", length = 8)
    private String claveProdServ;
}