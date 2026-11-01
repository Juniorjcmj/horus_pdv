package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Perfil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Janela principal: menu lateral por perfil, telas sob demanda, atalhos de teclado e barra de status. */
public class MainView extends BorderPane implements Tela.Navegador {
    private record Item(String id, String rotulo, String atalho, boolean visivel,
            BiFunction<AppContext, Tela.Navegador, Tela> fabrica) {
    }

    private final AppContext ctx;
    private final Perfil perfil;
    private final StackPane conteudo = new StackPane();
    private final Map<String, Tela> telas = new LinkedHashMap<>();
    private final Map<String, Button> botoes = new LinkedHashMap<>();
    private final Map<String, Item> itens = new LinkedHashMap<>();
    private final Label statusCatalogo = Ui.muted("");
    private String atual;
    private String anteriorAoDetalhe = "analise-dashboard";
    private br.com.quack.estoque.analise.DetalheProdutoView detalhe;

    public MainView(AppContext ctx, Runnable aoSair) {
        this.ctx = ctx;
        this.perfil = ctx.session.perfil();
        getStyleClass().add("rg-shell");

        List<Item> lista = List.of(
                new Item("inicio", "Início", "", true, InicioView::new),
                new Item("produtos", "Produtos", "F2", true, ProdutosView::new),
                new Item("entradas", "Entradas", "F3", true, EntradasView::new),
                new Item("ajustes", "Ajustes e histórico", "F4", true, MovimentacoesView::new),
                new Item("inventario", "Inventário", "F6", true, InventarioView::new),
                new Item("etiquetas", "Etiquetas", "F8", true, EtiquetasView::new),
                new Item("compras", "Compras", "", true, ComprasView::new),
                new Item("relatorios", "Relatórios", "", true, RelatoriosView::new),
                new Item("analise-dashboard", "Dashboard", "", true, br.com.quack.estoque.analise.DashboardView::new),
                new Item("analise-abc", "Curva ABC", "", perfil.relatoriosDeValor(), br.com.quack.estoque.analise.CurvaAbcView::new),
                new Item("analise-reposicao", "Reposição", "", true, br.com.quack.estoque.analise.ReposicaoView::new),
                new Item("analise-acuracidade", "Acuracidade", "", true, br.com.quack.estoque.analise.AcuraciaView::new),
                new Item("analise-perdas", "Perdas e parados", "", true, br.com.quack.estoque.analise.PerdasView::new),
                new Item("analise-fornecedores", "Fornecedores", "", true, br.com.quack.estoque.analise.FornecedoresView::new),
                new Item("analise-config", "Configurações", "", true, br.com.quack.estoque.analise.ConfiguracoesView::new),
                new Item("cadastros", "Cadastros", "", perfil.cadastrarCategoriasEFornecedores(), CadastrosView::new));

        VBox menu = new VBox(2);
        menu.getStyleClass().add("rg-sidebar");
        ImageView logo = new ImageView(new Image(MainView.class.getResource("quack-logo-negativo.png").toExternalForm(), 0, 72, true, true));
        logo.setFitHeight(30);
        logo.setPreserveRatio(true);
        HBox marca = new HBox(10, logo, new Label("Estoque"));
        marca.getStyleClass().add("rg-sidebar-brand");
        menu.getChildren().add(marca);
        for (Item i : lista) {
            if (!i.visivel()) {
                continue;
            }
            if (i.id().startsWith("analise-") && itens.keySet().stream().noneMatch(k -> k.startsWith("analise-"))) {
                Label secao = new Label("ANÁLISE");
                secao.getStyleClass().add("rg-nav-secao");
                menu.getChildren().add(secao);
            }
            itens.put(i.id(), i);
            Button b = new Button(i.rotulo() + (i.atalho().isEmpty() ? "" : "   " + i.atalho()));
            b.getStyleClass().add("rg-nav-btn");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setFocusTraversable(false);
            b.setOnAction(e -> ir(i.id()));
            botoes.put(i.id(), b);
            menu.getChildren().add(b);
        }
        javafx.scene.layout.Region folga = new javafx.scene.layout.Region();
        VBox.setVgrow(folga, javafx.scene.layout.Priority.ALWAYS);
        Label nome = new Label(ctx.session.user().name());
        Label papel = new Label(perfil.rotulo());
        Button sair = Fx.button("Sair", "rg-btn-small", aoSair);
        VBox usuario = new VBox(2, nome, papel, sair);
        usuario.getStyleClass().add("rg-sidebar-user");
        menu.getChildren().addAll(folga, usuario);
        setLeft(menu);

        setCenter(conteudo);
        HBox status = new HBox(16, Ui.muted("Servidor: " + ctx.api.baseUrl()), statusCatalogo, Ui.espaco(),
                Ui.muted("F5 atualiza o catálogo · Esc fecha janelas"));
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("rg-statusbar-app");
        setBottom(status);
        atualizarStatus();
    }

