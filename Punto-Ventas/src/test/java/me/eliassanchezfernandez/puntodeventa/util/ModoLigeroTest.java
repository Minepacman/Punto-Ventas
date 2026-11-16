package me.eliassanchezfernandez.puntodeventa.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static me.eliassanchezfernandez.puntodeventa.util.ModoLigero.Preferencia.*;
import static org.junit.jupiter.api.Assertions.*;

class ModoLigeroTest {

    @TempDir Path carpeta;

    @AfterEach
    void limpiar() {
        System.clearProperty("app.equipo.archivo");
        ModoLigero.reiniciarParaPruebas();
    }

    @Test
    void automaticoSoloSeEnciendeConDibujoPorSoftware() {
        assertTrue(ModoLigero.decidir(AUTOMATICO, true, null));
        assertFalse(ModoLigero.decidir(AUTOMATICO, false, null));
        assertFalse(ModoLigero.decidir(null, false, null));
    }

    @Test
    void laEleccionDeLaComputadoraMandaSobreLoAutomatico() {
        assertTrue(ModoLigero.decidir(ENCENDIDO, false, null));
        assertFalse(ModoLigero.decidir(APAGADO, true, null));
    }

    @Test
    void laPropiedadDeArranqueMandaSobreTodo() {
        assertFalse(ModoLigero.decidir(ENCENDIDO, true, "false"));
        assertTrue(ModoLigero.decidir(APAGADO, false, "TRUE"));
        assertTrue(ModoLigero.decidir(AUTOMATICO, true, "otra cosa"));
    }

    @Test
    void soloSeOfreceUnaVezEnAutomaticoYSiHuboLentitud() {
        assertTrue(ModoLigero.decidirSugerir(AUTOMATICO, false, false, "tardó 40 s en abrir"));
        assertFalse(ModoLigero.decidirSugerir(AUTOMATICO, false, false, null));
        assertFalse(ModoLigero.decidirSugerir(AUTOMATICO, false, true, "lento"));
        assertFalse(ModoLigero.decidirSugerir(AUTOMATICO, true, false, "lento"));
        assertFalse(ModoLigero.decidirSugerir(APAGADO, false, false, "lento"));
    }

    @Test
    void seGuardaEnLaComputadoraYSeRelee() throws Exception {
        Path f = carpeta.resolve("sub").resolve(ModoLigero.ARCHIVO);
        System.setProperty("app.equipo.archivo", f.toString());
        ModoLigero.reiniciarParaPruebas();
        assertEquals(AUTOMATICO, ModoLigero.preferencia());

        ModoLigero.guardarPreferencia(ENCENDIDO);
        assertTrue(Files.readString(f).contains("modo.ligero=encendido"));
        // elegir algo cuenta como "ya se ofreció"
        assertTrue(Files.readString(f).contains("modo.ligero.sugerido=true"));

        ModoLigero.reiniciarParaPruebas();
        assertEquals(ENCENDIDO, ModoLigero.preferencia());
    }

    @Test
    void unArchivoDanadoEsAutomatico() throws Exception {
        Path f = carpeta.resolve(ModoLigero.ARCHIVO);
        Files.writeString(f, "modo.ligero=quien sabe\n\u0000\u0000");
        System.setProperty("app.equipo.archivo", f.toString());
        ModoLigero.reiniciarParaPruebas();
        assertEquals(AUTOMATICO, ModoLigero.preferencia());
    }

    @Test
    void laHojaLigeraExisteYQuitaLasSombras() throws Exception {
        try (var in = ModoLigero.class.getResourceAsStream(ModoLigero.HOJA)) {
            assertNotNull(in, "falta " + ModoLigero.HOJA);
            String css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(css.contains("-fx-effect: null !important"));
        }
    }

    @Test
    void umbralesDeLentitudDeLaPantalla() {
        assertFalse(MedidorFluidez.esLenta(4, 30));
        assertTrue(MedidorFluidez.esLenta(30, 60));
        assertTrue(MedidorFluidez.esLenta(5, 150));
    }
}
