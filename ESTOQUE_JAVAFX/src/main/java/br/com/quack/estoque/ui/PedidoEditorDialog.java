package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Edição de um pedido de compra pendente: fornecedor, previsão de entrega, condição e forma de pagamento, frete,
 * desconto, observação e itens (incluir, remover, quantidade e custo). Grava com PUT /api/OrdemCompra/{numero}.
 */
public final class PedidoEditorDialog {
    static final String SEM_FORNECEDOR = "Sem fornecedor (cotação)";
    static final List<String> FORMAS = List.of("", "Boleto", "PIX", "Transferência", "Cartão de Crédito", "Dinheiro");

    /** Item editável do pedido. */
    public static final class Linha {
        final String codigo;
        final String nome;
        final SimpleStringProperty quantidade = new SimpleStringProperty();
        final SimpleStringProperty custo = new SimpleStringProperty();

        public Linha(String codigo, String nome, BigDecimal quantidade, BigDecimal custo) {
            this.codigo = codigo;
            this.nome = nome;
            this.quantidade.set(Fmt.qty(quantidade));
            this.custo.set(Fmt.dec2(custo == null ? BigDecimal.ZERO : custo));
        }

        BigDecimal total() {
            try {
                return Fmt.parse(quantidade.get()).multiply(Fmt.parseOrZero(custo.get())).setScale(2, RoundingMode.HALF_UP);
            } catch (DomainException e) {
                return BigDecimal.ZERO;
            }
        }
    }

    private final AppContext ctx;
    private final JsonNode oc;
    private final List<CadastroApi.Fornecedor> fornecedores;
    private final Runnable aoSalvar;
    private final ObservableList<Linha> itens = FXCollections.observableArrayList();
    private final TableView<Linha> tabela = Ui.tabela();
    private final Label totais = Ui.muted("");
    private final TextField frete = Ui.campo("0,00");
    private final TextField desconto = Ui.campo("0,00");
    private Modal modal;

    private PedidoEditorDialog(AppContext ctx, JsonNode oc, List<CadastroApi.Fornecedor> fornecedores, Runnable aoSalvar) {
        this.ctx = ctx;
        this.oc = oc;
        this.fornecedores = fornecedores;
        this.aoSalvar = aoSalvar;
    }

    public static void abrir(Window dono, AppContext ctx, JsonNode oc, List<CadastroApi.Fornecedor> fornecedores, Runnable aoSalvar) {
        new PedidoEditorDialog(ctx, oc, fornecedores, aoSalvar).montar(dono);
    }

