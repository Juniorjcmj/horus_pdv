package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.LoteApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Controle de validade por lote, como a tela da versão web: alertas (vencidos, críticos, atenção, valor em risco e
 * baixa de perda), consulta de lotes com filtros e paginação, baixa por lote na venda (modo FEFO e divergências),
 * prazos por categoria e registro manual de lote. Mudar o modo FEFO e os prazos: gerente/administrador.
 */
public class ValidadeView extends Tela {
    static final Map<String, String> FAIXAS = new LinkedHashMap<>();
    static final Map<String, String> ORIGENS = new LinkedHashMap<>();
    static final Map<String, String[]> MODOS = new LinkedHashMap<>();

    static {
        FAIXAS.put("vencido", "Vencido");
        FAIXAS.put("critico", "Crítico");
        FAIXAS.put("atencao", "Atenção");
        FAIXAS.put("ok", "No prazo");
        FAIXAS.put("esgotado", "Sem saldo");
        ORIGENS.put("nfe", "NF-e");
        ORIGENS.put("compra", "Compra");
        ORIGENS.put("ajuste", "Ajuste de estoque");
        ORIGENS.put("cadastro", "Cadastro de produto");
        ORIGENS.put("manual", "Registro manual");
        ORIGENS.put("inicial", "Carga inicial");
        MODOS.put("desligado", new String[] {"Desligado",
            "As vendas não baixam lote. O saldo por lote continua estimado (o que vence primeiro sai primeiro)."});
        MODOS.put("sombra", new String[] {"Sombra",
            "A venda baixa o lote em segundo plano, mas as telas ainda usam o saldo estimado. Use para comparar o saldo real com o estimado antes de ativar."});
        MODOS.put("ativo", new String[] {"Ativo",
            "As telas passam a usar o saldo real de cada lote, baixado pela venda (vence primeiro, sai primeiro)."});
    }

    private static final int POR_PAGINA = 25;

    private final Perfil perfil;
    private final TabPane abas = new TabPane();
    private final Carregando carregando;

    // alertas
    private final TableView<JsonNode> tAlertas = Ui.tabela();
    private final Label aVencidos = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label aCriticos = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label aAtencao = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label aRisco = Ui.texto("R$ 0,00", "kpi-value", "rg-stat-value");
    private final Label aSemLote = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label aNota = Ui.muted("");
    private final ToggleButton fVencido = new ToggleButton();
    private final ToggleButton fCritico = new ToggleButton();
    private final ToggleButton fAtencao = new ToggleButton();
    private List<JsonNode> alertas = new ArrayList<>();
    private String modoAtual = "desligado";

    // consulta
    private final TableView<JsonNode> tConsulta = Ui.tabela();
    private final TextField cBusca = Ui.campo("Produto, código ou número do lote");
    private final DatePicker cDe = new DatePicker();
    private final DatePicker cAte = new DatePicker();
    private final ComboBox<String> cCategoria = Ui.combo(new ArrayList<>());
    private final ComboBox<String> cFaixa = Ui.combo(new ArrayList<>());
    private final ComboBox<String> cOrigem = Ui.combo(new ArrayList<>());
    private final CheckBox cComSaldo = Ui.check("Só com saldo", false);
    private final Label cInfo = Ui.muted("");
    private final Button cAnterior = Ui.botao("‹ Anterior", () -> pagina(-1));
    private final Button cProxima = Ui.botao("Próxima ›", () -> pagina(1));
    private final Map<String, String> categoriaPorNome = new LinkedHashMap<>();
    private int paginaAtual = 1;
    private int totalPaginas = 1;

    // FEFO
    private final VBox modosBox = new VBox(8);
    private final TableView<JsonNode> tFefo = Ui.tabela();
    private final Label fComSaldo = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label fLotesDiv = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label fProdDiv = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label fSemLote = Ui.texto("0", "kpi-value", "rg-stat-value");
    private JsonNode fefo;

    // prazos por categoria
    private final TableView<LinhaCategoria> tCategorias = Ui.tabela();

    /** Prazo padrão / alerta editáveis de uma categoria. */
    static final class LinhaCategoria {
        final JsonNode origem;
        final SimpleStringProperty prazo = new SimpleStringProperty();
        final SimpleStringProperty alerta = new SimpleStringProperty();

