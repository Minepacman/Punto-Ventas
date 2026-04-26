package me.eliassanchezfernandez.puntodeventa;

/**
 * Clase de arranque separada.
 * Necesaria porque jpackage/jlink requieren que el main
 * NO extienda javafx.application.Application directamente
 * cuando se empaqueta como módulo nativo.
 */
public class Launcher {
    
    public static void main(String[] args) {
        PuntoDeVentaApp.main(args);
    }

}