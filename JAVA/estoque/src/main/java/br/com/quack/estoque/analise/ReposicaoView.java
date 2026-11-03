package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Csv;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.XlsxWriter;
import br.com.quack.estoque.ui.Carregando;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Toasts;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;

/**
 * Reposição: produtos abaixo do ponto de pedido ou com sugestão de compra, por urgência (ruptura em destaque). O gerente
 * escolhe as linhas, ajusta as quantidades e gera um rascunho de pedido de compra por fornecedor; CSV e Excel para quem
 * compra por fora. Consumo e desvio vêm da janela de consumo da configuração.
 */
public class ReposicaoView extends Tela {

    /** Linha da tabela: o item da API, se está marcado e a quantidade a pedir (editável, formato brasileiro). */
    static final class Linha {
        final JsonNode j;
        final BooleanProperty marcado = new SimpleBooleanProperty(false);
        final StringProperty qtd;

        Linha(JsonNode j) {
            this.j = j;
            this.qtd = new SimpleStringProperty(Fmt.qty(j.path("sugestao").decimalValue()));
        }

        String urgencia() {
            return j.path("urgencia").asText();
        }
    }

    private final Perfil perfil;
    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final FlowPane cartoes = new FlowPane(12, 12);
    private final TableView<Linha> tabela = Ui.tabela();
    private final Label info = Ui.muted("");
    private boolean primeiraVez = true;
    private int geracao;

    public ReposicaoView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        this.barra = new FiltrosBar(ctx, filtros, this::carregar, false);

