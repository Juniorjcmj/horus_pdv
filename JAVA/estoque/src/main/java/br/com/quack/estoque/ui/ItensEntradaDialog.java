package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.domain.Precificacao;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Itens de uma entrada já confirmada. Mostra o preço de venda atual de cada produto e, para quem vê custos, o lucro e o
 * markup sobre o custo desta nota. Preço, lucro e markup editam com um clique: os dois últimos recalculam o preço
 * (Precificacao) e a gravação usa o controle de versão do preço, como na tabela de Produtos.
 */
public final class ItensEntradaDialog {

    /** Item da nota e o produto do catálogo ligado a ele (null se não estiver no catálogo). */
    record Linha(JsonNode item, Produto produto) {
        BigDecimal custo() {
            return Fmt.parseOrZero(item.path("precoCusto").asText("0"));
        }

        boolean temProduto() {
            return produto != null;
        }

        boolean temCusto() {
            return produto != null && custo().signum() > 0;
        }
    }

    private final AppContext ctx;
    private final Window dono;
    private final Perfil perfil;
    private final JsonNode resumo;
    private final TableView<Linha> tabela = Ui.tabela();
    private final Label topo = Ui.muted("Carregando…");

    private ItensEntradaDialog(AppContext ctx, Window dono, JsonNode resumo) {
        this.ctx = ctx;
        this.dono = dono;
        this.perfil = ctx.session.perfil();
        this.resumo = resumo;
    }

    public static void abrir(AppContext ctx, Window dono, JsonNode resumo) {
        new ItensEntradaDialog(ctx, dono, resumo).mostrar();
    }

    private void mostrar() {
        montarColunas();
        VBox corpo = new VBox(8, topo, tabela);
        if (perfil.verCustos()) {
            corpo.getChildren().add(Ui.muted("Lucro e markup sobre o custo desta nota."
                    + (perfil.cadastrarProdutos() ? " Clique no preço, no lucro ou no markup para alterar; Enter salva." : "")));
        } else if (perfil.cadastrarProdutos()) {
            corpo.getChildren().add(Ui.muted("Clique no preço de venda para alterar; Enter salva."));
        }
        Carregando.async(() -> {
            JsonNode d = ctx.entradas.obterNota(resumo.path("id").asText());
            ctx.garantirCatalogo(false);
            return d;
        }, this::preencher, e -> topo.setText(Fx.message(e)));
        corpo.getChildren().add(Ui.barra(Ui.botao("Etiquetas dos itens", this::etiquetas)));
        double largura = perfil.verCustos() ? 1040 : 860;
        Modal.informativo(dono, "Itens da entrada", EntradasView.documento(resumo), corpo, largura, 0).mostrar();
    }

    private void montarColunas() {
        tabela.setPrefHeight(360);
        Ui.col(tabela, "Código", l -> l.item().path("productCode").asText(""), 90);
        Ui.col(tabela, "Produto", l -> l.item().path("productName").asText(""), 240);
        Ui.colNum(tabela, "Quantidade", l -> l.item().path("quantidade").asText("") + " " + l.item().path("unidadeComercial").asText(""), 100);
        if (perfil.verCustos()) {
            Ui.colNum(tabela, "Custo unit.", l -> "R$ " + Fmt.cost4(l.custo()), 90);
        }
        TableColumn<Linha, String> preco = Ui.colNum(tabela, "Preço venda", l -> l.temProduto() ? Fmt.dec2(l.produto().preco()) : "—", 96);
        TableColumn<Linha, String> lucro = null;
        TableColumn<Linha, String> markup = null;
        if (perfil.verCustos()) {
            lucro = Ui.colNum(tabela, "Lucro R$", l -> l.temProduto() ? Fmt.dec2(Precificacao.lucro(l.custo(), l.produto().preco())) : "—", 86);
            markup = Ui.colNum(tabela, "Markup", l -> l.temProduto() ? ProdutosView.pct(Precificacao.markup(l.custo(), l.produto().preco())) : "—", 86);
        }
        Ui.col(tabela, "Lote / validade", l -> l.item().path("numeroLote").asText("—")
                + (l.item().path("dataValidade").asText("").isBlank() ? "" : " · " + Fmt.dateOnly(l.item().path("dataValidade").asText())), 140);
        if (perfil.cadastrarProdutos()) {
            tornarEditavel(preco, lucro, markup);
        }
    }

