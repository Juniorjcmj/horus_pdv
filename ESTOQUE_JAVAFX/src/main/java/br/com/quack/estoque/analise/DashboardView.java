package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.analise.Graficos.Ponto;
import br.com.quack.estoque.analise.Graficos.Referencia;
import br.com.quack.estoque.ui.Carregando;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dashboard geral do estoque: cartões de KPI (variação e semáforo) e 6 gráficos. Cada bloco chama o seu endpoint
 * (api/Estoque/analise/*) de forma independente: um que falha ou demora não trava os outros.
 */
public class DashboardView extends Tela {
    private static final double LARGURA = 470;
    private static final double ALTURA = 370; // a rosca e a legenda cabem sem cortar
    /** Barras horizontais: mais que isso e os rótulos do eixo começam a se esconder. */
    private static final int MAX_BARRAS = 8;

    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final GradeResponsiva cartoes = new GradeResponsiva(200, 12, 5);
    private final Label rodapeKpis = Ui.muted("");
    private final BlocoCard estoque = new BlocoCard("Estoque ao longo do tempo", "Valor em estoque no fim de cada dia", LARGURA, ALTURA);
    private final BlocoCard ruptura = new BlocoCard("Ruptura", "% de produtos com saldo zerado ou negativo", LARGURA, ALTURA);
    private final BlocoCard cobertura = new BlocoCard("Cobertura por categoria", "Dias de estoque (saldo ÷ consumo)", LARGURA, ALTURA);
    private final BlocoCard giro = new BlocoCard("Maior giro", "Os 8 produtos que mais giram (vezes por ano)", LARGURA, ALTURA);
    private final BlocoCard parados = new BlocoCard("Maiores parados", "Sem venda há mais tempo que o limite", LARGURA, ALTURA);
    private final BlocoCard abc = new BlocoCard("Curva ABC do estoque", "Distribuição do estoque pelas classes A, B e C", LARGURA, ALTURA);
    private boolean primeiraVez = true;
    private int geracao;

    public DashboardView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        this.barra = new FiltrosBar(ctx, filtros, this::carregar);

        cartoes.getStyleClass().add("rg-kpi-flow");
        // Gráficos ocupam a largura toda: 3 colunas em tela larga, 2 em notebook, 1 em janela estreita.
        GradeResponsiva graficos = new GradeResponsiva(400, 12, 3, estoque, ruptura, cobertura, giro, parados, abc);
        VBox miolo = new VBox(12, cartoes, rodapeKpis, graficos);
        miolo.setFillWidth(true);
        ScrollPane rolagem = new ScrollPane(miolo);
        rolagem.setFitToWidth(true);
        rolagem.getStyleClass().add("rg-scroll-limpo");
        VBox.setVgrow(rolagem, Priority.ALWAYS);
        rolagem.setMinHeight(0);

        getChildren().addAll(
                Ui.cabecalho("Análise · Dashboard", "Estoque, giro, cobertura, ruptura e itens parados no período escolhido."),
                barra, rolagem);
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
        cartoes.getChildren().setAll(esqueleto(), esqueleto(), esqueleto(), esqueleto());
        rodapeKpis.setText("");
        for (BlocoCard b : List.of(estoque, ruptura, cobertura, giro, parados, abc)) {
            b.carregando();
        }

        bloco("kpis", f, minha, null, (dados, quando) -> {
            cartoes.getChildren().clear();
            for (JsonNode k : dados.path("kpis")) {
                cartoes.getChildren().add(cartao(k));
            }
            String aviso = dados.path("historicoCurto").asBoolean(false) ? " · Período curto (menos de 14 dias): indicadores de variação podem oscilar." : "";
            rodapeKpis.setText(AnaliseFmt.calculadoEm(quando) + " · " + f.resumoPeriodo()
                    + (dados.path("semHistorico").asBoolean(false) ? " · Sem fotografias diárias no período: giro indisponível." : "") + aviso);
        }, t -> cartoes.getChildren().setAll(noErro(t)));

        bloco("serie-estoque", f, minha, estoque, (d, q) -> {
            JsonNode pontos = d.path("pontos");
            if (d.path("vazio").asBoolean(true) || pontos.isEmpty()) {
                estoque.vazio("Ainda não há fotografias diárias neste período. Elas são gravadas todo dia, a partir da publicação da análise.");
                return;
            }
            boolean comValor = !pontos.get(0).path("valor").isNull();
            String unidade = comValor ? "R$" : "un";
            String nome = comValor ? "Valor em estoque" : "Itens em estoque";
            List<Ponto> lista = Graficos.de(pontos, p -> {
                double v = comValor ? p.path("valor").asDouble() : p.path("quantidade").asDouble();
                String data = p.path("data").asText();
                return new Ponto(AnaliseFmt.diaMes(data), v, AnaliseFmt.tooltipSerie(data, nome, unidade, v));
            });
            estoque.mostrar(Graficos.linha(nome, lista, unidade, List.of()), AnaliseFmt.calculadoEm(q));
        });

        bloco("serie-ruptura", f, minha, ruptura, (d, q) -> {
            JsonNode pontos = d.path("pontos");
            if (d.path("vazio").asBoolean(true) || pontos.isEmpty()) {
                ruptura.vazio("Sem fotografias diárias neste período para calcular a ruptura.");
                return;
            }
            List<Ponto> lista = Graficos.de(pontos, p -> {
                String data = p.path("data").asText();
                double v = p.path("percentual").asDouble();
                return new Ponto(AnaliseFmt.diaMes(data), v, AnaliseFmt.tooltipSerie(data, "Em ruptura", "%", v)
                        + " (" + p.path("produtosEmRuptura").asInt() + " produtos)");
            });
            ruptura.mostrar(Graficos.linha("Em ruptura", lista, "%", List.of(new Referencia("Meta 2%", 2, "rg-ref-meta"))), AnaliseFmt.calculadoEm(q));
        });

        bloco("cobertura-categorias", f, minha, cobertura, (d, q) -> {
            List<Ponto> lista = new ArrayList<>();
            for (JsonNode c : d.path("categorias")) {
                if (c.path("coberturaDias").isNull() || lista.size() >= MAX_BARRAS) {
                    continue;
                }
                double v = c.path("coberturaDias").asDouble();
                String nome = c.path("nome").asText();
                lista.add(new Ponto(AnaliseFmt.curto(nome, 22), v, AnaliseFmt.tooltipItem(nome, "Cobertura", "dias", v)
                        + "\n" + c.path("produtos").asInt() + " produtos"));
            }
            if (lista.isEmpty()) {
                cobertura.vazio("Nenhuma categoria com vendas no período para calcular a cobertura.");
                return;
            }
            Collections.reverse(lista); // o gráfico desenha de baixo para cima: a maior cobertura fica no topo
            cobertura.mostrar(Graficos.barrasHorizontais("Cobertura", lista, "dias"), AnaliseFmt.calculadoEm(q));
        });

        bloco("top-giro", f, minha, giro, (d, q) -> {
            List<Ponto> lista = new ArrayList<>();
            for (JsonNode p : d.path("produtos")) {
                if (lista.size() >= MAX_BARRAS) {
                    break;
                }
                double v = p.path("giroAnualizado").asDouble();
                String nome = p.path("nome").asText();
                lista.add(new Ponto(AnaliseFmt.curto(nome, 22), v, AnaliseFmt.tooltipItem(nome, "Giro anualizado", "x", v)
                        + "\nConsumo: " + AnaliseFmt.valor("", p.path("consumoDia")) + "/dia"));
            }
            if (lista.isEmpty()) {
                giro.vazio("Sem giro calculável: faltam fotografias diárias ou vendas no período.");
                return;
            }
            Collections.reverse(lista);
            giro.mostrar(Graficos.barrasHorizontais("Giro", lista, "x"), AnaliseFmt.calculadoEm(q));
        });

        bloco("top-parados", f, minha, parados, (d, q) -> {
            List<Ponto> lista = new ArrayList<>();
            boolean comValor = false;
            for (JsonNode p : d.path("produtos")) {
                if (lista.size() >= MAX_BARRAS) {
                    break;
                }
                comValor = !p.path("valor").isNull();
                double v = comValor ? p.path("valor").asDouble() : p.path("saldo").asDouble();
                String nome = p.path("nome").asText();
                String dias = p.path("diasSemSaida").isNull() ? "nunca vendeu" : p.path("diasSemSaida").asInt() + " dias sem venda";
                lista.add(new Ponto(AnaliseFmt.curto(nome, 22), v,
                        AnaliseFmt.tooltipItem(nome, comValor ? "Valor parado" : "Saldo", comValor ? "R$" : "", v) + "\n" + dias));
            }
            if (lista.isEmpty()) {
                parados.vazio("Nenhum produto parado além do limite de " + d.path("diasParado").asInt() + " dias.");
                return;
            }
            Collections.reverse(lista);
            parados.mostrar(Graficos.barrasHorizontais("Parados", lista, comValor ? "R$" : ""),
                    d.path("totalParados").asInt() + " parados no total · " + AnaliseFmt.calculadoEm(q));
        });

        bloco("abc-valor", f, minha, abc, (d, q) -> {
            if (d.path("vazio").asBoolean(true)) {
                abc.vazio("A classificação ABC é calculada uma vez por dia. Volte mais tarde ou peça ao gerente para recalcular.");
                return;
            }
            List<Ponto> lista = new ArrayList<>();
            double total = 0;
            boolean comValor = false;
            for (JsonNode c : d.path("classes")) {
                comValor = !c.path("valor").isNull();
                double v = comValor ? c.path("valor").asDouble() : c.path("produtos").asInt();
                total += v;
                lista.add(new Ponto("Classe " + c.path("classe").asText(), v,
                        AnaliseFmt.tooltipFatia(c.path("classe").asText(), c.path("produtos").asInt(), c.path("valor"), c.path("percentual"))));
            }
            String centro = comValor ? Graficos.eixo(total, "R$") : (int) total + " itens";
            abc.mostrar(Graficos.rosca(lista, centro), AnaliseFmt.calculadoEm(q));
        });
    }

    /** Chama um bloco fora da FX thread e entrega o resultado só se os filtros não mudaram nesse meio-tempo. */
    private void bloco(String rota, FiltrosAnalise f, int minha, BlocoCard card, java.util.function.BiConsumer<JsonNode, String> ok,
            java.util.function.Consumer<Throwable> falha) {
        Carregando.async(() -> ctx.analise.bloco(rota, f), r -> {
            if (minha != geracao) {
                return;
            }
            try {
                ok.accept(r.path("dados"), r.path("calculadoEm").asText(null));
            } catch (RuntimeException e) {
                falha.accept(e);
            }
        }, t -> {
            if (minha == geracao) {
                falha.accept(t);
            }
        });
    }

    private void bloco(String rota, FiltrosAnalise f, int minha, BlocoCard card, java.util.function.BiConsumer<JsonNode, String> ok) {
        bloco(rota, f, minha, card, ok, t -> card.erro(Fx.message(t)));
    }

    private static Node noErro(Throwable t) {
        Label l = Ui.texto("Não foi possível carregar os indicadores: " + Fx.message(t), "rg-bloco-erro");
        l.setWrapText(true);
        return l;
    }

    private static Node esqueleto() {
        VBox v = new VBox(Ui.texto("Carregando…", "rg-muted"));
        v.getStyleClass().addAll("rg-stat", "rg-kpi", "rg-kpi-esqueleto");
        return v;
    }

    /** Cartão de KPI: título, valor grande, variação e semáforo (classe CSS). */
    private static Node cartao(JsonNode k) {
        String unidade = k.path("unidade").asText("");
        boolean vazio = k.path("valor").isNull();
        Label titulo = Ui.texto(k.path("titulo").asText(), "rg-stat-label");
        titulo.setWrapText(true);
        Label valor = Ui.texto(vazio ? "—" : AnaliseFmt.valor(unidade, k.path("valor")), "rg-kpi-valor");
        VBox c = new VBox(2, titulo, valor);
        if (vazio && !k.path("motivo").isNull()) {
            c.getChildren().add(Ui.muted(k.path("motivo").asText()));
        } else {
            String var = AnaliseFmt.variacao(k.path("variacaoPercentual"));
            if (!var.isEmpty()) {
                Label v = Ui.muted(var);
                v.getStyleClass().add("rg-kpi-variacao");
                c.getChildren().add(v);
            }
        }
        c.getStyleClass().addAll("rg-stat", "rg-kpi");
        if (!k.path("semaforo").isNull()) {
            c.getStyleClass().add("rg-kpi-" + k.path("semaforo").asText());
        }
        c.setUserData(k.path("chave").asText());
        c.setId("kpi-" + k.path("chave").asText());
        return c;
    }

    /** Usado nos testes de tela: valores exibidos nos cartões por chave. */
    public java.util.Map<String, String> valoresDosCartoes() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Node n : cartoes.getChildren()) {
            if (n.getUserData() instanceof String chave && n instanceof VBox v && v.getChildren().size() > 1
                    && v.getChildren().get(1) instanceof Label l) {
                m.put(chave, l.getText());
            }
        }
        return m;
    }
}