        tabela.setEditable(true);
        colunas();
        tabela.setRowFactory(t -> new TableRow<>() {
            {
                setOnMouseClicked(e -> {
                    if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && e.getClickCount() == 2 && !isEmpty() && nav != null) {
                        nav.abrirProduto(getItem().j.path("produtoId").asText());
                    }
                });
            }

            @Override
            protected void updateItem(Linha item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("rg-linha-ruptura", "rg-linha-critico");
                if (!empty && item != null) {
                    if ("ruptura".equals(item.urgencia())) {
                        getStyleClass().add("rg-linha-ruptura");
                    } else if ("critico".equals(item.urgencia())) {
                        getStyleClass().add("rg-linha-critico");
                    }
                }
            }
        });
        javafx.scene.layout.VBox.setVgrow(tabela, Priority.ALWAYS);
        tabela.setMinHeight(200);

        List<javafx.scene.Node> acoes = new ArrayList<>();
        acoes.add(Ui.botao("Exportar CSV", () -> exportar(false)));
        acoes.add(Ui.botao("Exportar Excel", () -> exportar(true)));
        if (perfil.gerarPedidoCompra()) {
            acoes.add(Ui.primario("Gerar pedidos", this::gerarPedidos));
        }
        FlowPane selecao = Ui.barra(Ui.pequeno("Selecionar urgentes", () -> marcar(l -> "ruptura".equals(l.urgencia()) || "critico".equals(l.urgencia()))),
                Ui.pequeno("Selecionar todos", () -> marcar(l -> true)), Ui.pequeno("Limpar seleção", () -> marcar(l -> false)), info);
        getChildren().addAll(
                Ui.cabecalho("Análise · Reposição", "O que comprar agora: ponto de pedido, segurança e lead time calculados no servidor.",
                        acoes.toArray(new javafx.scene.Node[0])),
                barra, cartoes, selecao, tabela);
    }

    private void colunas() {
        TableColumn<Linha, Boolean> check = new TableColumn<>("");
        check.setCellValueFactory(c -> c.getValue().marcado);
        check.setCellFactory(CheckBoxTableCell.forTableColumn(check));
        check.setEditable(true);
        check.setMinWidth(34);
        check.setMaxWidth(40);
        check.setSortable(false);
        tabela.getColumns().add(check);

        Ui.col(tabela, "Urgência", l -> rotuloUrgencia(l.urgencia()), 80);
        Ui.col(tabela, "Código", l -> l.j.path("codigo").asText(), 90);
        Ui.col(tabela, "Produto", l -> l.j.path("nome").asText(), 230);
        Ui.col(tabela, "Fornecedor", l -> l.j.path("fornecedor").asText(), 140);
        Ui.colNum(tabela, "Estoque", l -> Fmt.qty(l.j.path("saldo").decimalValue()), 70);
        Ui.colNum(tabela, "Consumo/dia", l -> Fmt.qty(l.j.path("consumoDia").decimalValue()), 80);
        Ui.colNum(tabela, "Lead (dias)", l -> String.valueOf(l.j.path("leadTimeDias").asInt()), 70);
        Ui.colNum(tabela, "Ponto de pedido", l -> Fmt.qty(l.j.path("pontoPedido").decimalValue()), 90);
        Ui.colNum(tabela, "Em trânsito", l -> Fmt.qty(l.j.path("emTransito").decimalValue()), 80);
        Ui.colNum(tabela, "Sugestão", l -> Fmt.qty(l.j.path("sugestao").decimalValue()), 80);

        TableColumn<Linha, String> qtd = new TableColumn<>("Qtd. a pedir");
        qtd.setCellValueFactory(c -> c.getValue().qtd);
        qtd.setCellFactory(TextFieldTableCell.forTableColumn());
        qtd.setOnEditCommit(e -> {
            try {
                BigDecimal v = Fmt.parse(e.getNewValue());
                e.getRowValue().qtd.set(v.signum() <= 0 ? e.getOldValue() : Fmt.qty(v));
            } catch (RuntimeException ex) {
                e.getRowValue().qtd.set(e.getOldValue());
                Toasts.error(janela(), "Quantidade inválida: " + e.getNewValue());
            }
            tabela.refresh();
        });
        qtd.setEditable(true);
        qtd.setStyle("-fx-alignment: CENTER-RIGHT;");
        qtd.setMinWidth(90);
        qtd.setSortable(false);
        tabela.getColumns().add(qtd);
        if (perfil.verCustos()) {
            Ui.colNum(tabela, "Custo est. (R$)", l -> l.j.path("custoEstimado").isNull() ? "" : Fmt.dec2(l.j.path("custoEstimado").decimalValue()), 100);
        }
        Ui.distribuir(tabela);
    }

    static String rotuloUrgencia(String u) {
        return switch (u) {
            case "ruptura" -> "Ruptura";
            case "critico" -> "Crítico";
            case "repor" -> "Repor";
            default -> "Planejar";
        };
    }

    @Override
    public void ativar() {
        if (primeiraVez) {
            primeiraVez = false;
            barra.carregarListas();
        }
        carregar();
    }

    private void carregar() {
        int minha = ++geracao;
        FiltrosAnalise f = filtros.copia();
        info.setText("Carregando…");
        Carregando.async(() -> ctx.analise.reposicao(f), r -> {
            if (minha != geracao) {
                return;
            }
            mostrar(r.path("dados"), r.path("calculadoEm").asText(null));
        }, t -> {
            if (minha == geracao) {
                info.setText("Não foi possível carregar: " + Fx.message(t));
            }
        });
    }

    private void mostrar(JsonNode dados, String quando) {
        JsonNode r = dados.path("resumo");
        cartoes.getChildren().setAll(
                cartao("Itens a repor", String.valueOf(r.path("itens").asInt()), "rg-kpi"),
                cartao("Em ruptura", String.valueOf(r.path("emRuptura").asInt()), r.path("emRuptura").asInt() > 0 ? "rg-kpi rg-kpi-vermelho" : "rg-kpi rg-kpi-verde"),
                cartao("Críticos", String.valueOf(r.path("criticos").asInt()), r.path("criticos").asInt() > 0 ? "rg-kpi rg-kpi-amarelo" : "rg-kpi"),
                cartao("Fornecedores envolvidos", String.valueOf(r.path("fornecedores").asInt()), "rg-kpi"));
        if (!r.path("custoTotal").isNull()) {
            cartoes.getChildren().add(cartao("Custo estimado", Fmt.money(r.path("custoTotal").decimalValue()), "rg-kpi"));
        }
        List<Linha> linhas = new ArrayList<>();
        dados.path("itens").forEach(j -> linhas.add(new Linha(j)));
        tabela.setItems(FXCollections.observableArrayList(linhas));
        info.setText((linhas.isEmpty() ? "Nenhum produto precisa de reposição com os filtros atuais." : linhas.size() + " produtos")
                + " · consumo dos últimos " + dados.path("janelaDias").asInt() + " dias · " + AnaliseFmt.calculadoEm(quando)
                + (r.path("semFornecedor").asInt() > 0 ? " · " + r.path("semFornecedor").asInt() + " sem fornecedor (vão para um pedido de cotação)" : ""));
    }

    private static javafx.scene.Node cartao(String titulo, String valor, String classes) {
        javafx.scene.layout.VBox c = new javafx.scene.layout.VBox(2, Ui.texto(titulo.toUpperCase(java.util.Locale.ROOT), "kpi-label", "rg-stat-label"), Ui.texto(valor, "kpi-value", "rg-kpi-valor"));
        c.getStyleClass().add("rg-stat");
        for (String s : classes.split(" ")) {
            c.getStyleClass().add(s);
        }
        return c;
    }

    private void marcar(java.util.function.Predicate<Linha> regra) {
        tabela.getItems().forEach(l -> l.marcado.set(regra.test(l)));
    }

    private List<Linha> escolhidas() {
        List<Linha> marcadas = tabela.getItems().stream().filter(l -> l.marcado.get()).toList();
        return marcadas.isEmpty() ? List.copyOf(tabela.getItems()) : marcadas;
    }

    private void gerarPedidos() {
        List<Linha> marcadas = tabela.getItems().stream().filter(l -> l.marcado.get()).toList();
        if (marcadas.isEmpty()) {
            Toasts.error(janela(), "Marque os produtos que entram no pedido (ou use \"Selecionar urgentes\").");
            return;
        }
        Map<String, BigDecimal> itens = new LinkedHashMap<>();
        for (Linha l : marcadas) {
            BigDecimal q = Fmt.parseOrZero(l.qtd.get());
            if (q.signum() <= 0) {
                Toasts.error(janela(), "Informe a quantidade de " + l.j.path("nome").asText() + ".");
                return;
            }
            itens.put(l.j.path("produtoId").asText(), q);
        }
        if (!Fx.confirm(janela(), "Gerar pedidos", "Criar rascunhos de pedido de compra para " + itens.size()
                + " produtos, um por fornecedor (os sem fornecedor ficam num pedido para cotação)? Nada é enviado ao fornecedor: "
                + "os rascunhos ficam em Compras.")) {
            return;
        }
        rodar(() -> ctx.analise.gerarPedidos(itens, "Rascunho gerado pela análise de reposição."), r -> {
            JsonNode pedidos = r.path("pedidos");
            StringBuilder sb = new StringBuilder(pedidos.size() + (pedidos.size() == 1 ? " rascunho criado" : " rascunhos criados") + " em Compras");
            if (r.path("ignorados").size() > 0) {
                sb.append("; ").append(r.path("ignorados").size()).append(" produto(s) ficaram de fora (não encontrados).");
            }
            sucesso(sb.toString());
            carregar();
        });
    }

    private void exportar(boolean excel) {
        if (tabela.getItems().isEmpty()) {
            Toasts.error(janela(), "Não há itens para exportar.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("reposicao" + (excel ? ".xlsx" : ".csv"));
        fc.getExtensionFilters().add(excel ? new FileChooser.ExtensionFilter("Excel", "*.xlsx") : new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File arq = fc.showSaveDialog(janela());
        if (arq == null) {
            return;
        }
        List<String> cab = new ArrayList<>(List.of("Urgência", "Código", "Produto", "Fornecedor", "Unidade", "Estoque", "Mínimo",
                "Consumo/dia", "Lead time (dias)", "Segurança", "Ponto de pedido", "Em trânsito", "Sugestão", "Qtd. a pedir"));
        if (perfil.verCustos()) {
            cab.addAll(List.of("Custo unit. (R$)", "Custo est. (R$)"));
        }
        try {
            if (excel) {
                List<List<Object>> linhas = new ArrayList<>();
                for (Linha l : escolhidas()) {
                    JsonNode j = l.j;
                    List<Object> r = new ArrayList<>(List.of(rotuloUrgencia(l.urgencia()), j.path("codigo").asText(), j.path("nome").asText(),
                            j.path("fornecedor").asText(), j.path("unidade").asText(), j.path("saldo").decimalValue(), j.path("minimo").decimalValue(),
                            j.path("consumoDia").decimalValue(), j.path("leadTimeDias").asInt(), j.path("estoqueSeguranca").decimalValue(),
                            j.path("pontoPedido").decimalValue(), j.path("emTransito").decimalValue(), j.path("sugestao").decimalValue(),
                            Fmt.parseOrZero(l.qtd.get())));
                    if (perfil.verCustos()) {
                        r.add(j.path("custoUnitario").isNull() ? null : j.path("custoUnitario").decimalValue());
                        r.add(j.path("custoEstimado").isNull() ? null : j.path("custoEstimado").decimalValue());
                    }
                    linhas.add(r);
                }
                XlsxWriter.save(arq.toPath(), "Reposição", cab, linhas);
            } else {
                List<List<String>> linhas = new ArrayList<>();
                for (Linha l : escolhidas()) {
                    JsonNode j = l.j;
                    List<String> r = new ArrayList<>(List.of(rotuloUrgencia(l.urgencia()), j.path("codigo").asText(), j.path("nome").asText(),
                            j.path("fornecedor").asText(), j.path("unidade").asText(), Fmt.qty(j.path("saldo").decimalValue()),
                            Fmt.qty(j.path("minimo").decimalValue()), Fmt.qty(j.path("consumoDia").decimalValue()),
                            String.valueOf(j.path("leadTimeDias").asInt()), Fmt.qty(j.path("estoqueSeguranca").decimalValue()),
                            Fmt.qty(j.path("pontoPedido").decimalValue()), Fmt.qty(j.path("emTransito").decimalValue()),
                            Fmt.qty(j.path("sugestao").decimalValue()), l.qtd.get()));
                    if (perfil.verCustos()) {
                        r.add(j.path("custoUnitario").isNull() ? "" : Fmt.dec2(j.path("custoUnitario").decimalValue()));
                        r.add(j.path("custoEstimado").isNull() ? "" : Fmt.dec2(j.path("custoEstimado").decimalValue()));
                    }
                    linhas.add(r);
                }
                Csv.save(arq.toPath(), cab, linhas);
            }
            Toasts.success(janela(), (excel ? "Excel" : "CSV") + " gravado em " + arq.getName() + ".");
        } catch (IOException e) {
            Toasts.error(janela(), "Não foi possível gravar: " + e.getMessage());
        }
    }

    // ---------- apoio dos testes ----------

    /** Quantas linhas a tabela mostra. */
    public int totalDeLinhas() {
        return tabela.getItems().size();
    }

    /** Urgência da primeira linha ("ruptura" quando há ruptura: elas vêm primeiro). */
    public String urgenciaDaPrimeiraLinha() {
        return tabela.getItems().isEmpty() ? "" : tabela.getItems().get(0).urgencia();
    }

    /** Valores dos cartões do topo, na ordem. */
    public List<String> valoresDosCartoes() {
        List<String> v = new ArrayList<>();
        cartoes.getChildren().forEach(n -> {
            if (n instanceof javafx.scene.layout.VBox b && b.getChildren().size() > 1 && b.getChildren().get(1) instanceof Label l) {
                v.add(l.getText());
            }
        });
        return v;
    }
}
