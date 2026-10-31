package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.service.PrintService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Sugestão de reposição (giro + mínimo/máximo) que vira um pedido de compra por fornecedor. */
public class ComprasView extends Tela {
    /** Linha editável da sugestão. */
    public static final class LinhaCompra {
        final SimpleBooleanProperty incluir = new SimpleBooleanProperty(true);
        final SimpleStringProperty fornecedor = new SimpleStringProperty();
        final SimpleStringProperty quantidade = new SimpleStringProperty();
        final SimpleStringProperty custo = new SimpleStringProperty();
        final JsonNode origem;
        String fornecedorId;

        public LinhaCompra(JsonNode o, boolean valores) {
            this.origem = o;
            this.fornecedorId = o.path("fornecedorId").isNull() ? null : o.path("fornecedorId").asText();
            fornecedor.set(o.path("fornecedor").asText(CadastrosNome.SEM_FORNECEDOR));
            quantidade.set(Fmt.qty(o.path("sugerido").decimalValue()));
            custo.set(valores && o.hasNonNull("custoUnitario") ? Fmt.dec2(o.path("custoUnitario").decimalValue()) : "");
        }

        public void definirQuantidade(String texto) {
            quantidade.set(texto);
        }

        public BigDecimal qtd() {
            return Fmt.parse(quantidade.get());
        }

        public BigDecimal custoUnit() {
            return Fmt.parseOrZero(custo.get());
        }

        BigDecimal total() {
            try {
                return qtd().multiply(custoUnit()).setScale(2, RoundingMode.HALF_UP);
            } catch (DomainException e) {
                return BigDecimal.ZERO;
            }
        }

        public String codigo() {
            return origem.path("codigo").asText("");
        }

        public String nome() {
            return origem.path("nome").asText("");
        }
    }

    /** Nome do grupo sem fornecedor (o mesmo texto da API). */
    static final class CadastrosNome {
        static final String SEM_FORNECEDOR = "Sem fornecedor";
    }

    private final Perfil perfil;
    private final TextField dias = Ui.campo("30");
    private final TextField cobertura = Ui.campo("15");
    private final ComboBox<CadastroApi.Categoria> categoria = Ui.combo(new ArrayList<>());
    private final TableView<LinhaCompra> tabela = Ui.tabela();
    private final Label resumo = Ui.muted("");
    private List<CadastroApi.Fornecedor> fornecedores = new ArrayList<>();
    private final Button gerar;

    public ComprasView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        gerar = Ui.primario("Gerar pedidos por fornecedor", this::gerar);
        gerar.setVisible(perfil.gerarPedidoCompra());
        gerar.setManaged(perfil.gerarPedidoCompra());
        getChildren().add(Ui.cabecalho("Compras: sugestão de reposição",
                "Calcula quanto repor pelo giro de vendas e pelo estoque mínimo/máximo. "
                        + (perfil.gerarPedidoCompra() ? "Revise e gere um pedido por fornecedor."
                        : "Como estoquista você vê os produtos e as quantidades sugeridas."), gerar));

        dias.setText("30");
        cobertura.setText("15");
        dias.setPrefWidth(70);
        cobertura.setPrefWidth(70);
        categoria.setPromptText("Todas as categorias");
        categoria.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(CadastroApi.Categoria c) {
                return c == null ? "Todas as categorias" : c.caminho();
            }

