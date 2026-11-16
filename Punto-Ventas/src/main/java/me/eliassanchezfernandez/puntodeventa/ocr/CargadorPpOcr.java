package me.eliassanchezfernandez.puntodeventa.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Carga {@code ocr.ppocr.MotorPpOcr} junto con ONNX Runtime descargado (que NO está en el classpath del
 * programa instalado: pesa ~50 MB y solo lo bajan las PCs aptas). Se usa un cargador "hijo primero" para
 * el paquete {@code ocr.ppocr} y para {@code ai.onnxruntime}: las clases del motor se definen de nuevo
 * dentro de él (a partir de los mismos .class del programa) y así ven el jar descargado; todo lo demás
 * (MotorOcr, Renglon, el preproceso…) viene del cargador normal, de modo que el objeto se usa como
 * cualquier {@link MotorOcr}.
 * <p>
 * Si ONNX Runtime no puede cargar sus bibliotecas nativas (CPU sin las instrucciones que pide, Mac Intel,
 * 32 bits) se lanza {@link IOException} con la causa: la selección cae a Tesseract sin mostrar error.
 */
public final class CargadorPpOcr {

    static final String PAQUETE_MOTOR = "me.eliassanchezfernandez.puntodeventa.ocr.ppocr.";
    static final String PAQUETE_ORT = "ai.onnxruntime.";
    static final String CLASE = PAQUETE_MOTOR + "MotorPpOcr";

    private CargadorPpOcr() { }

    /**
     * @param jarOnnx jar de ONNX Runtime descargado (null = usar el del classpath, p. ej. en pruebas)
     */
    public static MotorOcr cargar(Path jarOnnx, Path det, Path rec, int hilos) throws IOException {
        ClassLoader padre = CargadorPpOcr.class.getClassLoader();
        try {
            ClassLoader cargador = new HijoPrimero(jarOnnx == null ? new URL[0] : new URL[] {jarOnnx.toUri().toURL()}, padre);
            Class<?> c = Class.forName(CLASE, true, cargador);
            Object motor = c.getConstructor(Path.class, Path.class, int.class).newInstance(det, rec, hilos);
            return (MotorOcr) motor;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable causa = e.getCause();
            if (causa instanceof IOException io) throw io;
            throw new IOException("PaddleOCR no pudo arrancar: " + causa, causa);
        } catch (MalformedURLException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            throw new IOException("PaddleOCR no pudo arrancar: " + e, e);
        }
    }

    /** Hilos para ONNX Runtime: la mitad de los núcleos (la caja sigue respondiendo), entre 1 y 4 */
    public static int hilosSugeridos() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static final class HijoPrimero extends URLClassLoader {
        private final ClassLoader padre;

        HijoPrimero(URL[] urls, ClassLoader padre) {
            super(urls, padre);
            this.padre = padre;
        }

        @Override
        protected Class<?> loadClass(String nombre, boolean resolver) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(nombre)) {
                Class<?> c = findLoadedClass(nombre);
                if (c == null && nombre.startsWith(PAQUETE_MOTOR)) {
                    String recurso = nombre.replace('.', '/') + ".class";
                    try (InputStream in = padre.getResourceAsStream(recurso)) {
                        if (in == null) throw new ClassNotFoundException(nombre);
                        byte[] b = in.readAllBytes();
                        c = defineClass(nombre, b, 0, b.length);
                    } catch (IOException e) {
                        throw new ClassNotFoundException(nombre, e);
                    }
                } else if (c == null && nombre.startsWith(PAQUETE_ORT) && getURLs().length > 0) {
                    try {
                        c = findClass(nombre);
                    } catch (ClassNotFoundException e) {
                        c = null;
                    }
                }
                if (c == null) c = super.loadClass(nombre, false);
                if (resolver) resolveClass(c);
                return c;
            }
        }

        @Override
        public URL getResource(String nombre) {
            // los nativos de ONNX Runtime (ai/onnxruntime/native/...) se buscan primero en el jar descargado
            if (nombre.startsWith("ai/onnxruntime/") && getURLs().length > 0) {
                URL u = findResource(nombre);
                if (u != null) return u;
            }
            return super.getResource(nombre);
        }
    }
}
