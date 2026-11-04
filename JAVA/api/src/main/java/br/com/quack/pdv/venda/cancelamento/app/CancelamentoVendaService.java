package br.com.quack.pdv.venda.cancelamento.app;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.venda.cancelamento.domain.CancelamentoResultado;
import br.com.quack.pdv.venda.cancelamento.domain.TrilhaRegistro;
import br.com.quack.pdv.venda.cancelamento.domain.VendaSnapshot;
import br.com.quack.pdv.venda.cancelamento.repo.CancelamentoRepository;
import br.com.quack.pdv.venda.fiado.app.FiadoEstornos;
import br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cancelamento de venda autorizado por gerente (HistoricoVendasAB.CancelarVendaComSupervisorAsync) e gravacao das
 * tentativas negadas (VendaCancelamentoAB.RegistrarNegadaAsync). Tudo numa transacao so: status da venda, estorno de
 * estoque e fiado, AuditLog e a trilha com a fotografia. Os lotes voltam depois do commit. Melhoria D13: os produtos
 * sao travados em ordem de codigo antes de qualquer estorno.
 */
@Service
public class CancelamentoVendaService {

    private static final Logger LOG = LoggerFactory.getLogger(CancelamentoVendaService.class);

    static final String MENSAGEM_SUCESSO = "Venda cancelada e mercadorias estornadas ao estoque com sucesso.";
    static final String MENSAGEM_FIADO_PARCIAL = "O cliente já pagou parte deste fiado: o saldo devedor é menor que "
            + "o valor da venda. Faça o estorno pelo financeiro.";

    /** Quem pediu, quem autorizou e por que. */
    public record Pedido(String companyId, String saleNumber, String supervisorId, String supervisorNome,
                         String operadorId, String operadorNome, String justificativa, String terminal, String ip) {}

    private final CancelamentoRepository repo;
    private final RegistradorEstoque registrador;
    private final BaixaEstoqueVenda travas;
    private final FiadoEstornos fiado;
    private final LoteOperacoes lotes;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNova;

