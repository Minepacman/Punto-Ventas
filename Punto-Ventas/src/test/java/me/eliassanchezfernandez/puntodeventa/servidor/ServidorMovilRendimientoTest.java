package me.eliassanchezfernandez.puntodeventa.servidor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import me.eliassanchezfernandez.puntodeventa.model.Cajero;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.CajeroRepository;
import me.eliassanchezfernandez.puntodeventa.service.AgregadosService;
import me.eliassanchezfernandez.puntodeventa.service.CajeroService;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Medición (no es prueba de la suite): API móvil contra una BD SQLite propia sembrada con
 * 5 000 productos y 50 000 ventas de 3 renglones en 90 días. Imprime, por endpoint, la
 * mediana de 3 llamadas (ms), los bytes asignados en el proceso y el tamaño de la respuesta.
 * <pre>APP_RENDIMIENTO=true ./gradlew test --tests '*ServidorMovilRendimientoTest'</pre>
 * Sin APP_RENDIMIENTO se omite. Nunca usa la BD de pruebas compartida ni ningún datos.db.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/puntodeventa-rendimiento-movil.db",
        "spring.jpa.hibernate.ddl-auto=create-drop"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "APP_RENDIMIENTO", matches = "true")
class ServidorMovilRendimientoTest {

    private static final int PRODUCTOS = 5_000;
    private static final int VENTAS = 50_000;
    private static final int DIAS = 90;
    private static final String CLAVE = "clave123";

