package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.impresion.EscPos;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ModoImpresora;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ProtocoloBascula;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionDispositivosRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuración de dispositivos: impresora de tickets, cajón, báscula, lector
 * de códigos y terminales bancarias. Una sola fila. Leer no pide permiso (la
 * caja la consulta en cada venta, desde una copia en memoria); TODO cambio es
 * solo ADMIN, se verifica aquí y deja una fila en la bitácora con el antes y el después.
 */
@Service
public class DispositivosService {

    public static final int MAX_TERMINALES = 10;
    public static final int MAX_LARGO_TERMINAL = 40;
    public static final List<Integer> BAUDIOS_PERMITIDOS = List.of(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200);

    @Autowired private ConfiguracionDispositivosRepository repo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private ConfiguracionService configuracionService;

    private static final long VIGENCIA_CACHE_MS = 30_000;
    private record Copia(ConfiguracionDispositivos valor, long hasta) { }
    private volatile Copia copia;

    /** Configuración vigente, de una copia en memoria SOLO de lectura (guardar la invalida) */
    public ConfiguracionDispositivos obtener() {
        Copia c = copia;
        long ahora = System.nanoTime() / 1_000_000;
        if (c == null || ahora - c.hasta() >= 0) {
            c = new Copia(obtenerParaEditar(), ahora + VIGENCIA_CACHE_MS);
            copia = c;
        }
        return c.valor();
    }

    /** Fila viva (la crea si no existe), para modificarla dentro de una transacción */
    @Transactional
    public ConfiguracionDispositivos obtenerParaEditar() {
        return repo.findById(ConfiguracionDispositivos.ID_UNICO)
                .orElseGet(() -> repo.save(new ConfiguracionDispositivos()));
    }

