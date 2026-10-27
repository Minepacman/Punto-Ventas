package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.BovedaCredenciales;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CredencialesPac;
import me.eliassanchezfernandez.puntodeventa.facturacion.Rfc;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.repository.ConfiguracionFiscalRepository;
import me.eliassanchezfernandez.puntodeventa.repository.DepartamentoRepository;
import me.eliassanchezfernandez.puntodeventa.util.RutasApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Configuración → Facturación: datos del emisor, serie, credenciales del PAC
 * (cifradas con la llave de la instalación) y claves SAT por defecto por
 * departamento. Todo cambio es solo ADMIN y queda en la bitácora SIN las
 * credenciales (solo el usuario enmascarado y si cambió la contraseña).
 */
@Service
public class ConfiguracionFiscalService {

    private static final Pattern SERIE = Pattern.compile("^[A-Za-z0-9]{1,10}$");

    @Autowired private ConfiguracionFiscalRepository repo;
    @Autowired private DepartamentoRepository departamentoRepo;
    @Autowired private SesionService sesionService;
    @Autowired private AuditoriaService auditoriaService;
    @Autowired private RutasApp rutas;

    private volatile BovedaCredenciales boveda;

    /** Configuración vigente (la crea si no existe). Es una lectura sin permiso: la pantalla de ventas la consulta. */
    public ConfiguracionFiscal obtener() {
        return repo.findById(ConfiguracionFiscal.ID_UNICO).orElseGet(ConfiguracionFiscal::new);
    }

    @Transactional
    public ConfiguracionFiscal obtenerParaEditar() {
        return repo.findById(ConfiguracionFiscal.ID_UNICO).orElseGet(() -> repo.save(new ConfiguracionFiscal()));
    }

    /** ¿Hay emisor y credenciales? (la UI decide si muestra "Facturar") */
    public boolean facturacionLista() {
        return obtener().estaLista();
    }

    // ── Emisor y serie ───────────────────────────────────────────────────

    @Transactional
    public ConfiguracionFiscal guardarEmisor(String rfc, String nombre, String regimen, String codigoPostal, String serie) {
        sesionService.verificarAdmin();
        String rfcLimpio;
        String cp;
        try {
            rfcLimpio = Rfc.validar(rfc);
            cp = CatalogoSat.normalizarCodigoPostal(codigoPostal);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(e.getMessage());
        }
        if (Rfc.esGenerico(rfcLimpio)) throw new NegocioException("El RFC del emisor no puede ser el genérico.");
        if (cp == null) throw new NegocioException("El código postal del negocio (lugar de expedición) es obligatorio.");
        String nom = nombre == null ? "" : nombre.trim().replaceAll("\\s+", " ");
        if (nom.isEmpty()) throw new NegocioException("La razón social o nombre del emisor es obligatorio (como en la CSF).");
        ConfiguracionService.validarLargo(nom, 254, "El nombre del emisor", false);
        String reg = regimen == null ? "" : regimen.trim();
        if (!CatalogoSat.esRegimen(reg)) throw new NegocioException("Elige el régimen fiscal del negocio.");
        if (CatalogoSat.regimenesPara(rfcLimpio).stream().noneMatch(r -> r.clave().equals(reg))) {
            throw new NegocioException("El régimen " + reg + " no aplica para un RFC de "
                    + (Rfc.esPersonaMoral(rfcLimpio) ? "persona moral." : "persona física."));
        }
        String ser = serie == null ? "" : serie.trim().toUpperCase(Locale.ROOT);
        if (!ser.isEmpty() && !SERIE.matcher(ser).matches()) {
            throw new NegocioException("La serie admite hasta 10 letras o números (ej. A, FAC).");
        }

        ConfiguracionFiscal c = obtenerParaEditar();
        String antes = emisor(c);
        c.setEmisorRfc(rfcLimpio);
        c.setEmisorNombre(nom.toUpperCase(Locale.ROOT));
        c.setEmisorRegimen(reg);
        c.setEmisorCodigoPostal(cp);
        c.setSerie(ser.isEmpty() ? null : ser);
        ConfiguracionFiscal g = repo.save(c);
        auditar("Facturación: emisor", antes, emisor(g));
        return g;
    }

    private static String emisor(ConfiguracionFiscal c) {
        return String.format("rfc=%s, nombre=%s, régimen=%s, cp=%s, serie=%s",
                c.getEmisorRfc(), c.getEmisorNombre(), c.getEmisorRegimen(), c.getEmisorCodigoPostal(), c.getSerie());
    }

    // ── Credenciales del PAC ─────────────────────────────────────────────

    /**
     * Guarda usuario y contraseña de Facturama cifrados y el modo sandbox.
     * Contraseña vacía = conservar la actual (la pantalla nunca la muestra).
     */
    @Transactional
    public ConfiguracionFiscal guardarCredenciales(String usuario, String contrasena, boolean sandbox) {
        sesionService.verificarAdmin();
        String usr = usuario == null ? "" : usuario.trim();
        if (usr.isEmpty()) throw new NegocioException("Captura el usuario de la API de Facturama.");
        ConfiguracionService.validarLargo(usr, 200, "El usuario del PAC", false);
        if (contrasena != null) ConfiguracionService.validarLargo(contrasena, 200, "La contraseña del PAC", false);

        ConfiguracionFiscal c = obtenerParaEditar();
        boolean cambiaContrasena = contrasena != null && !contrasena.isEmpty();
        if (!cambiaContrasena && c.getPacContrasenaCifrada() == null) {
            throw new NegocioException("Captura la contraseña de la API de Facturama.");
        }
        String usuarioAntes = usuarioEnClaro(c);
        boolean sandboxAntes = c.isPacSandbox();
        c.setPacUsuarioCifrado(boveda().cifrar(usr));
        if (cambiaContrasena) c.setPacContrasenaCifrada(boveda().cifrar(contrasena));
        c.setPacSandbox(sandbox);
        ConfiguracionFiscal g = repo.save(c);
        // En la bitácora NUNCA van las credenciales: solo el usuario enmascarado
        auditar("Facturación: PAC",
                "usuario=" + BovedaCredenciales.enmascarar(usuarioAntes) + ", sandbox=" + sandboxAntes,
                "usuario=" + BovedaCredenciales.enmascarar(usr) + ", sandbox=" + sandbox
                        + (cambiaContrasena ? ", contraseña cambiada" : ""));
        return g;
    }

