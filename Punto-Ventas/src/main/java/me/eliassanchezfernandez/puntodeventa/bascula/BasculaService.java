package me.eliassanchezfernandez.puntodeventa.bascula;

import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionDispositivos.ProtocoloBascula;
import me.eliassanchezfernandez.puntodeventa.service.DispositivosService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Lectura del peso de una báscula serial. Siempre BLOQUEA hasta recibir el peso
 * o agotar la espera: llamar fuera del hilo de JavaFX. Falla con
 * {@link NegocioException} y un mensaje claro cuando no hay báscula, el puerto
 * no abre, no llega nada o la trama no trae un peso; nunca deja la venta
 * trabada (el cajero puede teclear la cantidad a mano).
 */
@Service
public class BasculaService {

    @Autowired private DispositivosService dispositivos;
    @Autowired private SesionService sesionService;

    /** Cómo se abre el canal serie (las pruebas lo cambian por uno falso) */
    private static final Function<ConfiguracionDispositivos, FlujoSerial> FLUJO_REAL =
            c -> new FlujoJSerialComm(c.getBasculaPuerto(), c.getBasculaBaudios());

    private Function<ConfiguracionDispositivos, FlujoSerial> fabricaFlujo = FLUJO_REAL;

    /** Cambia el canal serie (pruebas); null vuelve al real */
    public void setFabricaFlujo(Function<ConfiguracionDispositivos, FlujoSerial> fabrica) {
        this.fabricaFlujo = fabrica != null ? fabrica : FLUJO_REAL;
    }

    /** ¿Hay una báscula activa y con puerto elegido? (para mostrar el botón "Leer peso") */
    public boolean disponible() {
        ConfiguracionDispositivos c = dispositivos.obtener();
        return c.isBasculaActiva() && c.getBasculaPuerto() != null && !c.getBasculaPuerto().isBlank();
    }

    /** Puertos serie del equipo; lista vacía si no hay o si el soporte serie no funciona aquí */
    public List<String> listarPuertos() {
        try {
            return FlujoJSerialComm.listarPuertos();
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    /** Peso en kg de la báscula configurada. Requiere sesión. */
    public synchronized double leerPeso() {
        sesionService.requerirSesion();
        ConfiguracionDispositivos c = dispositivos.obtener();
        if (!c.isBasculaActiva()) throw new NegocioException("La báscula no está activada en Configuración → Báscula.");
        return leer(c);
    }

    /** Prueba con parámetros SIN guardar (pantalla de Configuración). Solo ADMIN. */
    public synchronized double probar(ConfiguracionDispositivos borrador) {
        sesionService.verificarAdmin();
        return leer(borrador);
    }

    double leer(ConfiguracionDispositivos c) {
        if (c.getBasculaPuerto() == null || c.getBasculaPuerto().isBlank()) {
            throw new NegocioException("No se ha elegido el puerto de la báscula.");
        }
        Pattern regex = DispositivosService.validarRegex(c.getBasculaRegex());
        try (FlujoSerial flujo = fabricaFlujo.apply(c)) {
            flujo.abrir();
            return leerDe(flujo, c, regex);
        } catch (IOException e) {
            throw new NegocioException("No se pudo comunicar con la báscula: " + e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            if (e instanceof NegocioException ne) throw ne;
            throw new NegocioException("La báscula no está disponible en este equipo (" + e.getClass().getSimpleName() + ").");
        }
    }

    /** Protocolo de lectura sobre un canal ya abierto (visible para las pruebas) */
    double leerDe(FlujoSerial flujo, ConfiguracionDispositivos c, Pattern regex) throws IOException {
        int espera = c.getBasculaTimeoutMs();
        flujo.limpiarEntrada();
        boolean peticion = c.getProtocoloBascula() == ProtocoloBascula.PETICION;
        if (peticion) flujo.escribir(DispositivosService.comandoDesdeHex(c.getBasculaComando()));

        ByteArrayOutputStream recibido = new ByteArrayOutputStream();
        long limite = System.nanoTime() + espera * 1_000_000L;
        while (System.nanoTime() < limite) {
            int resta = (int) Math.max(1, (limite - System.nanoTime()) / 1_000_000L);
            byte[] trozo = flujo.leer(Math.min(resta, 100));
            if (trozo.length == 0) continue;
            recibido.writeBytes(trozo);
            String texto = recibido.toString(StandardCharsets.ISO_8859_1);
            // Por petición basta una trama completa; en flujo continuo se esperan dos para no tomar una a medias
            if (PesoParser.hayTramaCompleta(texto, regex) && (peticion || cuentaTramas(texto) >= 2)) break;
        }
        String texto = recibido.toString(StandardCharsets.ISO_8859_1);
        if (texto.isEmpty()) {
            throw new NegocioException("La báscula no respondió. Revisa el cable, el puerto y la velocidad (baudios).");
        }
        double peso = PesoParser.ultimoPeso(texto, regex).orElseThrow(() -> new NegocioException(
                "No se encontró un peso en lo que mandó la báscula: \"" + limpio(texto)
                        + "\". Revisa el protocolo y la expresión regular."));
        if (peso <= 0) throw new NegocioException("La báscula marca 0. Coloca el producto en el plato.");
        return peso;
    }

    private static int cuentaTramas(String texto) {
        return (int) java.util.Arrays.stream(texto.split("[\\r\\n\\u0002\\u0003]+")).filter(s -> !s.isBlank()).count();
    }

    private static String limpio(String texto) {
        String t = texto.replaceAll("[\\r\\n\\u0002\\u0003]+", " | ").trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }
}
