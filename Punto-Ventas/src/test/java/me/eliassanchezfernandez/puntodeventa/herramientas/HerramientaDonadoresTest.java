package me.eliassanchezfernandez.puntodeventa.herramientas;

import me.eliassanchezfernandez.puntodeventa.util.ClaveDonador;
import me.eliassanchezfernandez.puntodeventa.util.RevocacionesApoyo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Herramienta del desarrollador sobre una carpeta temporal (nunca la real):
 * crea llaves, emite PDV2 con vencimiento, renueva, revoca, publica la lista
 * firmada, busca y coteja mensajes de soporte contra el libro JSONL.
 */
class HerramientaDonadoresTest {

    @TempDir Path carpeta;

    private ByteArrayOutputStream salida;
    private HerramientaDonadores h;

    @BeforeEach
    void herramienta() throws Exception {
        salida = new ByteArrayOutputStream();
        h = new HerramientaDonadores(carpeta.resolve("dev"), new PrintStream(salida, true, StandardCharsets.UTF_8));
        h.crearLlaves();
    }

    private String texto() {
        return salida.toString(StandardCharsets.UTF_8);
    }

    private ClaveDonador verificador() throws Exception {
        return new ClaveDonador(h.leerPublica());
    }

    @Test
    void creaLlavesUnaSolaVez() throws Exception {
        assertThat(h.privada()).exists();
        assertThat(h.publica()).exists();
        assertThat(texto()).contains("kid " + ClaveDonador.kidDe(h.leerPublica()));
        assertThatThrownBy(() -> h.crearLlaves()).isInstanceOf(IllegalStateException.class);
        // La privada no está en el repositorio: la pública oficial del programa no es la de esta carpeta temporal
        assertThat(texto()).doesNotContain(ClaveDonador.CLAVE_PUBLICA_OFICIAL);
    }