    /** Liga os atalhos de teclado na cena (chamar depois de colocar a MainView numa Scene). */
    public void instalar(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F2) {
                if (!"produtos".equals(atual) && !"entradas".equals(atual)) {
                    ir("produtos");
                }
                Tela t = telas.get(atual);
                if (t != null) {
                    t.focarBusca();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                novaEntradaNfe();
                e.consume();
            } else if (e.getCode() == KeyCode.F4) {
                ir("ajustes");
                e.consume();
            } else if (e.getCode() == KeyCode.F5) {
                recarregarCatalogo();
                e.consume();
            } else if (e.getCode() == KeyCode.F6) {
                ir("inventario-contar");
                e.consume();
            } else if (e.getCode() == KeyCode.F8) {
                ir("etiquetas");
                e.consume();
            }
        });
        ir("inicio");
    }

    /** O menu deste perfil tem essa tela? */
    public boolean possui(String id) {
        return itens.containsKey(id);
    }

    private Tela tela(String id) {
        return telas.computeIfAbsent(id, k -> itens.get(k).fabrica().apply(ctx, this));
    }

    @Override
    public void ir(String id) {
        if ("inventario-contar".equals(id)) {
            ir("inventario");
            ((InventarioView) tela("inventario")).contarAtual();
            return;
        }
        if (!itens.containsKey(id)) {
            return;
        }
        atual = id;
        Tela t = tela(id);
        conteudo.getChildren().setAll(t);
        botoes.forEach((k, b) -> {
            b.getStyleClass().remove("rg-nav-btn-active");
            if (k.equals(id)) {
                b.getStyleClass().add("rg-nav-btn-active");
            }
        });
        t.ativar();
        atualizarStatus();
    }

    @Override
    public void abrirProduto(String produtoId) {
        if (detalhe == null) {
            detalhe = new br.com.quack.estoque.analise.DetalheProdutoView(ctx, this, () -> ir(anteriorAoDetalhe));
        }
        if (!"analise-produto".equals(atual) && atual != null) {
            anteriorAoDetalhe = atual;
        }
        atual = "analise-produto";
        conteudo.getChildren().setAll(detalhe);
        botoes.forEach((k, b) -> b.getStyleClass().remove("rg-nav-btn-active"));
        detalhe.abrir(produtoId);
    }

    /** Detalhe de análise aberto agora (para testes). */
    public br.com.quack.estoque.analise.DetalheProdutoView detalheAberto() {
        return "analise-produto".equals(atual) ? detalhe : null;
    }

    @Override
    public void novaEntradaNfe() {
        ir("entradas");
        EntradaNfeDialog.abrir(ctx, Ui.janela(this), () -> telas.get("entradas").ativar(), () -> ir("etiquetas"));
    }

    @Override
    public void recarregarCatalogo() {
        Fx.async(() -> {
            ctx.garantirCatalogo(true);
            return ctx.catalogo.todos().size();
        }, n -> {
            Toasts.success(Ui.janela(this), "Catálogo atualizado: " + n + " produtos.");
            atualizarStatus();
            Tela t = telas.get(atual);
            if (t != null) {
                t.ativar();
            }
        }, t -> Toasts.error(Ui.janela(this), Fx.message(t)));
    }

    private void atualizarStatus() {
        statusCatalogo.setText("Catálogo: " + ctx.catalogo.todos().size() + " produtos");
    }
}
