package br.com.quack.estoque.ui;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

/**
 * Célula de tabela que edita com um clique: abre um campo (ou uma lista de opções) com o valor atual selecionado;
 * Enter salva, Esc cancela, Tab salva e passa para a próxima coluna editável da mesma linha, e sair do campo também
 * salva. O valor confirmado vai para o {@code onEditCommit} da coluna.
 */
public final class CelulaEditavel<S> extends TableCell<S, String> {
    private final Function<S, String> valorParaEditar;
    private final Predicate<S> podeEditar;
    private final boolean numero;
    private final List<String> opcoes;
    private TextField campo;
    private ComboBox<String> lista;

    private CelulaEditavel(Function<S, String> valorParaEditar, Predicate<S> podeEditar, boolean numero, List<String> opcoes) {
        this.valorParaEditar = valorParaEditar;
        this.podeEditar = podeEditar;
        this.numero = numero;
        this.opcoes = opcoes;
        getStyleClass().add("rg-celula-editavel");
        if (numero) {
            setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1 && !isEmpty() && !isEditing() && editavel()) {
                getTableView().edit(getIndex(), getTableColumn());
            }
        });
    }

    /** Texto livre (nome, código) ou número (preço, estoque) conforme {@code numero}. */
    public static <S> void em(TableColumn<S, String> coluna, Function<S, String> valorParaEditar, boolean numero,
            Predicate<S> podeEditar) {
        coluna.setEditable(true);
        coluna.setCellFactory(c -> new CelulaEditavel<>(valorParaEditar, podeEditar, numero, null));
    }

    /** Escolha numa lista (unidade, categoria). */
    public static <S> void lista(TableColumn<S, String> coluna, List<String> opcoes, Predicate<S> podeEditar) {
        coluna.setEditable(true);
        coluna.setCellFactory(c -> new CelulaEditavel<>(null, podeEditar, false, opcoes));
    }

    private boolean editavel() {
        S linha = getTableRow() == null ? null : getTableRow().getItem();
        return linha != null && getTableView().isEditable() && getTableColumn().isEditable()
                && (podeEditar == null || podeEditar.test(linha));
    }

    @Override
    public void startEdit() {
        if (!editavel()) {
            return;
        }
        super.startEdit();
        if (!isEditing()) {
            return;
        }
        S linha = getTableRow().getItem();
        if (opcoes != null) {
            lista = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(opcoes));
            lista.setValue(getItem());
            lista.setMaxWidth(Double.MAX_VALUE);
            lista.setOnAction(e -> {
                if (isEditing()) {
                    commitEdit(lista.getValue());
                }
            });
            lista.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
            setText(null);
            setGraphic(lista);
            lista.requestFocus();
            lista.show();
            return;
        }
        campo = new TextField(valorParaEditar == null ? getItem() : valorParaEditar.apply(linha));
        campo.getStyleClass().add("rg-celula-campo");
        if (numero) {
            campo.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        campo.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                commitEdit(campo.getText());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                cancelEdit();
                e.consume();
            } else if (e.getCode() == KeyCode.TAB) {
                int linhaIdx = getIndex();
                TableColumn<S, ?> proxima = proximaEditavel(e.isShiftDown());
                commitEdit(campo.getText());
                if (proxima != null) {
                    javafx.application.Platform.runLater(() -> getTableView().edit(linhaIdx, proxima));
                }
                e.consume();
            }
        });
        // sair do campo (clicar em outro lugar) também salva
        campo.focusedProperty().addListener((o, antes, agora) -> {
            if (!agora && isEditing() && campo != null) {
                commitEdit(campo.getText());
            }
        });
        setText(null);
        setGraphic(campo);
        campo.requestFocus();
        campo.selectAll();
    }

    private TableColumn<S, ?> proximaEditavel(boolean voltar) {
        var colunas = getTableView().getVisibleLeafColumns();
        int i = colunas.indexOf(getTableColumn());
        for (int k = 1; k < colunas.size(); k++) {
            int j = voltar ? i - k : i + k;
            if (j < 0 || j >= colunas.size()) {
                return null;
            }
            TableColumn<S, ?> c = colunas.get(j);
            if (c.isEditable()) {
                return c;
            }
        }
        return null;
    }

    @Override
    public void cancelEdit() {
        super.cancelEdit();
        campo = null;
        lista = null;
        setText(getItem());
        setGraphic(null);
    }

    @Override
    public void commitEdit(String valor) {
        campo = null;
        lista = null;
        super.commitEdit(valor);
        setGraphic(null);
    }

    @Override
    protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty) {
            setText(null);
            setGraphic(null);
            setTooltip(null);
            return;
        }
        if (isEditing() && (campo != null || lista != null)) {
            setText(null);
            setGraphic(campo != null ? campo : lista);
            return;
        }
        setText(item);
        setGraphic(null);
        S linha = getTableRow() == null ? null : getTableRow().getItem();
        boolean pode = linha != null && (podeEditar == null || podeEditar.test(linha)) && getTableColumn() != null
                && getTableColumn().isEditable();
        setTooltip(pode ? new Tooltip((item == null ? "" : item) + "\nClique para editar") : null);
    }
}
