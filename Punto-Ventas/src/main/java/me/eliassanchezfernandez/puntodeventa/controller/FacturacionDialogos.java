package me.eliassanchezfernandez.puntodeventa.controller;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import me.eliassanchezfernandez.puntodeventa.PuntoDeVentaApp;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat;
import me.eliassanchezfernandez.puntodeventa.facturacion.CatalogoSat.Entrada;
import me.eliassanchezfernandez.puntodeventa.facturacion.ConstructorCfdi;
import me.eliassanchezfernandez.puntodeventa.facturacion.ProveedorCfdi.Formato;
import me.eliassanchezfernandez.puntodeventa.facturacion.Rfc;
import me.eliassanchezfernandez.puntodeventa.model.ClienteFiscal;
import me.eliassanchezfernandez.puntodeventa.model.Factura;
import me.eliassanchezfernandez.puntodeventa.model.Permiso;
import me.eliassanchezfernandez.puntodeventa.model.Venta;
import me.eliassanchezfernandez.puntodeventa.service.ClienteFiscalService;
import me.eliassanchezfernandez.puntodeventa.service.ConfiguracionFiscalService;
import me.eliassanchezfernandez.puntodeventa.service.FacturacionService;
import me.eliassanchezfernandez.puntodeventa.service.NegocioException;
import me.eliassanchezfernandez.puntodeventa.service.SesionService;
import me.eliassanchezfernandez.puntodeventa.util.Moneda;
import me.eliassanchezfernandez.puntodeventa.util.Ventanas;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Diálogos de facturación compartidos por Ventas (después de cobrar), Ventas
 * por período y la pantalla de Facturas: elegir o dar de alta al cliente
 * fiscal y timbrar; acciones sobre una factura (ver PDF, guardar, correo).
 * Todo lo que habla con el PAC corre fuera del hilo de JavaFX.
 */
@Component
public class FacturacionDialogos {

    @Autowired private FacturacionService facturacionService;
    @Autowired private ClienteFiscalService clienteFiscalService;
    @Autowired private ConfiguracionFiscalService fiscalService;
    @Autowired private SesionService sesionService;

    /** ¿Mostrar el botón Facturar? (configuración lista y permiso del usuario) */
    public boolean puedeFacturar() {
        return sesionService.tienePermiso(Permiso.FACTURAR) && fiscalService.facturacionLista();
    }

    // ── Facturar una venta ───────────────────────────────────────────────

