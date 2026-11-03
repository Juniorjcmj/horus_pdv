package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Promoções, como a tela da versão web: indicadores, busca e filtro, lista das campanhas, cadastro/edição (6 tipos de
 * regra, vigência, categoria e produtos), ativar/desativar, excluir e desempenho. Escrita: gerente/administrador.
 */
public class PromocoesView extends Tela {
    static final Map<String, String> TIPOS = new LinkedHashMap<>();

    static {
        TIPOS.put("desconto_percentual", "Desconto percentual (%)");
        TIPOS.put("desconto_valor", "Desconto em dinheiro (R$)");
        TIPOS.put("preco_fixo", "Preço promocional fixo");
        TIPOS.put("leve_x_pague_y", "Leve X, pague Y");
        TIPOS.put("combo_quantidade", "Combo por quantidade (ex.: 3 por R$ 10)");
        TIPOS.put("preco_atacado", "Preço de atacado (por volume)");
    }

    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private final Perfil perfil;
    private final TableView<JsonNode> tabela = Ui.tabela();
    private final TextField busca = Ui.campo("Buscar promoção pelo nome");
    private final ComboBox<String> filtro = Ui.combo(List.of("Todas", "Ativas", "Encerradas"));
    private final Label kTotal = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label kAtivas = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Label kProdutos = Ui.texto("0", "kpi-value", "rg-stat-value");
    private final Button nova = Ui.primario("Nova promoção", () -> abrirForm(null));
    private final Button editar = Ui.botao("Editar", () -> comSelecionada(this::abrirForm));
    private final Button ativar = Ui.botao("Ativar / desativar", () -> comSelecionada(this::alternar));
    private final Button resultado = Ui.botao("Desempenho", () -> comSelecionada(this::verResultado));
    private final Button excluir = Ui.perigo("Excluir", () -> comSelecionada(this::excluir));
    private final Carregando carregando;
    private List<JsonNode> todas = new ArrayList<>();
    private List<CadastroApi.Categoria> categorias = new ArrayList<>();

    public PromocoesView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        boolean gerencia = perfil.gerencia();
        for (Button b : new Button[] {nova, editar, ativar, excluir}) {
            b.setVisible(gerencia);
            b.setManaged(gerencia);
        }
        getChildren().add(Ui.cabecalho("Promoções",
                "Campanhas de desconto e preço dinâmico aplicadas no caixa; acompanhe o desempenho de cada uma.",
                Ui.botao("Atualizar", this::ativar), nova));

        HBox kpis = new HBox(12, cartao(kTotal, "TOTAL DE CAMPANHAS"), cartao(kAtivas, "PROMOÇÕES ATIVAS"),
                cartao(kProdutos, "PRODUTOS EM OFERTA"));
        getChildren().add(kpis);

        filtro.setValue("Todas");
        filtro.setOnAction(e -> filtrar());
        busca.textProperty().addListener((o, a, b) -> filtrar());
        busca.setPrefWidth(320);
        getChildren().add(Ui.barra(Ui.rotulado("Buscar", busca), Ui.rotulado("Situação", filtro)));