    /** Um clique edita; Enter salva. Lucro e markup só com custo (sem custo não há como calcular o preço). */
    private void tornarEditavel(TableColumn<Linha, String> preco, TableColumn<Linha, String> lucro, TableColumn<Linha, String> markup) {
        tabela.setEditable(true);
        CelulaEditavel.em(preco, l -> l.temProduto() ? Fmt.dec2(l.produto().preco()) : "", true, Linha::temProduto);
        preco.setOnEditCommit(e -> salvar(e.getRowValue(), "preco", e.getNewValue()));
        if (lucro != null) {
            CelulaEditavel.em(lucro, l -> l.temProduto() ? Fmt.dec2(Precificacao.lucro(l.custo(), l.produto().preco())) : "", true, Linha::temCusto);
            lucro.setOnEditCommit(e -> salvar(e.getRowValue(), "lucro", e.getNewValue()));
        }
        if (markup != null) {
            CelulaEditavel.em(markup, l -> {
                BigDecimal m = l.temProduto() ? Precificacao.markup(l.custo(), l.produto().preco()) : null;
                return m == null ? "" : Fmt.dec2(m);
            }, true, Linha::temCusto);
            markup.setOnEditCommit(e -> salvar(e.getRowValue(), "markup", e.getNewValue()));
        }
    }

    private void preencher(JsonNode d) {
        JsonNode nota = d.path("nota");
        JsonNode entrada = d.path("entrada");
        List<Linha> linhas = new ArrayList<>();
        for (JsonNode i : entrada.path("itens")) {
            linhas.add(new Linha(i, produtoDo(i)));
        }
        tabela.setItems(FXCollections.observableArrayList(linhas));
        topo.setText(EntradasView.documento(nota) + " · " + nota.path("fornecedorNome").asText("") + (nota.path("estornada").asBoolean(false)
                ? " · ESTORNADA em " + Fmt.dateTime(nota.path("estornadaEm").asText("")) + " (" + nota.path("estornoJustificativa").asText("") + ")" : "")
                + (entrada.path("observacao").asText("").isBlank() ? "" : " · " + entrada.path("observacao").asText()));
    }

    /** Pelo id gravado na entrada; sem ele (produto criado pela nota), pelo código. */
    private Produto produtoDo(JsonNode item) {
        Produto p = ctx.catalogo.porId(item.path("produtoExistenteId").asText(""));
        if (p != null) {
            return p;
        }
        String codigo = item.path("productCode").asText("");
        return codigo.isBlank() ? null
                : ctx.catalogo.todos().stream().filter(x -> x.codigo().equals(codigo)).findFirst().orElse(null);
    }

    /** Valida o valor digitado, calcula o novo preço e grava; em erro a célula volta ao valor anterior. */
    private void salvar(Linha linha, String campo, String texto) {
        String t = texto == null ? "" : texto.replace("%", "").trim();
        try {
            Produto p = linha.produto();
            if (p == null) {
                throw new DomainException("Este item não está no catálogo de produtos.");
            }
            BigDecimal v = Fmt.parse(t);
            BigDecimal novo = switch (campo) {
                case "lucro" -> Precificacao.precoPorLucro(linha.custo(), v);
                case "markup" -> Precificacao.precoPorMarkup(linha.custo(), v);
                default -> {
                    if (v.signum() <= 0) {
                        throw new DomainException("O preço de venda deve ser maior que zero.");
                    }
                    yield v;
                }
            };
            if (novo.compareTo(p.preco()) == 0) {
                tabela.refresh();
                return;
            }
            gravar(linha, novo);
        } catch (DomainException e) {
            tabela.refresh();
            Toasts.error(dono, e.getMessage());
        }
    }

    private void gravar(Linha linha, BigDecimal preco) {
        Produto p = linha.produto();
        Carregando.async("Salvando o preço…", () -> {
            Produto salvo = ctx.produtos.atualizarPreco(p.id(), Fmt.moneyApi(preco), p.versaoPreco());
            if (salvo == null) {
                ctx.garantirCatalogo(true);
                return ctx.catalogo.porId(p.id());
            }
            ctx.catalogo.substituir(salvo);
            return salvo;
        }, salvo -> {
            Produto novo = salvo != null ? salvo : p;
            trocarProduto(p, novo);
            Toasts.success(dono, "\"" + p.nome() + "\": preço " + Fmt.money(p.preco()) + " → " + Fmt.money(novo.preco())
                    + (perfil.verCustos() ? " · markup " + ProdutosView.pct(Precificacao.markup(linha.custo(), novo.preco())) : "")
                    + ". Use \"Preços alterados\" em Etiquetas para imprimir.");
        }, e -> {
            tabela.refresh();
            Toasts.error(dono, Fx.message(e));
        });
    }

    /** O mesmo produto pode aparecer em mais de um item da nota: todos passam a mostrar o preço novo. */
    private void trocarProduto(Produto antigo, Produto novo) {
        List<Linha> atual = new ArrayList<>(tabela.getItems());
        for (int i = 0; i < atual.size(); i++) {
            Linha l = atual.get(i);
            if (l.produto() != null && l.produto().id().equals(antigo.id())) {
                atual.set(i, new Linha(l.item(), novo));
            }
        }
        tabela.setItems(FXCollections.observableArrayList(atual));
    }

    private void etiquetas() {
        int n = 0;
        for (Linha l : tabela.getItems()) {
            if (l.produto() != null) {
                ctx.etiquetas.adicionar(l.produto().id(), 1);
                n++;
            }
        }
        Toasts.success(dono, n + " produtos na fila de etiquetas.");
    }
}
