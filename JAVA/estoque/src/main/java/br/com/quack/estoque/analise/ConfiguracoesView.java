package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.ui.Carregando;
import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Toasts;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Configurações da análise: janela de consumo, nível de serviço (Z), limites ABC/FSN, dias de parado e excesso, cobertura alvo,
 * lead time padrão, periodicidade da contagem e metas do semáforo. Cada campo mostra o padrão ao lado; "Restaurar padrão" apaga
 * a configuração da empresa. O estoquista vê os valores, sem editar (o servidor também recusa).
 */
public class ConfiguracoesView extends Tela {
    private record Campo(String chave, String rotulo, String ajuda, boolean inteiro) {
    }

    private static final List<Campo> GERAIS = List.of(
            new Campo("janelaConsumoDias", "Janela de consumo (dias)", "Período usado para o consumo médio e o desvio da demanda.", true),
            new Campo("nivelServicoZ", "Nível de serviço (Z)", "1,65 ≈ 95% de chance de não faltar durante o lead time.", false),
            new Campo("leadTimePadraoDias", "Lead time padrão (dias)", "Usado quando o produto e o fornecedor não têm prazo.", true),
            new Campo("coberturaAlvoDias", "Cobertura alvo (dias)", "Quantos dias de venda a reposição quer cobrir além do lead time.", true),
            new Campo("diasParado", "Parado após (dias sem venda)", "Produto com saldo e sem venda há mais que isso é \"parado\".", true),
            new Campo("diasExcesso", "Excesso acima de (dias de cobertura)", "Acima disso o que sobra é valor em excesso.", true));

    private static final List<Campo> CLASSIFICACAO = List.of(
            new Campo("abcLimiteA", "Limite da classe A (%)", "Faturamento acumulado até este valor é A.", false),
            new Campo("abcLimiteB", "Limite da classe B (%)", "Até este valor é B; acima é C.", false),
            new Campo("fsnLimiteFast", "FSN: rápido com saída em (% das semanas)", "Saída em ao menos esta fração das semanas = F; menos = S; nenhuma = N.", false));

    private static final List<Campo> CONTAGEM = List.of(
            new Campo("contagemDiasA", "Contar classe A a cada (dias)", "", true),
            new Campo("contagemDiasB", "Contar classe B a cada (dias)", "", true),
            new Campo("contagemDiasC", "Contar classe C a cada (dias)", "", true),
            new Campo("contagemItensDia", "Itens por dia na contagem cíclica", "", true));

    private static final List<String[]> METAS = List.of(
            new String[] {"giroAnualizado", "Giro anualizado (vezes/ano) · maior é melhor"},
            new String[] {"coberturaMediaDias", "Cobertura média (dias) · menor é melhor"},
            new String[] {"emRupturaAgoraPercentual", "Em ruptura agora (%) · menor é melhor"},
            new String[] {"taxaRupturaPercentual", "Taxa de ruptura (%) · menor é melhor"},
            new String[] {"acuracidade", "Acuracidade (%) · maior é melhor"});

    private final Map<String, TextField> campos = new LinkedHashMap<>();
    private final Map<String, Label> padroes = new LinkedHashMap<>();
    private final Map<String, TextField[]> metaCampos = new LinkedHashMap<>();
    private final Map<String, Label> metaPadroes = new LinkedHashMap<>();
    private final ComboBox<String> abcBase = new ComboBox<>(javafx.collections.FXCollections.observableArrayList("faturamento", "cmv"));
    private final Label status = Ui.muted("");
    private boolean podeEditar;

    public ConfiguracoesView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        VBox corpo = new VBox(14, secao("Consumo e reposição", GERAIS), baseAbc(), secao("Classificação ABC e FSN", CLASSIFICACAO),
                secao("Contagem cíclica", CONTAGEM), metas());
        corpo.setPadding(Ui.margem(4));
        ScrollPane rolagem = new ScrollPane(corpo);
        rolagem.setFitToWidth(true);
        rolagem.getStyleClass().add("rg-scroll-limpo");
        VBox.setVgrow(rolagem, Priority.ALWAYS);
        rolagem.setMinHeight(0);