        Ui.ajustar(Ui.col(tabela, "Promoção", j -> j.path("nome").asText(), 240), 160, null);
        Ui.ajustar(Ui.col(tabela, "Regra", PromocoesView::detalhe, 230), 160, null);
        Ui.ajustar(Ui.col(tabela, "Tipo", j -> TIPOS.getOrDefault(j.path("tipo").asText(), j.path("tipo").asText()), 180), 120, null);
        Ui.ajustar(Ui.col(tabela, "Vigência", j -> dataHora(j.path("inicioVigencia").asText()) + " → "
                + dataHora(j.path("fimVigencia").asText()), 250), 220, null);
        Ui.ajustar(Ui.col(tabela, "Aplica em", this::alvo, 200), 140, null);
        Ui.ajustar(Ui.col(tabela, "Situação", j -> situacao(j, OffsetDateTime.now()), 100), 90, null);
        Ui.distribuir(tabela);
        VBox.setVgrow(tabela, Priority.ALWAYS);
        tabela.setPlaceholder(Ui.muted("Nenhuma promoção encontrada. Crie uma campanha ou altere os filtros."));
        tabela.setRowFactory(t -> {
            TableRow<JsonNode> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    if (perfil.gerencia()) {
                        abrirForm(row.getItem());
                    } else {
                        verResultado(row.getItem());
                    }
                }
            });
            return row;
        });
        carregando = new Carregando(tabela, nova, editar, ativar, resultado, excluir);
        getChildren().addAll(carregando, Ui.barra(editar, ativar, resultado, Ui.espaco(), excluir));
    }

    private static VBox cartao(Label valor, String rotulo) {
        VBox c = new VBox(2, Ui.texto(rotulo, "kpi-label", "rg-stat-label"), valor);
        c.getStyleClass().add("rg-stat");
        c.setMinWidth(200);
        return c;
    }

    @Override
    public void ativar() {
        rodar(carregando, "Carregando promoções…", () -> {
            ctx.garantirCatalogo(false);
            return new Object[] {ctx.promocoes.listar(), ctx.cadastros.categorias()};
        }, r -> {
            List<JsonNode> l = new ArrayList<>();
            ((JsonNode) r[0]).forEach(l::add);
            todas = l;
            @SuppressWarnings("unchecked")
            List<CadastroApi.Categoria> cats = (List<CadastroApi.Categoria>) r[1];
            categorias = cats;
            filtrar();
        });
    }

    @Override
    public void focarBusca() {
        busca.requestFocus();
    }

    // ------------------------------------------------------------------ lista

    private void filtrar() {
        OffsetDateTime agora = OffsetDateTime.now();
        String q = busca.getText() == null ? "" : Fmt.normalizar(busca.getText().trim());
        String f = filtro.getValue() == null ? "Todas" : filtro.getValue();
        List<JsonNode> vis = todas.stream()
                .filter(j -> q.isEmpty() || Fmt.normalizar(j.path("nome").asText()).contains(q))
                .filter(j -> switch (f) {
                    case "Ativas" -> vigente(j, agora);
                    case "Encerradas" -> !j.path("ativa").asBoolean() || encerrada(j, agora);
                    default -> true;
                }).toList();
        tabela.setItems(FXCollections.observableArrayList(vis));
        kTotal.setText(String.valueOf(todas.size()));
        kAtivas.setText(String.valueOf(todas.stream().filter(j -> j.path("ativa").asBoolean() && !encerrada(j, agora)).count()));
        Set<String> emOferta = new HashSet<>();
        todas.stream().filter(j -> j.path("ativa").asBoolean() && !encerrada(j, agora))
                .forEach(j -> j.path("produtoIds").forEach(id -> emOferta.add(id.asText())));
        kProdutos.setText(String.valueOf(emOferta.size()));
    }

    public static OffsetDateTime instante(String iso) {
        try {
            return OffsetDateTime.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean encerrada(JsonNode j, OffsetDateTime agora) {
        OffsetDateTime fim = instante(j.path("fimVigencia").asText());
        return fim != null && fim.isBefore(agora);
    }

    static boolean vigente(JsonNode j, OffsetDateTime agora) {
        return j.path("ativa").asBoolean() && !encerrada(j, agora);
    }

    /** Ativa (no período), Agendada (ativa, ainda não começou), Encerrada (passou do fim) ou Inativa. */
    public static String situacao(JsonNode j, OffsetDateTime agora) {
        if (encerrada(j, agora)) {
            return "Encerrada";
        }
        if (!j.path("ativa").asBoolean()) {
            return "Inativa";
        }
        OffsetDateTime ini = instante(j.path("inicioVigencia").asText());
        return ini != null && ini.isAfter(agora) ? "Agendada" : "Ativa";
    }

    static String dataHora(String iso) {
        OffsetDateTime t = instante(iso);
        return t == null ? "—" : t.atZoneSameInstant(ZoneId.systemDefault()).format(DATA_HORA);
    }

    private static String dinheiro(JsonNode j, String campo) {
        return Fmt.money(j.hasNonNull(campo) ? j.get(campo).decimalValue() : BigDecimal.ZERO);
    }

    /** Regra em uma linha, como a web ("10% de desconto", "Leve 3, pague 2"...). */
    public static String detalhe(JsonNode j) {
        return switch (j.path("tipo").asText()) {
            case "desconto_percentual" -> Fmt.dec2(j.path("valorDesconto").decimalValue()) + "% de desconto";
            case "desconto_valor" -> dinheiro(j, "valorDesconto") + " de desconto";
            case "preco_fixo" -> "Preço fixo: " + dinheiro(j, "precoFixo");
            case "leve_x_pague_y" -> "Leve " + j.path("quantidadeLeva").asInt() + ", pague " + j.path("quantidadePaga").asInt();
            case "combo_quantidade" -> j.path("quantidadeMinima").asInt() + " unidades por " + dinheiro(j, "precoFixo");
            case "preco_atacado" -> "A partir de " + j.path("quantidadeMinima").asInt() + " un: " + dinheiro(j, "precoFixo") + " cada";
            default -> "—";
        };
    }

    private String alvo(JsonNode j) {
        int n = j.path("produtoIds").size();
        String cat = j.path("categoriaNome").asText("");
        if (!cat.isBlank()) {
            return "Categoria " + cat + (n > 0 ? " + " + n + " produto(s)" : "");
        }
        if (n == 1) {
            Produto p = ctx.catalogo.porId(j.path("produtoIds").get(0).asText());
            return p == null ? "1 produto" : p.nome();
        }
        return n + " produtos";
    }

    private void comSelecionada(java.util.function.Consumer<JsonNode> acao) {
        JsonNode j = tabela.getSelectionModel().getSelectedItem();
        if (j == null) {
            Toasts.error(janela(), "Selecione uma promoção na lista.");
            return;
        }
        acao.accept(j);
    }

    // ------------------------------------------------------------------ ações

    private void alternar(JsonNode j) {
        boolean novo = !j.path("ativa").asBoolean();
        rodar(carregando, novo ? "Ativando…" : "Desativando…", () -> {
            ctx.promocoes.definirAtiva(j.path("id").asText(), novo);
            return true;
        }, ok -> {
            sucesso("Promoção \"" + j.path("nome").asText() + "\" " + (novo ? "ativada." : "desativada."));
            ativar();
        });
    }

    private void excluir(JsonNode j) {
        Modal.confirmar(janela(), "Excluir promoção", "Excluir a promoção \"" + j.path("nome").asText()
                + "\"? Ela deixa de valer no caixa. Se só quer pausar, use Ativar / desativar.", "Excluir",
                () -> rodar(carregando, "Excluindo…", () -> {
                    ctx.promocoes.excluir(j.path("id").asText());
                    return true;
                }, ok -> {
                    sucesso("Promoção excluída.");
                    ativar();
                }));
    }

    private void verResultado(JsonNode j) {
        rodar(carregando, "Calculando o desempenho…", () -> ctx.promocoes.resultado(j.path("id").asText()), r -> {
            int vendas = r.path("quantidadeVendas").asInt();
            BigDecimal desconto = r.path("descontoTotal").decimalValue();
            GridPane g = new GridPane();
            g.setHgap(24);
            g.setVgap(10);
            g.add(stat("VENDAS IMPACTADAS", String.valueOf(vendas)), 0, 0);
            g.add(stat("DESCONTO CONCEDIDO", Fmt.money(desconto)), 1, 0);
            g.add(stat("RECEITA BRUTA", Fmt.money(r.path("receitaBruta").decimalValue())), 0, 1);
            g.add(stat("RECEITA LÍQUIDA", Fmt.money(r.path("receitaLiquida").decimalValue())), 1, 1);
            g.add(stat("MARGEM LÍQUIDA", Fmt.dec2(r.path("margemLiquida").decimalValue()) + "%"), 0, 2);
            g.add(stat("DESCONTO MÉDIO POR VENDA", vendas > 0
                    ? Fmt.money(desconto.divide(BigDecimal.valueOf(vendas), 2, RoundingMode.HALF_UP)) : "—"), 1, 2);
            Modal.informativo(janela(), "Desempenho: " + j.path("nome").asText(), detalhe(j) + " · " + situacao(j, OffsetDateTime.now()),
                    g, 560, 0).mostrar();
        });
    }

    private static VBox stat(String rotulo, String valor) {
        return new VBox(2, Ui.texto(rotulo, "kpi-label"), Ui.texto(valor, "h3"));
    }

    // ------------------------------------------------------------------ cadastro / edição

    private void abrirForm(JsonNode p) {
        PromocaoFormDialog.abrir(janela(), ctx, categorias, p, null, msg -> {
            sucesso(msg);
            ativar();
        });
    }

    public static String chaveTipo(String rotulo) {
        return TIPOS.entrySet().stream().filter(e -> e.getValue().equals(rotulo)).map(Map.Entry::getKey).findFirst()
                .orElse("desconto_percentual");
    }

    public static OffsetDateTime quando(LocalDate dia, String hora, String qual) {
        if (dia == null) {
            throw new DomainException("Informe a data de " + qual + " da vigência.");
        }
        LocalTime h;
        try {
            h = LocalTime.parse(hora == null || hora.isBlank() ? "00:00" : hora.trim(), HORA);
        } catch (RuntimeException e) {
            throw new DomainException("Hora de " + qual + " inválida: use HH:mm (ex.: 08:00).");
        }
        return dia.atTime(h).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static BigDecimal valorPositivo(String texto, String campo) {
        BigDecimal v;
        try {
            v = Fmt.parse(texto == null ? "" : texto.replace("%", "").trim());
        } catch (DomainException e) {
            throw new DomainException("Informe " + campo + ".");
        }
        if (v.signum() <= 0) {
            throw new DomainException(campo.substring(0, 1).toUpperCase() + campo.substring(1) + " deve ser maior que zero.");
        }
        return v;
    }

    private static int inteiroPositivo(String texto, String campo) {
        try {
            int v = Integer.parseInt(texto == null ? "" : texto.trim());
            if (v <= 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new DomainException("Informe " + campo + " (número inteiro maior que zero).");
        }
    }

    /** Valida o formulário e monta o PromocaoRequest da API (mesmas regras da tela web). */
    public static Map<String, Object> montarCorpo(String nome, String tipo, String percentual, String valor, String precoFixo,
            String leva, String paga, String qtdCombo, String precoCombo, String qtdAtacado, String precoAtacado,
            OffsetDateTime inicio, OffsetDateTime fim, String categoriaId, List<String> produtoIds, boolean ativa) {
        String n = nome == null ? "" : nome.trim();
        if (n.isEmpty()) {
            throw new DomainException("Informe o nome da promoção.");
        }
        if (!fim.isAfter(inicio)) {
            throw new DomainException("O fim da vigência deve ser depois do início.");
        }
        if (produtoIds.isEmpty() && (categoriaId == null || categoriaId.isBlank())) {
            throw new DomainException("Selecione ao menos um produto ou uma categoria.");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("nome", n);
        b.put("tipo", tipo);
        b.put("valorDesconto", null);
        b.put("precoFixo", null);
        b.put("quantidadeLeva", null);
        b.put("quantidadePaga", null);
        b.put("quantidadeMinima", null);
        switch (tipo) {
            case "desconto_percentual" -> {
                BigDecimal v = valorPositivo(percentual, "o percentual de desconto");
                if (v.compareTo(new BigDecimal("100")) >= 0) {
                    throw new DomainException("O percentual de desconto deve ser menor que 100%.");
                }
                b.put("valorDesconto", v);
            }
            case "desconto_valor" -> b.put("valorDesconto", valorPositivo(valor, "o valor do desconto"));
            case "preco_fixo" -> b.put("precoFixo", valorPositivo(precoFixo, "o preço promocional"));
            case "leve_x_pague_y" -> {
                int l = inteiroPositivo(leva, "a quantidade que leva");
                int pg = inteiroPositivo(paga, "a quantidade que paga");
                if (pg >= l) {
                    throw new DomainException("No \"leve X, pague Y\" a quantidade paga deve ser menor que a levada.");
                }
                b.put("quantidadeLeva", l);
                b.put("quantidadePaga", pg);
            }
            case "combo_quantidade" -> {
                b.put("quantidadeMinima", inteiroPositivo(qtdCombo, "a quantidade do combo"));
                b.put("precoFixo", valorPositivo(precoCombo, "o preço total do combo"));
            }
            default -> {
                b.put("quantidadeMinima", inteiroPositivo(qtdAtacado, "a quantidade mínima do atacado"));
                b.put("precoFixo", valorPositivo(precoAtacado, "o preço unitário de atacado"));
            }
        }
        b.put("inicioVigencia", inicio.toString());
        b.put("fimVigencia", fim.toString());
        b.put("ativa", ativa);
        b.put("categoriaId", categoriaId == null || categoriaId.isBlank() ? null : categoriaId);
        b.put("produtoIds", produtoIds);
        return b;
    }
}
