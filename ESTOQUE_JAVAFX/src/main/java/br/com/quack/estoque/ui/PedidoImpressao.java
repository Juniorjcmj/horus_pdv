package br.com.quack.estoque.ui;

import br.com.quack.estoque.domain.Fmt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.layout.VBox;

/**
 * Documentos de impressão do pedido de compra: o pedido (com custos, para o fornecedor escolhido) e a solicitação de
 * cotação (sem os nossos custos, com preço, total, prazo e condição em branco para cada fornecedor preencher).
 */
public final class PedidoImpressao {
    /** Linha do documento: código, produto, quantidade, unidade e custo unitário (pode ser zero). */
    public record Linha(String codigo, String nome, BigDecimal quantidade, String unidade, BigDecimal custo) {
        BigDecimal total() {
            return quantidade.multiply(custo == null ? BigDecimal.ZERO : custo).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private PedidoImpressao() {
    }

    public static VBox pedido(String numero, String fornecedor, String usuario, List<Linha> itens) {
        List<List<String>> linhas = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Linha l : itens) {
            linhas.add(List.of(l.codigo(), l.nome(), qtd(l), Fmt.dec2(l.custo()), Fmt.dec2(l.total())));
            total = total.add(l.total());
        }
        return Documento.tabela("Pedido de compra " + numero,
                List.of("Fornecedor: " + fornecedor, "Emitido em " + Fmt.date(LocalDate.now()) + " por " + usuario),
                List.of(Documento.Col.texto("Código", 12), Documento.Col.texto("Produto", 44), Documento.Col.numero("Quantidade", 14),
                        Documento.Col.numero("Custo unit.", 14), Documento.Col.numero("Total", 14)),
                linhas, List.of("Total do pedido: " + Fmt.money(total)));
    }

    public static VBox cotacao(String numero, String usuario, List<Linha> itens) {
        return Documento.tabela("Solicitação de cotação " + numero,
                List.of("Fornecedor: ________________________________________   Telefone: ____________________",
                        "Solicitado em " + Fmt.date(LocalDate.now()) + " por " + usuario
                                + ". Preencha o preço unitário e devolva com o prazo de entrega."),
                List.of(Documento.Col.texto("Código", 12), Documento.Col.texto("Produto", 44), Documento.Col.numero("Quantidade", 14),
                        Documento.Col.numero("Preço unit.", 14), Documento.Col.numero("Total", 14)),
                linhasCotacao(itens),
                List.of("Prazo de entrega: ______ dias     Condição de pagamento: ______________     Validade da proposta: ______"));
    }

    /** Linhas da cotação: sem custo (o fornecedor preenche). */
    public static List<List<String>> linhasCotacao(List<Linha> itens) {
        List<List<String>> linhas = new ArrayList<>();
        for (Linha l : itens) {
            linhas.add(List.of(l.codigo(), l.nome(), qtd(l), "__________", "__________"));
        }
        return linhas;
    }

    private static String qtd(Linha l) {
        return (Fmt.qty(l.quantidade()) + " " + (l.unidade() == null ? "" : l.unidade())).trim();
    }
}
