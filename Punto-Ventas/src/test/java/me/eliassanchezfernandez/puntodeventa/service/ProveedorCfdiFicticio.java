package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CfdiDocumento;
import me.eliassanchezfernandez.puntodeventa.facturacion.PacException;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PAC en memoria para las pruebas de FacturacionService: guarda lo timbrado
 * por número de orden (para simular la consulta tras un tiempo agotado) y se
 * le puede ordenar que falle de forma definitiva o ambigua.
 */
public class ProveedorCfdiFicticio implements ProveedorCfdi {

    public final List<CfdiDocumento> timbrados = new ArrayList<>();
    public final Map<String, Timbrado> porOrden = new LinkedHashMap<>();
    public final List<String> cancelados = new ArrayList<>();
    public final List<String> correos = new ArrayList<>();
    public PacException fallaTimbrar;
    /** Si es true, timbra "en el PAC" (queda registrado) pero la respuesta se pierde (tiempo agotado) */
    public boolean perderRespuesta;
    public boolean fallarDescarga;
    public boolean cancelacionEnEspera;
    public int consultasPorOrden;

    @Override
    public Timbrado timbrar(CfdiDocumento documento) throws PacException {
        if (fallaTimbrar != null) throw fallaTimbrar;
        timbrados.add(documento);
        Timbrado t = new Timbrado("fid-" + timbrados.size(), UUID.randomUUID().toString().toUpperCase(),
                documento.serie(), documento.folio(), LocalDateTime.now(), documento.total());
        porOrden.put(documento.numeroOrden(), t);
        if (perderRespuesta) {
            perderRespuesta = false;
            throw new PacException(PacException.Tipo.RED, "El PAC tardó demasiado en responder.");
        }
        return t;
    }

    @Override
    public Optional<Timbrado> buscarPorNumeroOrden(String numeroOrden) {
        consultasPorOrden++;
        return Optional.ofNullable(porOrden.get(numeroOrden));
    }

    @Override
    public Archivo descargar(String idExterno, Formato formato) throws PacException {
        if (fallarDescarga) throw new PacException(PacException.Tipo.RED, "sin red");
        String cuerpo = formato == Formato.PDF ? "%PDF-1.4 " + idExterno : "<cfdi:Comprobante id=\"" + idExterno + "\"/>";
        return new Archivo(cuerpo.getBytes(StandardCharsets.UTF_8), formato == Formato.PDF ? "application/pdf" : "application/xml");
    }

    @Override
    public Cancelacion cancelar(String idExterno, MotivoCancelacion motivo, String uuidSustitucion) {
        cancelados.add(idExterno + ":" + motivo.getClave() + (uuidSustitucion != null ? ":" + uuidSustitucion : ""));
        return cancelacionEnEspera ? new Cancelacion(false, true, "pending", "espera del receptor")
                : new Cancelacion(true, false, "canceled", null);
    }

    @Override
    public void enviarPorCorreo(String idExterno, String email) {
        correos.add(idExterno + "→" + email);
    }

    @Override
    public void probarConexion() { }
}