    @Test
    void emiteUnaPdv2ConVencimientoYLaAnotaEnElLibro() throws Exception {
        ClaveDonador.Datos d = h.emitir("Abarrotes Lupita", null, 12, "300", "2026-11-12", "RASTREO123", "+5255");
        assertThat(d.esPdv2()).isTrue();
        assertThat(d.kid()).isEqualTo(ClaveDonador.kidDe(h.leerPublica()));
        assertThat(d.nivel()).isEqualTo("basico");
        assertThat(d.emision()).isEqualTo(LocalDate.now());
        assertThat(d.vence()).isEqualTo(LocalDate.now().plusMonths(12));
        assertThat(texto()).contains("AVISO")   // la pública temporal no es la oficial del programa
                .contains("PDV2.");
        String linea = Files.readString(h.libro()).trim();
        assertThat(linea).startsWith("{").contains("\"ev\":\"emitida\"", "\"id\":\"" + d.id() + "\"", "\"monto\":\"300\"",
                "\"rastreo\":\"RASTREO123\"", "\"contacto\":\"+5255\"", "\"clave\":\"PDV2.");
        // La clave del libro valida con la pública
        String clave = linea.substring(linea.indexOf("PDV2."), linea.indexOf('"', linea.indexOf("PDV2.")));
        assertThat(verificador().validar(clave)).isEqualTo(d);

        // Sin vencimiento y nivel plus
        ClaveDonador.Datos eterna = h.emitir("Otro", "plus", 0, null, null, null, null);
        assertThat(eterna.vence()).isNull();
        assertThat(eterna.nivelApoyo()).isEqualTo(ClaveDonador.Nivel.PLUS);
        assertThatThrownBy(() -> h.emitir("X", "oro", 12, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.emitir("", null, 12, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(h.registros()).hasSize(2);
    }

    @Test
    void renuevaConElMismoIdSinPerderDias() throws Exception {
        ClaveDonador.Datos d = h.emitir("Abarrotes Lupita", null, 12, null, null, null, null);
        ClaveDonador.Datos r = h.renovar(d.id(), 12, null, "300", "2027-10-01", null);
        assertThat(r.id()).isEqualTo(d.id());
        assertThat(r.nombre()).isEqualTo(d.nombre());
        assertThat(r.emision()).isEqualTo(LocalDate.now());
        assertThat(r.vence()).isEqualTo(d.vence().plusMonths(12));   // se suma al vencimiento, no a hoy
        HerramientaDonadores.Registro reg = h.registro(d.id()).orElseThrow();
        assertThat(reg.vence()).isEqualTo(r.vence());
        assertThat(reg.estado(LocalDate.now())).isEqualTo(ClaveDonador.Estado.VIGENTE);
        assertThatThrownBy(() -> h.renovar("ZZZZ9999", 12, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revocaYPublicaLaListaFirmada() throws Exception {
        ClaveDonador.Datos a = h.emitir("A", null, 12, null, null, null, null);
        ClaveDonador.Datos b = h.emitir("B", null, 12, null, null, null, null);
        h.revocar(a.id(), "clave publicada en un foro");
        h.revocar(a.id(), "otra vez");   // idempotente
        assertThat(h.registro(a.id()).orElseThrow().revocada()).isTrue();
        assertThat(h.registro(b.id()).orElseThrow().revocada()).isFalse();
        assertThatThrownBy(() -> h.renovar(a.id(), 12, null, null, null, null)).isInstanceOf(IllegalStateException.class);

        Path archivo = carpeta.resolve("claves").resolve("revocadas.pdvr");
        String textoLista = h.publicarRevocaciones(archivo);
        assertThat(archivo).exists();
        assertThat(Files.readString(archivo).trim()).isEqualTo(textoLista);
        RevocacionesApoyo.Lista lista = RevocacionesApoyo.verificar(textoLista, verificador());
        assertThat(lista.revocadas()).containsExactly(a.id());
        assertThat(lista.clavesRetiradas()).isEmpty();
        assertThat(lista.emitido()).isEqualTo(LocalDate.now());
        // Sin salida solo se imprime
        salida.reset();
        h.publicarRevocaciones(null);
        assertThat(texto().trim()).startsWith("PDVR1.");
    }

    @Test
    void buscaYVerificaMensajesDeSoporteContraElLibro() throws Exception {
        ClaveDonador.Datos d = h.emitir("Abarrotes Lupita", null, 12, null, null, null, null);
        String clave = h.registro(d.id()).orElseThrow().clave();
        String huella = ClaveDonador.huella(clave);

        salida.reset();
        h.buscar("lupita", null, null);
        assertThat(texto()).contains(d.id(), "Abarrotes Lupita", "vigente");
        salida.reset();
        h.buscar(null, null, "7F3A-91C2");
        assertThat(texto()).contains("Nada en el libro");

        // Mensaje de soporte como lo arma el programa
        String mensaje = "Soporte Punto de Venta\nVersión: v1.0.3 (Windows 10 amd64)\nClave de apoyo: " + d.id()
                + " · Abarrotes Lupita · vigente hasta 12/11/2027 · firma " + huella + "\nInstalación: 7F3A-91C2 · una caja\nProblema: no imprime";
        salida.reset();
        Optional<HerramientaDonadores.Registro> r = h.verificar(null, mensaje);
        assertThat(r).isPresent();
        assertThat(texto()).contains("coincide", "ATENDER");
        Map<String, String> datos = HerramientaDonadores.datosDeMensaje(mensaje);
        assertThat(datos).containsEntry("id", d.id()).containsEntry("instalacion", "7F3A-91C2")
                .containsEntry("version", "1.0.3").containsEntry("huella", huella);

        // Se anota el soporte y aparece la instalación en el libro
        h.registrarSoporte(d.id(), "7f3a-91c2", "1.0.3", "no imprime");
        HerramientaDonadores.Registro reg = h.registro(d.id()).orElseThrow();
        assertThat(reg.soportes()).isEqualTo(1);
        assertThat(reg.instalaciones()).containsExactly("7F3A-91C2");
        salida.reset();
        h.buscar(null, null, "7F3A-91C2");
        assertThat(texto()).contains(d.id());

        // Huella que no coincide (clave distinta) y un id que no está en el libro
        salida.reset();
        h.verificar(null, mensaje.replace("firma " + huella, "firma AAAAAAAAAAAA"));
        assertThat(texto()).contains("NO coincide");
        salida.reset();
        assertThat(h.verificar(null, "Clave de apoyo: ZZZZ9999 · x")).isEmpty();
        assertThat(texto()).contains("NO está en el libro");
        salida.reset();
        assertThat(h.verificar(null, "hola, no tengo clave")).isEmpty();
        assertThat(texto()).contains("no trae un id");

        // Clave completa: se valida la firma y se coteja
        salida.reset();
        assertThat(h.verificar(clave, null)).isPresent();
        assertThat(texto()).contains("Firma válida", "ATENDER");
        // Clave de otra llave (fork con otra pública): firma inválida
        salida.reset();
        var otro = java.security.KeyPairGenerator.getInstance(ClaveDonador.ALGORITMO).generateKeyPair();
        String ajena = ClaveDonador.emitir(new ClaveDonador.Datos(ClaveDonador.kidDe(otro.getPublic()), d.id(), "Abarrotes Lupita",
                LocalDate.now(), null, "basico", java.util.List.of()), otro.getPrivate());
        assertThat(h.verificar(ajena, null)).isEmpty();
        assertThat(texto()).contains("FIRMA INVÁLIDA");

        // Revocada: el veredicto cambia
        h.revocar(d.id(), "compartida");
        salida.reset();
        h.verificar(null, mensaje);
        assertThat(texto()).contains("REVOCADA");
    }

    @Test
    void losArgumentosDeLineaDeComandosSeLeenPorPares() throws Exception {
        Map<String, String> a = HerramientaDonadores.argumentos(new String[] {"emitir", "--donador", "Lupita", "--nivel", "", "--meses", "6", "--solo"});
        assertThat(a).containsEntry("donador", "Lupita").containsEntry("meses", "6").doesNotContainKey("nivel").doesNotContainKey("solo");
        h.ejecutar("emitir", "--donador", "Por comando", "--meses", "6");
        assertThat(h.registros()).singleElement().satisfies(r -> {
            assertThat(r.nombre()).isEqualTo("Por comando");
            assertThat(r.vence()).isEqualTo(LocalDate.now().plusMonths(6));
        });
        assertThatThrownBy(() -> h.ejecutar("nada")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.ejecutar("emitir", "--donador", "X", "--meses", "999")).isInstanceOf(IllegalArgumentException.class);
    }
}
