package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.domain.RupturaAviso;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoItem;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.ProdutoVenda;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Baixa de estoque da venda pela porta unica (BaixarEstoqueAsync e BaixarEstoqueComPrecoFixoAsync). Melhoria D13: os
 * produtos sao travados em ordem de codigo (sem diferenciar maiusculas) antes de qualquer baixa, para que vendas
 * simultaneas com os mesmos produtos em ordem inversa nao entrem em deadlock. A ordem dos itens na resposta e a do
 * pedido de venda; so a ordem das travas muda.
 */
@Component
public class BaixaEstoqueVenda {

    /** Linha pedida na venda. */
    public record LinhaVenda(String productCode, String productName, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal desconto, String promocaoId) {}

    /** Item que foi gravado na venda. */
    public record ItemRegistrado(String productCode, String productName, BigDecimal unitPrice, BigDecimal costPrice,
                                 BigDecimal quantity, BigDecimal desconto, String promocaoId) {}

    /** Itens gravados e avisos de ruptura. */
    public record Baixa(List<ItemRegistrado> itens, List<RupturaAviso> rupturas) {}

    private record Grupo(String codigo, BigDecimal quantidade) {}

    private final VendaRepository repo;
    private final RegistradorEstoque registrador;

    public BaixaEstoqueVenda(VendaRepository repo, RegistradorEstoque registrador) {
        this.repo = repo;
        this.registrador = registrador;
    }

    /** Trava os produtos em ordem de codigo (D13). Produto inexistente e ignorado aqui; a baixa acusa o erro. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void travarEmOrdemDeCodigo(String companyId, List<String> codigos) {
        codigos.stream().map(HashPayload::trim).distinct().sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(c -> repo.travarProduto(companyId, c));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Baixa baixar(String companyId, List<LinhaVenda> linhas, boolean permitirNegativo, String origem) {
        if (linhas.isEmpty()) {
            throw new IllegalArgumentException("Venda deve conter ao menos um item.");
        }
        Map<String, Grupo> grupos = new LinkedHashMap<>();
        for (LinhaVenda l : linhas) {
            String codigo = HashPayload.trim(l.productCode());
            grupos.merge(codigo.toUpperCase(Locale.ROOT), new Grupo(codigo, l.quantity()),
                    (a, b) -> new Grupo(a.codigo(), a.quantidade().add(b.quantidade())));
        }
        travarEmOrdemDeCodigo(companyId, grupos.values().stream().map(Grupo::codigo).toList());

        Map<String, ProdutoVenda> info = new LinkedHashMap<>();
        List<RupturaAviso> rupturas = new ArrayList<>();
        for (Map.Entry<String, Grupo> e : grupos.entrySet()) {
            Grupo g = e.getValue();
            if (g.quantidade().signum() <= 0) {
                throw new IllegalArgumentException("Quantidade da venda deve ser maior que zero.");
            }
            ProdutoVenda p = repo.produtoParaVenda(companyId, g.codigo())
                    .orElseThrow(() -> new IllegalArgumentException("Produto " + g.codigo() + " não encontrado."));
            if (p.estoque().compareTo(g.quantidade()) < 0) {
                if (!permitirNegativo) {
                    throw new IllegalArgumentException("Estoque insuficiente para " + p.productName()
                            + ". Disponível: " + Dinheiro.formatarQuantidade(p.estoque()) + ".");
                }
                rupturas.add(new RupturaAviso(g.codigo(), p.productName(), p.estoque(), g.quantidade(),
                        p.estoque().subtract(g.quantidade())));
            }
            registrador.aplicar(companyId, p.id(), g.quantidade().negate(), EstoqueTipos.SAIDA_VENDA, p.custo(),
                    MovimentoOrigem.sistema("venda", origem, "Venda"));
            info.put(e.getKey(), p);
        }

        List<ItemRegistrado> itens = new ArrayList<>();
        for (LinhaVenda l : linhas) {
            String codigo = HashPayload.trim(l.productCode());
            ProdutoVenda p = info.get(codigo.toUpperCase(Locale.ROOT));
            String nome = l.productName() == null || HashPayload.emBranco(l.productName()) ? p.productName()
                    : HashPayload.trim(l.productName());
            itens.add(new ItemRegistrado(codigo, nome, l.unitPrice(), p.custo(), l.quantity(),
                    SaleMoney.money(l.desconto()), l.promocaoId()));
        }
        return new Baixa(itens, rupturas);
    }

    /** Baixa de pedido: preco congelado, sempre exige saldo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Baixa baixarComPrecoFixo(String companyId, List<PedidoItem> pedido, String origem) {
        travarEmOrdemDeCodigo(companyId, pedido.stream().map(PedidoItem::productCode).toList());
        List<ItemRegistrado> itens = new ArrayList<>();
        for (PedidoItem item : pedido) {
            String codigo = HashPayload.trim(item.productCode());
            if (item.quantity().signum() <= 0) {
                throw new IllegalArgumentException("Quantidade do pedido deve ser maior que zero.");
            }
            ProdutoVenda p = repo.produtoParaVenda(companyId, codigo)
                    .orElseThrow(() -> new IllegalArgumentException("Produto " + codigo + " não encontrado."));
            if (p.estoque().compareTo(item.quantity()) < 0) {
                throw new IllegalArgumentException("Estoque insuficiente para " + p.productName()
                        + ". Disponível: " + Dinheiro.formatarQuantidade(p.estoque()) + ".");
            }
            registrador.aplicar(companyId, p.id(), item.quantity().negate(), EstoqueTipos.SAIDA_VENDA, p.custo(),
                    MovimentoOrigem.sistema("venda", origem, "Venda de pedido"));
            itens.add(new ItemRegistrado(codigo, p.productName(), item.unitPrice(), p.custo(), item.quantity(),
                    BigDecimal.ZERO, null));
        }
        return new Baixa(itens, List.of());
    }
}
