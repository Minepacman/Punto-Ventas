package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Consulta de la bitácora para el administrador (Configuración → Bitácora) */
class BitacoraServiceTest extends BaseServicioTest {

    @Autowired private BitacoraService bitacoraService;
    @Autowired private AuditoriaService auditoriaService;

    @Test
    void soloElAdministradorConsultaLaBitacora() throws Exception {
        iniciarComoAdmin();
        crearUsuario("gerenteBit", Cajero.Rol.GERENTE, EnumSet.copyOf(Permiso.porDefecto(Cajero.Rol.GERENTE)));
        iniciarComo("gerenteBit", CLAVE_PRUEBA);
        assertThatThrownBy(() -> bitacoraService.entre(LocalDate.now(), LocalDate.now()))
                .isInstanceOf(NegocioException.class).hasMessageContaining("administrador");

        iniciarComoCajero("cajeroBit", EnumSet.allOf(Permiso.class));   // ni con todos los permisos
        assertThatThrownBy(() -> bitacoraService.entre(LocalDate.now(), LocalDate.now()))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void devuelveLasEntradasDelPeriodoDeLaMasRecienteALaMasVieja() throws Exception {
        Cajero admin = iniciarComoAdmin();
        auditoriaService.registrar(admin, ImpresionService.ACCION_CAJON_ABIERTO, "Venta #A-1 (automático al cobrar en efectivo)", (Cajero) null);
        auditoriaService.registrar(admin, ImpresionService.ACCION_CAJON_ABIERTO, "Manual, con motivo: «Dar cambio» · solo cambio de billetes", (Cajero) null);

        List<Auditoria> hoy = bitacoraService.entre(LocalDate.now(), LocalDate.now());
        assertThat(hoy).isNotEmpty();
        for (int i = 1; i < hoy.size(); i++) {
            assertThat(hoy.get(i - 1).getFecha()).isAfterOrEqualTo(hoy.get(i).getFecha());
        }
        List<Auditoria> cajon = hoy.stream().filter(BitacoraService::esDelCajon).toList();
        assertThat(cajon).hasSize(2);
        assertThat(cajon.get(0).getDetalle()).contains("Dar cambio");   // la más reciente primero
        assertThat(cajon.get(1).getDetalle()).contains("Venta #A-1");

        // Fuera del periodo no aparece nada; rango invertido se rechaza
        assertThat(bitacoraService.entre(LocalDate.now().minusDays(30), LocalDate.now().minusDays(29))).isEmpty();
        assertThatThrownBy(() -> bitacoraService.entre(LocalDate.now(), LocalDate.now().minusDays(1)))
                .isInstanceOf(NegocioException.class);
    }

    @Test
    void lasClavesDeAccionSeMuestranEnTextoLlano() {
        assertThat(BitacoraService.etiqueta("CAJON_ABIERTO")).isEqualTo("Cajón abierto");
        assertThat(BitacoraService.etiqueta("EFECTIVO_SALIDA")).isEqualTo("Salida de efectivo");
        assertThat(BitacoraService.etiqueta("ORDEN_COMPRA_CREADA")).isEqualTo("Orden compra creada");   // desconocida: legible
        assertThat(BitacoraService.etiqueta(null)).isEqualTo("—");
    }
}
