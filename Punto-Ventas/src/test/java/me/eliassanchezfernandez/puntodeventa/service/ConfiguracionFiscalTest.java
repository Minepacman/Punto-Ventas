package me.eliassanchezfernandez.puntodeventa.service;

import me.eliassanchezfernandez.puntodeventa.facturacion.CredencialesPac;
import me.eliassanchezfernandez.puntodeventa.model.Auditoria;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.ConfiguracionFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Departamento;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Producto;
import me.eliassanchezfernandez.puntodeventa.model.Unidad;
import me.eliassanchezfernandez.puntodeventa.repository.AuditoriaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuración → Facturación (emisor, credenciales cifradas, claves por departamento) y clientes fiscales. */
class ConfiguracionFiscalTest extends BaseServicioTest {

    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private UnidadService unidadService;
    @Autowired private AuditoriaRepository auditoriaRepo;

    @Test
    void elEmisorSeValidaYSoloLoCambiaElAdmin() throws Exception {
        iniciarComoAdmin();
        assertThat(fiscalService.facturacionLista()).isFalse();

        assertThatThrownBy(() -> fiscalService.guardarEmisor("MAL", "Tienda", "626", "78240", "A"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("RFC");
        assertThatThrownBy(() -> fiscalService.guardarEmisor("XAXX010101000", "Tienda", "616", "78240", "A"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("genérico");
        assertThatThrownBy(() -> fiscalService.guardarEmisor("GODE561231GR8", "Tienda", "601", "78240", "A"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("persona física");
        assertThatThrownBy(() -> fiscalService.guardarEmisor("GODE561231GR8", "Tienda", "626", "782", "A"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("código postal");
        assertThatThrownBy(() -> fiscalService.guardarEmisor("GODE561231GR8", "Tienda", "626", "78240", "A-1"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("serie");

        ConfiguracionFiscal c = fiscalService.guardarEmisor(" gode561231gr8 ", "  abarrotes   don pepe ", "626", "78240", "fac");
        assertThat(c.getEmisorRfc()).isEqualTo("GODE561231GR8");
        assertThat(c.getEmisorNombre()).isEqualTo("ABARROTES DON PEPE");
        assertThat(c.getSerie()).isEqualTo("FAC");
        assertThat(c.tieneEmisor()).isTrue();
        assertThat(c.isPacSandbox()).isTrue();          // por defecto: pruebas

        iniciarComoCajero("gerente1", EnumSet.of(Permiso.FACTURAR));
        assertThatThrownBy(() -> fiscalService.guardarEmisor("GODE561231GR8", "X", "626", "78240", "A"))
                .isInstanceOf(NegocioException.class).hasMessageContaining("administrador");
        assertThatThrownBy(() -> fiscalService.guardarCredenciales("u", "p", true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("administrador");
    }

    @Test
    void lasCredencialesSeGuardanCifradasYNoSeRegistranEnLaBitacora() throws Exception {
        iniciarComoAdmin();
        assertThatThrownBy(() -> fiscalService.credenciales())
                .isInstanceOf(NegocioException.class).hasMessageContaining("credenciales");
        assertThatThrownBy(() -> fiscalService.guardarCredenciales("usuario@tienda.com", "", true))
                .isInstanceOf(NegocioException.class).hasMessageContaining("contraseña");

        ConfiguracionFiscal c = fiscalService.guardarCredenciales("usuario@tienda.com", "MiClaveSecreta!", true);
        assertThat(c.getPacUsuarioCifrado()).startsWith("v1:").doesNotContain("usuario@tienda.com");
        assertThat(c.getPacContrasenaCifrada()).startsWith("v1:").doesNotContain("MiClaveSecreta");
        assertThat(c.tieneCredenciales()).isTrue();

        CredencialesPac cred = fiscalService.credenciales();
        assertThat(cred.usuario()).isEqualTo("usuario@tienda.com");
        assertThat(cred.contrasena()).isEqualTo("MiClaveSecreta!");
        assertThat(cred.sandbox()).isTrue();
        assertThat(fiscalService.usuarioEnmascarado()).isEqualTo("us••••••@tienda.com");

        // Contraseña vacía = conservar la anterior; cambia usuario y modo
        fiscalService.guardarCredenciales("otro@tienda.com", "", false);
        CredencialesPac cred2 = fiscalService.credenciales();
        assertThat(cred2.usuario()).isEqualTo("otro@tienda.com");
        assertThat(cred2.contrasena()).isEqualTo("MiClaveSecreta!");
        assertThat(cred2.sandbox()).isFalse();

        List<Auditoria> bitacora = auditoriaRepo.findTop200ByOrderByFechaDesc();
        assertThat(bitacora).anyMatch(a -> a.getDetalle() != null && a.getDetalle().contains("Facturación: PAC"));
        assertThat(bitacora).noneMatch(a -> a.getDetalle() != null
                && (a.getDetalle().contains("MiClaveSecreta") || a.getDetalle().contains("usuario@tienda.com")));

        fiscalService.borrarCredenciales();
        assertThat(fiscalService.obtener().tieneCredenciales()).isFalse();
    }

    @Test
    void clavesSatPorDefectoDeProductoDepartamentoYUnidad() throws Exception {
        iniciarComoAdmin();
        Departamento abarrotes = departamento("Abarrotes");
        Departamento limpieza = departamento("Limpieza");
        fiscalService.guardarClavesDepartamentos(Map.of(limpieza.getId(), "47131800", abarrotes.getId(), ""));
        assertThatThrownBy(() -> fiscalService.guardarClavesDepartamentos(Map.of(limpieza.getId(), "47")))
                .isInstanceOf(NegocioException.class).hasMessageContaining("8 dígitos");

        Unidad kilo = unidadService.buscarPorNombre("Kilogramo").orElseThrow();
        Unidad pieza = unidadService.buscarPorNombre("Pieza").orElseThrow();
        assertThat(kilo.getClaveUnidad()).isEqualTo("KGM");    // sembrada

        Producto cloro = producto("Cloro", 25, 10, Producto.TipoVenta.UNIDAD, limpieza);
        Producto arroz = producto("Arroz", 30, 10, Producto.TipoVenta.GRANEL, abarrotes);
        arroz.setUnidad(kilo);
        arroz = productoService.guardar(arroz);
        Producto refresco = producto("Refresco", 18, 10, Producto.TipoVenta.UNIDAD, abarrotes);
        refresco.setUnidad(pieza);
        refresco.setClaveProdServ("50202306");
        refresco.setClaveUnidad("xbo");
        refresco.setObjetoImp("02");
        refresco = productoService.guardar(refresco);

        assertThat(ConfiguracionFiscalService.claveProdServDe(cloro)).isEqualTo("47131800");       // del departamento
        assertThat(ConfiguracionFiscalService.claveProdServDe(arroz)).isEqualTo("50000000");       // genérica
        assertThat(ConfiguracionFiscalService.claveProdServDe(refresco)).isEqualTo("50202306");    // propia
        assertThat(ConfiguracionFiscalService.claveUnidadDe(cloro)).isEqualTo("H87");
        assertThat(ConfiguracionFiscalService.claveUnidadDe(arroz)).isEqualTo("KGM");
        assertThat(ConfiguracionFiscalService.claveUnidadDe(refresco)).isEqualTo("XBO");           // normalizada a mayúsculas
        assertThat(ConfiguracionFiscalService.objetoImpDe(cloro)).isEqualTo("02");

        Producto malo = producto("Malo", 1, 1);
        malo.setClaveProdServ("123");
        assertThatThrownBy(() -> productoService.guardar(malo)).isInstanceOf(NegocioException.class).hasMessageContaining("8 dígitos");
        malo.setClaveProdServ(null);
        malo.setObjetoImp("09");
        assertThatThrownBy(() -> productoService.guardar(malo)).isInstanceOf(NegocioException.class).hasMessageContaining("objeto");

        Unidad bolsa = new Unidad("Bolsa grande", "bg", false);
        bolsa.setClaveUnidad("xbg");
        assertThat(unidadService.guardar(bolsa).getClaveUnidad()).isEqualTo("XBG");
        Unidad mala = new Unidad("Rara", "r", false);
        mala.setClaveUnidad("KILOS");
        assertThatThrownBy(() -> unidadService.guardar(mala)).isInstanceOf(NegocioException.class).hasMessageContaining("unidad del SAT");
    }

    @Test
    void clientesFiscalesSeValidanContraLaCsf() throws Exception {
        iniciarComoCajero("fact", EnumSet.of(Permiso.FACTURAR));

        ClienteFiscal c = new ClienteFiscal();
        c.setRfc("gode561231gr8");
        c.setNombre("  Juan   Pérez  ");
        c.setRegimenFiscal("612");
        c.setCodigoPostal("78240");
        c.setUsoCfdi("g03");
        c.setEmail("juan@correo.com");
        ClienteFiscal g = clienteFiscalService.guardar(c);
        assertThat(g.getId()).isNotNull();
        assertThat(g.getRfc()).isEqualTo("GODE561231GR8");
        assertThat(g.getNombre()).isEqualTo("JUAN PÉREZ");
        assertThat(g.getUsoCfdi()).isEqualTo("G03");

        // RFC repetido
        ClienteFiscal dup = new ClienteFiscal();
        dup.setRfc("GODE561231GR8"); dup.setNombre("Otro"); dup.setRegimenFiscal("612"); dup.setCodigoPostal("78240"); dup.setUsoCfdi("G01");
        assertThatThrownBy(() -> clienteFiscalService.guardar(dup)).isInstanceOf(NegocioException.class).hasMessageContaining("Ya existe");

        // Régimen de persona moral en RFC de física
        ClienteFiscal malReg = new ClienteFiscal();
        malReg.setRfc("GOLM800101AB1"); malReg.setNombre("X"); malReg.setRegimenFiscal("601"); malReg.setCodigoPostal("78240"); malReg.setUsoCfdi("G03");
        assertThatThrownBy(() -> clienteFiscalService.guardar(malReg)).isInstanceOf(NegocioException.class).hasMessageContaining("persona física");

        // Genérico: régimen y uso se fuerzan a 616 / S01
        ClienteFiscal gen = new ClienteFiscal();
        gen.setRfc("XAXX010101000"); gen.setNombre("Público en general"); gen.setRegimenFiscal("612"); gen.setCodigoPostal("78240"); gen.setUsoCfdi("G03");
        ClienteFiscal genG = clienteFiscalService.guardar(gen);
        assertThat(genG.getRegimenFiscal()).isEqualTo("616");
        assertThat(genG.getUsoCfdi()).isEqualTo("S01");

        ClienteFiscal correoMalo = new ClienteFiscal();
        correoMalo.setRfc("ABC010101AB1"); correoMalo.setNombre("Empresa"); correoMalo.setRegimenFiscal("601"); correoMalo.setCodigoPostal("06600"); correoMalo.setUsoCfdi("G01"); correoMalo.setEmail("sin-arroba");
        assertThatThrownBy(() -> clienteFiscalService.guardar(correoMalo)).isInstanceOf(NegocioException.class).hasMessageContaining("correo");

        assertThat(clienteFiscalService.buscar("juan")).extracting(ClienteFiscal::getRfc).contains("GODE561231GR8");
        assertThat(clienteFiscalService.porRfc(" gode561231gr8")).isPresent();

        // Edición conserva el id
        g.setNombre("JUAN PEREZ GARCIA");
        g.setEmail("");
        ClienteFiscal editado = clienteFiscalService.guardar(g);
        assertThat(editado.getId()).isEqualTo(g.getId());
        assertThat(editado.getEmail()).isNull();

        iniciarComoCajero("sinpermiso", null);
        assertThatThrownBy(() -> clienteFiscalService.guardar(c)).isInstanceOf(PermisoRequeridoException.class);
        assertThatThrownBy(() -> clienteFiscalService.eliminar(editado.getId())).isInstanceOf(PermisoRequeridoException.class);
    }
}