        LinhaCategoria(JsonNode n) {
            origem = n;
            prazo.set(n.hasNonNull("prazoPadraoDias") ? n.get("prazoPadraoDias").asText() : "");
            alerta.set(n.hasNonNull("diasAlerta") ? n.get("diasAlerta").asText() : "");
        }

        String nome() {
            return (origem.hasNonNull("categoriaPaiId") && !origem.path("categoriaPaiId").asText().isBlank() ? "   ↳ " : "")
                    + origem.path("nome").asText();
        }
    }

    public ValidadeView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        getChildren().add(Ui.cabecalho("Controle de validade",
                "Lotes próximos do vencimento, com saldo e valor em risco; prazos de validade por categoria.",
                Ui.botao("Atualizar", this::recarregarAba), Ui.primario("Registrar lote", this::registrarLote)));

        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        abas.getTabs().addAll(new Tab("Alertas por lote", montarAlertas()), new Tab("Consulta de lotes", montarConsulta()),
                new Tab("Baixa por lote", montarFefo()), new Tab("Prazos por categoria", montarCategorias()));
        abas.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> recarregarAba());
        VBox.setVgrow(abas, Priority.ALWAYS);
        carregando = new Carregando(abas);
        getChildren().add(carregando);
        VBox.setVgrow(carregando, Priority.ALWAYS);
    }

    @Override
    public void ativar() {
        recarregarAba();
    }

    private void recarregarAba() {
        switch (abas.getSelectionModel().getSelectedIndex()) {
            case 1 -> carregarConsulta();
            case 2 -> carregarFefo();
            case 3 -> carregarCategorias();
            default -> carregarAlertas();
        }
    }

    // ------------------------------------------------------------------ comuns

    private static VBox cartao(Label valor, String rotulo, String... classes) {
        VBox c = new VBox(2, Ui.texto(rotulo, "kpi-label", "rg-stat-label"), valor);
        c.getStyleClass().add("rg-stat");
        c.getStyleClass().addAll(classes);
        c.setMinWidth(170);
        c.setPrefHeight(96);
        c.setMinHeight(96);
        return c;
    }

    static String data(String iso) {
        try {
            return Fmt.date(LocalDate.parse(iso.substring(0, 10)));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    static String validade(JsonNode j) {
        int dias = j.path("diasParaVencer").asInt();
        String quando = dias < 0 ? "venceu há " + (-dias) + (dias == -1 ? " dia" : " dias")
                : dias == 0 ? "vence hoje" : "em " + dias + (dias == 1 ? " dia" : " dias");
        return data(j.path("dataValidade").asText()) + " (" + quando + ")" + (j.path("validadePadrao").asBoolean() ? " · sugerida" : "");
    }

    private String saldoTitulo() {
        return "ativo".equals(modoAtual) ? "Saldo (real)" : "Qtd. estimada";
    }

    private void colunasLote(TableView<JsonNode> t, boolean comInicial, boolean comOrigem) {
        Ui.ajustar(Ui.col(t, "Produto", j -> j.path("productName").asText(), 240), 160, null);
        Ui.ajustar(Ui.col(t, "Código · categoria", j -> j.path("productCode").asText()
                + (j.path("categoriaNome").asText("").isBlank() ? "" : " · " + j.path("categoriaNome").asText()), 180), 120, null);
        Ui.ajustar(Ui.col(t, "Lote", j -> j.path("numeroLote").asText("").isBlank() ? "—" : j.path("numeroLote").asText(), 90), 70, null);
        Ui.ajustar(Ui.col(t, "Validade", ValidadeView::validade, 220), 170, null);
        if (comInicial) {
            Ui.colNum(t, "Qtd. inicial", j -> Fmt.qty(j.path("qtdInicial").decimalValue()), 80);
        }
        TableColumn<JsonNode, String> saldo = Ui.colNum(t, "Qtd. estimada", j -> Fmt.qty(j.path("qtdEstimada").decimalValue()), 96);
        saldo.setId("saldo");
        Ui.colNum(t, "Valor em risco", j -> Fmt.money(j.path("valorEmRisco").decimalValue()), 100);
        Ui.ajustar(Ui.col(t, "Situação", j -> FAIXAS.getOrDefault(j.path("faixa").asText(), j.path("faixa").asText()), 90), 80, null);
        if (comOrigem) {
            Ui.ajustar(Ui.col(t, "Origem", j -> ORIGENS.getOrDefault(j.path("origem").asText(), j.path("origem").asText()), 120), 90, null);
        }
        Ui.distribuir(t);
        VBox.setVgrow(t, Priority.ALWAYS);
    }

    private void tituloSaldo(TableView<JsonNode> t) {
        t.getColumns().stream().filter(c -> "saldo".equals(c.getId())).forEach(c -> {
            if (c.getGraphic() instanceof Label l) {
                l.setText(saldoTitulo());
            } else {
                c.setText(saldoTitulo());
            }
        });
    }

    // ------------------------------------------------------------------ alertas

    private Node montarAlertas() {
        fVencido.setGraphic(cartao(aVencidos, "VENCIDOS", "rg-kpi-vermelho"));
        fCritico.setGraphic(cartao(aCriticos, "CRÍTICOS", "rg-kpi-amarelo"));
        fAtencao.setGraphic(cartao(aAtencao, "ATENÇÃO"));
        for (ToggleButton b : new ToggleButton[] {fVencido, fCritico, fAtencao}) {
            b.getStyleClass().add("rg-filtro-cartao");
            b.setTooltip(new javafx.scene.control.Tooltip("Clique para mostrar só estes lotes; clique de novo para mostrar todos"));
            b.setOnAction(e -> {
                for (ToggleButton o : new ToggleButton[] {fVencido, fCritico, fAtencao}) {
                    if (o != b) {
                        o.setSelected(false);
                    }
                }
                filtrarAlertas();
            });
        }
        HBox cards = new HBox(10, fVencido, fCritico, fAtencao, cartao(aRisco, "VALOR EM RISCO (CUSTO)"),
                cartao(aSemLote, "CONTROLADOS SEM LOTE"));
        colunasLote(tAlertas, false, false);
        tAlertas.setPlaceholder(Ui.muted("Nenhum lote em alerta."));
        Button baixar = Ui.botao("Baixar perda do lote selecionado", this::baixarPerda);
        baixar.setTooltip(new javafx.scene.control.Tooltip("Reduz o estoque do produto pela quantidade do lote (perda por vencimento)"));
        VBox v = new VBox(10, new javafx.scene.control.ScrollPane(cards) {
            {
                setFitToHeight(true);
                setPrefHeight(110);
                setMinHeight(110);
                getStyleClass().add("rg-scroll-transparente");
            }
        }, aNota, tAlertas, Ui.barra(baixar));
        v.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        return v;
    }

    private void carregarAlertas() {
        rodar(carregando, "Carregando os alertas de validade…", ctx.lotes::alertas, r -> {
            modoAtual = r.path("modo").asText("desligado");
            aVencidos.setText(r.path("vencidos").asText("0"));
            aCriticos.setText(r.path("criticos").asText("0"));
            aAtencao.setText(r.path("atencao").asText("0"));
            aRisco.setText(Fmt.money(r.path("valorEmRisco").decimalValue()));
            aSemLote.setText(r.path("produtosSemLote").asText("0"));
            aNota.setText("ativo".equals(modoAtual)
                    ? "O saldo por lote é o real, baixado pelas vendas (vence primeiro, sai primeiro)."
                    : "O saldo por lote é estimado: considera que o que vence primeiro sai primeiro.");
            List<JsonNode> l = new ArrayList<>();
            r.path("itens").forEach(l::add);
            alertas = l;
            tituloSaldo(tAlertas);
            filtrarAlertas();
        });
    }

    private void filtrarAlertas() {
        String faixa = fVencido.isSelected() ? "vencido" : fCritico.isSelected() ? "critico" : fAtencao.isSelected() ? "atencao" : null;
        tAlertas.setItems(FXCollections.observableArrayList(
                alertas.stream().filter(j -> faixa == null || faixa.equals(j.path("faixa").asText())).toList()));
    }

    private void baixarPerda() {
        JsonNode j = tAlertas.getSelectionModel().getSelectedItem();
        if (j == null) {
            Toasts.error(janela(), "Selecione um lote na lista.");
            return;
        }
        BigDecimal qtd = j.path("qtdEstimada").decimalValue();
        if (qtd.signum() <= 0) {
            Toasts.error(janela(), "Este lote não tem saldo para baixar.");
            return;
        }
        String motivo = "Perda por vencimento (lote " + data(j.path("dataValidade").asText()) + ")";
        Modal.confirmar(janela(), "Baixar perda", "Dar baixa de " + Fmt.qty(qtd) + " de \"" + j.path("productName").asText()
                + "\" (validade " + data(j.path("dataValidade").asText()) + ") como perda por vencimento? O estoque do produto será reduzido.",
                "Baixar perda", () -> rodar(carregando, "Registrando a perda…", () -> {
                    ctx.produtos.ajustar(j.path("produtoId").asText(), "perda", qtd, motivo, null, null);
                    return true;
                }, ok -> {
                    ctx.catalogo.invalidar();
                    sucesso("Perda registrada: " + Fmt.qty(qtd) + " de " + j.path("productName").asText() + ".");
                    carregarAlertas();
                }));
    }

    // ------------------------------------------------------------------ consulta

    private Node montarConsulta() {
        cBusca.setPrefWidth(280);
        cBusca.setOnAction(e -> consultarDoInicio());
        cDe.setPromptText("De");
        cAte.setPromptText("Até");
        cDe.setPrefWidth(140);
        cAte.setPrefWidth(140);
        List<String> faixas = new ArrayList<>();
        faixas.add("Todas");
        faixas.addAll(FAIXAS.values());
        cFaixa.setItems(FXCollections.observableArrayList(faixas));
        cFaixa.setValue("Todas");
        List<String> origens = new ArrayList<>();
        origens.add("Todas");
        origens.addAll(ORIGENS.values());
        cOrigem.setItems(FXCollections.observableArrayList(origens));
        cOrigem.setValue("Todas");
        cCategoria.setPrefWidth(220);
        HBox rapidos = new HBox(6, Ui.muted("Validade:"));
        Object[][] atalhos = {{"Vencidos", null, -1}, {"Vencem hoje", 0, 0}, {"Próx. 7 dias", 0, 7}, {"Próx. 15 dias", 0, 15},
            {"Próx. 30 dias", 0, 30}, {"Próx. 60 dias", 0, 60}, {"Próx. 90 dias", 0, 90}};
        for (Object[] a : atalhos) {
            rapidos.getChildren().add(Ui.pequeno((String) a[0], () -> {
                LocalDate hoje = LocalDate.now();
                cDe.setValue(a[1] == null ? null : hoje.plusDays((Integer) a[1]));
                cAte.setValue(hoje.plusDays((Integer) a[2]));
                consultarDoInicio();
            }));
        }
        rapidos.getChildren().add(Ui.pequeno("Limpar", () -> {
            cBusca.clear();
            cDe.setValue(null);
            cAte.setValue(null);
            cCategoria.setValue("Todas");
            cFaixa.setValue("Todas");
            cOrigem.setValue("Todas");
            cComSaldo.setSelected(false);
            consultarDoInicio();
        }));
        rapidos.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Button filtrar = Ui.primario("Filtrar", this::consultarDoInicio);
        colunasLote(tConsulta, true, true);
        tConsulta.setPlaceholder(Ui.muted("Nenhum lote com esses filtros."));
        VBox v = new VBox(10, Ui.barra(Ui.rotulado("Buscar", cBusca), Ui.rotulado("De", cDe), Ui.rotulado("Até", cAte),
                Ui.rotulado("Categoria", cCategoria), Ui.rotulado("Situação", cFaixa), Ui.rotulado("Origem", cOrigem), cComSaldo,
                filtrar), rapidos, tConsulta, Ui.barra(cInfo, Ui.espaco(), cAnterior, cProxima));
        v.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        return v;
    }

    private void consultarDoInicio() {
        paginaAtual = 1;
        carregarConsulta();
    }

    private void pagina(int delta) {
        int nova = Math.max(1, Math.min(totalPaginas, paginaAtual + delta));
        if (nova != paginaAtual) {
            paginaAtual = nova;
            carregarConsulta();
        }
    }

    private static String chave(Map<String, String> mapa, String rotulo) {
        return mapa.entrySet().stream().filter(e -> e.getValue().equals(rotulo)).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private void carregarConsulta() {
        LoteApi.Filtro f = new LoteApi.Filtro(cBusca.getText(), cDe.getValue() == null ? null : cDe.getValue().toString(),
                cAte.getValue() == null ? null : cAte.getValue().toString(), categoriaPorNome.get(cCategoria.getValue()),
                chave(FAIXAS, cFaixa.getValue()), cComSaldo.isSelected(), chave(ORIGENS, cOrigem.getValue()), paginaAtual, POR_PAGINA);
        boolean precisaCategorias = categoriaPorNome.isEmpty();
        rodar(carregando, "Consultando lotes…", () -> new Object[] {ctx.lotes.consulta(f), precisaCategorias ? ctx.lotes.categorias() : null},
                r -> {
                    if (r[1] != null) {
                        categoriaPorNome.clear();
                        categoriaPorNome.put("Todas", null);
                        ordenarArvore((JsonNode) r[1]).forEach(c -> categoriaPorNome.put(new LinhaCategoria(c).nome().replace("   ↳ ", "  ↳ "),
                                c.path("categoriaId").asText()));
                        cCategoria.setItems(FXCollections.observableArrayList(categoriaPorNome.keySet()));
                        if (cCategoria.getValue() == null) {
                            cCategoria.setValue("Todas");
                        }
                    }
                    JsonNode d = (JsonNode) r[0];
                    modoAtual = d.path("modo").asText(modoAtual);
                    List<JsonNode> l = new ArrayList<>();
                    d.path("itens").forEach(l::add);
                    tConsulta.setItems(FXCollections.observableArrayList(l));
                    tituloSaldo(tConsulta);
                    int total = d.path("total").asInt();
                    totalPaginas = Math.max(1, (total + POR_PAGINA - 1) / POR_PAGINA);
                    cInfo.setText(total + " lote(s) · página " + paginaAtual + " de " + totalPaginas + " · valor em risco "
                            + Fmt.money(d.path("valorEmRisco").decimalValue()));
                    cAnterior.setDisable(paginaAtual <= 1);
                    cProxima.setDisable(paginaAtual >= totalPaginas);
                });
    }

    // ------------------------------------------------------------------ baixa por lote (FEFO)

    private Node montarFefo() {
        Label titulo = Ui.texto("Baixa por lote na venda (FEFO)", "rg-section-title");
        Label sub = Ui.muted("Cada venda tira a quantidade dos lotes que vencem primeiro. Um lote vencido nunca bloqueia a venda: o "
                + "caixa só avisa." + (perfil.gerencia() ? "" : " Só gerente ou administrador muda o modo."));
        sub.setWrapText(true);
        HBox kpis = new HBox(10, cartao(fComSaldo, "LOTES COM SALDO REAL"), cartao(fLotesDiv, "LOTES DIVERGENTES"),
                cartao(fProdDiv, "PRODUTOS DIVERGENTES"), cartao(fSemLote, "ESTOQUE NÃO COBERTO POR LOTE"));
        Ui.ajustar(Ui.col(tFefo, "Produto", j -> j.path("productCode").asText() + " · " + j.path("productName").asText(), 260), 160, null);
        Ui.ajustar(Ui.col(tFefo, "Lote", j -> j.path("numeroLote").asText("").isBlank() ? "—" : j.path("numeroLote").asText(), 90), 70, null);
        Ui.ajustar(Ui.col(tFefo, "Validade", j -> data(j.path("dataValidade").asText()), 100), 90, null);
        Ui.colNum(tFefo, "Estimado", j -> Fmt.qty(j.path("qtdEstimada").decimalValue()), 80);
        Ui.colNum(tFefo, "Real", j -> Fmt.qty(j.path("qtdReal").decimalValue()), 80);
        Ui.colNum(tFefo, "Diferença", j -> Fmt.qty(j.path("diferenca").decimalValue()), 80);
        Ui.distribuir(tFefo);
        VBox.setVgrow(tFefo, Priority.ALWAYS);
        tFefo.setPlaceholder(Ui.muted("Nenhuma divergência entre o saldo real e o estimado."));
        VBox v = new VBox(10, titulo, sub, modosBox, kpis, Ui.texto("Divergências (real × estimado)", "rg-section-title"), tFefo);
        v.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        return v;
    }

    private void carregarFefo() {
        rodar(carregando, "Carregando a baixa por lote…", ctx.lotes::fefo, r -> {
            fefo = r;
            modoAtual = r.path("modo").asText("desligado");
            fComSaldo.setText(r.path("lotesComSaldo").asText("0"));
            fLotesDiv.setText(r.path("lotesComDivergencia").asText("0"));
            fProdDiv.setText(r.path("produtosComDivergencia").asText("0"));
            fSemLote.setText(r.path("produtosComEstoqueSemLote").asText("0"));
            List<JsonNode> l = new ArrayList<>();
            r.path("itens").forEach(l::add);
            tFefo.setItems(FXCollections.observableArrayList(l));
            modosBox.getChildren().clear();
            HBox linha = new HBox(10);
            MODOS.forEach((modo, info) -> {
                boolean atual = modo.equals(modoAtual);
                Label t = Ui.texto(info[0] + (atual ? "  · atual" : ""), "label-strong");
                Label d = Ui.muted(info[1]);
                d.setWrapText(true);
                d.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                VBox card = new VBox(4, t, d);
                card.getStyleClass().addAll("rg-stat", atual ? "rg-kpi-verde" : "rg-kpi");
                card.setPrefWidth(320);
                card.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
                card.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(card, Priority.ALWAYS);
                if (perfil.gerencia() && !atual) {
                    card.setStyle("-fx-cursor: hand;");
                    card.setOnMouseClicked(e -> mudarModo(modo));
                }
                linha.getChildren().add(card);
            });
            modosBox.getChildren().add(linha);
        });
    }

    private void mudarModo(String modo) {
        Runnable aplicar = () -> rodar(carregando, "Alterando o modo…", () -> {
            ctx.lotes.definirModo(modo);
            return true;
        }, ok -> {
            sucesso("Baixa por lote: modo " + MODOS.get(modo)[0].toLowerCase() + ".");
            carregarFefo();
        });
        if ("ativo".equals(modo) && fefo != null) {
            Modal.confirmar(janela(), "Ativar o saldo real por lote", "Ativar o saldo real por lote? "
                    + fefo.path("lotesComDivergencia").asInt() + " lote(s) em " + fefo.path("produtosComDivergencia").asInt()
                    + " produto(s) divergem do saldo estimado. As telas de validade passam a mostrar o saldo real, baixado pelas vendas.",
                    "Ativar", aplicar);
        } else {
            aplicar.run();
        }
    }

    // ------------------------------------------------------------------ prazos por categoria

    private Node montarCategorias() {
        Label ajuda = Ui.muted("Prazo padrão: validade sugerida na entrada quando você não informa a data (dias a contar do recebimento). "
                + "Alerta: quantos dias antes do vencimento o lote entra em alerta. Subcategorias sem valor usam o do departamento."
                + (perfil.gerencia() ? " Clique no número para alterar (Enter salva)." : " Só gerente ou administrador altera."));
        ajuda.setWrapText(true);
        tCategorias.setEditable(perfil.gerencia());
        Ui.ajustar(Ui.col(tCategorias, "Categoria", LinhaCategoria::nome, 320), 200, null);
        TableColumn<LinhaCategoria, String> prazo = PedidoEditorDialog.colunaEditavel(tCategorias, "Prazo padrão (dias)", l -> l.prazo,
                140, true, null);
        TableColumn<LinhaCategoria, String> alerta = PedidoEditorDialog.colunaEditavel(tCategorias, "Alerta (dias antes)", l -> l.alerta,
                140, true, null);
        prazo.setOnEditCommit(e -> salvarCategoria(e.getRowValue(), e.getNewValue(), e.getRowValue().alerta.get(), true));
        alerta.setOnEditCommit(e -> salvarCategoria(e.getRowValue(), e.getRowValue().prazo.get(), e.getNewValue(), false));
        prazo.setEditable(perfil.gerencia());
        alerta.setEditable(perfil.gerencia());
        Ui.distribuir(tCategorias);
        VBox.setVgrow(tCategorias, Priority.ALWAYS);
        tCategorias.setPlaceholder(Ui.muted("Nenhuma categoria cadastrada."));
        VBox v = new VBox(10, ajuda, tCategorias);
        v.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        return v;
    }

    private void carregarCategorias() {
        rodar(carregando, "Carregando os prazos por categoria…", ctx.lotes::categorias, r -> {
            List<LinhaCategoria> l = new ArrayList<>();
            ordenarArvore(r).forEach(n -> l.add(new LinhaCategoria(n)));
            tCategorias.setItems(FXCollections.observableArrayList(l));
        });
    }

    /** Departamentos em ordem alfabética, cada um seguido das suas subcategorias (como na web). */
    static List<JsonNode> ordenarArvore(JsonNode categorias) {
        List<JsonNode> todas = new ArrayList<>();
        categorias.forEach(todas::add);
        java.util.Comparator<JsonNode> porNome = java.util.Comparator.comparing(n -> Fmt.normalizar(n.path("nome").asText()));
        java.util.Set<String> ids = new java.util.HashSet<>();
        todas.forEach(n -> ids.add(n.path("categoriaId").asText()));
        List<JsonNode> r = new ArrayList<>();
        todas.stream().filter(n -> !temPai(n, ids)).sorted(porNome).forEach(pai -> {
            r.add(pai);
            todas.stream().filter(f -> pai.path("categoriaId").asText().equals(f.path("categoriaPaiId").asText()))
                    .sorted(porNome).forEach(r::add);
        });
        return r;
    }

    private static boolean temPai(JsonNode n, java.util.Set<String> ids) {
        String pai = n.hasNonNull("categoriaPaiId") ? n.path("categoriaPaiId").asText() : "";
        return !pai.isBlank() && ids.contains(pai);
    }

    static Integer diasOuNulo(String texto, String campo) {
        String t = texto == null ? "" : texto.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(t);
            if (v < 0 || v > 3650) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new DomainException(campo + ": informe um número de dias entre 0 e 3650 (ou deixe vazio).");
        }
    }

    private void salvarCategoria(LinhaCategoria l, String prazoTxt, String alertaTxt, boolean mudouPrazo) {
        Integer prazo;
        Integer alerta;
        try {
            prazo = diasOuNulo(prazoTxt, "Prazo padrão");
            alerta = diasOuNulo(alertaTxt, "Alerta");
        } catch (DomainException ex) {
            Toasts.error(janela(), ex.getMessage());
            carregarCategorias();
            return;
        }
        if (mudouPrazo) {
            l.prazo.set(prazo == null ? "" : prazo.toString());
        } else {
            l.alerta.set(alerta == null ? "" : alerta.toString());
        }
        rodar(() -> {
            ctx.lotes.salvarCategoria(l.origem.path("categoriaId").asText(), prazo, alerta);
            return true;
        }, ok -> sucesso("Prazos de \"" + l.origem.path("nome").asText() + "\" salvos."));
    }

    // ------------------------------------------------------------------ registrar lote

    private void registrarLote() {
        Produto[] escolhido = new Produto[1];
        Label produtoLbl = Ui.texto("Nenhum produto escolhido", "label-strong");
        TextField busca = Ui.campo("Digite 2 letras do nome, o código ou o código de barras");
        new BuscaProdutoCampo(busca, t -> ctx.catalogo.buscar(t, 60), ctx.catalogo::porCodigoOuGtin, p -> {
            escolhido[0] = p;
            produtoLbl.setText(p.codigo() + " · " + p.nome() + " · estoque " + Fmt.qty(p.estoque()) + " " + p.unidade());
        });
        DatePicker validade = new DatePicker();
        validade.setPromptText("dd/mm/aaaa");
        TextField quantidade = Ui.campo("Ex.: 12");
        TextField lote = Ui.campo("Opcional (como na embalagem)");
        VBox corpo = new VBox(10, Ui.rotulado("Produto *", busca), produtoLbl,
                Ui.barra(Ui.rotulado("Validade *", validade), Ui.rotulado("Quantidade *", quantidade), Ui.rotulado("Nº do lote", lote)),
                Ui.muted("O registro só cria o lote para o controle de validade; não muda o estoque do produto."));
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), "Registrar lote", corpo, "Registrar", 620, () -> {
            BigDecimal qtd;
            try {
                if (escolhido[0] == null) {
                    throw new DomainException("Escolha o produto na lista.");
                }
                if (validade.getValue() == null) {
                    throw new DomainException("Informe a data de validade.");
                }
                qtd = Fmt.parse(quantidade.getText());
                if (qtd.signum() <= 0) {
                    throw new DomainException("A quantidade deve ser maior que zero.");
                }
            } catch (DomainException e) {
                m[0].erro(e.getMessage());
                return false;
            }
            Produto p = escolhido[0];
            String dia = validade.getValue().toString();
            m[0].ocupado(true);
            Carregando.async("Registrando o lote…", () -> {
                ctx.lotes.registrar(p.id(), dia, qtd, lote.getText());
                return true;
            }, ok -> {
                m[0].ocupado(false);
                m[0].fechar();
                sucesso("Lote de \"" + p.nome() + "\" registrado (validade " + Fmt.date(validade.getValue()) + ").");
                recarregarAba();
            }, err -> {
                m[0].ocupado(false);
                m[0].erro(Fx.message(err));
            });
            return false;
        });
        m[0].mostrar();
        busca.requestFocus();
    }
}
