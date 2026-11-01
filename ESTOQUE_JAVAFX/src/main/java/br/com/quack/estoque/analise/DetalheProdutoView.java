package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.analise.Graficos.Ponto;
import br.com.quack.estoque.analise.Graficos.Referencia;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.ui.Carregando;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Detalhe de análise de um produto: indicadores, saldo diário com mínimo/ponto de pedido/máximo, entradas x saídas por semana,
 * histograma da demanda diária e extrato paginado. Abre por duplo clique nas tabelas da análise ({@link #abrir}).
 */
public class DetalheProdutoView extends Tela {
    private static final int POR_PAGINA = 25;

    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final Label titulo = Ui.texto("Produto", "rg-page-title");
    private final Label subtitulo = Ui.muted("");
    private final FlowPane cartoes = new FlowPane(12, 12);
    private final BlocoCard saldo = new BlocoCard("Saldo diário", "Com mínimo, ponto de pedido e máximo", 470, 330);
    private final BlocoCard semanal = new BlocoCard("Entradas × saídas por semana", "Quantidades somadas de cada semana", 470, 330);
    private final BlocoCard histograma = new BlocoCard("Demanda diária", "Quantos dias venderam cada faixa de quantidade", 470, 330);
    private final TableView<JsonNode> extrato = Ui.tabela();
    private final Label paginaInfo = Ui.muted("");
    private final Button anterior = Ui.pequeno("‹ Anterior", () -> mudarPagina(-1));
    private final Button proxima = Ui.pequeno("Próxima ›", () -> mudarPagina(1));
    private final Runnable aoVoltar;
    private String produtoId;
    private int pagina = 1;
    private int paginas = 1;
    private int geracao;

    public DetalheProdutoView(AppContext ctx, Navegador nav, Runnable aoVoltar) {
        super(ctx, nav);
        this.aoVoltar = aoVoltar;
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        // um produto só: olha o ano todo por padrão (a vida de um produto é mais lenta que a do dashboard)
        if ("Últimos 30 dias".equals(filtros.atalho)) {
            filtros.aplicarAtalho("Últimos 12 meses", LocalDate.now());
        }
        // categoria/fornecedor não fazem sentido para um produto só: o detalhe usa só o período
        this.barra = new FiltrosBar(ctx, filtros, this::recarregar);
        barra.somentePeriodo();

        Ui.col(extrato, "Data", j -> Fmt.dateTime(j.path("data").asText()), 120);
        Ui.col(extrato, "Tipo", j -> rotuloTipo(j.path("tipo").asText()), 100);
        Ui.colNum(extrato, "Quantidade", j -> Fmt.qty(j.path("quantidade").decimalValue()), 80);
        Ui.colNum(extrato, "Anterior", j -> Fmt.qty(j.path("anterior").decimalValue()), 80);
        Ui.colNum(extrato, "Novo", j -> Fmt.qty(j.path("novo").decimalValue()), 80);
        Ui.colNum(extrato, "Custo (R$)", j -> j.path("custoUnitario").isNull() ? "" : Fmt.cost4(j.path("custoUnitario").decimalValue()), 80);
        Ui.col(extrato, "Origem", j -> j.path("origemTipo").asText("") + (j.path("origemId").isNull() ? "" : " " + j.path("origemId").asText()), 110);
        Ui.col(extrato, "Usuário", j -> j.path("usuario").asText(""), 110);
        Ui.col(extrato, "Motivo", j -> j.path("motivo").asText(""), 200);
        Ui.distribuir(extrato);
        extrato.setMinHeight(220);
        extrato.setPrefHeight(280);

        Button voltar = Ui.botao("‹ Voltar", () -> {
            if (this.aoVoltar != null) {
                this.aoVoltar.run();
            }
        });
        HBox pager = new HBox(8, anterior, proxima, paginaInfo);
        pager.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox cartaoExtrato = new VBox(8, Ui.texto("Extrato de movimentações", "rg-section-title"), extrato, pager);
        cartaoExtrato.getStyleClass().add("rg-card");

        VBox miolo = new VBox(12, cartoes, new FlowPane(12, 12, saldo, semanal, histograma), cartaoExtrato);
        ScrollPane rolagem = new ScrollPane(miolo);
        rolagem.setFitToWidth(true);
        rolagem.getStyleClass().add("rg-scroll-limpo");
        VBox.setVgrow(rolagem, Priority.ALWAYS);
        rolagem.setMinHeight(0);

        VBox cab = new VBox(2, titulo, subtitulo);
        HBox.setHgrow(cab, Priority.ALWAYS);
        HBox topo = new HBox(8, cab, voltar);
        getChildren().addAll(topo, barra, rolagem);
    }

    /** Mostra o produto (chamado pelo navegador depois de colocar a tela na janela). */
    public void abrir(String id) {
        this.produtoId = id;
        this.pagina = 1;
        ativar();
    }

    @Override
    public void ativar() {
        if (produtoId == null) {
            return;
        }
        recarregar();
    }

    private void recarregar() {
        if (produtoId == null) {
            return;
        }
        pagina = 1;
        int minha = ++geracao;
        FiltrosAnalise f = filtros.copia();
        f.categoriaId = null;
        f.fornecedorId = null;
        f.comparar = false;
        String id = produtoId;
        cartoes.getChildren().clear();
        for (BlocoCard b : List.of(saldo, semanal, histograma)) {
            b.carregando();
        }
        Carregando.async(() -> ctx.analise.produto(id, f), r -> {
            if (minha == geracao) {
                mostrar(r.path("dados"), r.path("calculadoEm").asText(null));
            }
        }, t -> {
            if (minha == geracao) {
                subtitulo.setText("Não foi possível carregar: " + Fx.message(t));
                for (BlocoCard b : List.of(saldo, semanal, histograma)) {
                    b.erro(Fx.message(t));
                }
            }
        });
        carregarExtrato(minha, f);
    }

    private void carregarExtrato(int minha, FiltrosAnalise f) {
        String id = produtoId;
        int pg = pagina;
        Carregando.async(() -> ctx.analise.extrato(id, f, pg, POR_PAGINA), r -> {
            if (minha != geracao) {
                return;
            }
            List<JsonNode> itens = new ArrayList<>();
            r.path("itens").forEach(itens::add);
            extrato.setItems(FXCollections.observableArrayList(itens));
            paginas = Math.max(1, r.path("paginas").asInt(1));
            paginaInfo.setText("Página " + pagina + " de " + paginas + " · " + r.path("total").asInt() + " movimentações no período");
            anterior.setDisable(pagina <= 1);
            proxima.setDisable(pagina >= paginas);
        }, t -> paginaInfo.setText("Extrato indisponível: " + Fx.message(t)));
    }

    private void mudarPagina(int delta) {
        int nova = Math.max(1, Math.min(paginas, pagina + delta));
        if (nova == pagina) {
            return;
        }
        pagina = nova;
        FiltrosAnalise f = filtros.copia();
        f.categoriaId = null;
        f.fornecedorId = null;
        carregarExtrato(geracao, f);
    }

    private void mostrar(JsonNode dados, String quando) {
        JsonNode p = dados.path("produto");
        JsonNode k = dados.path("kpis");
        titulo.setText(p.path("nome").asText());
        subtitulo.setText("Código " + p.path("codigo").asText() + " · " + (k.path("abc").isNull() ? "sem classificação" : "Classe " + k.path("abc").asText()
                + " / " + k.path("fsn").asText()) + " · " + AnaliseFmt.calculadoEm(quando));
        String un = p.path("unidade").asText("UN");
        cartoes.getChildren().setAll(
                kpi("Saldo", Fmt.qty(k.path("saldo").decimalValue()) + " " + un), kpiOpc("Valor em estoque", k.path("valorEstoque"), "R$"),
                kpi("Consumo/dia", Fmt.qty(k.path("consumoDia").decimalValue()) + " " + un),
                kpi("Cobertura", k.path("coberturaDias").isNull() ? "sem giro" : AnaliseFmt.valor("dias", k.path("coberturaDias"))),
                kpi("Lead time", AnaliseFmt.valor("dias", k.path("leadTimeDias"))), kpi("Ponto de pedido", Fmt.qty(k.path("pontoPedido").decimalValue())),
                kpi("Estoque de segurança", Fmt.qty(k.path("estoqueSeguranca").decimalValue())), kpi("Sugestão de compra", Fmt.qty(k.path("sugestao").decimalValue())),
                kpiOpc("Giro anualizado", k.path("giroAnualizado"), "x"), kpiOpc("Ruptura no período", k.path("taxaRupturaPercentual"), "%"),
                kpi("Em trânsito", Fmt.qty(k.path("emTransito").decimalValue())),
                kpi("Sem venda há", k.path("diasSemSaida").isNull() ? "nunca vendeu" : AnaliseFmt.valor("dias", k.path("diasSemSaida"))));

        JsonNode serie = dados.path("series");
        // saldo diário com linhas de referência
        JsonNode dias = serie.path("saldoDiario");
        if (dias.isEmpty()) {
            saldo.vazio("Sem fotografias diárias neste período.");
        } else {
            List<Ponto> pontos = Graficos.de(dias, d -> new Ponto(AnaliseFmt.diaMes(d.path("data").asText()), d.path("quantidade").asDouble(),
                    AnaliseFmt.tooltipSerie(d.path("data").asText(), "Saldo", "un", d.path("quantidade").asDouble())));
            List<Referencia> refs = new ArrayList<>();
            if (p.path("minimo").asDouble() > 0) {
                refs.add(new Referencia("Mínimo", p.path("minimo").asDouble(), "rg-ref-min"));
            }
            refs.add(new Referencia("Ponto de pedido", k.path("pontoPedido").asDouble(), "rg-ref-rop"));
            if (p.path("maximo").asDouble() > 0) {
                refs.add(new Referencia("Máximo", p.path("maximo").asDouble(), "rg-ref-max"));
            }
            saldo.mostrar(Graficos.linha("Saldo", pontos, "un", refs), null);
        }

        // entradas x saídas por semana
        JsonNode sem = serie.path("semanal");
        List<String> cats = new ArrayList<>();
        double[] entradas = new double[sem.size()];
        double[] saidas = new double[sem.size()];
        for (int i = 0; i < sem.size(); i++) {
            cats.add(AnaliseFmt.diaMes(sem.get(i).path("inicio").asText()));
            entradas[i] = sem.get(i).path("entradas").asDouble();
            saidas[i] = sem.get(i).path("saidas").asDouble();
        }
        boolean semMov = java.util.Arrays.stream(entradas).sum() + java.util.Arrays.stream(saidas).sum() == 0;
        if (semMov) {
            semanal.vazio("Nenhuma movimentação neste período.");
        } else {
            semanal.mostrar(Graficos.barrasVerticais(cats, List.of("Entradas", "Saídas"), List.of(entradas, saidas), "un", false), null);
        }

        // histograma da demanda diária
        JsonNode hist = serie.path("histograma");
        List<String> faixas = new ArrayList<>();
        double[] qtdDias = new double[hist.size()];
        for (int i = 0; i < hist.size(); i++) {
            faixas.add(hist.get(i).path("faixa").asText());
            qtdDias[i] = hist.get(i).path("dias").asDouble();
        }
        if (hist.size() <= 1 && qtdDias.length > 0 && qtdDias[0] == 0) {
            histograma.vazio("Sem dados de demanda.");
        } else {
            histograma.mostrar(Graficos.barrasVerticais(faixas, List.of("Dias"), List.of(qtdDias), "dias", false), "Faixa = quantidade vendida no dia");
        }
    }

    private static javafx.scene.Node kpi(String titulo, String valor) {
        VBox c = new VBox(2, Ui.texto(titulo, "rg-stat-label"), Ui.texto(valor, "rg-kpi-valor"));
        c.getStyleClass().addAll("rg-stat", "rg-kpi");
        return c;
    }

    private static javafx.scene.Node kpiOpc(String titulo, JsonNode v, String unidade) {
        return kpi(titulo, v.isNull() ? "—" : AnaliseFmt.valor(unidade, v));
    }

    static String rotuloTipo(String tipo) {
        return switch (tipo) {
            case "saida_venda" -> "Venda";
            case "devolucao" -> "Devolução";
            case "entrada" -> "Entrada";
            case "saida" -> "Saída";
            case "uso_interno" -> "Uso interno";
            case "perda" -> "Perda";
            case "inventario" -> "Inventário";
            case "transferencia" -> "Transferência";
            default -> tipo;
        };
    }

    // ---------- apoio dos testes ----------

    public String tituloMostrado() {
        return titulo.getText();
    }

    public int linhasDoExtrato() {
        return extrato.getItems().size();
    }
}