    private void montar(Window dono) {
        OrdemCompraApi.Cabecalho atual = OrdemCompraApi.Cabecalho.de(oc);
        String numero = oc.path("orderNumber").asText();

        ComboBox<String> forn = Ui.combo(new ArrayList<>());
        List<String> nomes = new ArrayList<>();
        nomes.add(SEM_FORNECEDOR);
        fornecedores.forEach(f -> nomes.add(f.nome()));
        forn.setItems(FXCollections.observableArrayList(nomes));
        String idAtual = atual.supplierId() == null ? "" : atual.supplierId();
        forn.setValue(fornecedores.stream().filter(f -> f.id().equals(idAtual)).map(CadastroApi.Fornecedor::nome).findFirst()
                .orElse(SEM_FORNECEDOR));
        forn.setPrefWidth(300);

        DatePicker previsao = new DatePicker(atual.previsaoEntrega() == null ? null : LocalDate.parse(atual.previsaoEntrega()));
        previsao.setPromptText("dd/mm/aaaa");
        previsao.setPrefWidth(150);
        TextField condicao = Ui.campo("Ex.: À vista, 30 dias, 30/60");
        condicao.setText(atual.condicaoPagamento() == null ? "" : atual.condicaoPagamento());
        condicao.setPrefWidth(200);
        ComboBox<String> forma = Ui.combo(FORMAS);
        forma.setEditable(true);
        forma.setValue(atual.formaPagamento() == null ? "" : atual.formaPagamento());
        forma.setPrefWidth(170);
        frete.setText(Fmt.dec2(atual.frete() == null ? BigDecimal.ZERO : atual.frete()));
        desconto.setText(Fmt.dec2(atual.desconto() == null ? BigDecimal.ZERO : atual.desconto()));
        frete.setPrefWidth(90);
        desconto.setPrefWidth(90);
        frete.textProperty().addListener((o, a, b) -> atualizarTotais());
        desconto.textProperty().addListener((o, a, b) -> atualizarTotais());
        TextField obs = Ui.campo("Observação do pedido");
        obs.setText(atual.observacao() == null ? "" : atual.observacao());

        // itens
        for (JsonNode i : oc.path("itens")) {
            itens.add(new Linha(i.path("productCode").asText(), i.path("productName").asText(), i.path("quantity").decimalValue(),
                    i.path("unitCost").decimalValue()));
        }
        tabela.setEditable(true);
        Ui.col(tabela, "Código", l -> l.codigo, 110);
        Ui.col(tabela, "Produto", l -> l.nome, 280);
        editavel("Quantidade", l -> l.quantidade, 90);
        editavel("Custo un. (R$)", l -> l.custo, 110);
        Ui.colNum(tabela, "Total", l -> Fmt.dec2(l.total()), 90);
        tabela.setItems(itens);
        tabela.setPrefHeight(260);
        Ui.distribuir(tabela);

        TextField busca = Ui.campo("Código, código de barras ou nome do produto");
        HBox.setHgrow(busca, Priority.ALWAYS);
        Button adicionar = Ui.botao("Adicionar item", () -> adicionar(busca));
        busca.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                adicionar(busca);
                e.consume();
            }
        });
        Button remover = Ui.botao("Remover item selecionado", () -> {
            Linha l = tabela.getSelectionModel().getSelectedItem();
            if (l != null) {
                itens.remove(l);
                atualizarTotais();
            }
        });
        HBox addBox = new HBox(8, busca, adicionar);

        VBox corpo = new VBox(10,
                Ui.barra(Ui.rotulado("Fornecedor", forn), Ui.rotulado("Previsão de entrega", previsao)),
                Ui.barra(Ui.rotulado("Condição de pagamento", condicao), Ui.rotulado("Forma de pagamento", forma),
                        Ui.rotulado("Frete (R$)", frete), Ui.rotulado("Desconto (R$)", desconto)),
                Ui.rotulado("Observação", obs),
                Ui.texto("Itens", "rg-section-title"),
                Ui.muted("Clique na quantidade ou no custo para alterar (Enter, Tab ou clicar fora confirma)."),
                addBox, tabela, Ui.barra(remover, Ui.espaco(), totais));
        atualizarTotais();

        modal = Modal.formularioRolavel(dono, "Editar pedido " + numero, "Só pedidos pendentes podem ser editados.", corpo,
                "Salvar pedido", 860, 640, () -> {
                    List<OrdemCompraApi.Item> novos;
                    OrdemCompraApi.Cabecalho cab;
                    try {
                        novos = itensValidos(itens);
                        BigDecimal fr = Fmt.parseOrZero(frete.getText());
                        BigDecimal de = Fmt.parseOrZero(desconto.getText());
                        if (fr.signum() < 0 || de.signum() < 0) {
                            throw new DomainException("Frete e desconto não podem ser negativos.");
                        }
                        String nome = forn.getValue();
                        String id = fornecedores.stream().filter(f -> f.nome().equals(nome)).map(CadastroApi.Fornecedor::id)
                                .findFirst().orElse("");
                        cab = new OrdemCompraApi.Cabecalho(id, obs.getText(),
                                previsao.getValue() == null ? null : previsao.getValue().toString(), condicao.getText(),
                                forma.getEditor().getText(),
                                fr, de);
                    } catch (DomainException e) {
                        modal.erro(e.getMessage());
                        return false;
                    }
                    modal.ocupado(true);
                    Carregando.async("Salvando o pedido…", () -> ctx.compras.atualizar(numero, cab, novos), r -> {
                        modal.ocupado(false);
                        modal.fechar();
                        aoSalvar.run();
                    }, err -> {
                        modal.ocupado(false);
                        modal.erro(Fx.message(err));
                    });
                    return false;
                });
        modal.mostrar();
    }

    private void editavel(String titulo, java.util.function.Function<Linha, SimpleStringProperty> prop, double largura) {
        colunaEditavel(tabela, titulo, prop, largura, true, () -> {
            tabela.refresh();
            atualizarTotais();
        });
    }

    /**
     * Coluna editável com um clique ({@link CelulaEditavel}): grava no Enter, no Tab e ao sair do campo (clicar em
     * outra linha ou em "Salvar"). O TextFieldTableCell padrão só gravava no Enter e perdia o valor digitado.
     */
    static <S> TableColumn<S, String> colunaEditavel(TableView<S> t, String titulo,
            java.util.function.Function<S, SimpleStringProperty> prop, double largura, boolean numero, Runnable aoMudar) {
        TableColumn<S, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> prop.apply(cd.getValue()));
        CelulaEditavel.em(c, l -> prop.apply(l).get(), numero, l -> true);
        c.setOnEditCommit(e -> {
            prop.apply(e.getRowValue()).set(e.getNewValue() == null ? "" : e.getNewValue().trim());
            if (aoMudar != null) {
                aoMudar.run();
            }
        });
        c.setPrefWidth(largura);
        if (numero) {
            c.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        t.getColumns().add(c);
        return c;
    }

    private void atualizarTotais() {
        BigDecimal soma = itens.stream().map(Linha::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fr = Fmt.parseOrZero(frete.getText() == null ? "" : frete.getText());
        BigDecimal de = Fmt.parseOrZero(desconto.getText() == null ? "" : desconto.getText());
        totais.setText(itens.size() + " item(ns) · produtos " + Fmt.money(soma) + " + frete " + Fmt.money(fr) + " − desconto "
                + Fmt.money(de) + " = " + Fmt.money(soma.add(fr).subtract(de)));
    }

    /** Valida e converte os itens; recusa lista vazia, quantidade ≤ 0 e custo negativo. */
    public static List<OrdemCompraApi.Item> itensValidos(List<Linha> linhas) {
        if (linhas.isEmpty()) {
            throw new DomainException("O pedido precisa de pelo menos um item.");
        }
        List<OrdemCompraApi.Item> r = new ArrayList<>();
        for (Linha l : linhas) {
            BigDecimal q;
            try {
                q = Fmt.parse(l.quantidade.get());
            } catch (DomainException e) {
                throw new DomainException("Quantidade inválida em " + l.nome + ".");
            }
            if (q.signum() <= 0) {
                throw new DomainException("A quantidade de " + l.nome + " deve ser maior que zero (ou remova o item).");
            }
            BigDecimal c = Fmt.parseOrZero(l.custo.get());
            if (c.signum() < 0) {
                throw new DomainException("O custo de " + l.nome + " não pode ser negativo.");
            }
            r.add(new OrdemCompraApi.Item(l.codigo, q, c));
        }
        return r;
    }

    private void adicionar(TextField busca) {
        String t = busca.getText() == null ? "" : busca.getText().trim();
        if (t.isEmpty()) {
            return;
        }
        Carregando.async(() -> {
            ctx.garantirCatalogo(false);
            Produto exato = ctx.catalogo.porCodigoOuGtin(t);
            return exato != null ? List.of(exato) : ctx.catalogo.buscar(t, 30);
        }, achados -> {
            if (achados.isEmpty()) {
                modal.erro("Nenhum produto encontrado para \"" + t + "\".");
            } else if (achados.size() == 1) {
                incluir(achados.get(0));
                busca.clear();
            } else {
                escolher(achados, p -> {
                    incluir(p);
                    busca.clear();
                });
            }
        }, err -> modal.erro(Fx.message(err)));
    }

    private void incluir(Produto p) {
        modal.erro(null);
        for (Linha l : itens) {
            if (l.codigo.equalsIgnoreCase(p.codigo())) {
                tabela.getSelectionModel().select(l);
                tabela.scrollTo(l);
                modal.erro(p.nome() + " já está no pedido: altere a quantidade na tabela.");
                return;
            }
        }
        BigDecimal sugestao = p.minimo() != null && p.maximo() != null && p.maximo().compareTo(p.estoque()) > 0
                ? p.maximo().subtract(p.estoque()) : BigDecimal.ONE;
        Linha nova = new Linha(p.codigo(), p.nome(), sugestao.signum() > 0 ? sugestao : BigDecimal.ONE, p.custo());
        itens.add(nova);
        tabela.getSelectionModel().select(nova);
        tabela.scrollTo(nova);
        atualizarTotais();
    }

    private void escolher(List<Produto> opcoes, java.util.function.Consumer<Produto> aoEscolher) {
        ListView<Produto> lista = new ListView<>(FXCollections.observableArrayList(opcoes));
        lista.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Produto p, boolean vazio) {
                super.updateItem(p, vazio);
                setText(vazio || p == null ? null : p.codigo() + " · " + p.nome() + " · estoque " + Fmt.qty(p.estoque()));
            }
        });
        lista.setPrefHeight(320);
        lista.getSelectionModel().selectFirst();
        Modal[] m = new Modal[1];
        Runnable ok = () -> {
            Produto p = lista.getSelectionModel().getSelectedItem();
            if (p != null) {
                aoEscolher.accept(p);
                m[0].fechar();
            }
        };
        lista.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                ok.run();
            }
        });
        lista.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                ok.run();
            }
        });
        m[0] = Modal.formulario(modal.stage(), "Escolha o produto", new VBox(8, lista), "Adicionar", 560, () -> {
            Produto p = lista.getSelectionModel().getSelectedItem();
            if (p != null) {
                aoEscolher.accept(p);
            }
            return true;
        });
        m[0].mostrar();
        lista.requestFocus();
    }
}