        boolean gerencia = ctx.session.perfil().gerencia();
        HBox botoes = new HBox(8);
        if (gerencia) {
            botoes.getChildren().addAll(Ui.primario("Salvar", this::salvar), Ui.botao("Restaurar padrão", this::restaurar));
        }
        getChildren().addAll(Ui.cabecalho("Análise · Configurações", gerencia
                ? "Parâmetros dos indicadores desta empresa. Ao salvar, os números e a classificação ABC/FSN são recalculados."
                : "Parâmetros dos indicadores desta empresa (somente leitura para o estoquista).", botoes), status, rolagem);
    }

    private VBox secao(String titulo, List<Campo> lista) {
        GridPane g = grade();
        int linha = 0;
        for (Campo c : lista) {
            TextField tf = Ui.campo("");
            tf.setPrefWidth(110);
            tf.setId("cfg-" + c.chave());
            campos.put(c.chave(), tf);
            Label padrao = Ui.muted("");
            padrao.setWrapText(false);
            padroes.put(c.chave(), padrao);
            Label rotulo = Ui.texto(c.rotulo(), "rg-form-label");
            rotulo.setWrapText(true);
            g.add(rotulo, 0, linha);
            g.add(tf, 1, linha);
            g.add(padrao, 2, linha);
            if (!c.ajuda().isBlank()) {
                g.add(Ui.muted(c.ajuda()), 3, linha);
            }
            linha++;
        }
        VBox v = new VBox(8, Ui.texto(titulo, "rg-section-title"), g);
        v.getStyleClass().add("rg-card");
        return v;
    }

    private VBox baseAbc() {
        abcBase.setId("cfg-abcBase");
        abcBase.setMinWidth(130);
        GridPane g = grade();
        g.add(Ui.texto("Base da curva ABC", "rg-form-label"), 0, 0);
        g.add(abcBase, 1, 0);
        Label padrao = Ui.muted("");
        padrao.setWrapText(false);
        padroes.put("abcBase", padrao);
        g.add(padrao, 2, 0);
        g.add(Ui.muted("Faturamento (vendas) ou CMV (custo das vendas)."), 3, 0);
        VBox v = new VBox(8, g);
        v.getStyleClass().add("rg-card");
        return v;
    }

    private VBox metas() {
        GridPane g = grade();
        g.add(Ui.texto("KPI", "rg-form-label"), 0, 0);
        g.add(Ui.texto("Verde", "rg-form-label"), 1, 0);
        g.add(Ui.texto("Amarelo", "rg-form-label"), 2, 0);
        int linha = 1;
        for (String[] m : METAS) {
            TextField verde = Ui.campo("");
            TextField amarelo = Ui.campo("");
            verde.setPrefWidth(90);
            amarelo.setPrefWidth(90);
            verde.setId("meta-" + m[0] + "-verde");
            amarelo.setId("meta-" + m[0] + "-amarelo");
            metaCampos.put(m[0], new TextField[] {verde, amarelo});
            Label padrao = Ui.muted("");
            padrao.setWrapText(false);
            metaPadroes.put(m[0], padrao);
            g.add(Ui.texto(m[1], "rg-form-label"), 0, linha);
            g.add(verde, 1, linha);
            g.add(amarelo, 2, linha);
            g.add(padrao, 3, linha);
            linha++;
        }
        VBox v = new VBox(8, Ui.texto("Metas do semáforo", "rg-section-title"),
                Ui.muted("Verde: dentro da meta. Amarelo: atenção. Além do amarelo fica vermelho."), g);
        v.getStyleClass().add("rg-card");
        return v;
    }

    private static GridPane grade() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(8);
        javafx.scene.layout.ColumnConstraints rotulo = new javafx.scene.layout.ColumnConstraints(250, 270, 320);
        javafx.scene.layout.ColumnConstraints valor = new javafx.scene.layout.ColumnConstraints(100, 110, 130);
        javafx.scene.layout.ColumnConstraints padrao = new javafx.scene.layout.ColumnConstraints(110, 130, 200);
        javafx.scene.layout.ColumnConstraints ajuda = new javafx.scene.layout.ColumnConstraints(200, 300, Double.MAX_VALUE);
        ajuda.setHgrow(Priority.ALWAYS);
        g.getColumnConstraints().addAll(rotulo, valor, padrao, ajuda);
        return g;
    }

    @Override
    public void ativar() {
        status.setText("Carregando…");
        Carregando.async(() -> ctx.analise.config(), this::mostrar, t -> status.setText("Não foi possível carregar: " + Fx.message(t)));
    }

    private void mostrar(JsonNode r) {
        podeEditar = r.path("podeEditar").asBoolean(false) && ctx.session.perfil().gerencia();
        JsonNode atual = r.path("atual");
        JsonNode padrao = r.path("padrao");
        campos.forEach((chave, tf) -> {
            tf.setText(numero(atual.path(chave)));
            tf.setEditable(podeEditar);
            tf.setDisable(!podeEditar);
            padroes.get(chave).setText("padrão " + numero(padrao.path(chave)));
        });
        abcBase.setValue(atual.path("abcBase").asText("faturamento"));
        abcBase.setDisable(!podeEditar);
        padroes.get("abcBase").setText("padrão " + padrao.path("abcBase").asText());
        metaCampos.forEach((chave, tfs) -> {
            tfs[0].setText(numero(atual.path("metas").path(chave).path("verde")));
            tfs[1].setText(numero(atual.path("metas").path(chave).path("amarelo")));
            tfs[0].setDisable(!podeEditar);
            tfs[1].setDisable(!podeEditar);
            metaPadroes.get(chave).setText("padrão " + numero(padrao.path("metas").path(chave).path("verde")) + " / "
                    + numero(padrao.path("metas").path(chave).path("amarelo")));
        });
        status.setText(podeEditar ? "" : "Somente leitura.");
    }

    private static String numero(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? "" : Fmt.qty(n.decimalValue());
    }

    private Map<String, Object> corpoDoFormulario() {
        Map<String, Object> body = new LinkedHashMap<>();
        campos.forEach((chave, tf) -> {
            BigDecimal v = Ui.numero(tf, "\"" + chave + "\"");
            boolean inteiro = (GERAIS.stream().anyMatch(c -> c.chave().equals(chave) && c.inteiro()))
                    || CONTAGEM.stream().anyMatch(c -> c.chave().equals(chave));
            body.put(chave, inteiro ? (Object) v.intValue() : v);
        });
        body.put("abcBase", abcBase.getValue());
        Map<String, Object> metas = new LinkedHashMap<>();
        metaCampos.forEach((chave, tfs) -> metas.put(chave, Map.of("verde", Ui.numero(tfs[0], "a meta verde"), "amarelo", Ui.numero(tfs[1], "a meta amarela"))));
        body.put("metas", metas);
        return body;
    }

    private void salvar() {
        Map<String, Object> body;
        try {
            body = corpoDoFormulario();
        } catch (RuntimeException e) {
            Toasts.error(janela(), Fx.message(e));
            return;
        }
        rodar(() -> ctx.analise.salvarConfig(body), r -> {
            mostrar(r);
            sucesso("Configurações salvas. Os indicadores já usam os novos valores.");
        });
    }

    private void restaurar() {
        if (!Fx.confirm(janela(), "Restaurar padrão", "Voltar todos os parâmetros da análise para os valores padrão?")) {
            return;
        }
        rodar(() -> ctx.analise.restaurarConfig(), r -> {
            mostrar(r);
            sucesso("Configurações restauradas para o padrão.");
        });
    }

    // ---------- apoio dos testes ----------

    public void definir(String chave, String valor) {
        campos.get(chave).setText(valor);
    }

    public String valor(String chave) {
        return campos.get(chave).getText();
    }

    public boolean editavel() {
        return podeEditar;
    }

    public void salvarAgora() {
        salvar();
    }

    public void restaurarSemConfirmar() {
        rodar(() -> ctx.analise.restaurarConfig(), this::mostrar);
    }
}
