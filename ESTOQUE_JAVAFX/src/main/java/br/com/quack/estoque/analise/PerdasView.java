package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Perdas e parados. Perdas: barras empilhadas por motivo, mês a mês, com alternância quantidade/R$ (valores só para o gerente), e a
 * tabela por motivo. Parados: todos os produtos sem venda além do limite, do maior valor ao menor; duplo clique abre o produto.
 */
public class PerdasView extends Tela {
    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final FlowPane cartoesPerdas = new FlowPane(12, 12);
    private final BlocoCard graficoPerdas = new BlocoCard("Perdas por mês e motivo", "Quebras, furtos, vencidos, faltas de inventário e ajustes negativos", 760, 340);
    private final TableView<JsonNode> tabelaMotivos = Ui.tabela();
    private final FlowPane cartoesParados = new FlowPane(12, 12);
    private final TableView<JsonNode> tabelaParados = Ui.tabela();
    private final Label infoParados = Ui.muted("");
    private final ToggleButton porQuantidade = new ToggleButton("Quantidade");
    private final ToggleButton porValor = new ToggleButton("Valor (R$)");
    private JsonNode ultimasPerdas;
    private boolean primeiraVez = true;
    private int geracao;

    public PerdasView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        // perdas e parados olham um período maior que o do dashboard
        if ("Últimos 30 dias".equals(filtros.atalho)) {
            filtros.aplicarAtalho("Últimos 12 meses", LocalDate.now());
        }
        this.barra = new FiltrosBar(ctx, filtros, this::carregar);

