package me.eliassanchezfernandez.puntodeventa.ocr;

import me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Decision;
import me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Equipo;
import me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.EstadoPpOcr;
import me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Motor;
import me.eliassanchezfernandez.puntodeventa.ocr.SeleccionMotorOcr.Preferencia;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeleccionMotorOcrTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final Equipo PC_X64_8GB = new Equipo("Windows 10", "amd64", 8 * GB, 4);
    private static final Equipo PC_X86 = new Equipo("Windows 10", "x86", 4 * GB, 2);
    private static final Equipo PC_2GB = new Equipo("Windows 10", "amd64", 2 * GB, 2);
    private static final Equipo MAC = new Equipo("Mac OS X", "aarch64", 16 * GB, 10);

    private static EstadoPpOcr listo(Equipo e, double segundos) {
        return new EstadoPpOcr(true, true, true, segundos, e.huella());
    }

    @Test
    void rapidoSiempreEsTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.RAPIDO, PC_X64_8GB, true, listo(PC_X64_8GB, 1.0));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.motivo()).contains("rápido");
        assertThat(d.medirPpOcr()).isFalse();
    }

    @Test
    void automaticoEnWindowsDe32BitsEsTesseractSinMedir() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_X86, true, new EstadoPpOcr(true, null, false, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.motivo()).contains("32 bits");
        assertThat(d.medirPpOcr()).isFalse();
        assertThat(d.descargarModelos()).isFalse();
    }

    @Test
    void automaticoConPocaMemoriaEsTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_2GB, true, new EstadoPpOcr(true, null, false, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.motivo()).contains("2.0 GB");
        assertThat(d.medirPpOcr()).isFalse();
    }

    @Test
    void automaticoConCuatroGigasEsTesseractPorqueOnnxUsaMasDeUnGigaNativo() {
        // PC típica de tienda (4 GB): PaddleOCR ni se baja ni se mide; con 6 GB o más sí
        Equipo pc4 = new Equipo("Windows 10", "amd64", 4 * GB, 2);
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, pc4, true, new EstadoPpOcr(true, null, false, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.medirPpOcr()).isFalse();
        assertThat(d.descargarModelos()).isFalse();
        Equipo pc6 = new Equipo("Windows 10", "amd64", 6 * GB - 400L * 1024 * 1024, 2);   // "6 GB" que reporta 5.6
        assertThat(SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, pc6, true, new EstadoPpOcr(true, null, false, null, null))
                .medirPpOcr()).isTrue();
        assertThat(SeleccionMotorOcr.RAM_MINIMA_PPOCR).isGreaterThan(5 * GB);
    }

    @Test
    void automaticoSinMedidaMideEnSegundoPlanoYMientrasUsaTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_X64_8GB, true, new EstadoPpOcr(true, null, false, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.medirPpOcr()).isTrue();
        assertThat(d.descargarModelos()).isTrue();
    }

    @Test
    void automaticoConMedidaRapidaEligePaddle() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, MAC, true, listo(MAC, 1.4));
        assertThat(d.motor()).isEqualTo(Motor.PPOCR);
        assertThat(d.motivo()).contains("1.4 s");
    }

    @Test
    void automaticoConMedidaLentaEligeTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_X64_8GB, true, listo(PC_X64_8GB, 7.5));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.motivo()).contains("7.5 s");
        assertThat(d.medirPpOcr()).isFalse();
    }

    @Test
    void siCambiaElHardwareSeVuelveAMedir() {
        EstadoPpOcr medidoEnOtra = new EstadoPpOcr(true, true, true, 1.0, new Equipo("Windows 10", "amd64", 8 * GB, 2).huella());
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_X64_8GB, true, medidoEnOtra);
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.medirPpOcr()).isTrue();
    }

    @Test
    void siOnnxNoCargaSeQuedaConTesseractSinError() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, PC_X64_8GB, true, new EstadoPpOcr(true, false, true, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.medirPpOcr()).isFalse();
        assertThat(d.motivo()).contains("no pudo arrancar");
    }

    @Test
    void precisoBajaLosModelosYMientrasUsaTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.PRECISO, PC_X64_8GB, true, new EstadoPpOcr(true, null, false, null, null));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.descargarModelos()).isTrue();
        Decision listo = SeleccionMotorOcr.decidir(Preferencia.PRECISO, PC_2GB, true, new EstadoPpOcr(true, true, true, null, null));
        assertThat(listo.motor()).as("Preciso respeta la elección aunque haya poca memoria").isEqualTo(Motor.PPOCR);
    }

    @Test
    void precisoEn32BitsExplicaPorQueNo() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.PRECISO, PC_X86, true, listo(PC_X86, 1));
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.motivo()).contains("32 bits");
    }

    @Test
    void sinPaddleIncluidoAutomaticoEsTesseract() {
        Decision d = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, MAC, true, EstadoPpOcr.noIncluido());
        assertThat(d.motor()).isEqualTo(Motor.TESSERACT);
        assertThat(d.medirPpOcr()).isFalse();
        assertThat(d.motivo()).contains("aún no trae PaddleOCR");
    }

    @Test
    void sinTesseractUsaPaddleSiEstaListoOAvisaQueNoHayLector() {
        assertThat(SeleccionMotorOcr.decidir(Preferencia.RAPIDO, MAC, false, listo(MAC, 1)).motor()).isEqualTo(Motor.PPOCR);
        Decision nada = SeleccionMotorOcr.decidir(Preferencia.AUTOMATICO, MAC, false, EstadoPpOcr.noIncluido());
        assertThat(nada.motor()).isEqualTo(Motor.NINGUNO);
        assertThat(nada.motivo()).contains("no se encontró Tesseract");
    }

    @Test
    void preferenciaDesconocidaEsAutomatico() {
        assertThat(Preferencia.de(null)).isEqualTo(Preferencia.AUTOMATICO);
        assertThat(Preferencia.de("basura")).isEqualTo(Preferencia.AUTOMATICO);
        assertThat(Preferencia.de("preciso")).isEqualTo(Preferencia.PRECISO);
    }

    @Test
    void arquitecturasDe32Bits() {
        for (String a : new String[] {"x86", "i386", "i686"}) assertThat(new Equipo("Windows 7", a, GB, 1).es32Bits()).isTrue();
        for (String a : new String[] {"amd64", "x86_64", "aarch64"}) assertThat(new Equipo("Linux", a, GB, 1).es32Bits()).isFalse();
    }
}