    /**
     * Pide el receptor, el uso y la forma de pago, timbra y, si todo sale bien,
     * ofrece ver el PDF o enviarlo. Devuelve la factura emitida.
     */
    public Optional<Factura> facturarVenta(Window owner, Venta venta) {
        if (!fiscalService.facturacionLista()) {
            Ventanas.alerta(Alert.AlertType.WARNING, "Facturar",
                    "Antes de facturar captura el emisor y las credenciales del PAC en Configuración → Facturación.");
            return Optional.empty();
        }
        Optional<Factura> bloquea = facturacionService.facturaQueBloquea(venta.getId());
        if (bloquea.isPresent()) {
            Factura f = bloquea.get();
            if (!f.isPendiente()) {
                Ventanas.alerta(Alert.AlertType.INFORMATION, "Venta ya facturada",
                        "La venta " + venta.getFolioMostrado() + " ya está en la factura " + f.getSerieFolio()
                                + (f.esGlobal() ? " (global)" : "") + ".");
                return Optional.empty();
            }
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Facturar venta " + venta.getFolioMostrado());
        dlg.setHeaderText("Factura de la venta " + venta.getFolioMostrado() + " por " + Moneda.formato(venta.getTotal())
                + (fiscalService.obtener().isPacSandbox() ? "\nMODO PRUEBAS: la factura no tendrá validez fiscal." : ""));
        if (owner != null) dlg.initOwner(owner);

        ComboBox<ClienteFiscal> cmbCliente = new ComboBox<>(FXCollections.observableArrayList(clienteFiscalService.listar()));
        cmbCliente.setMaxWidth(Double.MAX_VALUE);
        cmbCliente.setPromptText("Elige el cliente (RFC — nombre)");
        TextField txtBuscar = new TextField();
        txtBuscar.setPromptText("Buscar por RFC o nombre…");
        txtBuscar.textProperty().addListener((o, a, b) -> {
            cmbCliente.setItems(FXCollections.observableArrayList(clienteFiscalService.buscar(b)));
            if (cmbCliente.getItems().size() == 1) cmbCliente.setValue(cmbCliente.getItems().get(0));
        });
        Button btnNuevo = new Button("Nuevo cliente…");
        btnNuevo.setOnAction(e -> editarCliente(dlg.getDialogPane().getScene().getWindow(), null).ifPresent(c -> {
            cmbCliente.setItems(FXCollections.observableArrayList(clienteFiscalService.listar()));
            cmbCliente.getItems().stream().filter(x -> x.getId().equals(c.getId())).findFirst().ifPresent(cmbCliente::setValue);
        }));
        Button btnPublico = new Button("Público en general");
        btnPublico.setOnAction(e -> {
            ClienteFiscal gen = clienteFiscalService.porRfc(Rfc.PUBLICO_EN_GENERAL).orElseGet(() -> {
                ClienteFiscal c = new ClienteFiscal();
                c.setRfc(Rfc.PUBLICO_EN_GENERAL);
                c.setNombre("PUBLICO EN GENERAL");
                c.setRegimenFiscal(CatalogoSat.REGIMEN_SIN_OBLIGACIONES);
                c.setCodigoPostal(fiscalService.obtener().getEmisorCodigoPostal());
                c.setUsoCfdi(CatalogoSat.USO_SIN_EFECTOS);
                try {
                    return clienteFiscalService.guardar(c);
                } catch (NegocioException ex) {
                    Ventanas.alerta(Alert.AlertType.ERROR, "Cliente", ex.getMessage());
                    return null;
                }
            });
            if (gen != null) {
                cmbCliente.setItems(FXCollections.observableArrayList(clienteFiscalService.listar()));
                cmbCliente.getItems().stream().filter(x -> x.getId().equals(gen.getId())).findFirst().ifPresent(cmbCliente::setValue);
            }
        });

        ComboBox<Entrada> cmbUso = new ComboBox<>(FXCollections.observableArrayList(CatalogoSat.USOS_CFDI));
        cmbUso.setMaxWidth(Double.MAX_VALUE);
        cmbCliente.valueProperty().addListener((o, a, c) -> {
            if (c != null) CatalogoSat.usoCfdi(c.getUsoCfdi()).ifPresent(cmbUso::setValue);
        });
        ComboBox<Entrada> cmbForma = new ComboBox<>(FXCollections.observableArrayList(CatalogoSat.FORMAS_PAGO));
        cmbForma.setMaxWidth(Double.MAX_VALUE);
        CatalogoSat.formaPago(ConstructorCfdi.formaPagoSat(venta)).ifPresent(cmbForma::setValue);
        Label notaForma = new Label(venta.getPagos() != null && venta.getPagos().size() > 1
                ? "Pago dividido: el SAT admite UNA forma de pago; se propone la del pago mayor."
                : "Si pagó con tarjeta elige crédito (04) o débito (28).");
        notaForma.setWrapText(true);
        notaForma.getStyleClass().add("hint");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("Cliente (receptor):"), txtBuscar);
        grid.add(cmbCliente, 1, 1);
        grid.add(new HBox(8, btnNuevo, btnPublico), 1, 2);
        grid.addRow(3, new Label("Uso del CFDI:"), cmbUso);
        grid.addRow(4, new Label("Forma de pago:"), cmbForma);
        grid.add(notaForma, 1, 5);
        GridPane.setHgrow(cmbCliente, Priority.ALWAYS);
        GridPane.setHgrow(txtBuscar, Priority.ALWAYS);
        cmbCliente.setPrefWidth(420);

        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);
        Label progreso = new Label("Timbrando con el PAC… espera unos segundos.");
        progreso.setVisible(false);
        progreso.setManaged(false);