    public CancelamentoVendaService(CancelamentoRepository repo, RegistradorEstoque registrador,
                                    BaixaEstoqueVenda travas, FiadoEstornos fiado, LoteOperacoes lotes, Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.registrador = registrador;
        this.travas = travas;
        this.fiado = fiado;
        this.lotes = lotes;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.txNova = new TransactionTemplate(transactionManager);
        this.txNova.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Cancela a venda. Recusas voltam no resultado (com codigo); erros inesperados viram o codigo ERRO. */
    public CancelamentoResultado cancelar(Pedido p) {
        CancelamentoResultado r;
        String vendaId;
        try {
            Saida saida = tx.execute(s -> executar(p));
            r = saida.resultado();
            vendaId = saida.vendaId();
        } catch (RuntimeException ex) {
            LOG.warn("Cancelamento da venda {} falhou: {}", p.saleNumber(), mensagemRaiz(ex));
            String msg = mensagemRaiz(ex).contains(FiadoEstornos.REVISAO_DE_ESTORNO) ? MENSAGEM_FIADO_PARCIAL
                    : "Erro ao cancelar venda: " + mensagemRaiz(ex);
            return CancelamentoResultado.recusa(msg, "ERRO");
        }
        if (r.sucesso()) {
            // Devolve aos lotes de origem o que a venda tirou deles. Seguro: nunca lanca excecao.
            lotes.estornarVendaSeguro(p.companyId(), vendaId);
        }
        return r;
    }

    private record Saida(CancelamentoResultado resultado, String vendaId) {}

    private Saida executar(Pedido p) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String justificativa = p.justificativa().strip();

        Optional<String> trava = repo.travarVendaPorNumero(p.companyId(), p.saleNumber());
        if (trava.isEmpty()) {
            return new Saida(CancelamentoResultado.recusa("Venda não encontrada.", "NAO_ENCONTRADA"), null);
        }
        String vendaId = trava.get();
        VendaSnapshot snapshot = repo.capturar(p.companyId(), vendaId, p.saleNumber())
                .orElseThrow(() -> new IllegalStateException("Venda não encontrada."));

        String status = snapshot.status().strip().toLowerCase(Locale.ROOT);
        if (status.equals("cancelada")) {
            return new Saida(CancelamentoResultado.recusa("Esta venda já está cancelada.", "JA_CANCELADA"), vendaId);
        }
        if (!status.equals("finalizada")) {
            String msg = status.equals("corrigida")
                    ? "Esta venda foi corrigida e substituída por outra. Cancele a venda nova gerada pela correção."
                    : "Venda com status \"" + snapshot.status() + "\" não pode ser cancelada.";
            return new Saida(CancelamentoResultado.recusa(msg, "STATUS_INVALIDO"), vendaId);
        }

        Optional<VendaSnapshot.Nota> autorizada = snapshot.notas().stream()
                .filter(n -> n.status() == STATUS_AUTORIZADO).findFirst();
        if (autorizada.isPresent()) {
            VendaSnapshot.Nota n = autorizada.get();
            String tipo = n.modelo() == 55 ? "NF-e" : "NFC-e";
            return new Saida(CancelamentoResultado.recusaDeNota("Esta venda tem " + tipo + " autorizada (nº "
                    + n.numero() + "). O cancelamento precisa ser feito na SEFAZ: cancele a " + tipo
                    + ", que cancela a venda e estorna o estoque junto.", "NOTA_AUTORIZADA", n.id(), n.modelo()),
                    vendaId);
        }
        // Nota ainda na fila (assinada, transmitindo ou em contingencia): a venda pode ser cancelada e a fila fiscal
        // nao emite a nota (inutiliza o numero).
        Optional<VendaSnapshot.Nota> pendente = snapshot.notas().stream()
                .filter(n -> n.status() == STATUS_ASSINADO || n.status() == STATUS_TRANSMITINDO
                        || n.status() == STATUS_CONTINGENCIA_PENDENTE).findFirst();
        String avisoNota = pendente.map(n -> (n.modelo() == 55 ? "NF-e" : "NFC-e") + " nº " + n.numero()
                + " ainda não autorizada: não será emitida (número inutilizado) ou, se já foi transmitida, será "
                + "cancelada na SEFAZ automaticamente.").orElse(null);

        repo.marcarCancelada(p.companyId(), vendaId, agora, p.operadorId(), p.operadorNome(), p.supervisorId(),
                p.supervisorNome(), justificativa);

        List<VendaSnapshot.Item> paraEstornar = snapshot.itens().stream()
                .filter(i -> !i.codigo().isBlank() && i.quantidade().signum() > 0).toList();
        travas.travarEmOrdemDeCodigo(p.companyId(), paraEstornar.stream().map(i -> i.codigo().strip()).toList());
        for (VendaSnapshot.Item i : paraEstornar) {
            registrador.aplicarPorCodigo(p.companyId(), i.codigo().strip(), i.quantidade(), EstoqueTipos.DEVOLUCAO,
                    null, new MovimentoOrigem("venda", p.saleNumber(), "Cancelamento da venda #" + p.saleNumber(),
                            p.supervisorId(), p.supervisorNome()));
        }

        BigDecimal fiadoEstornado = fiado.estornarFiadoDaVenda(p.companyId(), vendaId, p.operadorNome());

        String resumoItens = String.join("; ", snapshot.itens().stream()
                .map(i -> Dinheiro.formatarQuantidade(i.quantidade()) + "x " + i.nome()).toList());
        repo.inserirAuditLog(p.companyId(), p.operadorId(), p.operadorNome(), "VendaCancelada",
                "Venda #" + p.saleNumber() + " cancelada. Total: R$ " + Dinheiro.formatarSemSimbolo(snapshot.totalVenda())
                        + ". Gerente: " + p.supervisorNome() + " (" + p.supervisorId() + "). Motivo: "
                        + justificativa.replaceAll("\\.+$", "") + ". "
                        + (fiadoEstornado.signum() > 0 ? "Fiado estornado: R$ "
                        + Dinheiro.formatarSemSimbolo(fiadoEstornado) + ". " : "")
                        + ("fechada".equals(snapshot.caixaSessaoStatus()) ? "Turno de caixa já fechado. " : "")
                        + (avisoNota == null ? "" : avisoNota + " ") + "Itens: " + resumoItens,
                "Vendas", vendaId, p.ip());
        repo.inserirTrilha(new TrilhaRegistro(p.companyId(), "cancelada", "venda", avisoNota, p.saleNumber(),
                p.operadorId(), p.operadorNome(), p.supervisorId(), p.supervisorNome(), justificativa, p.terminal(),
                p.ip(), paraEstornar.size(), fiadoEstornado, snapshot));

        return new Saida(new CancelamentoResultado(true, MENSAGEM_SUCESSO + (avisoNota == null ? "" : " " + avisoNota),
                paraEstornar.size(), VendaRepository.formatarData(agora), null, null, null, fiadoEstornado), vendaId);
    }