    /** Borra las credenciales guardadas (solo ADMIN) */
    @Transactional
    public void borrarCredenciales() {
        sesionService.verificarAdmin();
        ConfiguracionFiscal c = obtenerParaEditar();
        c.setPacUsuarioCifrado(null);
        c.setPacContrasenaCifrada(null);
        repo.save(c);
        auditar("Facturación: PAC", "credenciales guardadas", "credenciales borradas");
    }

    /** Usuario del PAC en claro (para mostrarlo enmascarado); null si no hay */
    public String usuarioEnClaro(ConfiguracionFiscal c) {
        try {
            return boveda().descifrar(c.getPacUsuarioCifrado());
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** Usuario enmascarado para la pantalla ("ab••••••@correo.com") */
    public String usuarioEnmascarado() {
        return BovedaCredenciales.enmascarar(usuarioEnClaro(obtener()));
    }

    /**
     * Credenciales descifradas para el cliente del PAC. Solo las usan los
     * servicios de facturación; nunca deben mostrarse ni registrarse.
     */
    public CredencialesPac credenciales() {
        ConfiguracionFiscal c = obtener();
        if (!c.tieneCredenciales()) {
            throw new NegocioException("Captura las credenciales de Facturama en Configuración → Facturación.");
        }
        try {
            return new CredencialesPac(boveda().descifrar(c.getPacUsuarioCifrado()),
                    boveda().descifrar(c.getPacContrasenaCifrada()), c.isPacSandbox());
        } catch (IllegalStateException e) {
            throw new NegocioException(e.getMessage());
        }
    }

    // ── Claves SAT por defecto por departamento ──────────────────────────

    /** Clave c_ClaveProdServ por departamento (id → clave; vacía = borrar). Solo ADMIN. */
    @Transactional
    public void guardarClavesDepartamentos(Map<Long, String> clavePorDepartamento) {
        sesionService.verificarAdmin();
        StringBuilder cambios = new StringBuilder();
        for (Map.Entry<Long, String> e : clavePorDepartamento.entrySet()) {
            Departamento d = departamentoRepo.findById(e.getKey()).orElse(null);
            if (d == null) continue;
            String clave;
            try {
                clave = CatalogoSat.normalizarProdServ(e.getValue());
            } catch (IllegalArgumentException ex) {
                throw new NegocioException(d.getNombre() + ": " + ex.getMessage());
            }
            if (java.util.Objects.equals(clave, d.getClaveProdServ())) continue;
            cambios.append(cambios.length() > 0 ? ", " : "").append(d.getNombre()).append(": ")
                    .append(d.getClaveProdServ()).append(" → ").append(clave);
            d.setClaveProdServ(clave);
            departamentoRepo.save(d);
        }
        if (cambios.length() > 0) auditar("Facturación: claves por departamento", "", cambios.toString());
    }

    /** Clave de producto/servicio a facturar: la del producto, o la del departamento, o la genérica de abarrotes */
    public static String claveProdServDe(Producto p) {
        if (p.getClaveProdServ() != null && !p.getClaveProdServ().isBlank()) return p.getClaveProdServ();
        Departamento d = p.getDepartamento();
        if (d != null && d.getClaveProdServ() != null && !d.getClaveProdServ().isBlank()) return d.getClaveProdServ();
        return CatalogoSat.PROD_SERV_ABARROTES;
    }

    /** Clave de unidad a facturar: la del producto, o la de su unidad de medida (o deducida), o Pieza */
    public static String claveUnidadDe(Producto p) {
        if (p.getClaveUnidad() != null && !p.getClaveUnidad().isBlank()) return p.getClaveUnidad();
        if (p.getUnidad() != null) return CatalogoSat.claveUnidadPara(p.getUnidad());
        return p.getTipoVenta() == Producto.TipoVenta.GRANEL ? "KGM" : CatalogoSat.UNIDAD_PIEZA;
    }

    /** Objeto de impuesto: el del producto o "02 Sí objeto" */
    public static String objetoImpDe(Producto p) {
        return p.getObjetoImp() != null && !p.getObjetoImp().isBlank() ? p.getObjetoImp() : CatalogoSat.OBJETO_IMP_SI;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    BovedaCredenciales boveda() {
        BovedaCredenciales b = boveda;
        if (b == null) boveda = b = new BovedaCredenciales(rutas.carpetaDatos());
        return b;
    }

    private void auditar(String seccion, String antes, String despues) {
        auditoriaService.registrar(sesionService.getCajeroActual(), "CONFIGURACION_MODIFICADA",
                "[" + seccion + "] antes: " + antes + " | después: " + despues, (Cajero) null);
    }
}
