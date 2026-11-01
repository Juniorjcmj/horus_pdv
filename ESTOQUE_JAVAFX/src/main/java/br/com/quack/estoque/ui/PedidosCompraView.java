package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.service.PrintService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Pedidos de compra já gerados (Compras, Reposição ou sistema web): lista com filtro por situação, itens, impressão do
 * pedido e da cotação, troca de fornecedor e preços depois da cotação, recebimento (entrada no estoque) e cancelamento.
 */
public class PedidosCompraView extends Tela {
    /** Situações do pedido (OrdemCompraStatus da API). */
    static final int PENDENTE = 0;
    static final int RECEBIDO = 1;
    static final int CANCELADO = 2;
    static final int PARCIAL = 3;

    private static final String SEM_FORNECEDOR = "Sem fornecedor (cotação)";
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Perfil perfil;
    private final TableView<JsonNode> tabela = Ui.tabela();
    private final ComboBox<String> filtro = Ui.combo(List.of("Em aberto", "Pendentes", "Recebidos em parte", "Recebidos",
            "Cancelados", "Todos"));
    private final TextField busca = Ui.campo("Nº do pedido ou fornecedor");
    private final Label resumo = Ui.muted("");
    private final Button verItens = Ui.botao("Ver itens", this::verItens);
    private final Button imprimir = Ui.botao("Imprimir pedido", () -> imprimir(false));
    private final Button cotacao = Ui.botao("Imprimir cotação", () -> imprimir(true));
    private final Button fornecedorPrecos = Ui.botao("Editar pedido", this::editarPedido);
    private final Button receber = Ui.primario("Receber", this::receber);
    private final Button cancelar = Ui.perigo("Cancelar pedido", this::cancelar);
    private final Carregando carregando;
    private List<JsonNode> todos = new ArrayList<>();
    private List<CadastroApi.Fornecedor> fornecedores = new ArrayList<>();

