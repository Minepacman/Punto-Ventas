package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La marca "tutorial de Estadísticas visto" es por usuario y se guarda en su fila. */
class TutorialesServiceTest extends BaseServicioTest {

    @Autowired private TutorialesService tutorialesService;

    @Test
    void laMarcaEsPorUsuarioYSeGuardaEnLaBase() throws Exception {
        iniciarComoAdmin();
        assertThat(tutorialesService.estadisticasVisto()).isFalse();        // columna nueva: null = no visto

        tutorialesService.marcarEstadisticasVisto();
        assertThat(tutorialesService.estadisticasVisto()).isTrue();
        assertThat(cajeroRepo.findByUsuarioIgnoreCase(SesionService.ADMIN_USUARIO).orElseThrow().isTutorialEstadisticasVisto()).isTrue();
        tutorialesService.marcarEstadisticasVisto();                        // idempotente
        assertThat(tutorialesService.estadisticasVisto()).isTrue();

        // Otro usuario sigue sin verlo
        crearUsuario("ana", Cajero.Rol.CAJERO, EnumSet.of(Permiso.VER_HISTORIAL));
        iniciarComo("ana", CLAVE_PRUEBA);
        assertThat(tutorialesService.estadisticasVisto()).isFalse();
        tutorialesService.marcarEstadisticasVisto();
        assertThat(tutorialesService.estadisticasVisto()).isTrue();

        // Y la marca del admin no cambió; se puede reiniciar para repetir el tutorial
        iniciarComoAdmin();
        assertThat(tutorialesService.estadisticasVisto()).isTrue();
        tutorialesService.reiniciarEstadisticas();
        assertThat(tutorialesService.estadisticasVisto()).isFalse();
    }

    @Test
    void sinSesionNoSePuedeConsultarNiMarcar() {
        sesionService.cerrarSesion();
        assertThatThrownBy(() -> tutorialesService.estadisticasVisto()).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> tutorialesService.marcarEstadisticasVisto()).isInstanceOf(RuntimeException.class);
    }
}
