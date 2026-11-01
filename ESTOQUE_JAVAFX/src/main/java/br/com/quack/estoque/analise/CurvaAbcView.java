package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Csv;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Toasts;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Curva ABC do período: Pareto (barras + acumulado com marcas em 80% e 95%, top 100 + "Outros"), matriz ABC x FSN
 * clicável (filtra a tabela) e a tabela de produtos. A classificação usa a base (faturamento ou CMV) da configuração.
 */
public class CurvaAbcView extends Tela {
    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final ParetoChart pareto = new ParetoChart();
    private final Label resumo = Ui.muted("");
    private final Label estadoVazio = Ui.muted("");
    private final TableView<JsonNode> tabela = Ui.tabela();
    private final GridPane matriz = new GridPane();
    private final Map<String, Button> celulas = new LinkedHashMap<>();
    private final Label filtroAtivo = Ui.muted("");
    private List<JsonNode> exibidos = new ArrayList<>();
    private String classe;
    private String fsn;
    private boolean primeiraVez = true;
    private int geracao;

    public CurvaAbcView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        this.barra = new FiltrosBar(ctx, filtros, this::carregar);

        pareto.setPrefHeight(230);
        pareto.setMinHeight(200);
        VBox cartaoPareto = new VBox(6, Ui.texto("Pareto do período", "rg-section-title"),
                Ui.muted("Barras: valor de cada produto. Linha: acumulado. Tracejado: limites das classes A e B."), pareto, resumo);
        cartaoPareto.getStyleClass().add("rg-card");
        cartaoPareto.setMinWidth(380);

        matriz.setHgap(6);
        matriz.setVgap(6);
        javafx.scene.layout.ColumnConstraints rotulos = new javafx.scene.layout.ColumnConstraints(72);
        rotulos.setMinWidth(72);
        matriz.getColumnConstraints().add(rotulos);
        VBox cartaoMatriz = new VBox(6, Ui.texto("Matriz ABC × FSN", "rg-section-title"),
                Ui.muted("Clique numa célula para filtrar a tabela; clique de novo para limpar."), matriz, filtroAtivo);
        cartaoMatriz.getStyleClass().add("rg-card");
        cartaoMatriz.setMinWidth(330);
        cartaoMatriz.setPrefWidth(360);

        FlowPane topo = new FlowPane(12, 12, cartaoPareto, cartaoMatriz);
        topo.prefWrapLengthProperty().bind(widthProperty().subtract(40));
        cartaoPareto.setPrefWidth(620);

        colunas();
        tabela.setMinHeight(220);
        tabela.setPrefHeight(300);
        VBox.setVgrow(tabela, Priority.ALWAYS);

