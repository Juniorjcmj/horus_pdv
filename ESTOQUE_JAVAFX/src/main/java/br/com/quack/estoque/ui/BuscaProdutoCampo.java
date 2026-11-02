package br.com.quack.estoque.ui;

import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * Campo de busca de produto com a lista de resultados aparecendo enquanto digita (a partir do 2º caractere), logo
 * abaixo do campo. Setas ↑/↓ andam na lista, Enter ou clique escolhe, Esc fecha. Código exato (leitor de código de
 * barras) + Enter escolhe direto.
 */
public final class BuscaProdutoCampo {
    /** Quantidade de caracteres para começar a sugerir. */
    public static final int MINIMO = 2;
    private static final int MAX_ITENS = 12;

    private final TextField campo;
    private final Function<String, List<Produto>> buscar;
    private final Function<String, Produto> exato;
    private final Consumer<Produto> aoEscolher;
    private final ListView<Produto> lista = new ListView<>();
    private final Popup popup = new Popup();
    private final PauseTransition espera = new PauseTransition(Duration.millis(120));

    /**
     * @param campo      campo de texto (já criado e posicionado na tela)
     * @param buscar     texto → produtos (na ordem em que devem aparecer)
     * @param exato      texto → produto com esse código/GTIN exato, ou null
     * @param aoEscolher chamado com o produto escolhido (o campo é limpo depois)
     */
    public BuscaProdutoCampo(TextField campo, Function<String, List<Produto>> buscar, Function<String, Produto> exato,
            Consumer<Produto> aoEscolher) {
        this.campo = campo;
        this.buscar = buscar;
        this.exato = exato;
        this.aoEscolher = aoEscolher;

        lista.getStyleClass().add("rg-busca-lista");
        lista.setFocusTraversable(false);
        lista.setPrefHeight(300);
        lista.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Produto p, boolean vazio) {
                super.updateItem(p, vazio);
                if (vazio || p == null) {
                    setText(null);
                    return;
                }
                StringBuilder sb = new StringBuilder(p.codigo()).append("  ·  ").append(p.nome())
                        .append("  ·  estoque ").append(Fmt.qty(p.estoque())).append(' ').append(p.unidade());
                if (p.custo() != null && p.custo().signum() > 0) {
                    sb.append("  ·  custo ").append(Fmt.money(p.custo()));
                }
                if (p.fornecedor() != null && !p.fornecedor().isBlank()) {
                    sb.append("  ·  ").append(p.fornecedor());
                }
                setText(sb.toString());
            }
        });
        lista.setOnMouseClicked(e -> {
            Produto p = lista.getSelectionModel().getSelectedItem();
            if (p != null) {
                escolher(p);
            }
        });
        popup.getContent().add(lista);
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        espera.setOnFinished(e -> atualizar());
        campo.textProperty().addListener((o, a, b) -> espera.playFromStart());
        campo.focusedProperty().addListener((o, a, agora) -> {
            if (!agora) {
                popup.hide();
            }
        });
        campo.setOnKeyPressed(e -> {
            KeyCode k = e.getCode();
            if (k == KeyCode.DOWN || k == KeyCode.UP) {
                if (!popup.isShowing()) {
                    atualizar();
                }
                int i = lista.getSelectionModel().getSelectedIndex();
                int n = lista.getItems().size();
                if (n > 0) {
                    int novo = k == KeyCode.DOWN ? Math.min(n - 1, i + 1) : Math.max(0, i - 1);
                    lista.getSelectionModel().select(novo);
                    lista.scrollTo(novo);
                }
                e.consume();
            } else if (k == KeyCode.ENTER) {
                e.consume();
                confirmar();
            } else if (k == KeyCode.ESCAPE && popup.isShowing()) {
                popup.hide();
                e.consume();
            }
        });
    }

    public TextField campo() {
        return campo;
    }

    /** Itens sugeridos agora (para testes). */
    List<Produto> sugestoes() {
        return List.copyOf(lista.getItems());
    }

    boolean listaAberta() {
        return popup.isShowing();
    }

    /** Refaz a lista com o texto atual (a partir do 2º caractere). */
    void atualizar() {
        String t = campo.getText() == null ? "" : campo.getText().trim();
        if (t.length() < MINIMO) {
            lista.getItems().clear();
            popup.hide();
            return;
        }
        List<Produto> achados = buscar.apply(t);
        lista.setItems(FXCollections.observableArrayList(achados.size() > MAX_ITENS ? achados.subList(0, MAX_ITENS) : achados));
        if (achados.isEmpty()) {
            popup.hide();
            return;
        }
        lista.getSelectionModel().selectFirst();
        lista.setPrefHeight(Math.min(300, 8 + Math.min(achados.size(), MAX_ITENS) * 28));
        lista.setPrefWidth(Math.max(520, campo.getWidth()));
        if (!popup.isShowing() && campo.getScene() != null && campo.getScene().getWindow() != null) {
            Bounds b = campo.localToScreen(campo.getBoundsInLocal());
            if (b != null) {
                popup.show(campo.getScene().getWindow(), b.getMinX(), b.getMaxY() + 2);
            }
        }
    }

    /** Enter: código exato escolhe direto; senão o item marcado na lista. */
    void confirmar() {
        String t = campo.getText() == null ? "" : campo.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        Produto p = exato.apply(t);
        if (p == null) {
            if (!popup.isShowing() || lista.getItems().isEmpty()) {
                atualizar();
            }
            p = lista.getSelectionModel().getSelectedItem();
        }
        if (p != null) {
            escolher(p);
        }
    }

    private void escolher(Produto p) {
        popup.hide();
        espera.stop();
        campo.clear();
        lista.getItems().clear();
        aoEscolher.accept(p);
        campo.requestFocus();
    }
}
