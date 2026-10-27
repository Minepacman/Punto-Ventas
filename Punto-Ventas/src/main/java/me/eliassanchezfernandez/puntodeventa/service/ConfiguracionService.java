package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Configuracion;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Configuración del negocio guardada en la BD (CFG-01, VEN-04, VEN-14).
 * Una sola fila; se crea con valores por defecto la primera vez.
 *
 * Todo cambio es solo ADMIN (se verifica aquí, no en la pantalla) y deja una
 * fila en la bitácora con el antes y el después.
 */
@Service
public class ConfiguracionService {

    @Autowired private ConfiguracionRepository configuracionRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private VentaRepository ventaRepo;

    private static final Pattern RFC = Pattern.compile("^[A-ZÑ&0-9]{12,13}$");
    private static final Pattern CODIGO_MONEDA = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern PREFIJO_FOLIO = Pattern.compile("^[A-Za-z0-9._-]{0,6}$");
    private static final long FOLIO_MAXIMO = 999_999_999L;

    @Value("${app.nombre-tienda:Mi Tienda}")
    private String nombreTiendaInicial;

    /** Segundos que se confía en la copia en memoria si nadie la invalida (guardar la invalida siempre) */
    private static final long VIGENCIA_CACHE_MS = 30_000;

    private record CopiaEnMemoria(Configuracion valor, long hasta) { }

    private volatile CopiaEnMemoria copia;

    /**
     * Configuración vigente (la crea si no existe). Se lee de una copia en
     * memoria: se consulta en cada venta y en cada pantalla, y en un disco
     * duro lento cada consulta cuesta. La copia SOLO es para lectura: quien
     * va a modificar usa obtenerParaEditar(). Guardar la invalida.
     */
    public Configuracion obtener() {
        CopiaEnMemoria c = copia;
        long ahora = System.nanoTime() / 1_000_000;
        if (c == null || ahora - c.hasta() >= 0) {
            c = new CopiaEnMemoria(obtenerParaEditar(), ahora + VIGENCIA_CACHE_MS);
            copia = c;
        }
        Moneda.configurar(c.valor().getMonedaSimbolo(), c.valor().getMonedaCodigo());   // formato único de dinero
        return c.valor();
    }

    /** Fila viva de la base de datos (la crea si no existe), para modificarla dentro de una transacción */
    @Transactional
    public Configuracion obtenerParaEditar() {
        return configuracionRepo.findById(Configuracion.ID_UNICO).orElseGet(() -> {
            Configuracion nueva = new Configuracion();
            nueva.setNombreTienda(nombreTiendaInicial != null && !nombreTiendaInicial.isBlank()
                    ? nombreTiendaInicial : "Mi Tienda");
            return configuracionRepo.save(nueva);
        });
    }