            @Override
            public CadastroApi.Categoria fromString(String s) {
                return null;
            }
        });
        getChildren().add(Ui.barra(Ui.rotulado("Vendas dos últimos (dias)", dias), Ui.rotulado("Cobertura desejada (dias)", cobertura),
                Ui.rotulado("Categoria", categoria), Ui.botao("Calcular sugestão", this::calcular)));

        tabela.setEditable(perfil.gerarPedidoCompra());
        if (perfil.gerarPedidoCompra()) {
            TableColumn<LinhaCompra, Boolean> inc = new TableColumn<>("Pedir");
            inc.setCellValueFactory(cd -> cd.getValue().incluir);
            inc.setCellFactory(CheckBoxTableCell.forTableColumn(inc));
            inc.setEditable(true);
            inc.setPrefWidth(52);
            tabela.getColumns().add(inc);

            TableColumn<LinhaCompra, String> forn = new TableColumn<>("Fornecedor");
            forn.setCellValueFactory(cd -> cd.getValue().fornecedor);
            forn.setCellFactory(c -> new ComboBoxTableCell<>(FXCollections.observableArrayList(nomesFornecedores())));
            forn.setOnEditCommit(e -> {
                LinhaCompra l = e.getRowValue();
                l.fornecedor.set(e.getNewValue());
                l.fornecedorId = fornecedores.stream().filter(f -> f.nome().equals(e.getNewValue())).map(CadastroApi.Fornecedor::id)
                        .findFirst().orElse(null);
                atualizarResumo();
            });
            forn.setEditable(true);
            forn.setPrefWidth(170);
            tabela.getColumns().add(forn);
        } else {
            Ui.col(tabela, "Fornecedor", l -> l.fornecedor.get(), 160);
        }
        Ui.col(tabela, "Código", LinhaCompra::codigo, 80);
        Ui.col(tabela, "Produto", LinhaCompra::nome, 240);
        Ui.colNum(tabela, "Estoque", l -> Fmt.qty(l.origem.path("saldo").decimalValue()), 64);
        Ui.colNum(tabela, "Mín.", l -> Fmt.qty(l.origem.path("minimo").decimalValue()), 50);
        Ui.colNum(tabela, "Máx.", l -> Fmt.qty(l.origem.path("maximo").decimalValue()), 50);
        Ui.colNum(tabela, "Vendido", l -> Fmt.qty(l.origem.path("vendaPeriodo").decimalValue()), 64);
        Ui.colNum(tabela, "Cobertura (dias)", l -> l.origem.path("coberturaDias").isNull() ? "—" : Fmt.qty(l.origem.path("coberturaDias").decimalValue()), 100);
        Ui.col(tabela, "Motivo", l -> l.origem.path("motivo").asText(""), 170);

        TableColumn<LinhaCompra, String> qtd = new TableColumn<>("Qtd. a comprar");
        qtd.setCellValueFactory(cd -> cd.getValue().quantidade);
        qtd.setPrefWidth(110);
        qtd.setStyle("-fx-alignment: CENTER-RIGHT;");
        if (perfil.gerarPedidoCompra()) {
            qtd.setCellFactory(TextFieldTableCell.forTableColumn());
            qtd.setOnEditCommit(e -> {
                e.getRowValue().quantidade.set(e.getNewValue());
                atualizarResumo();
                tabela.refresh();
            });
            qtd.setEditable(true);
        }
        tabela.getColumns().add(qtd);
        if (perfil.verCustos()) {
            TableColumn<LinhaCompra, String> cst = new TableColumn<>("Custo unit. (R$)");
            cst.setCellValueFactory(cd -> cd.getValue().custo);
            cst.setPrefWidth(100);
            cst.setStyle("-fx-alignment: CENTER-RIGHT;");
            cst.setCellFactory(TextFieldTableCell.forTableColumn());
            cst.setOnEditCommit(e -> {
                e.getRowValue().custo.set(e.getNewValue());
                atualizarResumo();
                tabela.refresh();
            });
            cst.setEditable(true);
            tabela.getColumns().add(cst);
            Ui.colNum(tabela, "Total (R$)", l -> Fmt.dec2(l.total()), 90);
        }
        VBox.setVgrow(tabela, Priority.ALWAYS);
        Button excluir = Ui.botao("Excluir item selecionado", () -> {
            LinhaCompra l = tabela.getSelectionModel().getSelectedItem();
            if (l != null) {
                tabela.getItems().remove(l);
                atualizarResumo();
            }
        });
        excluir.setVisible(perfil.gerarPedidoCompra());
        excluir.setManaged(perfil.gerarPedidoCompra());
        getChildren().addAll(tabela, Ui.barra(excluir, resumo));
    }

    private List<String> nomesFornecedores() {
        List<String> n = new ArrayList<>();
        n.add(CadastrosNome.SEM_FORNECEDOR);
        fornecedores.forEach(f -> n.add(f.nome()));
        return n;
    }

    @Override
    public void ativar() {
        rodar(() -> new Object[] {ctx.cadastros.categorias(), perfil.gerarPedidoCompra() ? ctx.cadastros.fornecedores() : List.of()}, r -> {
            @SuppressWarnings("unchecked")
            List<CadastroApi.Categoria> cats = (List<CadastroApi.Categoria>) r[0];
            @SuppressWarnings("unchecked")
            List<CadastroApi.Fornecedor> fors = (List<CadastroApi.Fornecedor>) r[1];
            fornecedores = fors;
            categoria.setItems(FXCollections.observableArrayList(CadastrosView.ordenar(cats.stream().filter(CadastroApi.Categoria::ativa).toList())));
            if (tabela.getItems().isEmpty()) {
                calcular();
            }
        });
    }

    private void calcular() {
        int d;
        int c;
        try {
            d = Ui.numero(dias, "o período").intValue();
            c = Ui.numero(cobertura, "a cobertura").intValue();
        } catch (DomainException e) {
            Toasts.error(janela(), e.getMessage());
            return;
        }
        String cat = categoria.getValue() == null ? null : categoria.getValue().id();
        rodar(() -> ctx.relatorios.reposicao(d, c, cat), r -> {
            List<LinhaCompra> linhas = new ArrayList<>();
            for (JsonNode i : r.path("itens")) {
                LinhaCompra nova = new LinhaCompra(i, perfil.verCustos());
                nova.incluir.addListener((o, a, b) -> atualizarResumo());
                linhas.add(nova);
            }
            tabela.setItems(FXCollections.observableArrayList(linhas));
            atualizarResumo();
        });
    }

    private void atualizarResumo() {
        Map<String, BigDecimal> porFornecedor = new LinkedHashMap<>();
        for (LinhaCompra l : tabela.getItems()) {
            if (l.incluir.get()) {
                porFornecedor.merge(l.fornecedor.get(), l.total(), BigDecimal::add);
            }
        }
        StringBuilder sb = new StringBuilder(tabela.getItems().size() + " produtos sugeridos");
        if (perfil.verCustos() && !porFornecedor.isEmpty()) {
            sb.append(" · ");
            porFornecedor.forEach((f, v) -> sb.append(f).append(": ").append(Fmt.money(v)).append("   "));
        }
        resumo.setText(sb.toString());
    }

    /** Agrupa as linhas marcadas por fornecedor; recusa itens sem fornecedor ou com quantidade/custo inválidos. */
    public static Map<String, List<LinhaCompra>> agrupar(List<LinhaCompra> linhas) {
        Map<String, List<LinhaCompra>> grupos = new LinkedHashMap<>();
        int semFornecedor = 0;
        for (LinhaCompra l : linhas) {
            if (!l.incluir.get()) {
                continue;
            }
            BigDecimal q;
            try {
                q = l.qtd();
            } catch (DomainException e) {
                throw new DomainException("Quantidade inválida em " + l.nome() + ".");
            }
            if (q.signum() <= 0) {
                throw new DomainException("A quantidade de " + l.nome() + " deve ser maior que zero (ou desmarque o item).");
            }
            if (l.fornecedorId == null || l.fornecedorId.isBlank()) {
                semFornecedor++;
                continue;
            }
            grupos.computeIfAbsent(l.fornecedorId, k -> new ArrayList<>()).add(l);
        }
        if (semFornecedor > 0) {
            throw new DomainException(semFornecedor + " item(ns) estão sem fornecedor. Escolha o fornecedor na coluna \"Fornecedor\" "
                    + "(duplo clique) ou desmarque os itens.");
        }
        if (grupos.isEmpty()) {
            throw new DomainException("Nenhum item marcado para pedido.");
        }
        return grupos;
    }

    private void gerar() {
        Map<String, List<LinhaCompra>> grupos;
        try {
            grupos = agrupar(tabela.getItems());
        } catch (DomainException e) {
            Toasts.error(janela(), e.getMessage());
            return;
        }
        Map<String, List<LinhaCompra>> alvo = grupos;
        Modal.confirmar(janela(), "Gerar pedidos de compra", "Serão criados " + alvo.size() + " pedido(s) de compra em aberto, um por fornecedor.",
                "Gerar pedidos", () -> rodar(() -> {
                    List<Object[]> criados = new ArrayList<>();
                    for (var e : alvo.entrySet()) {
                        List<OrdemCompraApi.Item> itens = new ArrayList<>();
                        for (LinhaCompra l : e.getValue()) {
                            itens.add(new OrdemCompraApi.Item(l.codigo(), l.qtd(), l.custoUnit()));
                        }
                        String numero = ctx.compras.criar(e.getKey(), "Gerado pelo Quack Estoque (sugestão de reposição)", itens);
                        criados.add(new Object[] {numero, e.getValue()});
                    }
                    return criados;
                }, criados -> {
                    sucesso(criados.size() + " pedido(s) de compra criados.");
                    mostrarPedidos(criados);
                    tabela.getItems().removeIf(l -> l.incluir.get());
                    atualizarResumo();
                }));
    }

    @SuppressWarnings("unchecked")
    private void mostrarPedidos(List<Object[]> criados) {
        VBox corpo = new VBox(8);
        for (Object[] c : criados) {
            String numero = (String) c[0];
            List<LinhaCompra> itens = (List<LinhaCompra>) c[1];
            String forn = itens.get(0).fornecedor.get();
            BigDecimal total = itens.stream().map(LinhaCompra::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            Button imprimir = Ui.pequeno("Imprimir", () -> imprimirPedido(numero, forn, itens));
            corpo.getChildren().add(Ui.barra(Ui.texto("Pedido " + numero + " · " + forn + " · " + itens.size() + " itens"
                    + (perfil.verCustos() ? " · " + Fmt.money(total) : ""), "rg-section-title"), Ui.espaco(), imprimir));
        }
        Modal.informativo(janela(), "Pedidos criados", "Os pedidos ficam em aberto para receber depois.", corpo, 560, 0).mostrar();
    }

    private void imprimirPedido(String numero, String fornecedor, List<LinhaCompra> itens) {
        List<List<String>> linhas = new ArrayList<>();
        for (LinhaCompra l : itens) {
            linhas.add(List.of(l.codigo(), l.nome(), Fmt.qty(l.qtd()), Fmt.dec2(l.custoUnit()), Fmt.dec2(l.total())));
        }
        BigDecimal total = itens.stream().map(LinhaCompra::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        var doc = Documento.tabela("Pedido de compra " + numero,
                List.of("Fornecedor: " + fornecedor, "Emitido em " + Fmt.date(LocalDate.now()) + " por " + ctx.session.user().name()),
                List.of(Documento.Col.texto("Código", 12), Documento.Col.texto("Produto", 44), Documento.Col.numero("Quantidade", 14),
                        Documento.Col.numero("Custo unit.", 14), Documento.Col.numero("Total", 14)),
                linhas, List.of("Total do pedido: " + Fmt.money(total)));
        PrintService.imprimirDocumento(doc, "", erro -> {
            if (erro != null) {
                Toasts.error(janela(), erro);
            } else {
                sucesso("Pedido " + numero + " enviado para a impressora.");
            }
        });
    }
}