    /**
     * Tentativa negada (senha errada, gerente invalido, venda ja cancelada...): grava em transacao propria, com a
     * fotografia da venda quando ela existe. Nunca lanca: a recusa ao operador nao pode virar erro 500.
     */
    public void registrarNegada(Pedido p, String motivo, String supervisorNomeConhecido) {
        try {
            txNova.executeWithoutResult(s -> gravarNegada(p, motivo, supervisorNomeConhecido));
        } catch (RuntimeException ex) {
            LOG.error("Tentativa negada de cancelamento nao registrada: {}", mensagemRaiz(ex));
        }
    }

    private void gravarNegada(Pedido p, String motivo, String supervisorNomeConhecido) {
        Optional<VendaSnapshot> snapshot = repo.capturar(p.companyId(), null, p.saleNumber());
        String supervisorNome = supervisorNomeConhecido;
        if (supervisorNome == null && p.supervisorId() != null && !p.supervisorId().isBlank()) {
            // Senha errada: o gerente nao foi validado, mas o nome de quem foi escolhido ajuda na auditoria.
            supervisorNome = repo.nomeDoUsuario(p.companyId(), p.supervisorId()).orElse(null);
        }
        String saleNumber = snapshot.map(VendaSnapshot::saleNumber).orElse(p.saleNumber());
        repo.inserirTrilha(new TrilhaRegistro(p.companyId(), "negada", "venda", motivo, saleNumber, p.operadorId(),
                p.operadorNome(), p.supervisorId(), supervisorNome, p.justificativa(), p.terminal(), p.ip(), null,
                null, snapshot.orElse(null)));
        String quemInformou = supervisorNome != null ? supervisorNome : p.supervisorId();
        repo.inserirAuditLog(p.companyId(), p.operadorId(), p.operadorNome(), "CancelamentoVendaNegado",
                "Cancelamento da venda #" + saleNumber + " negado: " + motivo
                        + (quemInformou == null || quemInformou.isBlank() ? "" : " Gerente informado: " + quemInformou + ".")
                        + (p.terminal() == null || p.terminal().isBlank() ? "" : " Terminal: " + p.terminal() + "."),
                "Vendas", snapshot.map(VendaSnapshot::vendaId).orElse(truncar(p.saleNumber(), 80)), p.ip());
    }

    private static final int STATUS_ASSINADO = 1;
    private static final int STATUS_TRANSMITINDO = 2;
    private static final int STATUS_AUTORIZADO = 3;
    private static final int STATUS_CONTINGENCIA_PENDENTE = 8;

    private static String truncar(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    static String mensagemRaiz(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
