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
 * Curva ABC do período: curva acumulada (% dos produtos × % do valor, faixas A/B/C sombreadas), matriz ABC x FSN
 * clicável (filtra a tabela) e a tabela de produtos. A classificação usa a base (faturamento ou CMV) da configuração.
 */
public class CurvaAbcView extends Tela {
    /** Faz o cartão da curva crescer no HBox. */
    private static final class HBoxGrow {
        static void cresce(javafx.scene.Node n) {
            javafx.scene.layout.HBox.setHgrow(n, Priority.ALWAYS);
        }
    }

    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final CurvaAbcChart curva = new CurvaAbcChart();
    private final Label frase = Ui.texto("", "rg-curva-abc-frase");
    private final FlowPane selos = new FlowPane(8, 6);
    private final Label base = Ui.muted("");
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

        curva.setPrefHeight(240);
        curva.setMinHeight(200);
        frase.setWrapText(true);
        VBox cartaoCurva = new VBox(6, Ui.texto("Curva ABC", "rg-section-title"), frase, curva, selos, base);
        cartaoCurva.getStyleClass().add("rg-card");
        cartaoCurva.setMinWidth(360);

        matriz.setHgap(6);
        matriz.setVgap(6);
        javafx.scene.layout.ColumnConstraints rotulos = new javafx.scene.layout.ColumnConstraints(64);
        rotulos.setMinWidth(64);
        matriz.getColumnConstraints().add(rotulos);
        for (int c = 0; c < 3; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            cc.setMinWidth(96);
            matriz.getColumnConstraints().add(cc);
        }
        VBox cartaoMatriz = new VBox(6, Ui.texto("Matriz ABC × FSN", "rg-section-title"),
                Ui.muted("Valor de venda (A, B, C) × frequência de saída (rápido, lento, parado). Clique numa célula para "
                        + "filtrar a tabela; clique de novo para limpar."), matriz, filtroAtivo);
        cartaoMatriz.getStyleClass().add("rg-card");
        cartaoMatriz.setMinWidth(360);
        cartaoMatriz.setPrefWidth(440);
        cartaoMatriz.setMaxWidth(520);

        // Lado a lado em janela larga (a curva cresce); um embaixo do outro quando estreita.
        javafx.scene.layout.HBox ladoALado = new javafx.scene.layout.HBox(12);
        VBox empilhado = new VBox(12);
        javafx.scene.layout.StackPane topo = new javafx.scene.layout.StackPane();
        HBoxGrow.cresce(cartaoCurva);
        Runnable organizar = () -> {
            boolean largo = getWidth() >= 1000;
            ladoALado.getChildren().clear();
            empilhado.getChildren().clear();
            if (largo) {
                cartaoMatriz.setMaxWidth(520);
                ladoALado.getChildren().addAll(cartaoCurva, cartaoMatriz);
                topo.getChildren().setAll(ladoALado);
            } else {
                cartaoMatriz.setMaxWidth(Double.MAX_VALUE);
                empilhado.getChildren().addAll(cartaoCurva, cartaoMatriz);
                topo.getChildren().setAll(empilhado);
            }
        };
        widthProperty().addListener((o, a, b) -> {
            boolean largo = b.doubleValue() >= 1000;
            boolean eraLargo = a.doubleValue() >= 1000;
            if (largo != eraLargo || topo.getChildren().isEmpty()) {
                organizar.run();
            }
        });
        organizar.run();

        colunas();
        tabela.setMinHeight(160);
        tabela.setPrefHeight(300);
        TabelasAnalise.duploCliqueAbreProduto(tabela, j -> j.path("produtoId").asText(), nav);
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

