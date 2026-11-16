package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.model.ClimaRegistro;
import me.eliassanchezfernandez.puntodeventa.model.DetalleVenta;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.repository.ClimaRegistroRepository;
import me.eliassanchezfernandez.puntodeventa.repository.ProductoRepository;
import me.eliassanchezfernandez.puntodeventa.repository.VentaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El recálculo de Estadísticas ya no calcula DENTRO de la transacción de escritura: lee y agrupa
 * en transacciones de solo lectura (SQLite: BEGIN DEFERRED, sin candado) y solo borra/inserta en
 * transacciones cortas. Aquí, SIN transacción envolvente y con ~90 días de ventas:
 *  - el resultado es idéntico al de la forma anterior (cálculo y escritura juntos en una
 *    transacción de escritura por mes, y la ventana completa en otra);
 *  - el tiempo con el candado de escritura se mide antes y después y se imprime.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgregadosCandadoTest {

    private static final String[] TABLAS = {"resumen_diario", "producto_diario", "pares_productos", "producto_clima"};

    @Autowired private AgregadosService agregadosService;
    @Autowired private AgregadosLote lote;
    @Autowired private VentaRepository ventaRepo;
    @Autowired private ProductoRepository productoRepo;
    @Autowired private ClimaRegistroRepository climaRepo;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private DataSource dataSource;

    private void sembrar(int dias, int ventasPorDia) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            List<Producto> productos = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                Producto p = new Producto();
                p.setCodigoBarras("AC-" + UUID.randomUUID().toString().substring(0, 8));
                p.setDescripcion("Candado " + i);
                p.setPrecioCosto(5 + i);
                p.setPrecioVenta(10 + i);
                p.setUsaInventario(false);
                productos.add(productoRepo.save(p));
            }
            Random azar = new Random(20261115L);
            LocalDate hoy = LocalDate.now();
            List<Venta> ventas = new ArrayList<>();
            List<ClimaRegistro> clima = new ArrayList<>();
            for (int d = dias - 1; d >= 0; d--) {
                LocalDate dia = hoy.minusDays(d);
                for (int h = 7; h < 22; h++) {
                    boolean llueve = azar.nextInt(4) == 0;
                    clima.add(new ClimaRegistro(dia.atTime(h, 0), llueve ? 14 : 28, llueve ? 61 : 0,
                            llueve ? ClimaService.LLUVIA : ClimaService.DESPEJADO, llueve ? ClimaService.FRIO : ClimaService.CALOR));
                }
                for (int n = 0; n < ventasPorDia; n++) {
                    Venta v = new Venta();
                    v.setFecha(dia.atTime(7 + azar.nextInt(15), azar.nextInt(60)));
                    double total = 0;
                    int renglones = 1 + azar.nextInt(4);
                    for (int r = 0; r < renglones; r++) {
                        Producto p = productos.get(azar.nextInt(productos.size()));
                        DetalleVenta det = new DetalleVenta();
                        det.setVenta(v);
                        det.setProducto(p);
                        det.setCantidad(1 + azar.nextInt(3));
                        det.setPrecioVenta(p.getPrecioVenta());
                        v.getDetalles().add(det);
                        total += det.getCantidad() * p.getPrecioVenta();
                    }
                    v.setTotal(total);
                    v.setPagoCon(total);
                    if (azar.nextInt(20) == 0) {
                        v.setCancelada(true);
                        v.setFechaCancelacion(v.getFecha().plusMinutes(5));
                    }
                    ventas.add(v);
                }
            }
            ventaRepo.saveAll(ventas);
            climaRepo.saveAll(clima);
        });
    }

    /** Todas las filas de las tablas de agregados (sin el id autonumérico), ordenadas */
    private List<String> foto() throws Exception {
        List<String> filas = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String tabla : TABLAS) {
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + tabla)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder(tabla);
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            if (md.getColumnName(i).equalsIgnoreCase("id")) continue;
                            sb.append('|').append(md.getColumnName(i)).append('=').append(rs.getString(i));
                        }
                        filas.add(sb.toString());
                    }
                }
            }
        }
        filas.sort(null);
        return filas;
    }

    /**
     * La forma ANTERIOR: cada mes se calculaba y guardaba dentro de UNA transacción de escritura
     * (en SQLite, BEGIN IMMEDIATE: candado desde el principio), igual que la ventana completa.
     * @return milisegundos dentro de transacciones de escritura
     */
    private long recalcularComoAntes() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        long t0 = System.nanoTime();
        lote.borrarTodo();
        LocalDate hoy = LocalDate.now();
        LocalDate d = ventaRepo.findFirstByOrderByFechaAsc().orElseThrow().getFecha().toLocalDate().withDayOfMonth(1);
        while (!d.isAfter(hoy)) {
            LocalDate desde = d;
            LocalDate fin = d.plusMonths(1).withDayOfMonth(1).minusDays(1);
            LocalDate hasta = fin.isAfter(hoy) ? hoy : fin;
            tx.executeWithoutResult(s -> {
                List<LocalDate> fechas = new ArrayList<>();
                for (LocalDate x = desde; !x.isAfter(hasta); x = x.plusDays(1)) fechas.add(x);
                Map<LocalDate, AgregadosService.DiaCalculado> dias = lote.calcularDias(desde, hasta);
                lote.reemplazarDias(fechas, dias.values());
            });
            d = hasta.plusDays(1);
        }
        LocalDateTime inicio = hoy.minusDays(AgregadosService.VENTANA_DIAS - 1).atStartOfDay();
        LocalDateTime finVentana = hoy.plusDays(1).atStartOfDay();
        tx.executeWithoutResult(s -> {
            AgregadosLote.Ventana v = lote.calcularVentana(inicio, finVentana);
            lote.reemplazarPares(v.pares());
            lote.reemplazarProductoClima(v.clima());
        });
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void elRecalculoEnFasesDaLoMismoYRetieneMenosElCandado() throws Exception {
        sembrar(92, 40);

        // Calentar (JIT, cachés de consultas) para que las dos mediciones sean comparables
        agregadosService.recalcularTodo();
        recalcularComoAntes();

        long antes = recalcularComoAntes();
        List<String> fotoAntes = foto();

        AgregadosService.Resultado r = agregadosService.recalcularTodo();
        AgregadosService.Tiempos ahora = agregadosService.ultimosTiempos();
        List<String> fotoAhora = foto();

        assertThat(r.dias()).isGreaterThanOrEqualTo(90);
        assertThat(r.pares()).isPositive();
        assertThat(r.paresClima()).isPositive();
        assertThat(fotoAhora).as("mismos agregados, fila por fila").isEqualTo(fotoAntes);
        assertThat(fotoAhora).isNotEmpty();

        System.out.printf("Candado de escritura del recálculo completo (92 días, %d ventas): antes %d ms; "
                        + "ahora %d ms (+%d ms de cálculo sin candado)%n",
                ventaRepo.count(), antes, ahora.msEscritura(), ahora.msCalculo());
        assertThat(ahora.msCalculo() + ahora.msEscritura()).isPositive();
        // La ventana sola (lo que corre cada noche y al actualizar)
        agregadosService.recalcularVentana();
        AgregadosService.Tiempos ventana = agregadosService.ultimosTiempos();
        System.out.printf("Ventana de %d días: cálculo sin candado %d ms, escritura con candado %d ms%n",
                AgregadosService.VENTANA_DIAS, ventana.msCalculo(), ventana.msEscritura());
        assertThat(foto()).isEqualTo(fotoAntes);
    }
}
