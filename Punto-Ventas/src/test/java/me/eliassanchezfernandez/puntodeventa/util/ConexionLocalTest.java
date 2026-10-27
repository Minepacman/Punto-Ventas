package me.eliassanchezfernandez.puntodeventa.util;

import me.eliassanchezfernandez.puntodeventa.config.PerfilBaseDatos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Archivo local de conexión de la caja: valores por defecto, guardado, ofuscación de la contraseña y permisos. */
class ConexionLocalTest {

    @Test
    void sinArchivoEsUnaSolaCajaConSqlite(@TempDir Path carpeta) {
        assertThat(ConexionLocal.existe(carpeta)).isFalse();
        ConexionLocal c = ConexionLocal.cargar(carpeta);
        assertThat(c.getModo()).isEqualTo(PerfilBaseDatos.SQLITE);
        assertThat(c.esSqlite()).isTrue();
        assertThat(c.getCajaNumero()).isEqualTo(1);
        assertThat(c.getCajaNombre()).isEqualTo("Caja 1");
        assertThat(c.urlPostgresql()).isNull();
    }

    @Test
    void guardaYVuelveALeerLaConfiguracionDelServidor(@TempDir Path carpeta) throws Exception {
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        c.setCajaNumero(2);
        c.setCajaNombre("Mostrador");
        c.setHost("192.168.1.10");
        c.setPuerto(5433);
        c.setBaseDatos("tienda");
        c.setUsuario("pos");
        c.setContrasena("s3cr3ta ñ");
        c.setAdminHash("$2a$10$hash");
        c.guardar(carpeta);

        assertThat(ConexionLocal.existe(carpeta)).isTrue();
        ConexionLocal leida = ConexionLocal.cargar(carpeta);
        assertThat(leida).isEqualTo(c);
        assertThat(leida.urlPostgresql()).isEqualTo("jdbc:postgresql://192.168.1.10:5433/tienda");
        assertThat(leida.getContrasena()).isEqualTo("s3cr3ta ñ");
        assertThat(leida.getAdminHash()).isEqualTo("$2a$10$hash");
        assertThat(leida.toString()).doesNotContain("s3cr3ta");
        assertThat(leida.descripcionServidor()).doesNotContain("s3cr3ta");
    }

    @Test
    void laContrasenaNoQuedaEnTextoPlanoYUsaLaClaveDeLaInstalacion(@TempDir Path carpeta) throws Exception {
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        c.setHost("srv");
        c.setContrasena("MiClave123");
        c.guardar(carpeta);

        String archivo = Files.readString(ConexionLocal.archivo(carpeta));
        assertThat(archivo).doesNotContain("MiClave123");
        assertThat(archivo).contains("pg.contrasena=enc\\:");
        assertThat(ConexionLocal.archivoClave(carpeta)).exists();
        assertThat(Files.readAllBytes(ConexionLocal.archivoClave(carpeta))).hasSize(32);

        // Sin la clave de la instalación la contraseña no se recupera (y el programa sigue arrancando)
        Files.delete(ConexionLocal.archivoClave(carpeta));
        ConexionLocal sinClave = ConexionLocal.cargar(carpeta);
        assertThat(sinClave.getContrasena()).isEmpty();
        assertThat(sinClave.getHost()).isEqualTo("srv");

        // La ofuscación es reversible solo con la misma clave
        byte[] clave = ConexionLocal.claveInstalacion(carpeta, true);
        String ofuscada = ConexionLocal.ofuscar("abc", clave);
        assertThat(ConexionLocal.desofuscar(ofuscada, clave)).isEqualTo("abc");
        assertThat(ConexionLocal.ofuscar("abc", clave)).isNotEqualTo(ofuscada);   // IV aleatorio
        byte[] otra = new byte[32];
        assertThatThrownBy(() -> ConexionLocal.desofuscar(ofuscada, otra)).isInstanceOf(Exception.class);
    }

    @Test
    void losArchivosQuedanSoloParaElUsuarioDondeHayPermisosPosix(@TempDir Path carpeta) throws Exception {
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        c.setHost("srv");
        c.setContrasena("x");
        c.guardar(carpeta);
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        Set<PosixFilePermission> esperado = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(ConexionLocal.archivo(carpeta))).isEqualTo(esperado);
        assertThat(Files.getPosixFilePermissions(ConexionLocal.archivoClave(carpeta))).isEqualTo(esperado);
    }

    @Test
    void unArchivoDanadoOConValoresRarosNoRompeElArranque(@TempDir Path carpeta) throws Exception {
        Files.writeString(ConexionLocal.archivo(carpeta), "modo=LO_QUE_SEA\ncaja.numero=abc\npg.puerto=-5\npg.contrasena=plana\n");
        ConexionLocal c = ConexionLocal.cargar(carpeta);
        assertThat(c.getModo()).isEqualTo(PerfilBaseDatos.SQLITE);
        assertThat(c.getCajaNumero()).isEqualTo(1);
        assertThat(c.getPuerto()).isEqualTo(ConexionLocal.PUERTO_PG_POR_DEFECTO);
        assertThat(c.getContrasena()).isEqualTo("plana");   // escrita a mano, se acepta
    }

    @Test
    void validaLosDatosDelServidor() {
        ConexionLocal c = new ConexionLocal();
        c.setModo(PerfilBaseDatos.POSTGRESQL);
        assertThatThrownBy(c::validarServidor).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dirección");
        c.setHost("servidor local");
        assertThatThrownBy(c::validarServidor).isInstanceOf(IllegalArgumentException.class);
        c.setHost("servidor");
        c.setBaseDatos("mi base");
        assertThatThrownBy(c::validarServidor).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("base de datos");
        c.setBaseDatos("puntodeventa");
        c.validarServidor();
        c.setModo(PerfilBaseDatos.SQLITE);
        c.setHost("");
        c.validarServidor();   // en SQLite no hay nada que validar
    }

    @Test
    void elPerfilSaleDeLaUrl() {
        assertThat(PerfilBaseDatos.deUrl("jdbc:postgresql://srv:5432/pos")).isEqualTo(PerfilBaseDatos.POSTGRESQL);
        assertThat(PerfilBaseDatos.deUrl("jdbc:sqlite:datos.db")).isEqualTo(PerfilBaseDatos.SQLITE);
        assertThat(PerfilBaseDatos.deUrl(null)).isEqualTo(PerfilBaseDatos.SQLITE);
        assertThat(PerfilBaseDatos.POSTGRESQL.driver()).isEqualTo("org.postgresql.Driver");
        assertThat(PerfilBaseDatos.SQLITE.dialecto()).contains("SQLiteDialect");
        assertThat(RutasApp.sinCredenciales("jdbc:postgresql://usuario:clave@srv:5432/pos?ssl=true"))
                .isEqualTo("jdbc:postgresql://srv:5432/pos");
    }
}
