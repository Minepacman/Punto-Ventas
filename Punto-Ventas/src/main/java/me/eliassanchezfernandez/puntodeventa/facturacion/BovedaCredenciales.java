package me.eliassanchezfernandez.puntodeventa.facturacion;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Cifra las credenciales del PAC con una llave propia de la instalación
 * (AES-256-GCM), guardada en un archivo junto a la base de datos
 * (datos/facturacion.key). Así la contraseña no queda en claro en datos.db
 * ni en los respaldos de la base.
 *
 * Nivel de protección (honesto): es OFUSCACIÓN FUERTE, no un secreto absoluto.
 * Quien tenga acceso de lectura a la carpeta de datos (la base Y el archivo
 * de llave) puede descifrarla; protege contra leer la base con un visor de
 * SQLite, contra respaldos que viajan sin la llave y contra filtraciones
 * accidentales (bitácora, exportaciones). Si se pierde la llave hay que
 * volver a capturar las credenciales.
 */
public class BovedaCredenciales {

    public static final String NOMBRE_ARCHIVO_LLAVE = "facturacion.key";
    static final String PREFIJO = "v1:";
    private static final int BYTES_LLAVE = 32;
    private static final int BYTES_IV = 12;
    private static final int BITS_TAG = 128;

    private final Path archivoLlave;
    private final SecureRandom azar = new SecureRandom();
    private volatile byte[] llave;

    public BovedaCredenciales(Path carpetaDatos) {
        this.archivoLlave = carpetaDatos.resolve(NOMBRE_ARCHIVO_LLAVE);
    }

    /** Texto cifrado y codificado ("v1:" + base64(iv + cifrado)); null si el texto está vacío */
    public String cifrar(String textoClaro) {
        if (textoClaro == null || textoClaro.isEmpty()) return null;
        try {
            byte[] iv = new byte[BYTES_IV];
            azar.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(llave(), "AES"), new GCMParameterSpec(BITS_TAG, iv));
            byte[] cifrado = cipher.doFinal(textoClaro.getBytes(StandardCharsets.UTF_8));
            byte[] todo = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, todo, 0, iv.length);
            System.arraycopy(cifrado, 0, todo, iv.length, cifrado.length);
            return PREFIJO + Base64.getEncoder().encodeToString(todo);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudieron cifrar las credenciales: " + e.getMessage(), e);
        }
    }

    /**
     * Texto en claro; null si el cifrado está vacío.
     * @throws IllegalStateException si la llave cambió o el dato está dañado
     */
    public String descifrar(String cifradoCodificado) {
        if (cifradoCodificado == null || cifradoCodificado.isEmpty()) return null;
        if (!cifradoCodificado.startsWith(PREFIJO)) {
            throw new IllegalStateException("Las credenciales guardadas tienen un formato desconocido.");
        }
        try {
            byte[] todo = Base64.getDecoder().decode(cifradoCodificado.substring(PREFIJO.length()));
            if (todo.length <= BYTES_IV) throw new IllegalStateException("Credenciales dañadas.");
            byte[] iv = new byte[BYTES_IV];
            System.arraycopy(todo, 0, iv, 0, BYTES_IV);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(llave(), "AES"), new GCMParameterSpec(BITS_TAG, iv));
            byte[] claro = cipher.doFinal(todo, BYTES_IV, todo.length - BYTES_IV);
            return new String(claro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("No se pudieron leer las credenciales guardadas (¿cambió el archivo "
                    + NOMBRE_ARCHIVO_LLAVE + "?). Vuelve a capturarlas en Configuración → Facturación.");
        }
    }

    /** ¿Ya existe la llave de esta instalación? */
    public boolean hayLlave() {
        return Files.isRegularFile(archivoLlave);
    }

    public Path archivoLlave() {
        return archivoLlave;
    }

    /**
     * Versión para mostrar en pantalla: primeros 2 caracteres y el dominio del
     * correo, el resto con puntos ("el••••••@correo.com"). Nunca el valor completo.
     */
    public static String enmascarar(String valor) {
        if (valor == null || valor.isEmpty()) return "";
        int arroba = valor.indexOf('@');
        String local = arroba > 0 ? valor.substring(0, arroba) : valor;
        String dominio = arroba > 0 ? valor.substring(arroba) : "";
        String visible = local.length() > 2 ? local.substring(0, 2) : local.substring(0, Math.min(1, local.length()));
        return visible + "••••••" + dominio;
    }

    // ── Llave de la instalación ──────────────────────────────────────────

    private byte[] llave() {
        byte[] k = llave;
        if (k != null) return k;
        synchronized (this) {
            if (llave == null) llave = leerOCrearLlave();
            return llave;
        }
    }

    private byte[] leerOCrearLlave() {
        try {
            if (Files.isRegularFile(archivoLlave)) {
                byte[] k = Base64.getDecoder().decode(Files.readString(archivoLlave, StandardCharsets.US_ASCII).trim());
                if (k.length != BYTES_LLAVE) throw new IllegalStateException("La llave " + archivoLlave + " está dañada.");
                return k;
            }
            byte[] k = new byte[BYTES_LLAVE];
            azar.nextBytes(k);
            Files.createDirectories(archivoLlave.getParent());
            Files.writeString(archivoLlave, Base64.getEncoder().encodeToString(k), StandardCharsets.US_ASCII);
            try {
                Files.setPosixFilePermissions(archivoLlave, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException | IOException ignorada) {
                // Windows: hereda los permisos de la carpeta del usuario
            }
            return k;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("No se pudo leer ni crear la llave de credenciales (" + archivoLlave + "): " + e.getMessage(), e);
        }
    }
}
