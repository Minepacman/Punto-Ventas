package me.eliassanchezfernandez.puntodeventa.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** El hilo diferido corre las tareas en orden, aguanta fallos y en modo no diferido todo es inmediato */
class ArranqueDiferidoTest {

    private static ArranqueDiferido nuevo(boolean diferido, long esperaMs) {
        ArranqueDiferido a = new ArranqueDiferido();
        ReflectionTestUtils.setField(a, "diferido", diferido);
        ReflectionTestUtils.setField(a, "esperaMaximaMs", esperaMs);
        return a;
    }

    @Test
    void sinDiferirCorreEnElActoYEnElMismoHilo() {
        ArranqueDiferido a = nuevo(false, 0);
        List<String> hilos = new CopyOnWriteArrayList<>();
        a.ejecutar("uno", () -> hilos.add(Thread.currentThread().getName()));
        assertThat(hilos).containsExactly(Thread.currentThread().getName());
        assertThat(a.hayPendientes()).isFalse();
    }

    @Test
    void diferidoCorreEnOrdenEnSuHiloYUnFalloNoDetieneALasDemas() throws Exception {
        ArranqueDiferido a = nuevo(true, 100);     // nadie avisa de la ventana: arranca solo tras 100 ms
        List<String> orden = new CopyOnWriteArrayList<>();
        a.ejecutar("primera", () -> orden.add("primera:" + Thread.currentThread().getName()));
        a.ejecutar("falla", () -> { throw new IllegalStateException("se rompe"); });
        a.ejecutar("tercera", () -> orden.add("tercera"));
        assertThat(a.hayPendientes()).isTrue();     // aún no corren: la ventana no está lista
        // Marcar fin del arranque sin servicio de configuración: esa última tarea falla y se ignora
        a.marcarVersionDeEsquema();
        assertThat(a.esperar(5_000)).isTrue();
        assertThat(orden).containsExactly("primera:arranque-diferido", "tercera");
        assertThat(a.hayPendientes()).isFalse();
    }

    @Test
    void unaTareaEncoladaDespuesDelArranqueTambienCorre() throws Exception {
        ArranqueDiferido a = nuevo(true, 0);
        a.marcarVersionDeEsquema();          // arranque "completo" (la marca falla sin servicio y se ignora)
        assertThat(a.esperar(5_000)).isTrue();
        List<String> orden = new CopyOnWriteArrayList<>();
        a.ejecutar("tardía", () -> orden.add("tardía"));
        assertThat(a.esperar(5_000)).isTrue();
        assertThat(orden).containsExactly("tardía");
    }
}
