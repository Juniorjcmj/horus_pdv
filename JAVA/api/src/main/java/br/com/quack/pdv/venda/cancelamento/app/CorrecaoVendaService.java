package br.com.quack.pdv.venda.cancelamento.app;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.cancelamento.domain.CorrecaoResultado;
import br.com.quack.pdv.venda.cancelamento.repo.CorrecaoRepository;
import br.com.quack.pdv.venda.cancelamento.repo.CorrecaoRepository.ItemOriginal;
import br.com.quack.pdv.venda.cancelamento.repo.CorrecaoRepository.VendaTravada;
import br.com.quack.pdv.venda.fiado.app.FiadoDebitos;
import br.com.quack.pdv.venda.fiado.app.FiadoEstornos;
import br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda;
import br.com.quack.pdv.venda.registro.app.SaleMoney;
import br.com.quack.pdv.venda.registro.domain.VendaPagamentoDto;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.ProdutoVenda;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Correcao de venda (HistoricoVendasAB.CorrigirVendaAsync): porte literal. Remove produtos da venda, devolve o estoque
 * de TODA a venda original, gera uma venda nova com os itens restantes (numerada pelo NumeradorDocumentos) e marca a
 * original como "corrigida". Os produtos sao travados em ordem de codigo (D13). Lotes e decisao fiscal ficam para
 * depois do commit.
 */
@Service
public class CorrecaoVendaService {

    /** Precisao do decimal do C# (28 digitos significativos) para a proporcao dos pagamentos. */
    private static final MathContext DECIMAL_CSHARP = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** Pagamento da venda nova (VendaPagamentoRequest). */
    private static final class NovoPagamento {
        final String tipo;
        BigDecimal amount;
        BigDecimal cashGiven;
        BigDecimal changeAmount = BigDecimal.ZERO;

        NovoPagamento(String tipo, BigDecimal amount, BigDecimal cashGiven) {
            this.tipo = tipo;
            this.amount = amount;
            this.cashGiven = cashGiven;
        }
    }

    private record Saida(CorrecaoResultado resultado, String vendaOriginalId, String novaVendaId,
                         List<LoteOperacoes.ItemVenda> itensNovos) {}

    private final CorrecaoRepository repo;
    private final VendaRepository vendas;
    private final RegistradorEstoque registrador;
    private final BaixaEstoqueVenda travas;
    private final NumeradorDocumentos numerador;
    private final FiadoEstornos estornos;
    private final FiadoDebitos debitos;
    private final LoteOperacoes lotes;
    private final Clock clock;
    private final TransactionTemplate tx;