    public void invalidarCache() {
        copia = null;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { copia = null; }
                    });
        }
    }

    private ConfiguracionDispositivos guardar(ConfiguracionDispositivos c) {
        ConfiguracionDispositivos g = repo.save(c);
        invalidarCache();
        return g;
    }

    // ── Impresora de tickets y cajón ─────────────────────────────────────

    /**
     * Impresora de tickets (solo ADMIN, auditado). El ancho del papel (58/80)
     * es el del ticket: si cambia se guarda por ConfiguracionService.guardarTicket.
     */
    @Transactional
    public ConfiguracionDispositivos guardarImpresora(ModoImpresora modo, String nombreImpresora, String codePage,
                                                      boolean imprimirAutomatico, int copias,
                                                      boolean abrirCajonEfectivo, int anchoMm) {
        sesionService.verificarAdmin();
        if (modo == null) throw new NegocioException("Elige cómo se imprimen los tickets.");
        if (anchoMm != 58 && anchoMm != 80) throw new NegocioException("El ancho del papel debe ser de 58 u 80 mm.");
        if (copias < 1 || copias > ConfiguracionDispositivos.COPIAS_MAXIMO) {
            throw new NegocioException("Las copias deben estar entre 1 y " + ConfiguracionDispositivos.COPIAS_MAXIMO + ".");
        }
        String nombre = limpiar(nombreImpresora);
        ConfiguracionService.validarLargo(nombre, 200, "El nombre de la impresora", false);
        if (modo == ModoImpresora.TERMICA && nombre == null) {
            throw new NegocioException("Elige la impresora de tickets de la lista.");
        }
        EscPos.CodePage cp = EscPos.CodePage.de(codePage);

        ConfiguracionDispositivos c = obtenerParaEditar();
        String antes = impresora(c, configuracionService.obtener().getTicketAnchoMm());
        c.setModoImpresora(modo);
        c.setImpresoraNombre(nombre);
        c.setImpresoraCodePage(cp.name());
        c.setImprimirAutomatico(imprimirAutomatico);
        c.setCopias(copias);
        c.setAbrirCajonEfectivo(abrirCajonEfectivo);
        ConfiguracionDispositivos g = guardar(c);

        Configuracion negocio = configuracionService.obtener();
        if (negocio.getTicketAnchoMm() != anchoMm) {
            configuracionService.guardarTicket(negocio.getTicketEncabezado(), negocio.getTicketPie(),
                    negocio.getTicketRfc(), negocio.getTicketTelefono(), negocio.getTicketAgradecimiento(), anchoMm);
        }
        auditar("Impresora", antes, impresora(g, anchoMm));
        return g;
    }

    /** Solo la casilla "abrir el cajón al cobrar en efectivo" (diálogo de Cajón de dinero) */
    @Transactional
    public ConfiguracionDispositivos guardarCajon(boolean abrirCajonEfectivo) {
        sesionService.verificarAdmin();
        ConfiguracionDispositivos c = obtenerParaEditar();
        String antes = "abrirAlCobrarEnEfectivo=" + c.isAbrirCajonEfectivo();
        c.setAbrirCajonEfectivo(abrirCajonEfectivo);
        ConfiguracionDispositivos g = guardar(c);
        auditar("Cajón de dinero", antes, "abrirAlCobrarEnEfectivo=" + g.isAbrirCajonEfectivo());
        return g;
    }

    private static String impresora(ConfiguracionDispositivos c, int ancho) {
        return String.format("modo=%s, impresora=%s, codePage=%s, auto=%s, copias=%d, cajón=%s, papel=%dmm",
                c.getModoImpresora(), c.getImpresoraNombre(), c.getImpresoraCodePage(), c.isImprimirAutomatico(),
                c.getCopias(), c.isAbrirCajonEfectivo(), ancho);
    }

    // ── Báscula ──────────────────────────────────────────────────────────

    /** Parámetros de la báscula serial (solo ADMIN, auditado) */
    @Transactional
    public ConfiguracionDispositivos guardarBascula(boolean activa, String puerto, int baudios,
                                                    ProtocoloBascula protocolo, String comandoHex,
                                                    String regex, int timeoutMs) {
        sesionService.verificarAdmin();
        String puertoLimpio = limpiar(puerto);
        ConfiguracionService.validarLargo(puertoLimpio, 100, "El puerto", false);
        if (activa && puertoLimpio == null) throw new NegocioException("Elige el puerto de la báscula.");
        if (!BAUDIOS_PERMITIDOS.contains(baudios)) {
            throw new NegocioException("Velocidad no válida. Usa una de: " + BAUDIOS_PERMITIDOS + ".");
        }
        if (protocolo == null) throw new NegocioException("Elige el protocolo de la báscula.");
        String hex = limpiar(comandoHex) == null ? ConfiguracionDispositivos.COMANDO_PESO_POR_DEFECTO
                : limpiar(comandoHex).replace(" ", "").toUpperCase();
        if (protocolo == ProtocoloBascula.PETICION) comandoDesdeHex(hex);   // valida
        String exp = limpiar(regex) == null ? ConfiguracionDispositivos.REGEX_PESO_POR_DEFECTO : limpiar(regex);
        ConfiguracionService.validarLargo(exp, 200, "La expresión regular", false);
        validarRegex(exp);
        if (timeoutMs < 200 || timeoutMs > 10_000) {
            throw new NegocioException("La espera de la báscula debe estar entre 200 y 10000 ms.");
        }

        ConfiguracionDispositivos c = obtenerParaEditar();
        String antes = bascula(c);
        c.setBasculaActiva(activa);
        c.setBasculaPuerto(puertoLimpio);
        c.setBasculaBaudios(baudios);
        c.setProtocoloBascula(protocolo);
        c.setBasculaComando(hex);
        c.setBasculaRegex(exp);
        c.setBasculaTimeoutMs(timeoutMs);
        ConfiguracionDispositivos g = guardar(c);
        auditar("Báscula", antes, bascula(g));
        return g;
    }

    private static String bascula(ConfiguracionDispositivos c) {
        return String.format("activa=%s, puerto=%s, baudios=%d, protocolo=%s, comando=%s, regex=%s, espera=%dms",
                c.isBasculaActiva(), c.getBasculaPuerto(), c.getBasculaBaudios(), c.getProtocoloBascula(),
                c.getBasculaComando(), c.getBasculaRegex(), c.getBasculaTimeoutMs());
    }

    /** "05" o "1B 40" → bytes; NegocioException si no es hexadecimal válido */
    public static byte[] comandoDesdeHex(String hex) {
        String h = hex == null ? "" : hex.replace(" ", "");
        if (h.isEmpty() || h.length() % 2 != 0 || h.length() > 32 || !h.matches("[0-9A-Fa-f]+")) {
            throw new NegocioException("El comando debe estar en hexadecimal, en pares de dígitos (ej. 05).");
        }
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** Compila la expresión; rechaza las que tienen errores de sintaxis */
    public static Pattern validarRegex(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new NegocioException("La expresión regular no es válida: " + e.getDescription());
        }
    }

    // ── Lector de códigos ────────────────────────────────────────────────

    @Transactional
    public ConfiguracionDispositivos guardarLector(boolean sufijoEnter) {
        sesionService.verificarAdmin();
        ConfiguracionDispositivos c = obtenerParaEditar();
        String antes = "sufijoEnter=" + c.isLectorSufijoEnter();
        c.setLectorSufijoEnter(sufijoEnter);
        ConfiguracionDispositivos g = guardar(c);
        auditar("Lector de códigos", antes, "sufijoEnter=" + g.isLectorSufijoEnter());
        return g;
    }

    // ── Terminales bancarias ─────────────────────────────────────────────

    /** Lista de terminales (una por línea) y si se exigen los datos del voucher; solo ADMIN, auditado */
    @Transactional
    public ConfiguracionDispositivos guardarTerminales(String terminalesUnaPorLinea, boolean exigirDatos) {
        sesionService.verificarAdmin();
        List<String> lista = new java.util.ArrayList<>();
        for (String t : (terminalesUnaPorLinea == null ? "" : terminalesUnaPorLinea).split("\\R")) {
            String limpio = t.trim();
            if (limpio.isEmpty()) continue;
            ConfiguracionService.validarLargo(limpio, MAX_LARGO_TERMINAL, "El nombre de la terminal", false);
            if (lista.stream().noneMatch(x -> x.equalsIgnoreCase(limpio))) lista.add(limpio);
        }
        if (lista.isEmpty()) throw new NegocioException("Escribe al menos una terminal bancaria.");
        if (lista.size() > MAX_TERMINALES) {
            throw new NegocioException("Máximo " + MAX_TERMINALES + " terminales.");
        }
        ConfiguracionDispositivos c = obtenerParaEditar();
        String antes = "terminales=" + c.getListaTerminales() + ", exigir=" + c.isExigirDatosTarjeta();
        c.setTerminalesBancarias(String.join("\n", lista));
        c.setExigirDatosTarjeta(exigirDatos);
        ConfiguracionDispositivos g = guardar(c);
        auditar("Terminal bancaria", antes, "terminales=" + g.getListaTerminales() + ", exigir=" + g.isExigirDatosTarjeta());
        return g;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private void auditar(String seccion, String antes, String despues) {
        auditoriaService.registrar(sesionService.getCajeroActual(), "CONFIGURACION_MODIFICADA",
                "[" + seccion + "] antes: " + antes + " | después: " + despues, (Cajero) null);
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
