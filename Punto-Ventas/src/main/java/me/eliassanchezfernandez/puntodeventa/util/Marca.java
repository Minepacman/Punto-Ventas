package me.eliassanchezfernandez.puntodeventa.util;

/**
 * Nombre comercial y canales oficiales del programa, en un solo lugar.
 *
 * <p>El nombre VISIBLE es «Caja Libre». Los nombres TÉCNICOS no cambian con él y
 * no deben cambiarse sin una migración: repositorio {@code Minepacman/Punto-Ventas}
 * (lo consultan las versiones ya instaladas), carpeta de datos {@code PuntoDeVenta}
 * / {@code ~/.puntodeventa}, paquete Java, nombre del instalador ({@code PuntoDeVenta.app},
 * {@code punto-de-venta}), UUID de actualización de Windows, identificador del paquete
 * de macOS, {@code User-Agent} y prefijo de los archivos de cada release.
 *
 * <p>«Caja Libre» identifica la versión del autor (política en {@code MARCA.md} y
 * términos adicionales GPLv3 §7 en {@code AVISOS-ADICIONALES.md}): una versión
 * modificada debe usar otro nombre y otro logotipo. El dueño verificará el nombre en
 * MARCANET (IMPI) antes de registrarlo; mientras tanto no se usa ningún símbolo (®/™).
 */
public final class Marca {

    private Marca() { }

    /** Nombre del producto tal como lo ve el usuario */
    public static final String NOMBRE = "Caja Libre";

    /** Autor y titular de los derechos (también es el beneficiario que debe mostrar el banco al donar) */
    public static final String AUTOR = "Elias Oziel Sánchez Fernández";

    /** Año del primer aviso de copyright */
    public static final int ANIO = 2026;

    /** Aviso corto de copyright (la licencia completa está en LICENSE) */
    public static final String COPYRIGHT = "© " + ANIO + " " + AUTOR;

    /** Sitio oficial (GitHub Pages); la CLABE oficial solo se publica aquí y en el repositorio */
    public static final String SITIO_OFICIAL = "https://minepacman.github.io/Punto-Ventas/";

    /** Repositorio oficial (código, versiones, sumas y attestations) */
    public static final String REPOSITORIO = "https://github.com/Minepacman/Punto-Ventas";

    /** Instalación en macOS con Homebrew (tap propio) */
    public static final String HOMEBREW = "brew tap minepacman/tap && brew install --cask punto-de-venta";

    /** Texto fijo que acompaña a los datos de donación en el programa y en el sitio */
    public static final String AVISO_DONACION = "Dona solo a la cuenta oficial, la misma que aparece en " + SITIO_OFICIAL
            + ". Antes de confirmar la transferencia revisa que tu banco muestre como beneficiario a «" + AUTOR
            + "»; si aparece otro nombre, no transfieras.";

    /** «La versión oficial es gratuita» (redacción válida bajo GPLv3: informa, no restringe) */
    public static final String AVISO_GRATUITO = NOMBRE + " es gratuito. El autor no cobra por el programa ni por descargarlo. "
            + "Si alguien te lo vendió, te lo instaló por una cuota o te ofreció «claves» o «soporte oficial», esa persona "
            + "no es el autor y el autor no responde por ello.";
}
