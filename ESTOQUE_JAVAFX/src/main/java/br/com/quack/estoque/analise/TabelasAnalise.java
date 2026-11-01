package br.com.quack.estoque.analise;

import br.com.quack.estoque.ui.Tela;
import java.util.function.Function;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;

/** Comportamentos comuns das tabelas da análise. */
public final class TabelasAnalise {
    private TabelasAnalise() {
    }

    /** Duplo clique numa linha abre o detalhe de análise do produto (id extraído da linha). */
    public static <T> void duploCliqueAbreProduto(TableView<T> tabela, Function<T, String> produtoId, Tela.Navegador nav) {
        tabela.setRowFactory(t -> {
            TableRow<T> linha = new TableRow<>();
            linha.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !linha.isEmpty() && nav != null) {
                    String id = produtoId.apply(linha.getItem());
                    if (id != null && !id.isBlank()) {
                        nav.abrirProduto(id);
                    }
                }
            });
            return linha;
        });
    }
}
