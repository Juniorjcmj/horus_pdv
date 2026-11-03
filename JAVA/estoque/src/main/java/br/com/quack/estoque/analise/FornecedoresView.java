package br.com.quack.estoque.analise;

import br.com.quack.estoque.AppContext;
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
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Fornecedores: prazo de entrega prometido x real (média dos pedidos recebidos), desvio, % no prazo e produtos vinculados.
 * Fornecedor sem pedido recebido mostra o prazo real vazio. O lead time real alimenta o ponto de pedido da reposição.
 */
public class FornecedoresView extends Tela {
    private final FiltrosAnalise filtros;
    private final BlocoCard grafico = new BlocoCard("Prazo prometido × real", "Dias; os 8 fornecedores com mais pedidos recebidos", 620, 340);
    private final TableView<JsonNode> tabela = Ui.tabela();
    private final Label info = Ui.muted("");
    private int geracao;

    public FornecedoresView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.filtros = FiltrosAnalise.carregar(ctx.paths.analiseFile(), LocalDate.now());
        Ui.col(tabela, "Fornecedor", j -> j.path("nome").asText(), 220);
        Ui.colNum(tabela, "Prometido (dias)", j -> j.path("prometidoDias").isNull() ? "—" : String.valueOf(j.path("prometidoDias").asInt()), 100);
        Ui.colNum(tabela, "Real (dias)", j -> j.path("realDias").isNull() ? "—" : Fmt.qty(j.path("realDias").decimalValue()), 90);
        Ui.colNum(tabela, "Desvio (dias)", j -> j.path("desvioDias").isNull() ? "—" : Fmt.qty(j.path("desvioDias").decimalValue()), 90);
        Ui.colNum(tabela, "Atraso médio (dias)", j -> j.path("atrasoDias").isNull() ? "—" : Fmt.qty(j.path("atrasoDias").decimalValue()), 110);
        Ui.colNum(tabela, "Pedidos recebidos", j -> String.valueOf(j.path("pedidos").asInt()), 100);
        Ui.colNum(tabela, "No prazo", j -> j.path("noPrazoPercentual").isNull() ? "—" : AnaliseFmt.valor("%", j.path("noPrazoPercentual")), 80);
        Ui.colNum(tabela, "Produtos", j -> String.valueOf(j.path("produtos").asInt()), 80);
        Ui.distribuir(tabela);
        VBox.setVgrow(tabela, Priority.ALWAYS);
        tabela.setMinHeight(220);
        getChildren().addAll(Ui.cabecalho("Análise · Fornecedores", "Quanto cada fornecedor demora de fato para entregar, comparado ao prometido.",
                Ui.botao("Atualizar", this::carregar)), info, new FlowPane(12, 12, grafico), tabela);
    }

    @Override
    public void ativar() {
        carregar();
    }

    private void carregar() {
        int minha = ++geracao;
        grafico.carregando();
        Carregando.async(() -> ctx.analise.bloco("fornecedores", filtros), r -> {
            if (minha == geracao) {
                mostrar(r.path("dados"), r.path("calculadoEm").asText(null));
            }
        }, t -> {
            if (minha == geracao) {
                grafico.erro(Fx.message(t));
            }
        });
    }

    private void mostrar(JsonNode d, String quando) {
        List<JsonNode> todos = new ArrayList<>();
        d.path("fornecedores").forEach(todos::add);
        tabela.setItems(FXCollections.observableArrayList(todos));
        info.setText(d.path("vazio").asBoolean(true) ? "Nenhum fornecedor cadastrado." : todos.size() + " fornecedores · " + AnaliseFmt.calculadoEm(quando));

        List<JsonNode> comReal = todos.stream().filter(j -> !j.path("realDias").isNull()).limit(8).toList();
        if (comReal.isEmpty()) {
            grafico.vazio("Nenhum pedido de compra recebido ainda: o prazo real aparece depois do primeiro recebimento.");
            return;
        }
        List<String> nomes = new ArrayList<>();
        double[] prometido = new double[comReal.size()];
        double[] real = new double[comReal.size()];
        for (int i = 0; i < comReal.size(); i++) {
            nomes.add(AnaliseFmt.curto(comReal.get(i).path("nome").asText(), 16));
            prometido[i] = comReal.get(i).path("prometidoDias").asDouble(0);
            real[i] = comReal.get(i).path("realDias").asDouble();
        }
        grafico.mostrar(Graficos.barrasVerticais(nomes, List.of("Prometido", "Real"), List.of(prometido, real), "dias", false), AnaliseFmt.calculadoEm(quando));
    }

    // ---------- apoio dos testes ----------

    public int linhas() {
        return tabela.getItems().size();
    }

    public String primeiraLinha() {
        return tabela.getItems().isEmpty() ? "" : tabela.getItems().get(0).path("nome").asText() + "|" + tabela.getItems().get(0).path("pedidos").asInt();
    }
}
