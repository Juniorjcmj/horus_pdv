package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.analise.Graficos.Ponto;
import br.com.quack.estoque.analise.Graficos.Referencia;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.ui.Carregando;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Modal;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Toasts;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Acuracidade do inventário e contagem cíclica: linha de acuracidade por inventário aplicado (meta 98%), divergências e a
 * lista do dia (A, B e C pela periodicidade) com campos grandes para bipar/digitar. Registrar a contagem abre um inventário
 * de escopo "produtos"; o gerente aplica na hora (senha) ou depois, na tela de Inventário.
 */
public class AcuraciaView extends Tela {

    /** Linha da contagem cíclica: o item e o que foi digitado (vazio = não contado). */
    static final class LinhaContagem {
        final JsonNode j;
        final StringProperty contado = new SimpleStringProperty("");

        LinhaContagem(JsonNode j) {
            this.j = j;
        }
    }

    private final Perfil perfil;
    private final FiltrosAnalise filtros;
    private final FiltrosBar barra;
    private final FlowPane cartoes = new FlowPane(12, 12);
    private final BlocoCard grafico = new BlocoCard("Acuracidade por inventário", "Σ mín(contado, sistema) ÷ Σ sistema · linha tracejada = meta", 520, 330);
    private final TableView<JsonNode> divergencias = Ui.tabela();
    private final Label divergenciasResumo = Ui.muted("");
    private final TableView<LinhaContagem> contagem = new TableView<>();
    private final Label contagemInfo = Ui.muted("Gere a lista do dia para começar.");
    private boolean primeiraVez = true;
    private int geracao;

    public AcuraciaView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        // o período padrão da acuracidade é maior que o do dashboard: contagens são esparsas
        if ("Últimos 30 dias".equals(filtros.atalho)) {
            filtros.aplicarAtalho("Últimos 12 meses", LocalDate.now());
        }
        this.barra = new FiltrosBar(ctx, filtros, this::carregar);

        Ui.col(divergencias, "Inv.", j -> "#" + j.path("inventario").asInt(), 50);
        Ui.col(divergencias, "Data", j -> Fmt.dateTime(j.path("data").asText()), 110);
        Ui.col(divergencias, "Código", j -> j.path("codigo").asText(), 90);
        Ui.col(divergencias, "Produto", j -> j.path("nome").asText(), 240);
        Ui.colNum(divergencias, "Sistema", j -> Fmt.qty(j.path("sistema").decimalValue()), 80);
        Ui.colNum(divergencias, "Contado", j -> Fmt.qty(j.path("contado").decimalValue()), 80);
        Ui.colNum(divergencias, "Diferença", j -> Fmt.qty(j.path("diferenca").decimalValue()), 80);
        if (perfil.verCustos()) {
            Ui.colNum(divergencias, "Valor (R$)", j -> j.path("valor").isNull() ? "" : Fmt.dec2(j.path("valor").decimalValue()), 90);
        }
        Ui.distribuir(divergencias);
        divergencias.setMinHeight(180);
        divergencias.setPrefHeight(230);
        TabelasAnalise.duploCliqueAbreProduto(divergencias, j -> j.path("produtoId").asText(), nav);

        VBox cartaoDiv = new VBox(8, Ui.texto("Divergências dos inventários", "rg-section-title"), divergenciasResumo, divergencias);
        cartaoDiv.getStyleClass().add("rg-card");
        cartaoDiv.setMinWidth(380);
        cartaoDiv.setPrefWidth(620);

        VBox cartaoContagem = montarContagem();
        VBox miolo = new VBox(12, cartoes, new FlowPane(12, 12, grafico, cartaoDiv), cartaoContagem);
        ScrollPane rolagem = new ScrollPane(miolo);
        rolagem.setFitToWidth(true);
        rolagem.getStyleClass().add("rg-scroll-limpo");
        VBox.setVgrow(rolagem, Priority.ALWAYS);
        rolagem.setMinHeight(0);