    public CorrecaoVendaService(CorrecaoRepository repo, VendaRepository vendas, RegistradorEstoque registrador,
                                BaixaEstoqueVenda travas, NumeradorDocumentos numerador, FiadoEstornos estornos,
                                FiadoDebitos debitos, LoteOperacoes lotes, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.vendas = vendas;
        this.registrador = registrador;
        this.travas = travas;
        this.numerador = numerador;
        this.estornos = estornos;
        this.debitos = debitos;
        this.lotes = lotes;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Corrige a venda. Regras violadas viram 400 com a mensagem da .NET (InvalidOperationException). */
    public CorrecaoResultado corrigir(String companyId, String saleNumber, List<String> produtosParaRemover,
                                      String responsavelId, String responsavelNome, String justificativa) {
        Saida saida = tx.execute(s -> executar(companyId, saleNumber, produtosParaRemover, responsavelId,
                responsavelNome, justificativa));
        // Pos-commit: estorno e novo consumo de lotes FEFO (seguro, nunca lanca).
        lotes.estornarVendaSeguro(companyId, saida.vendaOriginalId());
        lotes.consumirVendaSeguro(companyId, saida.novaVendaId(), saida.itensNovos());
        return saida.resultado();
    }

    private Saida executar(String companyId, String saleNumber, List<String> produtosParaRemover,
                           String responsavelId, String responsavelNome, String justificativa) {
        numerador.serializarVendas(companyId);
        OffsetDateTime agoraUtc = OffsetDateTime.now(clock);
        String just = justificativa.strip();

        VendaTravada venda = repo.travarVenda(companyId, saleNumber)
                .orElseThrow(() -> invalida("Venda não encontrada."));
        if (!venda.status().equalsIgnoreCase("finalizada")) {
            throw invalida("Apenas vendas finalizadas podem ser corrigidas. Status atual: " + venda.status() + ".");
        }
        String vendaId = venda.id();

        List<ItemOriginal> todos = repo.itens(companyId, vendaId);
        if (todos.isEmpty()) throw invalida("Venda sem itens.");

        Set<String> remover = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        produtosParaRemover.forEach(c -> remover.add(c.strip()));
        List<ItemOriginal> restantes = todos.stream().filter(i -> !remover.contains(i.productCode())).toList();
        List<ItemOriginal> removidos = todos.stream().filter(i -> remover.contains(i.productCode())).toList();
        if (removidos.isEmpty()) throw invalida("Nenhum item válido selecionado para remoção.");
        if (restantes.isEmpty()) {
            throw invalida("Não é possível remover todos os itens. Para isso, utilize o cancelamento da venda.");
        }

        List<VendaPagamentoDto> pagamentos = vendas.pagamentos(companyId, vendaId);

        repo.marcarCorrigida(companyId, vendaId, agoraUtc, responsavelId, responsavelNome, just);

        // D13: trava todos os produtos da venda original em ordem de codigo antes de qualquer movimento.
        travas.travarEmOrdemDeCodigo(companyId, todos.stream().map(ItemOriginal::productCode).toList());
        for (ItemOriginal item : todos) {
            if (item.quantity().signum() > 0 && !item.productCode().isBlank()) {
                registrador.aplicarPorCodigo(companyId, item.productCode(), item.quantity(), EstoqueTipos.DEVOLUCAO,
                        null, new MovimentoOrigem("correcao", saleNumber,
                                "Correcao da venda #" + saleNumber + " (devolucao da original)", responsavelId,
                                responsavelNome));
            }
        }

        repo.marcarDocumentosDaOriginal(companyId, vendaId, agoraUtc, responsavelId, responsavelNome, just);

        var debitoFiado = estornos.debitoDaVenda(companyId, vendaId);

        BigDecimal novoTotal = restantes.stream().map(ItemOriginal::itemTotal).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        BigDecimal proporcao = venda.total().signum() > 0
                ? novoTotal.divide(venda.total(), DECIMAL_CSHARP) : BigDecimal.ZERO;
        List<NovoPagamento> novos = proporcionais(pagamentos, proporcao, novoTotal);
        String novoPaymentType = novos.size() == 1 ? novos.getFirst().tipo : "Múltiplo";

        String origemProvisoria = RegistradorEstoque.novaOrigemProvisoria();
        List<BaixaItem> baixados = new ArrayList<>();
        for (ItemOriginal item : restantes) {
            ProdutoVenda produto = vendas.produtoParaVenda(companyId, item.productCode())
                    .orElseThrow(() -> invalida("Produto " + item.productCode() + " não encontrado ao re-baixar estoque."));
            registrador.aplicar(companyId, produto.id(), item.quantity().negate(), EstoqueTipos.SAIDA_VENDA,
                    produto.custo(), new MovimentoOrigem("correcao", origemProvisoria, "Venda corrigida",
                            responsavelId, responsavelNome));
            baixados.add(new BaixaItem(item));
        }

        OffsetDateTime agora = agoraBrasilia();
        String novoNumero = numerador.proximoNumeroVenda(companyId);
        registrador.vincularOrigem(companyId, origemProvisoria, novoNumero, "Venda #" + novoNumero);
        String novaVendaId = "sale-" + companyId + "-" + novoNumero;
        inserirVendaNova(companyId, novaVendaId, novoNumero, venda, novoPaymentType, novoTotal, baixados, novos,
                agora);

        repo.vincular(companyId, vendaId, novaVendaId);

        vendas.inserirAuditoria(companyId, responsavelNome, "VendaCorrigida", "Venda #" + saleNumber
                + " corrigida → nova venda #" + novoNumero + ". Itens removidos: " + removidos.size()
                + ". Total original: R$ " + Dinheiro.formatarSemSimbolo(venda.total()) + ", novo total: R$ "
                + Dinheiro.formatarSemSimbolo(novoTotal) + ". Motivo: " + just, "Vendas", vendaId);

        if (debitoFiado.isPresent() && debitoFiado.get().valor().signum() > 0) {
            estornos.reverterDebito(companyId, debitoFiado.get().clienteId(), vendaId, debitoFiado.get().valor(),
                    responsavelNome);
            BigDecimal reposicao = SaleMoney.money(debitoFiado.get().valor().multiply(proporcao, DECIMAL_CSHARP));
            if (reposicao.signum() > 0) {
                debitos.registrarDebito(companyId, debitoFiado.get().clienteId(), reposicao, novaVendaId,
                        venda.operatorName(), true);
            }
        }

        CorrecaoResultado resultado = new CorrecaoResultado(saleNumber, novoNumero,
                VendaRepository.formatarData(agoraUtc), responsavelNome, removidos.size(),
                Dinheiro.formatarSemSimbolo(novoTotal), false);
        List<LoteOperacoes.ItemVenda> itensNovos = baixados.stream()
                .map(b -> new LoteOperacoes.ItemVenda(b.item().productCode(), b.item().quantity())).toList();
        return new Saida(resultado, vendaId, novaVendaId, itensNovos);
    }

    private record BaixaItem(ItemOriginal item) {}

    /** Pagamentos proporcionais ao novo total; o ultimo fecha a diferenca e o dinheiro recebido vira o valor, sem troco. */
    private static List<NovoPagamento> proporcionais(List<VendaPagamentoDto> originais, BigDecimal proporcao,
                                                     BigDecimal novoTotal) {
        List<NovoPagamento> novos = new ArrayList<>();
        for (VendaPagamentoDto p : originais) {
            novos.add(new NovoPagamento(p.paymentType(), SaleMoney.money(p.amount().multiply(proporcao, DECIMAL_CSHARP)),
                    p.cashGiven() == null ? null
                            : SaleMoney.money(p.cashGiven().multiply(proporcao, DECIMAL_CSHARP))));
        }
        BigDecimal soma = novos.stream().map(n -> n.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!novos.isEmpty() && soma.compareTo(novoTotal) != 0) {
            NovoPagamento ultimo = novos.getLast();
            ultimo.amount = ultimo.amount.add(novoTotal.subtract(soma));
        }
        // A venda nova nao tem entrega de dinheiro de verdade: "recebido" = valor e troco zero.
        for (NovoPagamento n : novos) {
            if (n.cashGiven != null) {
                n.cashGiven = n.amount;
                n.changeAmount = BigDecimal.ZERO;
            }
        }
        return novos;
    }

    /** InserirVendaAsync da correcao: venda, pagamentos e itens. */
    private void inserirVendaNova(String companyId, String saleId, String saleNumber, VendaTravada original,
                                  String paymentType, BigDecimal total, List<BaixaItem> itens,
                                  List<NovoPagamento> pagamentos, OffsetDateTime agora) {
        vendas.inserirVenda(saleId, companyId, saleNumber, original.customerName(), original.customerCpf(),
                paymentType, total, original.operatorName(), agora, null, null, agora, original.caixaSessaoId());
        if (pagamentos.isEmpty()) {
            String tipo = paymentType == null || paymentType.isBlank() || paymentType.equals("-") ? "dinheiro"
                    : paymentType;
            vendas.inserirPagamento(saleId + "-pag-001", companyId, saleId, tipo, total, total, BigDecimal.ZERO,
                    agora);
        }
        for (int i = 0; i < pagamentos.size(); i++) {
            NovoPagamento p = pagamentos.get(i);
            String tipo = p.tipo == null || p.tipo.isBlank() ? "dinheiro" : p.tipo.strip();
            vendas.inserirPagamento(saleId + "-pag-" + String.format(Locale.ROOT, "%03d", i + 1), companyId, saleId,
                    tipo, p.amount, p.cashGiven, p.changeAmount, agora);
        }
        for (int i = 0; i < itens.size(); i++) {
            ItemOriginal item = itens.get(i).item();
            BigDecimal itemTotal = SaleMoney.itemNet(item.quantity(), item.unitPrice(), item.desconto());
            vendas.inserirItem(saleId + "-item-" + String.format(Locale.ROOT, "%03d", i + 1), saleId,
                    item.productCode(), item.productName(), item.quantity(), item.unitPrice(), item.costPrice(),
                    item.desconto(), itemTotal, item.promocaoId());
        }
    }

    private OffsetDateTime agoraBrasilia() {
        return OffsetDateTime.now(clock).atZoneSameInstant(BRASILIA).toOffsetDateTime();
    }

    private static NegocioException invalida(String mensagem) {
        return new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