        VBox caja = new VBox(10, grid, error, progreso);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        ButtonType facturar = new ButtonType("Facturar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(facturar, ButtonType.CANCEL);
        Button btnFacturar = (Button) dlg.getDialogPane().lookupButton(facturar);
        btnFacturar.getStyleClass().add("button-primary");
        Ventanas.estilizar(dlg);

        final Factura[] resultado = {null};
        btnFacturar.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ev.consume();   // el diálogo se cierra solo cuando el PAC responde
            ClienteFiscal c = cmbCliente.getValue();
            if (c == null) { mostrarError(error, "Elige el cliente al que se factura."); return; }
            if (cmbUso.getValue() == null) { mostrarError(error, "Elige el uso del CFDI."); return; }
            if (cmbForma.getValue() == null) { mostrarError(error, "Elige la forma de pago."); return; }
            error.setVisible(false);
            error.setManaged(false);
            progreso.setVisible(true);
            progreso.setManaged(true);
            dlg.getDialogPane().setDisable(true);
            Long clienteId = c.getId();
            String uso = cmbUso.getValue().clave();
            String forma = cmbForma.getValue().clave();
            enSegundoPlano(() -> facturacionService.facturarVenta(venta.getId(), clienteId, uso, forma), f -> {
                resultado[0] = f;
                dlg.setResult(facturar);
                dlg.close();
            }, msg -> {
                dlg.getDialogPane().setDisable(false);
                progreso.setVisible(false);
                progreso.setManaged(false);
                mostrarError(error, msg);
            });
        });
        dlg.showAndWait();
        if (resultado[0] == null) return Optional.empty();
        Factura f = resultado[0];
        accionesFactura(owner, f, "Factura " + f.getSerieFolio() + " emitida" + (f.isSandbox() ? " (PRUEBAS)" : "")
                + "\nUUID: " + f.getUuid() + "\nTotal: " + Moneda.formato(f.getTotal()));
        return Optional.of(f);
    }

    // ── Acciones sobre una factura ───────────────────────────────────────

    /** Ventana con Ver PDF / Guardar XML / Guardar PDF / Enviar por correo */
    public void accionesFactura(Window owner, Factura f, String encabezado) {
        Dialog<ButtonType> dlg = new Dialog<>();
        dlg.setTitle("Factura " + f.getSerieFolio());
        dlg.setHeaderText(encabezado);
        if (owner != null) dlg.initOwner(owner);
        Button verPdf = new Button("Ver PDF");
        Button guardarPdf = new Button("Guardar PDF…");
        Button guardarXml = new Button("Guardar XML…");
        Button correo = new Button("Enviar por correo…");
        Label estado = new Label();
        estado.setWrapText(true);
        estado.getStyleClass().add("hint");
        Window w = dlg.getDialogPane().getScene() != null ? dlg.getDialogPane().getScene().getWindow() : owner;
        verPdf.setOnAction(e -> verPdf(f, estado));
        guardarPdf.setOnAction(e -> guardarArchivo(w, f, Formato.PDF, estado));
        guardarXml.setOnAction(e -> guardarArchivo(w, f, Formato.XML, estado));
        correo.setOnAction(e -> enviarCorreo(w, f, estado));
        boolean timbrada = f.getIdExterno() != null;
        for (Button b : List.of(verPdf, guardarPdf, guardarXml, correo)) b.setDisable(!timbrada);
        VBox caja = new VBox(10, new HBox(8, verPdf, guardarPdf, guardarXml), correo, estado);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        Ventanas.estilizar(dlg);
        dlg.showAndWait();
    }

    public void verPdf(Factura f, Label estado) {
        estado.setText("Abriendo el PDF…");
        enSegundoPlano(() -> facturacionService.archivo(f.getId(), Formato.PDF), ruta -> {
            estado.setText("PDF: " + ruta);
            abrirArchivo(ruta);
        }, msg -> estado.setText(msg));
    }

    public void guardarArchivo(Window owner, Factura f, Formato formato, Label estado) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Guardar " + formato);
        String ext = formato == Formato.PDF ? "pdf" : "xml";
        fc.setInitialFileName((f.getSerieFolio() + "-" + (f.getUuid() != null ? f.getUuid() : "")).replace('/', '_') + "." + ext);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(ext.toUpperCase(), "*." + ext));
        File destino = fc.showSaveDialog(owner);
        if (destino == null) return;
        File dest = destino.getName().toLowerCase().endsWith("." + ext) ? destino : new File(destino.getPath() + "." + ext);
        estado.setText("Guardando…");
        enSegundoPlano(() -> {
            Path origen = facturacionService.archivo(f.getId(), formato);
            try {
                Files.copy(origen, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new NegocioException("No se pudo guardar el archivo: " + e.getMessage());
            }
            return dest.toPath();
        }, p -> estado.setText("Guardado en " + p), msg -> estado.setText(msg));
    }

    public void enviarCorreo(Window owner, Factura f, Label estado) {
        TextInputDialog d = new TextInputDialog(f.getReceptorEmail() != null ? f.getReceptorEmail() : "");
        d.setTitle("Enviar por correo");
        d.setHeaderText("Facturama enviará el PDF y el XML de la factura " + f.getSerieFolio());
        d.setContentText("Correo:");
        if (owner != null) d.initOwner(owner);
        Ventanas.estilizar(d);
        Optional<String> email = d.showAndWait().filter(s -> !s.isBlank());
        if (email.isEmpty()) return;
        estado.setText("Enviando…");
        enSegundoPlano(() -> { facturacionService.enviarPorCorreo(f.getId(), email.get()); return email.get(); },
                e -> estado.setText("Enviada a " + e), msg -> estado.setText(msg));
    }

    // ── Cliente fiscal ───────────────────────────────────────────────────

    /** Alta o edición de un cliente fiscal; devuelve el guardado */
    public Optional<ClienteFiscal> editarCliente(Window owner, ClienteFiscal existente) {
        Dialog<ClienteFiscal> dlg = new Dialog<>();
        dlg.setTitle(existente == null ? "Nuevo cliente fiscal" : "Editar cliente fiscal");
        dlg.setHeaderText("Datos EXACTOS de la Constancia de Situación Fiscal del cliente");
        if (owner != null) dlg.initOwner(owner);
        TextField rfc = new TextField(existente != null ? existente.getRfc() : "");
        rfc.setPromptText("RFC (13 física, 12 moral)");
        TextField nombre = new TextField(existente != null ? existente.getNombre() : "");
        nombre.setPromptText("Nombre o razón social sin régimen societario");
        ComboBox<Entrada> regimen = new ComboBox<>(FXCollections.observableArrayList(CatalogoSat.REGIMENES));
        regimen.setMaxWidth(Double.MAX_VALUE);
        rfc.textProperty().addListener((o, a, b) -> {
            Entrada actual = regimen.getValue();
            regimen.setItems(FXCollections.observableArrayList(CatalogoSat.regimenesPara(b)));
            if (actual != null && regimen.getItems().contains(actual)) regimen.setValue(actual);
        });
        if (existente != null) CatalogoSat.regimen(existente.getRegimenFiscal()).ifPresent(regimen::setValue);
        TextField cp = new TextField(existente != null ? existente.getCodigoPostal() : "");
        cp.setPromptText("Código postal fiscal (5 dígitos)");
        ComboBox<Entrada> uso = new ComboBox<>(FXCollections.observableArrayList(CatalogoSat.USOS_CFDI));
        uso.setMaxWidth(Double.MAX_VALUE);
        CatalogoSat.usoCfdi(existente != null ? existente.getUsoCfdi() : CatalogoSat.USO_GASTOS_GENERAL).ifPresent(uso::setValue);
        TextField email = new TextField(existente != null && existente.getEmail() != null ? existente.getEmail() : "");
        email.setPromptText("Correo para enviar la factura (opcional)");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("RFC:"), rfc);
        grid.addRow(1, new Label("Nombre / razón social:"), nombre);
        grid.addRow(2, new Label("Régimen fiscal:"), regimen);
        grid.addRow(3, new Label("Código postal:"), cp);
        grid.addRow(4, new Label("Uso de CFDI habitual:"), uso);
        grid.addRow(5, new Label("Correo:"), email);
        for (Node n : List.of(rfc, nombre, regimen, cp, uso, email)) GridPane.setHgrow(n, Priority.ALWAYS);
        nombre.setPrefWidth(380);
        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("field-error-label");
        error.setVisible(false);
        error.setManaged(false);
        VBox caja = new VBox(10, grid, error);
        caja.setPadding(new Insets(10));
        dlg.getDialogPane().setContent(caja);
        ButtonType guardar = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(guardar, ButtonType.CANCEL);
        Button btn = (Button) dlg.getDialogPane().lookupButton(guardar);
        btn.getStyleClass().add("button-primary");
        final ClienteFiscal[] guardado = {null};
        btn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            ClienteFiscal c = new ClienteFiscal();
            if (existente != null) c.setId(existente.getId());
            c.setRfc(rfc.getText());
            c.setNombre(nombre.getText());
            c.setRegimenFiscal(regimen.getValue() != null ? regimen.getValue().clave() : null);
            c.setCodigoPostal(cp.getText());
            c.setUsoCfdi(uso.getValue() != null ? uso.getValue().clave() : null);
            c.setEmail(email.getText());
            try {
                guardado[0] = clienteFiscalService.guardar(c);
            } catch (NegocioException e) {
                mostrarError(error, e.getMessage());
                ev.consume();
            }
        });
        dlg.setResultConverter(b -> b == guardar ? guardado[0] : null);
        Ventanas.estilizar(dlg);
        Platform.runLater(rfc::requestFocus);
        return dlg.showAndWait();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void mostrarError(Label error, String msg) {
        error.setText(msg);
        error.setVisible(true);
        error.setManaged(true);
    }

    /** Ejecuta en un hilo aparte y entrega el resultado (o el mensaje de error) en el hilo de JavaFX */
    static <T> void enSegundoPlano(Supplier<T> trabajo, Consumer<T> exito, Consumer<String> fallo) {
        Thread t = new Thread(() -> {
            T r;
            try {
                r = trabajo.get();
            } catch (NegocioException e) {
                Platform.runLater(() -> fallo.accept(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                Platform.runLater(() -> fallo.accept("Error inesperado: " + e.getMessage()));
                return;
            }
            Platform.runLater(() -> exito.accept(r));
        }, "facturacion");
        t.setDaemon(true);
        t.start();
    }

    static void abrirArchivo(Path ruta) {
        try {
            PuntoDeVentaApp.abrirEnlace(ruta.toUri().toString());
        } catch (RuntimeException e) {
            Ventanas.alerta(Alert.AlertType.INFORMATION, "PDF", "El archivo está en: " + ruta);
        }
    }
}