        getChildren().addAll(Ui.cabecalho("Análise · Acuracidade e contagem cíclica",
                "Quanto o sistema acerta do que existe na prateleira, e a lista de produtos para contar hoje."), barra, rolagem);
    }

    private VBox montarContagem() {
        contagem.setEditable(true);
        contagem.setFixedCellSize(42);
        contagem.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        contagem.setPlaceholder(new Label("Nada para contar hoje: todos os produtos estão dentro da periodicidade."));
        TableColumn<LinhaContagem, String> classe = new TableColumn<>("Classe");
        classe.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().j.path("classe").asText()));
        classe.setMaxWidth(70);
        TableColumn<LinhaContagem, String> codigo = new TableColumn<>("Código");
        codigo.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().j.path("codigo").asText()));
        TableColumn<LinhaContagem, String> nome = new TableColumn<>("Produto");
        nome.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().j.path("nome").asText()));
        nome.setMinWidth(200);
        TableColumn<LinhaContagem, String> ultima = new TableColumn<>("Última contagem");
        ultima.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().j.path("ultimaContagem").isNull() ? "nunca contado"
                : Fmt.dateTime(c.getValue().j.path("ultimaContagem").asText()) + " (" + c.getValue().j.path("diasDesdeUltima").asInt() + " dias)"));
        TableColumn<LinhaContagem, String> contado = new TableColumn<>("Contado");
        contado.setCellValueFactory(c -> c.getValue().contado);
        contado.setCellFactory(col -> {
            TextFieldTableCell<LinhaContagem, String> cell = new TextFieldTableCell<>(new javafx.util.converter.DefaultStringConverter());
            cell.getStyleClass().add("rg-campo-contagem");
            return cell;
        });
        contado.setOnEditCommit(e -> {
            String v = e.getNewValue() == null ? "" : e.getNewValue().trim();
            if (!v.isEmpty()) {
                try {
                    if (Fmt.parse(v).signum() < 0) {
                        throw new RuntimeException();
                    }
                } catch (RuntimeException ex) {
                    Toasts.error(janela(), "Quantidade inválida: " + v);
                    v = "";
                }
            }
            e.getRowValue().contado.set(v);
            contagem.refresh();
        });
        contado.setEditable(true);
        contado.setMinWidth(120);
        contagem.getColumns().addAll(List.of(classe, codigo, nome, ultima, contado));
        contagem.setMinHeight(260);
        contagem.setPrefHeight(320);

        List<javafx.scene.Node> botoes = new ArrayList<>();
        botoes.add(Ui.botao("Gerar lista do dia", this::gerarLista));
        botoes.add(Ui.botao("Registrar contagem", () -> registrar(false)));
        if (perfil.aplicarOuCancelarInventario()) {
            botoes.add(Ui.primario("Contar e aplicar", () -> registrar(true)));
        }
        HBox barraBotoes = new HBox(8, botoes.toArray(new javafx.scene.Node[0]));
        VBox card = new VBox(8, Ui.texto("Contagem cíclica do dia", "rg-section-title"),
                Ui.muted("A lista não mostra o saldo do sistema: conte o que está na prateleira e digite. Itens em branco ficam de fora."),
                barraBotoes, contagemInfo, contagem);
        card.getStyleClass().add("rg-card");
        return card;
    }

    @Override
    public void ativar() {
        if (primeiraVez) {
            primeiraVez = false;
            barra.carregarListas();
            gerarLista();
        }
        carregar();
    }

    private void carregar() {
        int minha = ++geracao;
        FiltrosAnalise f = filtros.copia();
        grafico.carregando();
        Carregando.async(() -> new JsonNode[] {ctx.analise.bloco("acuracidade", f), ctx.analise.bloco("divergencias", f)}, r -> {
            if (minha == geracao) {
                mostrar(r[0].path("dados"), r[1].path("dados"), r[0].path("calculadoEm").asText(null));
            }
        }, t -> {
            if (minha == geracao) {
                grafico.erro(Fx.message(t));
            }
        });
    }

    private void mostrar(JsonNode ac, JsonNode div, String quando) {
        JsonNode atual = ac.path("atual");
        double meta = ac.path("meta").asDouble(98);
        cartoes.getChildren().setAll(
                cartao("Acuracidade do período", atual.isNull() ? "sem contagens" : AnaliseFmt.valor("%", atual.path("acuracidade")),
                        atual.isNull() ? "rg-kpi" : atual.path("acuracidade").asDouble() >= meta ? "rg-kpi rg-kpi-verde" : "rg-kpi rg-kpi-vermelho"),
                cartao("Itens com contagem exata", atual.isNull() ? "—" : AnaliseFmt.valor("%", atual.path("itensExatos")), "rg-kpi"),
                cartao("Itens contados", atual.isNull() ? "0" : String.valueOf(atual.path("itens").asInt()), "rg-kpi"),
                cartao("Meta", AnaliseFmt.valor("%", ac.path("meta")), "rg-kpi"));

        JsonNode pontos = ac.path("pontos");
        if (pontos.isEmpty()) {
            grafico.vazio("Nenhum inventário aplicado neste período. Use a contagem cíclica abaixo para começar.");
        } else {
            List<Ponto> lista = Graficos.de(pontos, p -> new Ponto("#" + p.path("numero").asInt() + " " + AnaliseFmt.diaMes(p.path("data").asText()),
                    p.path("acuracidade").asDouble(), AnaliseFmt.tooltipSerie(p.path("data").asText(), "Inventário #" + p.path("numero").asInt()
                            + " (" + p.path("itens").asInt() + " itens)", "%", p.path("acuracidade").asDouble())));
            grafico.mostrar(Graficos.linha("Acuracidade", lista, "%", List.of(new Referencia("Meta " + (int) meta + "%", meta, "rg-ref-meta"))),
                    AnaliseFmt.calculadoEm(quando));
        }

        List<JsonNode> itens = new ArrayList<>();
        div.path("itens").forEach(itens::add);
        divergencias.setItems(FXCollections.observableArrayList(itens));
        divergenciasResumo.setText(div.path("vazio").asBoolean(true) ? "Nenhuma divergência no período."
                : "Sobras: " + Fmt.qty(div.path("sobras").decimalValue()) + " · Faltas: " + Fmt.qty(div.path("faltas").decimalValue())
                        + (div.path("valorFaltas").isNull() ? "" : " · Valor das faltas: " + Fmt.money(div.path("valorFaltas").decimalValue())
                                + " · Valor das sobras: " + Fmt.money(div.path("valorSobras").decimalValue())) + " · duplo clique abre o produto");
    }

    private static javafx.scene.Node cartao(String titulo, String valor, String classes) {
        VBox c = new VBox(2, Ui.texto(titulo.toUpperCase(java.util.Locale.ROOT), "kpi-label", "rg-stat-label"), Ui.texto(valor, "kpi-value", "rg-kpi-valor"));
        c.getStyleClass().add("rg-stat");
        for (String s : classes.split(" ")) {
            c.getStyleClass().add(s);
        }
        return c;
    }

    // ---------- contagem cíclica ----------

    private void gerarLista() {
        contagemInfo.setText("Carregando…");
        Carregando.async(ctx.analise::contagemCiclica, r -> {
            List<LinhaContagem> linhas = new ArrayList<>();
            r.path("itens").forEach(j -> linhas.add(new LinhaContagem(j)));
            contagem.setItems(FXCollections.observableArrayList(linhas));
            contagemInfo.setText(linhas.size() + " produtos para contar hoje (A a cada " + r.path("periodicidade").path("a").asInt() + " dias, B a cada "
                    + r.path("periodicidade").path("b").asInt() + ", C a cada " + r.path("periodicidade").path("c").asInt() + "; até "
                    + r.path("itensPorDia").asInt() + " por dia).");
        }, t -> contagemInfo.setText("Não foi possível carregar: " + Fx.message(t)));
    }

    private Map<String, BigDecimal> digitados() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (LinhaContagem l : contagem.getItems()) {
            String v = l.contado.get() == null ? "" : l.contado.get().trim();
            if (!v.isEmpty()) {
                m.put(l.j.path("produtoId").asText(), Fmt.parse(v));
            }
        }
        return m;
    }

    private void registrar(boolean aplicar) {
        Map<String, BigDecimal> itens = digitados();
        if (itens.isEmpty()) {
            Toasts.error(janela(), "Digite a quantidade contada de pelo menos um produto.");
            return;
        }
        if (aplicar) {
            pedirSenhaEAplicar(itens);
            return;
        }
        rodar(() -> abrirEContar(itens), id -> {
            sucesso("Contagem registrada no inventário (" + itens.size() + " produtos). O gerente aplica o acerto na tela Inventário.");
            gerarLista();
        });
    }

    /** Abre o inventário (escopo produtos) e lança a contagem de cada item digitado. Devolve o id do inventário. */
    private String abrirEContar(Map<String, BigDecimal> itens) {
        JsonNode inv = ctx.inventarios.abrir("Contagem cíclica " + Fmt.date(LocalDate.now()), "produtos", null, new ArrayList<>(itens.keySet()), ctx.terminal());
        String id = inv.path("id").asText(inv.path("Id").asText());
        for (Map.Entry<String, BigDecimal> e : itens.entrySet()) {
            ctx.inventarios.contar(id, e.getKey(), null, e.getValue(), "Contagem cíclica", ctx.terminal());
        }
        return id;
    }

    private void pedirSenhaEAplicar(Map<String, BigDecimal> itens) {
        PasswordField senha = new PasswordField();
        senha.setPromptText("Senha do gerente");
        TextField justificativa = Ui.campo("Justificativa");
        justificativa.setText("Contagem cíclica");
        VBox corpo = new VBox(8, Ui.muted("Serão contados e ajustados " + itens.size() + " produtos. O acerto respeita vendas e entradas feitas depois da contagem."),
                Ui.rotulado("Justificativa", justificativa), Ui.rotulado("Senha de " + ctx.session.user().name() + " (gerente)", senha));
        Modal[] m = new Modal[1];
        m[0] = Modal.formulario(janela(), "Aplicar contagem cíclica", corpo, "Contar e aplicar", 480, () -> {
            String j = justificativa.getText() == null ? "" : justificativa.getText().trim();
            if (j.length() < 5) {
                m[0].erro("Informe uma justificativa de no mínimo 5 caracteres.");
                return false;
            }
            if (senha.getText().isEmpty()) {
                m[0].erro("Informe a senha do gerente.");
                return false;
            }
            m[0].ocupado(true);
            Carregando.async(() -> {
                String id = abrirEContar(itens);
                return ctx.inventarios.aplicar(id, ctx.session.user().id(), senha.getText(), j, false, ctx.terminal());
            }, r -> {
                m[0].ocupado(false);
                m[0].fechar();
                ctx.catalogo.invalidar();
                sucesso("Contagem aplicada: " + r.path("ajustados").asInt() + " produtos ajustados.");
                gerarLista();
                carregar();
            }, t -> {
                m[0].ocupado(false);
                senha.clear();
                m[0].erro(Fx.message(t));
            });
            return false;
        });
        m[0].mostrar();
    }

    // ---------- apoio dos testes ----------

    public int itensNaLista() {
        return contagem.getItems().size();
    }

    /** Preenche o "contado" dos primeiros N itens com a quantidade dada e devolve os ids. */
    public List<String> preencher(int n, String quantidade) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(n, contagem.getItems().size()); i++) {
            contagem.getItems().get(i).contado.set(quantidade);
            ids.add(contagem.getItems().get(i).j.path("produtoId").asText());
        }
        return ids;
    }

    /** Registra sem aplicar (mesmo caminho do botão). */
    public void registrarSemAplicar() {
        registrar(false);
    }

    public List<String> acuraciasNoGrafico() {
        return new ArrayList<>(cartoes.getChildren().stream().filter(n -> n instanceof VBox).map(n -> ((Label) ((VBox) n).getChildren().get(1)).getText()).toList());
    }
}