        // Curva: só da consulta sem filtro da matriz (a matriz filtra a tabela, não a curva).
        if (classe == null || curva.getProperties().get("vazia") == null) {
            List<CurvaAbcChart.Ponto> pontos = new ArrayList<>();
            int totalProdutos = Math.max(1, abc.path("totalProdutos").asInt(1));
            for (JsonNode p : abc.path("curva")) {
                int pos = p.path("pos").asInt();
                pontos.add(new CurvaAbcChart.Ponto((double) pos / totalProdutos, p.path("acumulada").asDouble(),
                        p.path("classe").asText(), "#" + pos + " " + p.path("nome").asText() + " (" + p.path("codigo").asText() + ")\n"
                                + Fmt.money(p.path("valor").decimalValue()) + " · classe " + p.path("classe").asText()
                                + " · acumulado " + AnaliseFmt.valor("%", p.path("acumulada"))));
            }
            curva.setDados(pontos);
            curva.getProperties().put("vazia", pontos.isEmpty() ? null : Boolean.FALSE);

            selos.getChildren().clear();
            int total = 0;
            for (JsonNode c : abc.path("resumo")) {
                total += c.path("produtos").asInt();
            }
            String fraseTxt = "";
            for (JsonNode c : abc.path("resumo")) {
                String letra = c.path("classe").asText();
                int prods = c.path("produtos").asInt();
                double pctProd = total == 0 ? 0 : 100.0 * prods / total;
                Label selo = Ui.texto("Classe " + letra + " · " + Fmt.qty(java.math.BigDecimal.valueOf(prods)) + " produtos ("
                        + CurvaAbcChart.AnaliseFmtCurva.pct(pctProd) + ") · " + AnaliseFmt.valor("%", c.path("participacao"))
                        + " do valor · " + Fmt.money(c.path("valor").decimalValue()), "rg-selo-abc", "rg-selo-" + letra.toLowerCase());
                selos.getChildren().add(selo);
                if ("A".equals(letra)) {
                    fraseTxt = CurvaAbcChart.AnaliseFmtCurva.pct(pctProd) + " dos produtos (classe A) fazem "
                            + AnaliseFmt.valor("%", c.path("participacao")) + " do "
                            + ("cmv".equals(abc.path("baseAbc").asText()) ? "custo das vendas" : "faturamento") + " do período.";
                }
            }
            frase.setText(fraseTxt);
            base.setText("Base: " + ("cmv".equals(abc.path("baseAbc").asText()) ? "CMV" : "faturamento") + " · total "
                    + Fmt.money(abc.path("totalValor").decimalValue()) + " · " + Fmt.qty(java.math.BigDecimal.valueOf(total))
                    + " produtos vendidos no período. Passe o mouse na curva para ver cada produto.");
        }

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
        int maior = 1;
        for (JsonNode n : dados) {
            maior = Math.max(maior, n.path("produtos").asInt());
        }
        for (int c = 0; c < 3; c++) {
            Label cab = Ui.texto(c == 0 ? "Rápido (F)" : c == 1 ? "Lento (S)" : "Parado (N)", "rg-matriz-cabecalho");
            cab.setMaxWidth(Double.MAX_VALUE);
            cab.setAlignment(javafx.geometry.Pos.CENTER);
            matriz.add(cab, c + 1, 0);
        }
        for (int l = 0; l < 3; l++) {
            matriz.add(Ui.texto("Classe " + abcs[l], "rg-matriz-cabecalho", "rg-selo-" + abcs[l].toLowerCase()), 0, l + 1);
            for (int c = 0; c < 3; c++) {
                JsonNode cel = null;
                for (JsonNode n : dados) {
                    if (abcs[l].equals(n.path("abc").asText()) && fsns[c].equals(n.path("fsn").asText())) {
                        cel = n;
                    }
                }
                int produtos = cel == null ? 0 : cel.path("produtos").asInt();
                boolean temValor = cel != null && !cel.path("valorEstoque").isNull();
                String valor = temValor ? "\n" + Graficos.eixo(cel.path("valorEstoque").asDouble(), "R$") : "";
                Button b = new Button(Fmt.qty(java.math.BigDecimal.valueOf(produtos)) + (produtos == 1 ? " item" : " itens") + valor);
                b.setTooltip(new javafx.scene.control.Tooltip("Classe " + abcs[l] + " · " + (c == 0 ? "saída rápida" : c == 1
                        ? "saída lenta" : "parado") + "\n" + produtos + " produtos"
                        + (temValor ? "\nValor em estoque: " + Fmt.money(cel.path("valorEstoque").decimalValue()) : "")));
                b.getStyleClass().add("rg-matriz-celula");
                // cor mais forte onde há mais produtos (4 níveis)
                int nivel = produtos == 0 ? 0 : (int) Math.ceil(3.0 * produtos / maior);
                b.getStyleClass().add("rg-matriz-n" + nivel);
                b.setMinSize(96, 58);
                b.setMaxWidth(Double.MAX_VALUE);
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