        ToggleGroup grupo = new ToggleGroup();
        porQuantidade.setToggleGroup(grupo);
        porValor.setToggleGroup(grupo);
        boolean valores = ctx.session.perfil().verCustos();
        porValor.setVisible(valores);
        porValor.setManaged(valores);
        (valores ? porValor : porQuantidade).setSelected(true);
        grupo.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) {
                a.setSelected(true);
            } else if (ultimasPerdas != null) {
                desenharPerdas();
            }
        });

        Ui.col(tabelaMotivos, "Motivo", j -> j.path("motivo").asText(), 220);
        Ui.colNum(tabelaMotivos, "Quantidade", j -> Fmt.qty(j.path("quantidade").decimalValue()), 90);
        if (valores) {
            Ui.colNum(tabelaMotivos, "Valor (R$)", j -> j.path("valor").isNull() ? "" : Fmt.dec2(j.path("valor").decimalValue()), 100);
        }
        Ui.distribuir(tabelaMotivos);
        tabelaMotivos.setPrefHeight(240);
        tabelaMotivos.setMinHeight(160);

        HBox alternar = new HBox(6, Ui.muted("Mostrar por"), porQuantidade, porValor);
        alternar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox cartaoMotivos = new VBox(8, Ui.texto("Por motivo", "rg-section-title"), tabelaMotivos);
        cartaoMotivos.getStyleClass().add("rg-card");
        cartaoMotivos.setPrefWidth(400);
        cartaoMotivos.setMinWidth(320);
        VBox perdas = new VBox(12, cartoesPerdas, alternar, new FlowPane(12, 12, graficoPerdas, cartaoMotivos));
        perdas.setPadding(Ui.margem(10));

        Ui.col(tabelaParados, "Código", j -> j.path("codigo").asText(), 90);
        Ui.col(tabelaParados, "Produto", j -> j.path("nome").asText(), 260);
        Ui.colNum(tabelaParados, "Estoque", j -> Fmt.qty(j.path("saldo").decimalValue()), 80);
        Ui.colNum(tabelaParados, "Dias sem venda", j -> j.path("diasSemSaida").isNull() ? "nunca vendeu" : String.valueOf(j.path("diasSemSaida").asInt()), 110);
        Ui.col(tabelaParados, "Última venda", j -> j.path("ultimaSaida").isNull() ? "—" : Fmt.dateTime(j.path("ultimaSaida").asText()), 120);
        if (valores) {
            Ui.colNum(tabelaParados, "Valor parado (R$)", j -> j.path("valor").isNull() ? "" : Fmt.dec2(j.path("valor").decimalValue()), 120);
        }
        Ui.distribuir(tabelaParados);
        VBox.setVgrow(tabelaParados, Priority.ALWAYS);
        TabelasAnalise.duploCliqueAbreProduto(tabelaParados, j -> j.path("produtoId").asText(), nav);
        VBox parados = new VBox(10, cartoesParados, infoParados, tabelaParados);
        parados.setPadding(Ui.margem(10));

        TabPane abas = new TabPane(new Tab("Perdas", new javafx.scene.control.ScrollPane(perdas) {
            {
                setFitToWidth(true);
                getStyleClass().add("rg-scroll-limpo");
            }
        }), new Tab("Parados", parados));
        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        abas.setMinHeight(0);
        VBox.setVgrow(abas, Priority.ALWAYS);
        getChildren().addAll(Ui.cabecalho("Análise · Perdas e parados", "O que se perde e o que está encalhado no estoque."), barra, abas);
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
        graficoPerdas.carregando();
        Fx.async(() -> ctx.analise.bloco("perdas", f), r -> {
            if (minha == geracao) {
                ultimasPerdas = r.path("dados");
                desenharPerdas();
            }
        }, t -> {
            if (minha == geracao) {
                graficoPerdas.erro(Fx.message(t));
            }
        });
        Fx.async(() -> ctx.analise.bloco("parados", f), r -> {
            if (minha == geracao) {
                mostrarParados(r.path("dados"));
            }
        }, t -> {
            if (minha == geracao) {
                infoParados.setText("Não foi possível carregar: " + Fx.message(t));
            }
        });
    }

    private boolean porValorSelecionado() {
        return porValor.isVisible() && porValor.isSelected();
    }

    private void desenharPerdas() {
        JsonNode d = ultimasPerdas;
        JsonNode t = d.path("totais");
        cartoesPerdas.getChildren().setAll(cartao("Quantidade perdida", Fmt.qty(t.path("quantidade").decimalValue()), "rg-kpi"),
                cartao("Ocorrências", String.valueOf(t.path("ocorrencias").asInt()), "rg-kpi"));
        if (!t.path("valor").isNull()) {
            cartoesPerdas.getChildren().add(cartao("Valor perdido", Fmt.money(t.path("valor").decimalValue()), "rg-kpi"));
            cartoesPerdas.getChildren().add(cartao("% do CMV", t.path("percentualCmv").isNull() ? "—" : AnaliseFmt.valor("%", t.path("percentualCmv")), "rg-kpi"));
        }
        List<JsonNode> motivos = new ArrayList<>();
        d.path("motivos").forEach(motivos::add);
        tabelaMotivos.setItems(FXCollections.observableArrayList(motivos));
        if (d.path("vazio").asBoolean(true)) {
            graficoPerdas.vazio("Nenhuma perda registrada neste período.");
            return;
        }
        boolean valor = porValorSelecionado();
        Set<String> nomesMotivos = new LinkedHashSet<>();
        motivos.forEach(m -> nomesMotivos.add(m.path("motivo").asText()));
        List<String> meses = new ArrayList<>();
        d.path("meses").forEach(m -> meses.add(rotuloMes(m.path("mes").asText())));
        List<String> series = new ArrayList<>(nomesMotivos);
        Map<String, double[]> porMotivo = new LinkedHashMap<>();
        series.forEach(s -> porMotivo.put(s, new double[meses.size()]));
        int i = 0;
        for (JsonNode m : d.path("meses")) {
            for (JsonNode x : m.path("motivos")) {
                double[] arr = porMotivo.get(x.path("motivo").asText());
                if (arr != null) {
                    arr[i] = valor ? x.path("valor").asDouble() : x.path("quantidade").asDouble();
                }
            }
            i++;
        }
        graficoPerdas.mostrar(Graficos.barrasVerticais(meses, series, new ArrayList<>(porMotivo.values()), valor ? "R$" : "un", true), null);
    }

    static String rotuloMes(String aaaaMm) {
        String[] nomes = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};
        int mes = Integer.parseInt(aaaaMm.substring(5, 7));
        return nomes[mes - 1] + "/" + aaaaMm.substring(2, 4);
    }

    private void mostrarParados(JsonNode d) {
        cartoesParados.getChildren().setAll(cartao("Produtos parados", String.valueOf(d.path("total").asInt()), d.path("total").asInt() > 0 ? "rg-kpi rg-kpi-amarelo" : "rg-kpi rg-kpi-verde"));
        if (!d.path("valorTotal").isNull()) {
            cartoesParados.getChildren().add(cartao("Valor parado", Fmt.money(d.path("valorTotal").decimalValue()), "rg-kpi"));
        }
        List<JsonNode> itens = new ArrayList<>();
        d.path("itens").forEach(itens::add);
        tabelaParados.setItems(FXCollections.observableArrayList(itens));
        infoParados.setText(d.path("vazio").asBoolean(true) ? "Nenhum produto parado além de " + d.path("diasParado").asInt() + " dias."
                : "Sem venda há mais de " + d.path("diasParado").asInt() + " dias · duplo clique abre o produto.");
    }

    private static javafx.scene.Node cartao(String titulo, String valor, String classes) {
        VBox c = new VBox(2, Ui.texto(titulo, "rg-stat-label"), Ui.texto(valor, "rg-kpi-valor"));
        c.getStyleClass().add("rg-stat");
        for (String s : classes.split(" ")) {
            c.getStyleClass().add(s);
        }
        return c;
    }

    // ---------- apoio dos testes ----------

    public List<String> cartoesDePerdas() {
        return textos(cartoesPerdas);
    }

    public List<String> cartoesDeParados() {
        return textos(cartoesParados);
    }

    public int linhasDeParados() {
        return tabelaParados.getItems().size();
    }

    /** Simula o duplo clique na primeira linha da tabela de parados. */
    public String primeiroParadoId() {
        return tabelaParados.getItems().isEmpty() ? null : tabelaParados.getItems().get(0).path("produtoId").asText();
    }

    private static List<String> textos(FlowPane p) {
        List<String> v = new ArrayList<>();
        p.getChildren().forEach(n -> {
            if (n instanceof VBox b && b.getChildren().size() > 1 && b.getChildren().get(1) instanceof Label l) {
                v.add(l.getText());
            }
        });
        return v;
    }
}