    @Autowired private ServidorMovilService servidor;
    @Autowired private SesionService sesionService;
    @Autowired private CajeroService cajeroService;
    @Autowired private CajeroRepository cajeroRepo;
    @Autowired private AgregadosService agregadosService;
    @Autowired private PlatformTransactionManager tx;
    @PersistenceContext private EntityManager em;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void medirApiMovilConBdGrande() throws Exception {
        Cajero admin = cajeroRepo.findByUsuarioIgnoreCase("admin").orElseThrow();
        admin.setDebeCambiarContrasena(false);
        cajeroRepo.save(admin);
        sesionService.autenticar("admin", "admin123");
        long t0 = System.nanoTime();
        sembrar(admin.getId());
        System.out.printf("Siembra: %d productos, %d ventas en %d ms%n", PRODUCTOS, VENTAS, (System.nanoTime() - t0) / 1_000_000);
        t0 = System.nanoTime();
        agregadosService.actualizar();
        System.out.printf("Agregados: %d ms%n", (System.nanoTime() - t0) / 1_000_000);

        String login = "rend" + System.nanoTime() % 100000;
        Cajero c = cajeroService.crear(login, "Medición", CLAVE, Cajero.Rol.CAJERO,
                EnumSet.of(Permiso.VER_HISTORIAL, Permiso.VER_GANANCIAS));
        c.setDebeCambiarContrasena(false);
        cajeroRepo.save(c);
        sesionService.cerrarSesion();

        servidor.iniciar(0);
        try {
            String base = "http://127.0.0.1:" + servidor.getPuerto();
            String token = new ObjectMapper().readTree(http.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(
                            Map.of("usuario", login, "contrasena", CLAVE, "recordar", false)))).build(),
                    HttpResponse.BodyHandlers.ofString()).body()).get("token").asText();
            LocalDate hoy = LocalDate.now();
            medir(base, token, "/api/ventas?desde=" + hoy + "&hasta=" + hoy + "&r=hoy");
            medir(base, token, "/api/ventas?desde=" + hoy.minusDays(6) + "&hasta=" + hoy + "&r=7dias");
            medir(base, token, "/api/ventas?desde=" + hoy.minusDays(92) + "&hasta=" + hoy + "&r=93dias");
            medir(base, token, "/api/ventas?desde=" + hoy.minusDays(92) + "&hasta=" + hoy + "&pagina=20&r=93dias");
            medir(base, token, "/api/inventario");
            medir(base, token, "/api/inventario?q=a");
            medir(base, token, "/api/estadisticas?periodo=dia");
            medir(base, token, "/api/estadisticas?periodo=semana");
            medir(base, token, "/api/estadisticas?periodo=mes");
            medir(base, token, "/api/sesion");
        } finally {
            servidor.detener();
        }
    }

    private void medir(String base, String token, String ruta) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + ruta)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<byte[]> calentar = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(calentar.statusCode()).as(ruta).isEqualTo(200);
        long[] ms = new long[3];
        long asignado = 0;
        int bytes = 0;
        for (int i = 0; i < ms.length; i++) {
            long a0 = asignadoTotal();
            long t0 = System.nanoTime();
            HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
            asignado += asignadoTotal() - a0;
            bytes = resp.body().length;
        }
        Arrays.sort(ms);
        System.out.printf("MEDICION %-60s %6d ms  %8.1f MB asignados  %8.1f KB respuesta%n",
                ruta.replaceAll("\\d{4}-\\d{2}-\\d{2}", "F"), ms[1], asignado / 3.0 / 1_048_576, bytes / 1024.0);
    }

    /** Bytes asignados por todos los hilos vivos (aprox.: incluye el cliente HTTP de la propia prueba) */
    private static long asignadoTotal() {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long[] ids = mx.getAllThreadIds();
        long total = 0;
        for (long b : mx.getThreadAllocatedBytes(ids)) if (b > 0) total += b;
        return total;
    }

    private void sembrar(Long cajeroId) {
        TransactionTemplate t = new TransactionTemplate(tx);
        Random azar = new Random(42);
        List<Long> ids = new ArrayList<>();
        t.executeWithoutResult(st -> {
            for (int i = 0; i < PRODUCTOS; i++) {
                Producto p = new Producto();
                p.setCodigoBarras("750" + String.format("%010d", i));
                p.setDescripcion("Producto de prueba " + i + (i % 3 == 0 ? " azúcar" : " galleta"));
                p.setPrecioCosto(5 + i % 50);
                p.setPrecioVenta(8 + i % 50);
                p.setUsaInventario(true);
                p.setCantidadActual(i % 40);
                p.setCantidadMinima(5);
                em.persist(p);
                ids.add(p.getId());
                if (i % 1000 == 999) { em.flush(); em.clear(); }
            }
        });
        LocalDateTime inicio = LocalDate.now().minusDays(DIAS - 1).atTime(8, 0);
        long pasoSeg = (long) DIAS * 86_400 / VENTAS;
        for (int lote = 0; lote < VENTAS; lote += 5_000) {
            int desde = lote;
            t.executeWithoutResult(st -> {
                Cajero cajero = em.getReference(Cajero.class, cajeroId);
                for (int i = desde; i < Math.min(VENTAS, desde + 5_000); i++) {
                    Venta v = new Venta();
                    v.setFecha(inicio.plusSeconds(i * pasoSeg));
                    v.setCajero(cajero);
                    v.setFolio("R-" + i);
                    double total = 0;
                    for (int k = 0; k < 3; k++) {
                        DetalleVenta d = new DetalleVenta();
                        d.setVenta(v);
                        d.setProducto(em.getReference(Producto.class, ids.get(azar.nextInt(ids.size()))));
                        d.setCantidad(1 + azar.nextInt(3));
                        d.setPrecioVenta(10 + azar.nextInt(40));
                        d.setCostoUnitario(d.getPrecioVenta() * 0.7);
                        total += d.getImporte();
                        v.getDetalles().add(d);
                    }
                    v.setTotal(total);
                    v.setPagoCon(total);
                    if (i % 97 == 0) v.setCancelada(true);
                    em.persist(v);
                    if (i % 500 == 499) { em.flush(); em.clear(); cajero = em.getReference(Cajero.class, cajeroId); }
                }
            });
        }
    }
}