    /** Descarta la copia en memoria (también cuando la transacción termina, por si se revierte) */
    public void invalidarCache() {
        copia = null;
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { copia = null; }
                    });
        }
    }

    private Configuracion guardarConfig(Configuracion c) {
        Configuracion g = configuracionRepo.save(c);
        invalidarCache();
        return g;
    }

    public String getNombreTienda() {
        return obtener().getNombreTienda();
    }

    /** Tasa de IVA como fracción (0.16) */
    public double getTasaIva() {
        return obtener().getTasaIva();
    }

    public int getPlazoCancelacionHoras() {
        return obtener().getPlazoCancelacionHoras();
    }

    /**
     * Solo ADMIN. tasaIvaPorcentaje se captura como 16 (no 0.16).
     * El plazo de cancelación nuevo solo aplica a ventas futuras (VEN-19).
     */
    @Transactional
    public Configuracion guardar(String nombreTienda, String direccion,
                                 double tasaIvaPorcentaje, int plazoCancelacionHoras) {
        sesionService.verificarAdmin();

        if (nombreTienda == null || nombreTienda.isBlank()) {
            throw new NegocioException("El nombre del negocio es obligatorio.");
        }
        validarLargo(nombreTienda, 150, "El nombre del negocio");
        validarLargo(direccion, 250, "La dirección");
        if (!Double.isFinite(tasaIvaPorcentaje) || tasaIvaPorcentaje < 0 || tasaIvaPorcentaje > 100) {
            throw new NegocioException("La tasa de IVA debe estar entre 0 y 100 %.");
        }
        if (plazoCancelacionHoras < 0 || plazoCancelacionHoras > 24 * 365) {
            throw new NegocioException("El plazo de cancelación debe estar entre 0 y 8760 horas.");
        }

        Configuracion c = obtenerParaEditar();
        String antes = String.format("tienda=%s, iva=%.2f%%, plazo=%dh",
                c.getNombreTienda(), c.getTasaIva() * 100, c.getPlazoCancelacionHoras());
        c.setNombreTienda(nombreTienda.trim());
        c.setDireccion(direccion == null || direccion.isBlank() ? null : direccion.trim());
        c.setTasaIva(Math.round(tasaIvaPorcentaje * 10000.0) / 1000000.0);
        c.setPlazoCancelacionHoras(plazoCancelacionHoras);
        Configuracion guardada = guardarConfig(c);

        auditar("Negocio", antes, String.format("tienda=%s, iva=%.2f%%, plazo=%dh",
                guardada.getNombreTienda(), guardada.getTasaIva() * 100, guardada.getPlazoCancelacionHoras()));
        return guardada;
    }

    // ── Opciones habilitadas ──────────────────────────────────────────────

    public boolean isPedirFondoCaja()    { return obtener().isPedirFondoCaja(); }
    public boolean isAvisoStockBajo()    { return obtener().isAvisoStockBajo(); }
    public boolean isPermitirGranel()    { return obtener().isPermitirGranel(); }
    public boolean isConfirmarCobro()    { return obtener().isConfirmarCobro(); }

    public boolean isMostrarSugerencias() { return obtener().isMostrarSugerencias(); }

    /** Compatibilidad: conserva el valor actual de "mostrar sugerencias" */
    @Transactional
    public Configuracion guardarOpciones(boolean pedirFondoCaja, boolean avisoStockBajo,
                                         boolean permitirGranel, boolean confirmarCobro) {
        return guardarOpciones(pedirFondoCaja, avisoStockBajo, permitirGranel, confirmarCobro,
                obtener().isMostrarSugerencias());
    }

    @Transactional
    public Configuracion guardarOpciones(boolean pedirFondoCaja, boolean avisoStockBajo,
                                         boolean permitirGranel, boolean confirmarCobro,
                                         boolean mostrarSugerencias) {
        sesionService.verificarAdmin();
        Configuracion c = obtenerParaEditar();
        String antes = opciones(c);
        c.setPedirFondoCaja(pedirFondoCaja);
        c.setAvisoStockBajo(avisoStockBajo);
        c.setPermitirGranel(permitirGranel);
        c.setConfirmarCobro(confirmarCobro);
        c.setMostrarSugerencias(mostrarSugerencias);
        Configuracion g = guardarConfig(c);
        auditar("Opciones", antes, opciones(g));
        return g;
    }

    private static String opciones(Configuracion c) {
        return String.format("fondoCaja=%s, avisoStockBajo=%s, granel=%s, confirmarCobro=%s, sugerencias=%s",
                c.isPedirFondoCaja(), c.isAvisoStockBajo(), c.isPermitirGranel(), c.isConfirmarCobro(),
                c.isMostrarSugerencias());
    }

    // ── Ubicación del negocio y clima ─────────────────────────────────────

    /**
     * Coordenadas del negocio y el interruptor del clima (solo ADMIN). Las
     * coordenadas son lo único que se envía al servicio de clima; el clima
     * no puede activarse sin ellas. Vacías = se borran y el clima se apaga.
     */
    @Transactional
    public Configuracion guardarUbicacionClima(Double latitud, Double longitud, String ciudad, boolean climaActivo) {
        sesionService.verificarAdmin();
        if ((latitud == null) != (longitud == null)) {
            throw new NegocioException("Captura la latitud Y la longitud (o deja las dos vacías).");
        }
        if (latitud != null && (!Double.isFinite(latitud) || latitud < -90 || latitud > 90)) {
            throw new NegocioException("La latitud debe estar entre -90 y 90 (ej: 19.4326).");
        }
        if (longitud != null && (!Double.isFinite(longitud) || longitud < -180 || longitud > 180)) {
            throw new NegocioException("La longitud debe estar entre -180 y 180 (ej: -99.1332).");
        }
        if (climaActivo && latitud == null) {
            throw new NegocioException("Para activar el clima captura primero las coordenadas del negocio.");
        }
        validarLargo(ciudad, 100, "La ciudad");

        Configuracion c = obtenerParaEditar();
        String antes = ubicacion(c);
        c.setLatitud(latitud);
        c.setLongitud(longitud);
        c.setCiudad(limpiar(ciudad));
        c.setClimaActivo(climaActivo && latitud != null);
        Configuracion g = guardarConfig(c);
        auditar("Ubicación y clima", antes, ubicacion(g));
        return g;
    }

    private static String ubicacion(Configuracion c) {
        return String.format("ciudad=%s, lat=%s, lon=%s, clima=%s",
                c.getCiudad(), c.getLatitud(), c.getLongitud(), c.isClimaActivo());
    }

    // ── Ticket ────────────────────────────────────────────────────────────

    /** Datos que se guardan para el ticket (la impresión llega en otra etapa) */
    @Transactional
    public Configuracion guardarTicket(String encabezado, String pie, String rfc, String telefono,
                                       String agradecimiento, int anchoMm) {
        sesionService.verificarAdmin();
        if (anchoMm != 58 && anchoMm != 80) {
            throw new NegocioException("El ancho del ticket debe ser de 58 u 80 mm.");
        }
        String rfcLimpio = limpiar(rfc) == null ? null : limpiar(rfc).toUpperCase().replace(" ", "");
        if (rfcLimpio != null && !RFC.matcher(rfcLimpio).matches()) {
            throw new NegocioException("El RFC debe tener 12 o 13 caracteres (letras y números).");
        }
        validarLargo(encabezado, 250, "El encabezado", true);
        validarLargo(pie, 250, "El pie de ticket", true);
        validarLargo(telefono, 30, "El teléfono");
        validarLargo(agradecimiento, 150, "El mensaje de agradecimiento", true);

        Configuracion c = obtenerParaEditar();
        String antes = ticket(c);
        c.setTicketEncabezado(limpiar(encabezado));
        c.setTicketPie(limpiar(pie));
        c.setTicketRfc(rfcLimpio);
        c.setTicketTelefono(limpiar(telefono));
        c.setTicketAgradecimiento(limpiar(agradecimiento));
        c.setTicketAnchoMm(anchoMm);
        Configuracion g = guardarConfig(c);
        auditar("Ticket", antes, ticket(g));
        return g;
    }

    private static String ticket(Configuracion c) {
        return String.format("rfc=%s, tel=%s, ancho=%dmm, encabezado=%s, pie=%s, gracias=%s",
                c.getTicketRfc(), c.getTicketTelefono(), c.getTicketAnchoMm(),
                c.getTicketEncabezado(), c.getTicketPie(), c.getTicketAgradecimiento());
    }

    // ── Formas de pago ────────────────────────────────────────────────────

    /** Formas de pago que la caja acepta hoy (Crédito nunca: no hay módulo de clientes) */
    public List<Venta.FormaPago> formasPagoHabilitadas() {
        Configuracion c = obtener();
        List<Venta.FormaPago> r = new ArrayList<>();
        for (Venta.FormaPago f : Venta.FormaPago.values()) {
            if (c.permiteFormaPago(f)) r.add(f);
        }
        return r;
    }

    @Transactional
    public Configuracion guardarFormasPago(boolean efectivo, boolean tarjeta,
                                           boolean transferencia, boolean vales) {
        sesionService.verificarAdmin();
        if (!efectivo && !tarjeta && !transferencia && !vales) {
            throw new NegocioException("Debe quedar habilitada al menos una forma de pago.");
        }
        Configuracion c = obtenerParaEditar();
        String antes = formasPago(c);
        c.setPagoEfectivo(efectivo);
        c.setPagoTarjeta(tarjeta);
        c.setPagoTransferencia(transferencia);
        c.setPagoVales(vales);
        Configuracion g = guardarConfig(c);
        auditar("Formas de pago", antes, formasPago(g));
        return g;
    }

    private static String formasPago(Configuracion c) {
        return String.format("efectivo=%s, tarjeta=%s, transferencia=%s, vales=%s",
                c.isPagoEfectivo(), c.isPagoTarjeta(), c.isPagoTransferencia(), c.isPagoVales());
    }

    // ── Moneda ────────────────────────────────────────────────────────────

    @Transactional
    public Configuracion guardarMoneda(String simbolo, String codigo) {
        sesionService.verificarAdmin();
        String sim = simbolo == null ? "" : simbolo.trim();
        String cod = codigo == null ? "" : codigo.trim().toUpperCase();
        validarLargo(sim, 3, "El símbolo de la moneda");
        if (sim.isEmpty() || sim.length() > 3) {
            throw new NegocioException("El símbolo de la moneda debe tener de 1 a 3 caracteres (ej: $).");
        }
        if (!CODIGO_MONEDA.matcher(cod).matches()) {
            throw new NegocioException("El código de la moneda debe tener 3 letras (ej: MXN, USD).");
        }
        Configuracion c = obtenerParaEditar();
        String antes = c.getMonedaSimbolo() + " " + c.getMonedaCodigo();
        c.setMonedaSimbolo(sim);
        c.setMonedaCodigo(cod);
        Configuracion g = guardarConfig(c);
        Moneda.configurar(sim, cod);
        auditar("Moneda", antes, sim + " " + cod);
        return g;
    }

    // ── Folios (solo presentación; el id de la venta nunca cambia) ────────

    /** Número que tendrá la próxima venta (id + desfase) */
    public long siguienteNumeroFolio() {
        return ventaRepo.maxId() + 1 + obtener().getFolioDesfase();
    }

    /** Folio completo de la próxima venta, con prefijo */
    public String siguienteFolio() {
        return obtener().getFolioPrefijo() + siguienteNumeroFolio();
    }

    /**
     * Prefijo opcional y número deseado para la próxima venta. El número no
     * puede bajar del actual (así un folio nunca se repite); lo que se guarda
     * es un desfase de presentación, no se renumera ninguna venta.
     */
    @Transactional
    public Configuracion guardarFolios(String prefijo, long siguienteNumero) {
        sesionService.verificarAdmin();
        String pre = prefijo == null ? "" : prefijo.trim();
        if (!PREFIJO_FOLIO.matcher(pre).matches()) {
            throw new NegocioException("El prefijo admite hasta 6 letras, números, punto, guion o guion bajo (ej: A-).");
        }
        Configuracion c = obtenerParaEditar();
        long siguienteId = ventaRepo.maxId() + 1;
        long actual = siguienteId + c.getFolioDesfase();
        if (siguienteNumero < actual) {
            throw new NegocioException("El siguiente folio no puede ser menor que " + actual
                    + " (para no repetir folios ya usados).");
        }
        if (siguienteNumero > FOLIO_MAXIMO) {
            throw new NegocioException("El folio es demasiado grande.");
        }
        String antes = c.getFolioPrefijo() + actual;
        c.setFolioPrefijo(pre.isEmpty() ? null : pre);
        c.setFolioDesfase((int) (siguienteNumero - siguienteId));
        Configuracion g = guardarConfig(c);
        auditar("Folios", "siguiente=" + antes, "siguiente=" + pre + siguienteNumero);
        return g;
    }

    // ── Respaldo automático ───────────────────────────────────────────────

    @Transactional
    public Configuracion guardarRespaldoAutomatico(boolean activo, String carpeta, int copias) {
        sesionService.verificarAdmin();
        if (copias < 1 || copias > 60) {
            throw new NegocioException("Las copias a conservar deben estar entre 1 y 60.");
        }
        String ruta = carpeta == null ? "" : carpeta.trim();
        validarLargo(ruta, 500, "La carpeta de respaldos");
        if (activo) {
            if (ruta.isEmpty()) throw new NegocioException("Elige la carpeta donde se guardarán los respaldos.");
            try {
                Path dir = Path.of(ruta);
                Files.createDirectories(dir);
                if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
                    throw new NegocioException("No se puede escribir en la carpeta elegida.");
                }
            } catch (IOException | java.nio.file.InvalidPathException e) {
                throw new NegocioException("La carpeta de respaldos no es válida: " + e.getMessage());
            }
        }
        Configuracion c = obtenerParaEditar();
        String antes = String.format("activo=%s, carpeta=%s, copias=%d",
                c.isRespaldoAuto(), c.getRespaldoCarpeta(), c.getRespaldoCopias());
        c.setRespaldoAuto(activo);
        c.setRespaldoCarpeta(ruta.isEmpty() ? null : ruta);
        c.setRespaldoCopias(copias);
        Configuracion g = guardarConfig(c);
        auditar("Respaldo automático", antes, String.format("activo=%s, carpeta=%s, copias=%d",
                g.isRespaldoAuto(), g.getRespaldoCarpeta(), g.getRespaldoCopias()));
        return g;
    }

    /** Anota el día del último respaldo automático (lo llama el sistema, sin sesión) */
    @Transactional
    public void marcarRespaldoAutomatico(LocalDate dia) {
        Configuracion c = obtenerParaEditar();
        c.setRespaldoUltimo(dia);
        guardarConfig(c);
    }

    // ── Acceso móvil ──────────────────────────────────────────────────────

    public static final int PUERTO_MOVIL_MINIMO = 1024;
    public static final int PUERTO_MOVIL_MAXIMO = 65535;

    /** Valida el puerto del servidor móvil (1024-65535) */
    public static int validarPuertoMovil(int puerto) {
        if (puerto < PUERTO_MOVIL_MINIMO || puerto > PUERTO_MOVIL_MAXIMO) {
            throw new NegocioException("El puerto debe estar entre " + PUERTO_MOVIL_MINIMO
                    + " y " + PUERTO_MOVIL_MAXIMO + ".");
        }
        return puerto;
    }

    /** Guarda si el servidor móvil arranca con el programa y su puerto (solo ADMIN, auditado) */
    @Transactional
    public Configuracion guardarAccesoMovil(boolean activo, int puerto) {
        sesionService.verificarAdmin();
        validarPuertoMovil(puerto);
        Configuracion c = obtenerParaEditar();
        String antes = String.format("activo=%s, puerto=%d", c.isMovilActivo(), c.getMovilPuerto());
        c.setMovilActivo(activo);
        c.setMovilPuerto(puerto);
        Configuracion g = guardarConfig(c);
        auditar("Acceso móvil", antes, String.format("activo=%s, puerto=%d", g.isMovilActivo(), g.getMovilPuerto()));
        return g;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void auditar(String seccion, String antes, String despues) {
        auditoriaService.registrar(sesionService.getCajeroActual(), "CONFIGURACION_MODIFICADA",
                "[" + seccion + "] antes: " + antes + " | después: " + despues, (Cajero) null);
    }

    private static String limpiar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static void validarLargo(String texto, int max, String campo) {
        validarLargo(texto, max, campo, false);
    }

    /** Longitud máxima y sin caracteres de control (los saltos de línea solo donde se permiten) */
    static void validarLargo(String texto, int max, String campo, boolean permitirSaltos) {
        if (texto == null) return;
        if (texto.trim().length() > max) {
            throw new NegocioException(campo + " no puede pasar de " + max + " caracteres.");
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            boolean salto = c == '\n' || c == '\r';
            if (Character.isISOControl(c) && !(permitirSaltos && salto)) {
                throw new NegocioException(campo + " contiene caracteres no permitidos.");
            }
        }
    }
}
