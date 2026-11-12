package me.eliassanchezfernandez.puntodeventa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Dispositivos y terminal bancaria: impresora de tickets, cajón de dinero,
 * báscula serial, lector de códigos y lista de terminales bancarias. Una sola
 * fila (id = 1) que DispositivosService crea con valores por defecto. Va en una
 * tabla aparte de {@link Configuracion} para no mezclar lo del negocio con lo
 * del equipo; todas las columnas son nullable (el accesor da el valor por
 * defecto) para convivir con bases ya existentes.
 *
 * El ancho del papel (58/80 mm) no está aquí: sigue siendo el del ticket
 * ({@link Configuracion#getTicketAnchoMm()}).
 */
@Entity
@Table(name = "configuracion_dispositivos")
@Getter
@Setter
@NoArgsConstructor
public class ConfiguracionDispositivos {

    public static final long ID_UNICO = 1L;

    /** Cómo se imprimen los tickets */
    public enum ModoImpresora {
        NINGUNA("Ninguna"),
        TERMICA("Impresora de tickets ESC/POS (58/80 mm)"),
        SISTEMA("Impresora normal (sistema)");

        private final String etiqueta;
        ModoImpresora(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        @Override public String toString() { return etiqueta; }
    }

    /** Cómo entrega el peso la báscula */
    public enum ProtocoloBascula {
        /** Se envía un comando (ENQ, 0x05) y la báscula responde con el peso (Torrey, CAS...) */
        PETICION("Pedir el peso (enviar ENQ y leer)"),
        /** La báscula manda el peso sola, varias veces por segundo */
        CONTINUO("Flujo continuo (solo leer)");

        private final String etiqueta;
        ProtocoloBascula(String etiqueta) { this.etiqueta = etiqueta; }
        public String getEtiqueta() { return etiqueta; }
        @Override public String toString() { return etiqueta; }
    }

    public static final int COPIAS_POR_DEFECTO = 1;
    public static final int COPIAS_MAXIMO = 5;
    public static final int BAUDIOS_POR_DEFECTO = 9600;
    public static final int TIMEOUT_BASCULA_POR_DEFECTO_MS = 1500;
    /** Número decimal (con punto o coma): toma el primero que aparezca en la trama */
    public static final String REGEX_PESO_POR_DEFECTO = "(\\d+(?:[.,]\\d+)?)";
    public static final String COMANDO_PESO_POR_DEFECTO = "05";
    public static final String TERMINALES_POR_DEFECTO = "Terminal principal";

    @Id
    private Long id = ID_UNICO;

    // ── Impresora de tickets ─────────────────────────────────────────────

    @Column(name = "impresora_modo", length = 20)
    private String impresoraModo;

    @Column(name = "impresora_nombre", length = 200)
    private String impresoraNombre;

    @Column(name = "impresora_code_page", length = 10)
    private String impresoraCodePage;

    @Column(name = "imprimir_automatico")
    private Boolean imprimirAutomatico;

    @Column(name = "impresora_copias")
    private Integer copias;

    @Column(name = "abrir_cajon_efectivo")
    private Boolean abrirCajonEfectivo;

    // ── Báscula ──────────────────────────────────────────────────────────

    @Column(name = "bascula_activa")
    private Boolean basculaActiva;

    @Column(name = "bascula_puerto", length = 100)
    private String basculaPuerto;

    @Column(name = "bascula_baudios")
    private Integer basculaBaudios;

    @Column(name = "bascula_protocolo", length = 20)
    private String basculaProtocolo;

    /** Comando de petición en hexadecimal (ej. "05" = ENQ) */
    @Column(name = "bascula_comando", length = 40)
    private String basculaComando;

    /** Expresión regular que extrae el peso (primer grupo, o toda la coincidencia) */
    @Column(name = "bascula_regex", length = 200)
    private String basculaRegex;

    @Column(name = "bascula_timeout_ms")
    private Integer basculaTimeoutMs;

    // ── Lector de códigos ────────────────────────────────────────────────

    /** Solo informativo: el lector manda Enter al final de cada código */
    @Column(name = "lector_sufijo_enter")
    private Boolean lectorSufijoEnter;

    // ── Terminal bancaria ────────────────────────────────────────────────

    /** Nombres de las terminales, uno por línea */
    @Column(name = "terminales_bancarias", length = 600)
    private String terminalesBancarias;

    /** Exigir terminal, últimos 4 dígitos y autorización en cada pago con tarjeta */
    @Column(name = "exigir_datos_tarjeta")
    private Boolean exigirDatosTarjeta;

    // ── Accesores con valor por defecto ──────────────────────────────────

    public ModoImpresora getModoImpresora() {
        try {
            return impresoraModo == null ? ModoImpresora.NINGUNA : ModoImpresora.valueOf(impresoraModo);
        } catch (IllegalArgumentException e) {
            return ModoImpresora.NINGUNA;
        }
    }

    public void setModoImpresora(ModoImpresora modo) {
        this.impresoraModo = modo == null ? null : modo.name();
    }

    public ProtocoloBascula getProtocoloBascula() {
        try {
            return basculaProtocolo == null ? ProtocoloBascula.PETICION : ProtocoloBascula.valueOf(basculaProtocolo);
        } catch (IllegalArgumentException e) {
            return ProtocoloBascula.PETICION;
        }
    }

    public void setProtocoloBascula(ProtocoloBascula protocolo) {
        this.basculaProtocolo = protocolo == null ? null : protocolo.name();
    }

    public String getImpresoraCodePage()    { return impresoraCodePage != null ? impresoraCodePage : "CP850"; }
    public boolean isImprimirAutomatico()   { return imprimirAutomatico != null && imprimirAutomatico; }
    public int getCopias()                  { return copias != null && copias >= 1 ? copias : COPIAS_POR_DEFECTO; }
    public boolean isAbrirCajonEfectivo()   { return abrirCajonEfectivo != null && abrirCajonEfectivo; }

    public boolean isBasculaActiva()        { return basculaActiva != null && basculaActiva; }
    public int getBasculaBaudios()          { return basculaBaudios != null ? basculaBaudios : BAUDIOS_POR_DEFECTO; }
    public String getBasculaComando()       { return basculaComando != null && !basculaComando.isBlank() ? basculaComando : COMANDO_PESO_POR_DEFECTO; }
    public String getBasculaRegex()         { return basculaRegex != null && !basculaRegex.isBlank() ? basculaRegex : REGEX_PESO_POR_DEFECTO; }
    public int getBasculaTimeoutMs()        { return basculaTimeoutMs != null ? basculaTimeoutMs : TIMEOUT_BASCULA_POR_DEFECTO_MS; }

    public boolean isLectorSufijoEnter()    { return lectorSufijoEnter == null || lectorSufijoEnter; }

    public boolean isExigirDatosTarjeta()   { return exigirDatosTarjeta != null && exigirDatosTarjeta; }

    public String getTerminalesBancarias() {
        return terminalesBancarias != null && !terminalesBancarias.isBlank() ? terminalesBancarias : TERMINALES_POR_DEFECTO;
    }

    /** Lista de nombres de terminal (sin vacíos ni repetidos, en el orden configurado) */
    public List<String> getListaTerminales() {
        List<String> lista = new ArrayList<>();
        for (String t : getTerminalesBancarias().split("\\R")) {
            String limpio = t.trim();
            if (!limpio.isEmpty() && lista.stream().noneMatch(x -> x.equalsIgnoreCase(limpio))) lista.add(limpio);
        }
        return lista;
    }
}
