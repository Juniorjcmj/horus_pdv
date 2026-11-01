package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Base das telas do menu lateral: recarrega os dados em {@link #ativar()} e fala com a API fora da FX thread. */
public abstract class Tela extends VBox {

    /** Navegação entre telas (usada pelos atalhos e pelos cartões do Início). */
    public interface Navegador {
        void ir(String id);

        /** Abre o assistente de entrada por NF-e (F3). */
        void novaEntradaNfe();

        /** Recarrega o catálogo em memória (F5). */
        void recarregarCatalogo();

        /** Abre o detalhe de análise de um produto (duplo clique nas tabelas da análise). */
        default void abrirProduto(String produtoId) {
        }
    }

    protected final AppContext ctx;
    protected final Navegador nav;

    protected Tela(AppContext ctx, Navegador nav) {
        super(12);
        this.ctx = ctx;
        this.nav = nav;
        getStyleClass().add("rg-page");
    }

    /** Chamado sempre que a tela aparece. */
    public abstract void ativar();

    /** Foco no campo de busca (F2). */
    public void focarBusca() {
    }

    protected Window janela() {
        return Ui.janela(this);
    }

    protected <T> void rodar(Supplier<T> trabalho, Consumer<T> ok) {
        Fx.async(trabalho, ok, this::erro);
    }

    protected void erro(Throwable t) {
        Toasts.error(janela(), Fx.message(t));
    }

    protected void sucesso(String msg) {
        Toasts.success(janela(), msg);
    }
}
