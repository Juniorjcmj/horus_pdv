package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.Csv;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.service.PrintService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Relatórios de apoio à compra (curva ABC, crítico, parado, validade e perdas), calculados no servidor. Cada aba tem filtros,
 * totais, exportação CSV (; e formato brasileiro) e impressão em A4 pelo driver do Windows.
 */
public class RelatoriosView extends Tela {

    /** Coluna de um relatório: título, como extrair o texto do JSON, alinhamento e pesos de tela/impressão. */
    public record Col(String titulo, Function<JsonNode, String> valor, boolean numero, double largura, double peso) {
    }

    private final Perfil perfil;
    private final TabPane abas = new TabPane();
    private final List<Painel> paineis = new ArrayList<>();
    private List<CadastroApi.Categoria> categorias = new ArrayList<>();

    public RelatoriosView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        getChildren().add(Ui.cabecalho("Relatórios",
                perfil.relatoriosDeValor() ? "Curva ABC, estoque crítico e parado, validade e perdas. Exporte em CSV ou imprima."
                        : "Estoque crítico e validade (sem valores). Exporte em CSV ou imprima."));
        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(abas, Priority.ALWAYS);
        getChildren().add(abas);

        if (perfil.relatoriosDeValor()) {
            ComboBox<CadastroApi.Categoria> cat = Ui.combo(new ArrayList<>());
            cat.setPromptText("Todas as categorias");
            DatePicker de = new DatePicker(LocalDate.now().minusDays(89));
            DatePicker ate = new DatePicker(LocalDate.now());
            Painel curva = new Painel("Curva ABC", Ui.barra(Ui.rotulado("De", de), Ui.rotulado("Até", ate), Ui.rotulado("Categoria", cat)),
                    () -> {
                        if (de.getValue() == null || ate.getValue() == null) {
                            throw new DomainException("Informe o período.");
                        }
                        return ctx.relatorios.curvaAbc(de.getValue(), ate.getValue(), cat.getValue() == null ? null : cat.getValue().id());
                    }, colunasCurva(), r -> r.path("itens"), RelatoriosView::totalCurva, "curva-abc");
            curva.aoAtivar = () -> cat.setItems(FXCollections.observableArrayList(categorias));
            adicionar(curva);
        }
        adicionar(new Painel("Estoque crítico", null, ctx.relatorios::critico, colunasCritico(perfil.verCustos()), r -> r,
                r -> r.size() + " produtos zerados ou abaixo do mínimo", "estoque-critico"));
        if (perfil.relatoriosDeValor()) {
            TextField dias = Ui.campo("60");
            dias.setText("60");
            dias.setPrefWidth(80);
            adicionar(new Painel("Estoque parado", Ui.barra(Ui.rotulado("Sem venda há (dias)", dias)),
                    () -> ctx.relatorios.parado(Ui.numero(dias, "os dias").intValue()), colunasParado(), r -> r,
                    r -> {
                        double soma = 0;
                        for (JsonNode n : r) {
                            soma += n.path("valorParado").asDouble(0);
                        }
                        return r.size() + " produtos parados · valor parado pelo custo " + Fmt.money(java.math.BigDecimal.valueOf(soma).setScale(2,
                                java.math.RoundingMode.HALF_UP));
                    }, "estoque-parado"));
        }
        TextField diasVal = Ui.campo("30");
        diasVal.setText("30");
        diasVal.setPrefWidth(80);
        adicionar(new Painel("Validade", Ui.barra(Ui.rotulado("Vence em até (dias)", diasVal)),
                () -> ctx.relatorios.validade(Ui.numero(diasVal, "os dias").intValue()), colunasValidade(perfil.verCustos()), r -> r,
                r -> r.size() + " lotes vencidos ou a vencer", "validade"));
        if (perfil.relatoriosDeValor()) {
            DatePicker de = new DatePicker(LocalDate.now().withDayOfMonth(1));
            DatePicker ate = new DatePicker(LocalDate.now());
            adicionar(new Painel("Perdas", Ui.barra(Ui.rotulado("De", de), Ui.rotulado("Até", ate)),
                    () -> ctx.relatorios.perdas(de.getValue(), ate.getValue()), colunasPerdas(), r -> r.path("itens"),
                    r -> "Quantidade total " + Fmt.qty(r.path("quantidadeTotal").decimalValue()) + " · valor pelo custo "
                            + Fmt.money(r.path("valorTotal").decimalValue()), "perdas"));
        }
        abas.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            int i = abas.getTabs().indexOf(b);
            if (i >= 0 && paineis.get(i).vazio()) {
                paineis.get(i).carregar();
            }
        });
    }

    private void adicionar(Painel p) {
        paineis.add(p);
        abas.getTabs().add(new Tab(p.titulo, p));
    }

    @Override
    public void ativar() {
        rodar(ctx.cadastros::categorias, cats -> {
            categorias = CadastrosView.ordenar(cats.stream().filter(CadastroApi.Categoria::ativa).toList());
            paineis.forEach(p -> p.aoAtivar.run());
        });
        int i = abas.getSelectionModel().getSelectedIndex();
        if (i >= 0 && !paineis.isEmpty()) {
            paineis.get(i).carregar();
        }
    }

    // ------------------------------------------------------------------ colunas (também usadas pelo CSV e pelos testes)

    private static String n(JsonNode r, String campo) {
        return r.hasNonNull(campo) ? Fmt.qty(r.get(campo).decimalValue()) : "";
    }

    private static String din(JsonNode r, String campo) {
        return r.hasNonNull(campo) ? Fmt.dec2(r.get(campo).decimalValue()) : "";
    }

    private static String t(JsonNode r, String campo) {
        return r.path(campo).asText("");
    }

    public static List<Col> colunasCurva() {
        return List.of(new Col("Classe", r -> t(r, "classe"), false, 60, 6), new Col("Código", r -> t(r, "codigo"), false, 90, 10),
                new Col("Produto", r -> t(r, "nome"), false, 260, 34), new Col("Qtd. vendida", r -> n(r, "quantidade"), true, 90, 11),
                new Col("Faturamento (R$)", r -> din(r, "faturamento"), true, 110, 14), new Col("Part. %", r -> din(r, "participacao"), true, 70, 8),
                new Col("Acum. %", r -> din(r, "participacaoAcumulada"), true, 70, 8), new Col("Estoque", r -> n(r, "saldo"), true, 70, 9),
                new Col("Cobertura (dias)", r -> r.hasNonNull("coberturaDias") ? n(r, "coberturaDias") : "—", true, 100, 10));
    }

    public static List<Col> colunasCritico(boolean valores) {
        List<Col> c = new ArrayList<>(List.of(new Col("Código", r -> t(r, "codigo"), false, 90, 10),
                new Col("Produto", r -> t(r, "nome"), false, 250, 32), new Col("Fornecedor", r -> t(r, "fornecedor"), false, 140, 18),
                new Col("Estoque", r -> n(r, "saldo"), true, 70, 9), new Col("Mínimo", r -> n(r, "minimo"), true, 70, 9),
                new Col("Faltam", r -> n(r, "faltam"), true, 70, 9), new Col("Situação", r -> t(r, "situacao"), false, 110, 13)));
        if (valores) {
            c.add(new Col("Custo p/ repor (R$)", r -> din(r, "custoReposicao"), true, 120, 14));
        }
        return c;
    }

    public static List<Col> colunasParado() {
        return List.of(new Col("Código", r -> t(r, "codigo"), false, 90, 10), new Col("Produto", r -> t(r, "nome"), false, 250, 32),
                new Col("Estoque", r -> n(r, "saldo"), true, 70, 9),
                new Col("Última venda", r -> r.path("nuncaVendido").asBoolean(false) ? "nunca vendido" : Fmt.dateTime(t(r, "ultimaVenda")), false, 110, 13),
                new Col("Dias sem venda", r -> r.hasNonNull("diasSemVenda") ? String.valueOf(r.get("diasSemVenda").asInt()) : "—", true, 100, 11),
                new Col("Última entrada", r -> Fmt.dateTime(t(r, "ultimaEntrada")), false, 110, 13),
                new Col("Valor parado (R$)", r -> din(r, "valorParado"), true, 120, 14));
    }

    public static List<Col> colunasValidade(boolean valores) {
        List<Col> c = new ArrayList<>(List.of(new Col("Código", r -> t(r, "codigo"), false, 90, 10),
                new Col("Produto", r -> t(r, "nome"), false, 250, 32), new Col("Lote", r -> t(r, "numeroLote"), false, 90, 10),
                new Col("Validade", r -> Fmt.dateOnly(t(r, "dataValidade")), false, 90, 11),
                new Col("Dias restantes", r -> r.path("vencido").asBoolean(false) ? "VENCIDO (" + r.path("diasRestantes").asInt() + ")"
                        : String.valueOf(r.path("diasRestantes").asInt()), true, 110, 12), new Col("Quantidade", r -> n(r, "quantidade"), true, 80, 9)));
        if (valores) {
            c.add(new Col("Valor (R$)", r -> din(r, "valor"), true, 100, 12));
        }
        return c;
    }

    public static List<Col> colunasPerdas() {
        return List.of(new Col("Data", r -> Fmt.dateTime(t(r, "data")), false, 110, 12), new Col("Código", r -> t(r, "codigo"), false, 90, 10),
                new Col("Produto", r -> t(r, "nome"), false, 220, 26), new Col("Motivo", r -> t(r, "motivo"), false, 200, 22),
                new Col("Usuário", r -> t(r, "usuario"), false, 110, 12), new Col("Quantidade", r -> n(r, "quantidade"), true, 80, 9),
                new Col("Valor (R$)", r -> din(r, "valor"), true, 100, 12));
    }

    static String totalCurva(JsonNode r) {
        StringBuilder sb = new StringBuilder("Faturamento do período " + Fmt.money(r.path("faturamentoTotal").decimalValue()));
        for (JsonNode c : r.path("classes")) {
            sb.append(" · ").append(c.path("classe").asText()).append(": ").append(c.path("produtos").asInt()).append(" produtos (")
                    .append(Fmt.dec2(c.path("participacao").decimalValue())).append("%)");
        }
        return sb.toString();
    }

    /** Linhas de texto na ordem das colunas: o mesmo conteúdo da tela, do CSV e da impressão. */
    public static List<List<String>> linhas(List<Col> colunas, Iterable<JsonNode> itens) {
        List<List<String>> out = new ArrayList<>();
        for (JsonNode i : itens) {
            List<String> l = new ArrayList<>();
            for (Col c : colunas) {
                l.add(c.valor().apply(i));
            }
            out.add(l);
        }
        return out;
    }

    // ------------------------------------------------------------------ painel de um relatório

    private final class Painel extends VBox {
        final String titulo;
        final String arquivo;
        final java.util.function.Supplier<JsonNode> consulta;
        final List<Col> colunas;
        final Function<JsonNode, JsonNode> itens;
        final Function<JsonNode, String> totais;
        final TableView<JsonNode> tabela = Ui.tabela();
        final Label total = Ui.texto("", "rg-section-title");
        Runnable aoAtivar = () -> {
        };
        private JsonNode ultimo;
        private List<JsonNode> exibidos = new ArrayList<>();

        Painel(String titulo, Node filtros, java.util.function.Supplier<JsonNode> consulta, List<Col> colunas,
                Function<JsonNode, JsonNode> itens, Function<JsonNode, String> totais, String arquivo) {
            super(10);
            this.titulo = titulo;
            this.arquivo = arquivo;
            this.consulta = consulta;
            this.colunas = colunas;
            this.itens = itens;
            this.totais = totais;
            setPadding(Ui.margem(10));
            for (Col c : colunas) {
                if (c.numero()) {
                    Ui.colNum(tabela, c.titulo(), c.valor(), c.largura());
                } else {
                    Ui.col(tabela, c.titulo(), c.valor(), c.largura());
                }
            }
            javafx.scene.layout.HBox barra = Ui.barra();
            if (filtros != null) {
                barra.getChildren().add(filtros);
            }
            barra.getChildren().addAll(Ui.espaco(), Ui.primario("Gerar", this::carregar), Ui.botao("Exportar CSV", this::exportar),
                    Ui.botao("Imprimir", this::imprimir));
            getChildren().addAll(barra, tabela, total);
        }

        boolean vazio() {
            return ultimo == null;
        }

        void carregar() {
            Fx.async(consulta::get, r -> {
                ultimo = r;
                exibidos = new ArrayList<>();
                itens.apply(r).forEach(exibidos::add);
                tabela.setItems(FXCollections.observableArrayList(exibidos));
                total.setText(totais.apply(r));
            }, e -> Toasts.error(Ui.janela(this), Fx.message(e)));
        }

        void exportar() {
            if (exibidos.isEmpty()) {
                Toasts.error(Ui.janela(this), "Gere o relatório antes de exportar.");
                return;
            }
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(arquivo + ".csv");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            File f = fc.showSaveDialog(Ui.janela(this));
            if (f == null) {
                return;
            }
            try {
                Csv.save(f.toPath(), colunas.stream().map(Col::titulo).toList(), linhas(colunas, exibidos));
                Toasts.success(Ui.janela(this), "CSV gravado em " + f.getName() + ".");
            } catch (IOException e) {
                Toasts.error(Ui.janela(this), "Não foi possível gravar: " + e.getMessage());
            }
        }

        void imprimir() {
            if (exibidos.isEmpty()) {
                Toasts.error(Ui.janela(this), "Gere o relatório antes de imprimir.");
                return;
            }
            var doc = Documento.tabela(titulo, List.of("Emitido em " + Fmt.date(LocalDate.now()) + " por " + ctx.session.user().name()),
                    colunas.stream().map(c -> new Documento.Col(c.titulo(), c.peso(), c.numero())).toList(), linhas(colunas, exibidos),
                    List.of(total.getText()));
            PrintService.imprimirDocumento(doc, "", erro -> {
                if (erro != null) {
                    Toasts.error(Ui.janela(this), erro);
                } else {
                    Toasts.success(Ui.janela(this), "Relatório enviado para a impressora.");
                }
            });
        }
    }
}
