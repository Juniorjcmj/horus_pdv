package br.com.quack.pdv.venda.fiado.app;

import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.fiado.domain.FiadoDtos;
import br.com.quack.pdv.venda.fiado.repo.FiadoRepository;
import br.com.quack.pdv.venda.fiado.repo.FiadoRepository.ClienteSaldo;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.app.SaleMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Conta corrente do fiado (FiadoService + FiadoAB): recebimento atomico, extrato, devedores e resumo. */
@Service
public class FiadoService {

    private static final Logger LOG = LoggerFactory.getLogger(FiadoService.class);
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal TOLERANCIA = new BigDecimal("0.009");

    private final FiadoRepository repo;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final TransactionTemplate tx;

    public FiadoService(FiadoRepository repo, AuditoriaService auditoria, Clock clock,
                        PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.repo = repo;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(clock).atZoneSameInstant(BRASILIA).toOffsetDateTime();
    }

    /** Recebimento: a trava do cliente (UPDLOCK) serializa recebimentos concorrentes do mesmo cliente. */
    public FiadoDtos.Movimento receber(String companyId, String operadorId, String operadorNome, String clienteId,
                                       BigDecimal valor, String formaPagamento, String observacao) {
        if (HashPayload.emBranco(clienteId)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST,
                    "Cliente é obrigatório para registrar recebimento de fiado.");
        }
        if (valor.signum() <= 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "O valor recebido deve ser maior que zero.");
        }
        String forma = HashPayload.emBranco(formaPagamento) ? "dinheiro" : HashPayload.trim(formaPagamento);
        FiadoDtos.Movimento mov = tx.execute(st -> gravarCredito(companyId, HashPayload.trim(clienteId), valor, forma,
                observacao, operadorNome));
        auditar(companyId, operadorId, operadorNome, mov, valor, forma);
        return mov;
    }

    private FiadoDtos.Movimento gravarCredito(String companyId, String clienteId, BigDecimal valor, String forma,
                                      String observacao, String operadorNome) {
        try {
            ClienteSaldo c = repo.travarCliente(companyId, clienteId)
                    .orElseThrow(() -> new IllegalArgumentException("Cliente " + clienteId + " não encontrado."));
            if (valor.compareTo(c.saldo().add(TOLERANCIA)) > 0) {
                throw new IllegalArgumentException("Valor do recebimento (R$ " + SaleMoney.n2(valor)
                        + ") não pode ser maior que o saldo devedor (R$ " + SaleMoney.n2(c.saldo()) + ").");
            }
            BigDecimal atual = c.saldo().subtract(valor).max(BigDecimal.ZERO);
            OffsetDateTime agora = agora();
            String id = "fm-" + UUID.randomUUID().toString().replace("-", "");
            repo.atualizarSaldo(companyId, clienteId, atual);
            repo.inserirCredito(id, companyId, clienteId, valor, c.saldo(), atual, forma,
                    HashPayload.emBranco(observacao) ? "Recebimento de fiado" : HashPayload.trim(observacao),
                    HashPayload.emBranco(operadorNome) ? "Operador" : operadorNome, agora);
            return new FiadoDtos.Movimento(id, companyId, clienteId, c.nome(), c.documento(), 2, valor, c.saldo(),
                    atual, null, forma, observacao, operadorNome, agora);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** O .NET dispara a auditoria sem esperar (fire-and-forget): falha nela nunca desfaz o recebimento. */
    private void auditar(String companyId, String operadorId, String operadorNome, FiadoDtos.Movimento mov,
                         BigDecimal valor, String forma) {
        try {
            auditoria.registrar(companyId, operadorId, operadorNome, "FiadoRecebimento",
                    "Recebeu " + Dinheiro.formatarSemSimbolo(valor) + " do cliente " + mov.clienteNome() + " via "
                            + forma + ". Saldo atual: " + Dinheiro.formatarSemSimbolo(mov.saldoAtual()) + ".",
                    "Cliente", mov.clienteId(), null);
        } catch (RuntimeException e) {
            LOG.warn("Falha ao auditar o recebimento de fiado do cliente {}: {}", mov.clienteId(), e.toString());
        }
    }

    @Transactional(readOnly = true)
    public List<FiadoDtos.Movimento> extrato(String companyId, String clienteId, OffsetDateTime inicio,
                                             OffsetDateTime fim) {
        return repo.extrato(companyId, clienteId, inicio, fim);
    }

    @Transactional(readOnly = true)
    public List<FiadoDtos.Devedor> devedores(String companyId, String busca) {
        String termo = HashPayload.emBranco(busca) ? null : HashPayload.trim(busca);
        OffsetDateTime agora = agora();
        return repo.devedores(companyId, termo, ultima -> diasDesde(ultima, agora));
    }

    @Transactional(readOnly = true)
    public FiadoDtos.Resumo resumo(String companyId) {
        FiadoRepository.Totais t = repo.totais(companyId);
        OffsetDateTime agora = agora();
        BigDecimal em30 = BigDecimal.ZERO;
        BigDecimal em60 = BigDecimal.ZERO;
        for (FiadoRepository.SaldoUltimaCompra l : repo.saldosComUltimaCompra(companyId)) {
            if (l.ultimaCompra() == null) continue;
            long dias = Duration.between(l.ultimaCompra(), agora).toSeconds() / 86_400;
            if (dias >= 60) {
                em60 = em60.add(l.saldo());
                em30 = em30.add(l.saldo());
            } else if (dias >= 30) {
                em30 = em30.add(l.saldo());
            }
        }
        return new FiadoDtos.Resumo(t.totalAReceber(), t.quantidadeDevedores(), em30, em60, t.maiorDebito());
    }

    /** Math.Max(0, (int)(agora - ultima).TotalDays). */
    private static int diasDesde(OffsetDateTime ultima, OffsetDateTime agora) {
        long segundos = Duration.between(ultima, agora).toSeconds();
        return (int) Math.max(0, segundos / 86_400);
    }
}
