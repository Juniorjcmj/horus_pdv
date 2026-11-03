package br.com.quack.caixa.ui;

import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.service.CatalogService;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Escolha de cliente cadastrado (fiado / identificação). */
public final class CustomerPickerDialog {
    /** cleared = operador escolheu "Sem cliente". */
    public record Choice(Customer customer, boolean cleared) {
    }

    private CustomerPickerDialog() {
    }

    public static Optional<Choice> show(Window owner, CatalogService catalog) {
        TextField search = new TextField();
        search.setPromptText("Buscar por nome ou documento");
        ListView<Customer> list = new ListView<>();
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Customer c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.name() + "  |  " + c.document() + "  |  Limite R$ "
                        + Money.formatBr(c.creditLimit()) + "  Saldo R$ " + Money.formatBr(c.debt()));
            }
        });
        list.getItems().setAll(catalog.searchCustomers(""));
        search.textProperty().addListener((o, a, b) -> list.getItems().setAll(catalog.searchCustomers(b)));
        VBox.setVgrow(list, Priority.ALWAYS);

        Stage[] stage = new Stage[1];
        Choice[] result = new Choice[1];
        Runnable select = () -> {
            Customer c = list.getSelectionModel().getSelectedItem();
            if (c != null) {
                result[0] = new Choice(c, false);
                stage[0].close();
            }
        };
        Button ok = Fx.button("Selecionar (Enter)", "rg-btn-primary", select);
        Button none = Fx.button("Sem cliente", null, () -> {
            result[0] = new Choice(null, true);
            stage[0].close();
        });
        HBox actions = new HBox(8, ok, none);
        VBox root = new VBox(10, new Label("Selecionar cliente"), search, list, actions);
        root.setPadding(new Insets(16));
        root.setPrefSize(640, 460);
        stage[0] = Fx.modal(owner, "Cliente", root);
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                list.requestFocus();
                list.getSelectionModel().selectFirst();
            } else if (e.getCode() == KeyCode.ENTER) {
                if (list.getSelectionModel().isEmpty()) {
                    list.getSelectionModel().selectFirst();
                }
                select.run();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                select.run();
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                select.run();
            }
        });
        stage[0].setOnShown(e -> search.requestFocus());
        stage[0].showAndWait();
        return Optional.ofNullable(result[0]);
    }
}
