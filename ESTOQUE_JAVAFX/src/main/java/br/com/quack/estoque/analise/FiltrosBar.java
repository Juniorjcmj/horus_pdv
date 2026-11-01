package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Ui;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.ListCell;
import javafx.scene.layout.FlowPane;
import javafx.util.StringConverter;

/**
 * Barra de filtros comuns das telas de análise: período (atalhos + datas), comparação com o período anterior, categoria e
 * fornecedor. Lembra os últimos valores no terminal ({@code analise.json}). Chama {@code aoAplicar} ao clicar em Atualizar.
 */
public final class FiltrosBar extends FlowPane {
    private final AppContext ctx;
    private final FiltrosAnalise filtros;
    private final ComboBox<String> atalho = new ComboBox<>(FXCollections.observableArrayList(FiltrosAnalise.ATALHOS));
    private final DatePicker de = new DatePicker();
    private final DatePicker ate = new DatePicker();
    private final CheckBox comparar = new CheckBox("Comparar com o período anterior");
    private final ComboBox<CadastroApi.Categoria> categoria = new ComboBox<>();
    private final ComboBox<CadastroApi.Fornecedor> fornecedor = new ComboBox<>();
    private final Runnable aoAplicar;
    private javafx.scene.layout.VBox categoriaBox;
    private javafx.scene.layout.VBox fornecedorBox;
    private boolean ajustando;

    public FiltrosBar(AppContext ctx, FiltrosAnalise filtros, Runnable aoAplicar) {
        this(ctx, filtros, aoAplicar, true);
    }

    /** Com {@code comPeriodo=false} mostra só categoria e fornecedor (telas cujo período vem da configuração, como a reposição). */
    public FiltrosBar(AppContext ctx, FiltrosAnalise filtros, Runnable aoAplicar, boolean comPeriodo) {
        super(8, 8);
        this.ctx = ctx;
        this.filtros = filtros;
        this.aoAplicar = aoAplicar;
        getStyleClass().add("rg-toolbar");

        atalho.setPrefWidth(170);
        de.setPrefWidth(130);
        ate.setPrefWidth(130);
        categoria.setPrefWidth(220);
        fornecedor.setPrefWidth(200);
        categoria.setPromptText("Todas as categorias");
        fornecedor.setPromptText("Todos os fornecedores");
        categoria.setConverter(conversorCategoria());
        fornecedor.setConverter(conversorFornecedor());
        categoria.setCellFactory(lv -> celula(categoria.getConverter()));
        fornecedor.setCellFactory(lv -> celula(fornecedor.getConverter()));

        atalho.valueProperty().addListener((o, a, nome) -> {
            if (ajustando || nome == null) {
                return;
            }
            filtros.aplicarAtalho(nome, LocalDate.now());
            mostrarDatas();
        });
        de.valueProperty().addListener((o, a, d) -> datasEditadas());
        ate.valueProperty().addListener((o, a, d) -> datasEditadas());

        Button atualizar = Ui.primario("Atualizar", this::aplicar);
        if (comPeriodo) {
            getChildren().addAll(Ui.rotulado("Período", atalho), Ui.rotulado("De", de), Ui.rotulado("Até", ate));
        }
        categoriaBox = Ui.rotulado("Categoria", categoria);
        fornecedorBox = Ui.rotulado("Fornecedor", fornecedor);
        getChildren().addAll(categoriaBox, fornecedorBox);
        if (comPeriodo) {
            getChildren().add(Ui.rotulado(" ", comparar));
        }
        getChildren().add(Ui.rotulado(" ", atualizar));
        mostrarDatas();
        comparar.setSelected(filtros.comparar);
    }

    /** Esconde categoria e fornecedor (telas de um produto só). */
    public void somentePeriodo() {
        getChildren().removeAll(categoriaBox, fornecedorBox);
    }

    private static <T> ListCell<T> celula(StringConverter<T> c) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : c.toString(item));
            }
        };
    }

    private static StringConverter<CadastroApi.Categoria> conversorCategoria() {
        return new StringConverter<>() {
            @Override
            public String toString(CadastroApi.Categoria c) {
                return c == null ? "Todas as categorias" : c.caminho();
            }

            @Override
            public CadastroApi.Categoria fromString(String s) {
                return null;
            }
        };
    }

    private static StringConverter<CadastroApi.Fornecedor> conversorFornecedor() {
        return new StringConverter<>() {
            @Override
            public String toString(CadastroApi.Fornecedor f) {
                return f == null ? "Todos os fornecedores" : f.nome();
            }

            @Override
            public CadastroApi.Fornecedor fromString(String s) {
                return null;
            }
        };
    }

    private void mostrarDatas() {
        ajustando = true;
        atalho.setValue(filtros.atalho);
        de.setValue(filtros.de);
        ate.setValue(filtros.ate);
        ajustando = false;
    }

    private void datasEditadas() {
        if (ajustando || de.getValue() == null || ate.getValue() == null) {
            return;
        }
        filtros.atalho = FiltrosAnalise.PERSONALIZADO;
        filtros.de = de.getValue();
        filtros.ate = ate.getValue();
        ajustando = true;
        atalho.setValue(FiltrosAnalise.PERSONALIZADO);
        ajustando = false;
    }

    /** Carrega as categorias e os fornecedores (fora da FX thread) e restaura a seleção lembrada. */
    public void carregarListas() {
        Fx.async(() -> new Object[] {ctx.cadastros.categorias(), ctx.cadastros.fornecedores()}, r -> {
            @SuppressWarnings("unchecked")
            List<CadastroApi.Categoria> cats = (List<CadastroApi.Categoria>) r[0];
            @SuppressWarnings("unchecked")
            List<CadastroApi.Fornecedor> forns = (List<CadastroApi.Fornecedor>) r[1];
            List<CadastroApi.Categoria> c = new ArrayList<>();
            c.add(null);
            c.addAll(cats);
            List<CadastroApi.Fornecedor> f = new ArrayList<>();
            f.add(null);
            f.addAll(forns);
            categoria.setItems(FXCollections.observableArrayList(c));
            fornecedor.setItems(FXCollections.observableArrayList(f));
            categoria.setValue(cats.stream().filter(x -> x.id().equals(filtros.categoriaId)).findFirst().orElse(null));
            fornecedor.setValue(forns.stream().filter(x -> x.id().equals(filtros.fornecedorId)).findFirst().orElse(null));
        }, t -> {
            // sem as listas o filtro por categoria/fornecedor some, mas o período continua funcionando
        });
    }

    /** Copia os controles para o modelo, grava a memória e avisa a tela. */
    public void aplicar() {
        if (de.getValue() != null && ate.getValue() != null && de.getValue().isAfter(ate.getValue())) {
            br.com.quack.estoque.ui.Toasts.error(Ui.janela(this), "A data inicial não pode ser depois da final.");
            return;
        }
        filtros.categoriaId = categoria.getValue() == null ? null : categoria.getValue().id();
        filtros.fornecedorId = fornecedor.getValue() == null ? null : fornecedor.getValue().id();
        filtros.comparar = comparar.isSelected();
        filtros.salvar(ctx.paths.analiseFile());
        aoAplicar.run();
    }
}