    public PedidosCompraView(AppContext ctx, Navegador nav) {
        super(ctx, nav);
        this.perfil = ctx.session.perfil();
        getChildren().add(Ui.cabecalho("Pedidos de compra",
                "Pedidos gerados em Compras, na Reposição ou no sistema web. Imprima a cotação para quantos fornecedores "
                        + "quiser, edite o pedido (fornecedor, preços, itens, prazo) e dê entrada ao receber.",
                Ui.botao("Atualizar", this::ativar)));

        filtro.setValue("Em aberto");
        filtro.setOnAction(e -> aplicarFiltro());
        busca.setPrefWidth(260);
        busca.textProperty().addListener((o, a, b) -> aplicarFiltro());
        getChildren().add(Ui.barra(Ui.rotulado("Situação", filtro), Ui.rotulado("Buscar", busca)));

        Ui.ajustar(Ui.colNum(tabela, "Nº", j -> numero(j), 60), 50, null);
        Ui.ajustar(Ui.col(tabela, "Data", j -> Fmt.dateOnly(j.path("createdAt").asText("")), 90), 86, null);
        Ui.ajustar(Ui.col(tabela, "Fornecedor", PedidosCompraView::fornecedor, 240), 160, null);
        Ui.ajustar(Ui.col(tabela, "Situação", j -> rotulo(j.path("status").asInt()), 120), 110, null);
        Ui.ajustar(Ui.col(tabela, "Previsão", j -> j.path("previsaoEntrega").isNull() ? "—"
                : Fmt.dateOnly(j.path("previsaoEntrega").asText("")), 90), 86, "Previsão de entrega");
        if (perfil.verCustos()) {
            Ui.ajustar(Ui.colNum(tabela, "Total", j -> Fmt.money(j.path("totalEstimado").decimalValue()), 100), 90,
                    "Total estimado pelos custos do pedido");
        }
        Ui.ajustar(Ui.col(tabela, "Criado por", j -> j.path("createdByName").asText(""), 140), 100, null);
        Ui.distribuir(tabela);
        VBox.setVgrow(tabela, Priority.ALWAYS);
        tabela.setRowFactory(t -> {
            TableRow<JsonNode> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    verItens();
                }
            });
            return row;
        });
        tabela.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> atualizarBotoes());
        tabela.setPlaceholder(Ui.muted("Nenhum pedido com esse filtro."));

        carregando = new Carregando(tabela, verItens, imprimir, cotacao, fornecedorPrecos, receber, cancelar);
        getChildren().addAll(carregando, Ui.barra(verItens, imprimir, cotacao, fornecedorPrecos, Ui.espaco(), cancelar, receber),
                resumo);
        atualizarBotoes();
    }

    @Override
    public void ativar() {
        rodar(carregando, "Carregando os pedidos de compra…",
                () -> new Object[] {ctx.compras.listar(), ctx.cadastros.fornecedores()}, r -> {
                    List<JsonNode> lista = new ArrayList<>();
                    ((JsonNode) r[0]).forEach(lista::add);
                    // mais recentes primeiro (número é sequencial)
                    lista.sort((a, b) -> Long.compare(numeroLong(b), numeroLong(a)));
                    todos = lista;
                    @SuppressWarnings("unchecked")
                    List<CadastroApi.Fornecedor> f = (List<CadastroApi.Fornecedor>) r[1];
                    fornecedores = f;
                    aplicarFiltro();
                });
    }

    @Override
    public void focarBusca() {
        busca.requestFocus();
    }

    // ------------------------------------------------------------------ lista

    private void aplicarFiltro() {
        String f = filtro.getValue() == null ? "Em aberto" : filtro.getValue();
        String q = busca.getText() == null ? "" : Fmt.normalizar(busca.getText().trim());
        List<JsonNode> vis = todos.stream().filter(j -> passaSituacao(f, j.path("status").asInt()))
                .filter(j -> q.isEmpty() || numero(j).equals(q) || Fmt.normalizar(fornecedor(j)).contains(q)).toList();
        JsonNode sel = tabela.getSelectionModel().getSelectedItem();
        tabela.setItems(FXCollections.observableArrayList(vis));
        if (sel != null) {
            vis.stream().filter(j -> numero(j).equals(numero(sel))).findFirst().ifPresent(j -> tabela.getSelectionModel().select(j));
        }
        long abertos = todos.stream().filter(j -> aberto(j.path("status").asInt())).count();
        long cotacoes = todos.stream().filter(j -> aberto(j.path("status").asInt()) && semFornecedor(j)).count();
        resumo.setText(vis.size() + " pedido(s) na lista · " + abertos + " em aberto"
                + (cotacoes > 0 ? " · " + cotacoes + " aguardando cotação (sem fornecedor)" : ""));
        atualizarBotoes();
    }

    static boolean passaSituacao(String filtro, int status) {
        return switch (filtro) {
            case "Pendentes" -> status == PENDENTE;
            case "Recebidos em parte" -> status == PARCIAL;
            case "Recebidos" -> status == RECEBIDO;
            case "Cancelados" -> status == CANCELADO;
            case "Todos" -> true;
            default -> aberto(status);
        };
    }

    static boolean aberto(int status) {
        return status == PENDENTE || status == PARCIAL;
    }

    static String rotulo(int status) {
        return switch (status) {
            case PENDENTE -> "Pendente";
            case RECEBIDO -> "Recebido";
            case CANCELADO -> "Cancelado";
            case PARCIAL -> "Recebido em parte";
            default -> "—";
        };
    }

    static boolean semFornecedor(JsonNode j) {
        return j.path("supplierId").isNull() || j.path("supplierId").asText("").isBlank();
    }

    static String fornecedor(JsonNode j) {
        return semFornecedor(j) ? SEM_FORNECEDOR : j.path("supplierName").asText("");
    }

    private static String numero(JsonNode j) {
        return j.path("orderNumber").asText("");
    }

    private static long numeroLong(JsonNode j) {
        try {
            return Long.parseLong(numero(j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void atualizarBotoes() {
        JsonNode j = tabela.getSelectionModel().getSelectedItem();
        int st = j == null ? -1 : j.path("status").asInt();
        verItens.setDisable(j == null);
        imprimir.setDisable(j == null);
        cotacao.setDisable(j == null);
        fornecedorPrecos.setDisable(st != PENDENTE);
        receber.setDisable(!aberto(st));
        cancelar.setDisable(st != PENDENTE);
    }

    /** Pedido selecionado com os itens (a lista não traz os itens). */
    private void comDetalhe(String mensagem, java.util.function.Consumer<JsonNode> acao) {
        JsonNode sel = tabela.getSelectionModel().getSelectedItem();
        if (sel == null) {
            Toasts.error(janela(), "Selecione um pedido.");
            return;
        }
        rodar(carregando, mensagem, () -> ctx.compras.obter(numero(sel)), acao);
    }

    private String unidade(String codigo) {
        Produto p = ctx.catalogo.vazio() ? null : ctx.catalogo.porCodigoOuGtin(codigo);
        return p == null ? "" : p.unidade();
    }

    private List<PedidoImpressao.Linha> linhas(JsonNode oc) {
        List<PedidoImpressao.Linha> r = new ArrayList<>();
        for (JsonNode i : oc.path("itens")) {
            r.add(new PedidoImpressao.Linha(i.path("productCode").asText(), i.path("productName").asText(),
                    i.path("quantity").decimalValue(), unidade(i.path("productCode").asText()), i.path("unitCost").decimalValue()));
        }
        return r;
    }

    // ------------------------------------------------------------------ ações

    private void verItens() {
        comDetalhe("Abrindo o pedido…", oc -> {
            TableView<JsonNode> t = Ui.tabela();
            Ui.col(t, "Código", i -> i.path("productCode").asText(), 110);
            Ui.col(t, "Produto", i -> i.path("productName").asText(), 260);
            Ui.colNum(t, "Pedido", i -> Fmt.qty(i.path("quantity").decimalValue()), 70);
            Ui.colNum(t, "Recebido", i -> Fmt.qty(i.path("quantityReceived").decimalValue()), 76);
            if (perfil.verCustos()) {
                Ui.colNum(t, "Custo un.", i -> Fmt.dec2(i.path("unitCost").decimalValue()), 80);
                Ui.colNum(t, "Total", i -> Fmt.dec2(i.path("itemTotal").decimalValue()), 86);
            }
            List<JsonNode> itens = new ArrayList<>();
            oc.path("itens").forEach(itens::add);
            t.setItems(FXCollections.observableArrayList(itens));
            t.setPrefHeight(Math.min(420, 60 + itens.size() * 30));
            Ui.distribuir(t);
            String obs = oc.path("note").asText("");
            VBox corpo = new VBox(8, t);
            if (!obs.isBlank()) {
                corpo.getChildren().add(Ui.muted("Observação: " + obs));
            }
            if (oc.path("status").asInt() == CANCELADO && !oc.path("motivoCancelamento").asText("").isBlank()) {
                corpo.getChildren().add(Ui.muted("Motivo do cancelamento: " + oc.path("motivoCancelamento").asText()));
            }
            Modal.informativo(janela(), "Pedido " + numero(oc) + " · " + fornecedor(oc),
                    rotulo(oc.path("status").asInt()) + " · criado em " + Fmt.dateTime(oc.path("createdAt").asText(""))
                            + " por " + oc.path("createdByName").asText(""), corpo, 760, 0).mostrar();
        });
    }

    private void imprimir(boolean comoCotacao) {
        comDetalhe("Preparando a impressão…", oc -> {
            var doc = comoCotacao ? PedidoImpressao.cotacao(numero(oc), ctx.session.user().name(), linhas(oc))
                    : PedidoImpressao.pedido(numero(oc), fornecedor(oc), ctx.session.user().name(), linhas(oc));
            String ok = (comoCotacao ? "Cotação " : "Pedido ") + numero(oc) + " enviado para a impressora.";
            PrintService.imprimirDocumento(doc, "", erro -> {
                if (erro != null) {
                    Toasts.error(janela(), erro);
                } else {
                    sucesso(ok);
                }
            });
        });
    }

    /** Linha editável (fornecedor e preços / recebimento). */
    static final class ItemEditavel {
        final JsonNode origem;
        final SimpleStringProperty quantidade = new SimpleStringProperty();
        final SimpleStringProperty custo = new SimpleStringProperty();
        final SimpleStringProperty receber = new SimpleStringProperty();
        final SimpleStringProperty validade = new SimpleStringProperty("");
        final SimpleStringProperty lote = new SimpleStringProperty("");

        ItemEditavel(JsonNode i) {
            origem = i;
            quantidade.set(Fmt.qty(i.path("quantity").decimalValue()));
            custo.set(Fmt.dec2(i.path("unitCost").decimalValue()));
            BigDecimal falta = i.path("quantity").decimalValue().subtract(i.path("quantityReceived").decimalValue());
            receber.set(Fmt.qty(falta.signum() > 0 ? falta : BigDecimal.ZERO));
        }

        String codigo() {
            return origem.path("productCode").asText();
        }

        String nome() {
            return origem.path("productName").asText();
        }
    }

    private static TableColumn<ItemEditavel, String> editavel(TableView<ItemEditavel> t, String titulo,
            java.util.function.Function<ItemEditavel, SimpleStringProperty> prop, double largura) {
        return PedidoEditorDialog.colunaEditavel(t, titulo, prop, largura, !titulo.startsWith("Lote") && !titulo.startsWith("Validade"),
                t::refresh);
    }

    /** Editor completo do pedido pendente (fornecedor, prazos, pagamento, frete, desconto, observação e itens). */
    private void editarPedido() {
        comDetalhe("Abrindo o pedido…", oc -> {
            if (oc.path("status").asInt() != PENDENTE) {
                Toasts.error(janela(), "Só pedidos pendentes podem ser editados (este está " + rotulo(oc.path("status").asInt())
                        .toLowerCase(java.util.Locale.ROOT) + ").");
                return;
            }
            PedidoEditorDialog.abrir(janela(), ctx, oc, fornecedores, () -> {
                sucesso("Pedido " + numero(oc) + " atualizado.");
                ativar();
            });
        });
    }

    private void receber() {
        comDetalhe("Abrindo o pedido…", oc -> {
            List<ItemEditavel> itens = new ArrayList<>();
            oc.path("itens").forEach(i -> itens.add(new ItemEditavel(i)));
            TableView<ItemEditavel> t = Ui.tabela();
            t.setEditable(true);
            Ui.col(t, "Produto", ItemEditavel::nome, 240);
            Ui.colNum(t, "Pedido", i -> Fmt.qty(i.origem.path("quantity").decimalValue()), 70);
            Ui.colNum(t, "Já recebido", i -> Fmt.qty(i.origem.path("quantityReceived").decimalValue()), 84);
            editavel(t, "Receber agora", i -> i.receber, 100);
            editavel(t, "Validade (dd/mm/aaaa)", i -> i.validade, 140);
            editavel(t, "Lote", i -> i.lote, 90);
            t.setItems(FXCollections.observableArrayList(itens));
            t.setPrefHeight(Math.min(380, 60 + itens.size() * 30));

            boolean semPreco = itens.stream().anyMatch(i -> i.origem.path("unitCost").decimalValue().signum() <= 0);
            VBox corpo = new VBox(10, Ui.muted("Já vem preenchido com o que falta receber. Ajuste o que chegou (0 = não chegou). "
                    + "A entrada atualiza o estoque e o custo do produto."), t);
            if (semPreco) {
                corpo.getChildren().add(0, Ui.caixa("Há itens sem custo neste pedido. Informe os preços em \"Editar pedido\" "
                        + "antes de receber, para não zerar o custo dos produtos.", "rg-warn-box"));
            }
            Modal[] m = new Modal[1];
            m[0] = Modal.formulario(janela(), "Receber pedido " + numero(oc) + " · " + fornecedor(oc), corpo, "Dar entrada", 780, () -> {
                if (semPreco) {
                    m[0].erro("Informe os preços do pedido antes de receber (botão \"Editar pedido\").");
                    return false;
                }
                List<OrdemCompraApi.Recebido> recebidos;
                try {
                    recebidos = recebidos(itens);
                } catch (DomainException e) {
                    m[0].erro(e.getMessage());
                    return false;
                }
                m[0].ocupado(true);
                Carregando.async("Dando entrada no estoque…", () -> {
                    ctx.compras.receber(numero(oc), recebidos);
                    return true;
                }, ok -> {
                    m[0].ocupado(false);
                    m[0].fechar();
                    ctx.catalogo.invalidar();
                    sucesso("Entrada do pedido " + numero(oc) + " registrada (" + recebidos.size() + " item(ns)).");
                    ativar();
                }, err -> {
                    m[0].ocupado(false);
                    m[0].erro(Fx.message(err));
                });
                return false;
            });
            m[0].mostrar();
        });
    }

    /** Itens com quantidade a receber; valida número e data. */
    static List<OrdemCompraApi.Recebido> recebidos(List<ItemEditavel> itens) {
        List<OrdemCompraApi.Recebido> r = new ArrayList<>();
        for (ItemEditavel i : itens) {
            BigDecimal q = Fmt.parseOrZero(i.receber.get());
            if (q.signum() < 0) {
                throw new DomainException("A quantidade de " + i.nome() + " não pode ser negativa.");
            }
            if (q.signum() == 0) {
                continue;
            }
            String v = i.validade.get() == null ? "" : i.validade.get().trim();
            String iso = null;
            if (!v.isEmpty()) {
                try {
                    iso = LocalDate.parse(v, DIA).toString();
                } catch (DateTimeParseException e) {
                    throw new DomainException("Validade de " + i.nome() + " inválida: use dd/mm/aaaa.");
                }
            }
            r.add(new OrdemCompraApi.Recebido(i.codigo(), q, iso, i.lote.get()));
        }
        if (r.isEmpty()) {
            throw new DomainException("Informe a quantidade recebida de pelo menos um item.");
        }
        return r;
    }

    private void cancelar() {
        JsonNode sel = tabela.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        Modal.pedirTexto(janela(), "Cancelar pedido " + numero(sel), "Motivo do cancelamento", "", "Cancelar pedido",
                motivo -> rodar(carregando, "Cancelando o pedido…", () -> {
                    ctx.compras.cancelar(numero(sel), motivo);
                    return true;
                }, ok -> {
                    sucesso("Pedido " + numero(sel) + " cancelado.");
                    ativar();
                }));
    }
}
