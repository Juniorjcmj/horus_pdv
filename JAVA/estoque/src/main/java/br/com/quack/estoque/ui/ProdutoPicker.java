package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Janela para escolher um produto do catálogo (busca por nome, código ou GTIN; leitor de código de barras). */
public final class ProdutoPicker {
    private ProdutoPicker() {
    }

    public static void escolher(Window dono, AppContext ctx, boolean mostrarCusto, Consumer<Produto> aoEscolher) {
        TextField busca = Ui.campo("Nome, código ou GTIN (pode bipar o código de barras)");
        TableView<Produto> tabela = Ui.tabela();
        tabela.setPrefHeight(340);
        Ui.col(tabela, "Código", Produto::codigo, 90);
        Ui.col(tabela, "Produto", Produto::nome, 280);
        Ui.col(tabela, "Un", Produto::unidade, 44);
        Ui.colNum(tabela, "Preço", p -> Fmt.dec2(p.preco()), 80);
        if (mostrarCusto) {
            Ui.colNum(tabela, "Custo", p -> Fmt.dec2(p.custo()), 80);
        }
        Ui.colNum(tabela, "Estoque", p -> Fmt.qty(p.estoque()), 70);
        Label aviso = Ui.muted("");
        Runnable filtrar = () -> {
            var lista = ctx.catalogo.buscar(busca.getText(), 200);
            tabela.setItems(FXCollections.observableArrayList(lista));
            aviso.setText(lista.size() >= 200 ? "Mostrando os 200 primeiros: refine a busca." : "");
            if (!lista.isEmpty()) {
                tabela.getSelectionModel().selectFirst();
            }
        };
        busca.textProperty().addListener((o, a, b) -> filtrar.run());
        VBox corpo = new VBox(8, busca, tabela, aviso);
        Modal[] m = new Modal[1];
        Runnable escolher = () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p == null) {
                m[0].erro("Selecione um produto.");
                return;
            }
            m[0].fechar();
            aoEscolher.accept(p);
        };
        m[0] = Modal.formulario(dono, "Escolher produto", corpo, "Escolher", 720, () -> {
            Produto p = tabela.getSelectionModel().getSelectedItem();
            if (p == null) {
                m[0].erro("Selecione um produto.");
                return false;
            }
            aoEscolher.accept(p);
            return true;
        });
        tabela.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                escolher.run();
            }
        });
        busca.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                var leitura = ctx.catalogo.ler(busca.getText().trim());
                if (leitura != null) {
                    m[0].fechar();
                    aoEscolher.accept(leitura.produto());
                } else {
                    escolher.run();
                }
            } else if (e.getCode() == KeyCode.DOWN) {
                tabela.requestFocus();
            }
        });
        Fx.async(() -> {
            ctx.garantirCatalogo(false);
            return true;
        }, ok -> filtrar.run(), t -> m[0].erro(Fx.message(t)));
        m[0].mostrar();
        busca.requestFocus();
    }
}
