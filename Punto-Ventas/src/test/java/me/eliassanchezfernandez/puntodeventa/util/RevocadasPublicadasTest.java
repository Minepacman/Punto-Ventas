package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El archivo publicado en el repositorio ({@code claves/revocadas.pdvr}, raíz
 * del repo) debe estar firmado con la llave oficial que lleva el programa: si
 * alguien lo regenera con otra llave o lo edita a mano, esta prueba lo detecta
 * antes de que los programas instalados lo ignoren en silencio (fail-open).
 */
class RevocadasPublicadasTest {

    @Test
    void laListaPublicadaEstaFirmadaConLaLlaveOficial() throws Exception {
        Path archivo = Path.of("..", "claves", "revocadas.pdvr");
        assertThat(archivo).as("claves/revocadas.pdvr en la raíz del repositorio (./gradlew publicarRevocaciones)").exists();
        String texto = Files.readString(archivo);
        RevocacionesApoyo.Lista lista = RevocacionesApoyo.verificar(texto, ClaveDonador.oficial());
        assertThat(lista.emitido()).isAfter(java.time.LocalDate.of(2026, 11, 1));
        assertThat(lista.revocadas()).allMatch(ClaveDonador::esId);
        assertThat(lista.clavesRetiradas()).allMatch(k -> k.matches("[0-9a-f]{8}"));
        // La llave oficial no puede estar retirada por su propia lista
        assertThat(lista.clavesRetiradas()).doesNotContainAnyElementsOf(ClaveDonador.oficial().kids());
    }
}