        getChildren().addAll(
                Ui.cabecalho("Análise · Curva ABC", "Quem sustenta o faturamento (A, B, C) e com que frequência sai (F, S, N).",
                        Ui.botao("Exportar CSV", this::exportar)),
                barra, topo, estadoVazio, tabela);
    }

    private void colunas() {
        Ui.col(tabela, "Classe", j -> j.path("classe").asText(), 60);
        Ui.col(tabela, "FSN", j -> j.path("fsn").asText("—"), 50);
        Ui.col(tabela, "Código", j -> j.path("codigo").asText(), 90);
        Ui.col(tabela, "Produto", j -> j.path("nome").asText(), 260);
        Ui.colNum(tabela, "Qtd. vendida", j -> Fmt.qty(j.path("quantidade").decimalValue()), 90);
        Ui.colNum(tabela, "Valor (R$)", j -> Fmt.dec2(j.path("valor").decimalValue()), 110);
        Ui.colNum(tabela, "Part. %", j -> Fmt.dec2(j.path("participacao").decimalValue()), 70);
        Ui.colNum(tabela, "Acum. %", j -> Fmt.dec2(j.path("acumulada").decimalValue()), 70);
        Ui.colNum(tabela, "Estoque", j -> Fmt.qty(j.path("saldo").decimalValue()), 80);
        Ui.colNum(tabela, "Valor em estoque (R$)", j -> Fmt.dec2(j.path("valorEstoque").decimalValue()), 120);
        Ui.distribuir(tabela);
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
        estadoVazio.setText("Carregando…");
        Fx.async(() -> new JsonNode[] {ctx.analise.abc(f, classe, fsn), ctx.analise.bloco("abc-fsn", f)}, r -> {
            if (minha != geracao) {
                return;
            }
            mostrar(r[0].path("dados"), r[1].path("dados"), f);
        }, t -> {
            if (minha == geracao) {
                estadoVazio.setText("Não foi possível carregar: " + Fx.message(t));
            }
        });
    }

    private void mostrar(JsonNode abc, JsonNode abcFsn, FiltrosAnalise f) {
        boolean vazio = abc.path("vazio").asBoolean(true);
        estadoVazio.setText(vazio ? "Sem vendas no período " + f.resumoPeriodo() + ": não há curva para mostrar." : "");
        estadoVazio.setManaged(vazio);
        estadoVazio.setVisible(vazio);

        List<ParetoChart.Ponto> pontos = new ArrayList<>();
        for (JsonNode p : abc.path("pareto")) {
            String nome = p.path("nome").asText();
            pontos.add(new ParetoChart.Ponto(p.path("valor").asDouble(), p.path("acumulada").asDouble(),
                    "#" + p.path("posicao").asInt() + " " + nome + "\n" + Fmt.money(p.path("valor").decimalValue())
                            + " · acumulado " + AnaliseFmt.valor("%", p.path("acumulada"))));
        }
        pareto.setDados(pontos, abc.path("limiteA").asDouble(80), abc.path("limiteB").asDouble(95));

        StringBuilder sb = new StringBuilder("Base: ").append("cmv".equals(abc.path("baseAbc").asText()) ? "CMV" : "faturamento")
                .append(" · total ").append(Fmt.money(abc.path("totalValor").decimalValue()));
        for (JsonNode c : abc.path("resumo")) {
            sb.append(" · ").append(c.path("classe").asText()).append(": ").append(c.path("produtos").asInt()).append(" produtos (")
                    .append(AnaliseFmt.valor("%", c.path("participacao"))).append(")");
        }
        resumo.setText(sb.toString());

        exibidos = new ArrayList<>();
        abc.path("itens").forEach(exibidos::add);
        tabela.setItems(FXCollections.observableArrayList(exibidos));

        montarMatriz(abcFsn.path("celulas"));
    }

    private void montarMatriz(JsonNode dados) {
        matriz.getChildren().clear();
        celulas.clear();
        String[] fsns = {"F", "S", "N"};
        String[] abcs = {"A", "B", "C"};
        for (int c = 0; c < 3; c++) {
            matriz.add(Ui.texto(fsns[c] + (c == 0 ? " · rápido" : c == 1 ? " · lento" : " · parado"), "rg-muted"), c + 1, 0);
        }
        for (int l = 0; l < 3; l++) {
            matriz.add(Ui.texto("Classe " + abcs[l], "rg-muted"), 0, l + 1);
            for (int c = 0; c < 3; c++) {
                JsonNode cel = null;
                for (JsonNode n : dados) {
                    if (abcs[l].equals(n.path("abc").asText()) && fsns[c].equals(n.path("fsn").asText())) {
                        cel = n;
                    }
                }
                int produtos = cel == null ? 0 : cel.path("produtos").asInt();
                String valor = cel == null || cel.path("valorEstoque").isNull() ? "" : "\n" + Fmt.money(cel.path("valorEstoque").decimalValue());
                Button b = new Button(produtos + (produtos == 1 ? " item" : " itens") + valor);
                b.setTooltip(new javafx.scene.control.Tooltip("Classe " + abcs[l] + " · FSN " + fsns[c] + ": " + produtos + " produtos"));
                b.getStyleClass().add("rg-matriz-celula");
                b.setMinSize(100, 54);
                b.setPrefWidth(120);
                String a = abcs[l];
                String s = fsns[c];
                b.setOnAction(e -> alternar(a, s));
                if (a.equals(classe) && s.equals(fsn)) {
                    b.getStyleClass().add("rg-matriz-ativa");
                }
                celulas.put(a + s, b);
                matriz.add(b, c + 1, l + 1);
            }
        }
        filtroAtivo.setText(classe == null ? "Mostrando todos os produtos da curva." : "Filtro: classe " + classe + " e FSN " + fsn + ".");
    }

    private void alternar(String a, String s) {
        if (a.equals(classe) && s.equals(fsn)) {
            classe = null;
            fsn = null;
        } else {
            classe = a;
            fsn = s;
        }
        carregar();
    }

    /** Usado nos testes: simula o clique na célula da matriz. */
    public void clicarCelula(String abc, String fsnLetra) {
        alternar(abc, fsnLetra);
    }

    /** Usado nos testes: códigos dos produtos mostrados na tabela. */
    public List<String> codigosNaTabela() {
        return tabela.getItems().stream().map(j -> j.path("codigo").asText()).toList();
    }

    public List<String> classesFsnNaTabela() {
        return tabela.getItems().stream().map(j -> j.path("classe").asText() + j.path("fsn").asText()).distinct().toList();
    }

    private void exportar() {
        if (exibidos.isEmpty()) {
            Toasts.error(janela(), "Não há curva para exportar.");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setInitialFileName("curva-abc.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File arq = fc.showSaveDialog(janela());
        if (arq == null) {
            return;
        }
        List<List<String>> linhas = new ArrayList<>();
        for (JsonNode j : exibidos) {
            linhas.add(List.of(j.path("classe").asText(), j.path("fsn").asText(""), j.path("codigo").asText(), j.path("nome").asText(),
                    Fmt.qty(j.path("quantidade").decimalValue()), Fmt.dec2(j.path("valor").decimalValue()),
                    Fmt.dec2(j.path("participacao").decimalValue()), Fmt.dec2(j.path("acumulada").decimalValue()),
                    Fmt.qty(j.path("saldo").decimalValue()), Fmt.dec2(j.path("valorEstoque").decimalValue())));
        }
        try {
            Csv.save(arq.toPath(), List.of("Classe", "FSN", "Código", "Produto", "Qtd. vendida", "Valor (R$)", "Part. %", "Acum. %",
                    "Estoque", "Valor em estoque (R$)"), linhas);
            Toasts.success(janela(), "CSV gravado em " + arq.getName() + ".");
        } catch (IOException e) {
            Toasts.error(janela(), "Não foi possível gravar: " + e.getMessage());
        }
    }
}
