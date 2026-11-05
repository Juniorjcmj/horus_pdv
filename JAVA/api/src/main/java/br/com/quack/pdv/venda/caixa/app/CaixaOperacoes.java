package br.com.quack.pdv.venda.caixa.app;

import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessao;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import br.com.quack.pdv.venda.caixa.domain.TipoMovimentoCaixa;
import br.com.quack.pdv.venda.caixa.repo.CaixaRepository;
import br.com.quack.pdv.venda.caixa.repo.CaixaRepository.EventoProcessado;
import br.com.quack.pdv.venda.caixa.repo.CaixaRepository.SituacaoTurno;
import br.com.quack.pdv.venda.registro.HashPayload;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Abrir, fechar e movimentar com idempotencia: porta de AbrirIdempotenteAsync, FecharIdempotenteAsync e
 * RegistrarMovimentoIdempotenteAsync do CaixaAB. A sequencia e a do .NET: confere o evento, grava dentro da transacao
 * (com um placeholder em ProcessedEvents), faz commit, monta o status em outra conexao e so entao atualiza o
 * ResponsePayload do evento (falha nessa atualizacao so e logada).
 */
@Service
public class CaixaOperacoes {

    private static final Logger LOG = LoggerFactory.getLogger(CaixaOperacoes.class);

    private final CaixaRepository repo;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CaixaOperacoes(CaixaRepository repo, PlatformTransactionManager transactionManager, Clock clock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Resultado da transacao: o status guardado (repeticao), a sessao ja existente, ou o id do evento a completar. */
    private record Efeito(CaixaStatusDto repeticao, boolean sessaoExistente, String eventoProcessadoId) {
        static Efeito repeticao(CaixaStatusDto s) { return new Efeito(s, false, null); }
        static Efeito sessaoJaExistia() { return new Efeito(null, true, null); }
        static Efeito gravado(String eventoId) { return new Efeito(null, false, eventoId); }
    }

    // ---------------------------------------------------------------- abrir

    public CaixaStatusDto abrir(String id, String companyId, OffsetDateTime openedAt, BigDecimal openingAmount,
                                String operatorId, String operatorName, String eventId, String payloadHash,
                                Supplier<CaixaStatusDto> statusBuilder, boolean sessaoDoCliente) {
        String evento = aparar(eventId);
        String hash = !HashPayload.emBranco(payloadHash) ? minusculo(payloadHash)
                : (!HashPayload.emBranco(evento)
                        ? HashPayload.calcularAberturaSemHashDoCliente(Dinheiro.formatarSemSimbolo(openingAmount))
                        : "");
        if (!HashPayload.emBranco(evento)) {
            CaixaStatusDto existente = obterEventoProcessado(companyId, evento, hash);
            if (existente != null) return existente;
        }
        Efeito efeito;
        try {
            efeito = tx.execute(s -> gravarAbertura(id, companyId, openedAt, openingAmount, operatorId, operatorName,
                    evento, hash, sessaoDoCliente));
        } catch (DuplicateKeyException e) {
            return repeticaoConcorrente(e, companyId, evento, hash);
        }
        return concluir(efeito, statusBuilder);
    }

    private Efeito gravarAbertura(String id, String companyId, OffsetDateTime openedAt, BigDecimal openingAmount,
                                  String operatorId, String operatorName, String evento, String hash,
                                  boolean sessaoDoCliente) {
        if (!HashPayload.emBranco(evento)) {
            Efeito repeticao = repeticaoComBloqueio(companyId, evento, hash);
            if (repeticao != null) return repeticao;
        }
        if (sessaoDoCliente) {
            // Abertura offline com id gerado no cliente: se a sessao ja existe, e reenvio (idempotente).
            if (repo.sessaoExisteComBloqueio(companyId, id)) return Efeito.sessaoJaExistia();
        } else if (repo.operadorTemCaixaAbertoComBloqueio(companyId, operatorId)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST,
                    "Você já possui um caixa aberto. Feche o caixa atual antes de abrir outro.");
        }
        repo.inserirSessao(id, companyId, openedAt, openingAmount, operatorId, operatorName);
        return Efeito.gravado(inserirEvento(companyId, evento, "CASH_OPEN", hash, openedAt));
    }

    // ---------------------------------------------------------------- fechar

    public CaixaStatusDto fechar(String id, String companyId, OffsetDateTime closedAt, BigDecimal closingAmount,
                                 String closedById, String closedByName, String note, BigDecimal expectedCashAmount,
                                 BigDecimal differenceAmount, String differenceReason, String eventId,
                                 String payloadHash, Supplier<CaixaStatusDto> statusBuilder) {
        String evento = aparar(eventId);
        String hash = !HashPayload.emBranco(payloadHash) ? minusculo(payloadHash)
                : (!HashPayload.emBranco(evento)
                        ? HashPayload.calcularFechamentoSemHashDoCliente(Dinheiro.formatarSemSimbolo(closingAmount),
                                note, differenceReason)
                        : "");
        if (!HashPayload.emBranco(evento)) {
            CaixaStatusDto existente = obterEventoProcessado(companyId, evento, hash);
            if (existente != null) return existente;
        }
        Efeito efeito;
        try {
            efeito = tx.execute(s -> gravarFechamento(id, companyId, closedAt, closingAmount, closedById,
                    closedByName, note, expectedCashAmount, differenceAmount, differenceReason, evento, hash));
        } catch (DuplicateKeyException e) {
            return repeticaoConcorrente(e, companyId, evento, hash);
        }
        return concluir(efeito, statusBuilder);
    }

    private Efeito gravarFechamento(String id, String companyId, OffsetDateTime closedAt, BigDecimal closingAmount,
                                    String closedById, String closedByName, String note,
                                    BigDecimal expectedCashAmount, BigDecimal differenceAmount,
                                    String differenceReason, String evento, String hash) {
        if (!HashPayload.emBranco(evento)) {
            Efeito repeticao = repeticaoComBloqueio(companyId, evento, hash);
            if (repeticao != null) return repeticao;
        }
        SituacaoTurno situacao = repo.situacaoDoTurnoComBloqueio(id, companyId);
        if (situacao == SituacaoTurno.INEXISTENTE) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Sessão de caixa não encontrada.");
        }
        if (situacao == SituacaoTurno.FECHADO) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "O caixa atual já foi fechado.");
        }
        repo.fecharSessao(id, companyId, closedAt, closingAmount, closedById, closedByName, note, expectedCashAmount,
                differenceAmount, differenceReason);
        return Efeito.gravado(inserirEvento(companyId, evento, "CASH_CLOSE", hash, closedAt));
    }

    // ---------------------------------------------------------------- movimento

    /** Dados ja validados do movimento a gravar. */
    public record NovoMovimento(String companyId, CaixaSessao sessao, int tipo, BigDecimal valor, String motivo,
                                String operatorId, String operatorName, String ip, String eventId, String hash) {}

    public CaixaStatusDto movimentar(NovoMovimento m, OffsetDateTime agora, Supplier<CaixaStatusDto> statusBuilder) {
        Efeito efeito;
        try {
            efeito = tx.execute(s -> gravarMovimento(m, agora));
        } catch (DuplicateKeyException e) {
            return repeticaoConcorrente(e, m.companyId(), m.eventId(), m.hash());
        }
        return concluir(efeito, statusBuilder);
    }

    private Efeito gravarMovimento(NovoMovimento m, OffsetDateTime agora) {
        if (!HashPayload.emBranco(m.eventId())) {
            Efeito repeticao = repeticaoComBloqueio(m.companyId(), m.eventId(), m.hash());
            if (repeticao != null) return repeticao;
        }
        // Com EventId o Id do movimento e derivado dele: se o registro do evento se perdeu (restauracao de backup;
        // ProcessedEvents nao e sincronizado) mas o movimento voltou da nuvem, o reenvio reconhece a linha.
        boolean comEvento = !HashPayload.emBranco(m.eventId());
        String movimentoId = comEvento ? idDeMovimento(m.companyId(), m.eventId()) : "cxm-" + clock.millis();
        if (comEvento && repo.movimentoIgual(m.companyId(), movimentoId, m.sessao().id(), m.tipo(), m.valor())) {
            return new Efeito(null, true, inserirEvento(m.companyId(), m.eventId(), "CASH_MOVEMENT", m.hash(), agora));
        }
        if (comEvento && repo.movimentoExiste(m.companyId(), movimentoId)) {
            movimentoId = "cxm-" + clock.millis(); // colisao rara de 13 digitos com outro movimento
        }
        repo.inserirMovimento(movimentoId, m.companyId(), m.sessao().id(), m.tipo(), m.valor(),
                m.motivo(), agora, m.operatorId(), m.operatorName());
        boolean reforco = m.tipo() == TipoMovimentoCaixa.REFORCO;
        repo.inserirAuditoria(m.companyId(), m.operatorId(), m.operatorName(),
                reforco ? "CaixaReforco" : "CaixaSangria", "CaixaSessao", m.sessao().id(),
                (reforco ? "Reforço" : "Sangria") + " de " + Dinheiro.formatarSemSimbolo(m.valor()) + " — "
                        + m.motivo(),
                m.ip());
        // Placeholder DENTRO da transacao (UPDLOCK + unique); o ResponsePayload real entra depois do commit.
        return Efeito.gravado(inserirEvento(m.companyId(), m.eventId(), "CASH_MOVEMENT", m.hash(), agora));
    }

    /** "cxm-" + 13 digitos derivados do SHA-256 de empresa e evento: mesma forma do "cxm-"+millis (o contrato compara o tamanho). */
    static String idDeMovimento(String companyId, String eventId) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((companyId + "|" + eventId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long v = java.nio.ByteBuffer.wrap(h).getLong() >>> 1;
            return "cxm-" + (1_000_000_000_000L + v % 9_000_000_000_000L);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- comuns

    /** Quem so podem mexer no turno: o dono, ou gerente/administrador (EnsureResponsavelPeloCaixa). */
    public static void garantirResponsavel(CaixaSessao sessao, String usuarioId, String perfil) {
        if (sessao.operatorId().equals(usuarioId) || br.com.quack.pdv.security.Perfis.ehGerenteOuAdmin(perfil)) return;
        throw new NegocioException(HttpStatus.BAD_REQUEST, "Esse caixa foi aberto por " + sessao.operatorName()
                + " — só ela ou um gerente/administrador podem mexer nele.");
    }

    /** ObterEventoProcessadoAsync: repeticao guardada; o hash so e conferido quando informado. */
    public CaixaStatusDto obterEventoProcessado(String companyId, String eventId, String hashAtual) {
        return repo.buscarEvento(companyId, eventId).map(ev -> {
            if (!HashPayload.emBranco(hashAtual) && !ev.payloadHash().equalsIgnoreCase(hashAtual)) {
                throw conflito(eventId);
            }
            return CaixaPayload.ler(ev.responsePayload()).comReplay(true);
        }).orElse(null);
    }

    private Efeito repeticaoComBloqueio(String companyId, String eventId, String hash) {
        EventoProcessado ev = repo.buscarEventoComBloqueio(companyId, eventId).orElse(null);
        if (ev == null) return null;
        if (!ev.payloadHash().equalsIgnoreCase(hash)) throw conflito(eventId);
        return Efeito.repeticao(CaixaPayload.ler(ev.responsePayload()).comReplay(true));
    }

    private String inserirEvento(String companyId, String evento, String tipo, String hash, OffsetDateTime quando) {
        if (HashPayload.emBranco(evento)) return null;
        String id = "pe-" + UUID.randomUUID().toString().replace("-", "");
        repo.inserirEvento(id, companyId, evento, tipo, hash, quando);
        return id;
    }

    private CaixaStatusDto concluir(Efeito efeito, Supplier<CaixaStatusDto> statusBuilder) {
        if (efeito.repeticao() != null) return efeito.repeticao();
        CaixaStatusDto status = statusBuilder.get().comReplay(efeito.sessaoExistente());
        if (efeito.eventoProcessadoId() != null) {
            try {
                repo.atualizarRespostaDoEvento(efeito.eventoProcessadoId(), CaixaPayload.serializar(status));
            } catch (RuntimeException e) {
                // O commit ja foi feito: o reenvio devolvera o status ao vivo.
                LOG.warn("Falha ao guardar a resposta do evento {}: {}", efeito.eventoProcessadoId(), e.toString());
            }
        }
        return status;
    }

    /** Outra requisicao com o mesmo (CompanyId, EventId) comitou no mesmo instante: devolve a resposta dela. */
    private CaixaStatusDto repeticaoConcorrente(DuplicateKeyException erro, String companyId, String evento, String hash) {
        if (HashPayload.emBranco(evento)) throw erro;
        CaixaStatusDto repeticao = obterEventoProcessado(companyId, evento, hash);
        if (repeticao != null) return repeticao;
        throw erro;
    }

    private static NegocioException conflito(String eventId) {
        return new NegocioException(HttpStatus.CONFLICT, "Conflito de idempotência: o EventId '" + eventId
                + "' já foi processado anteriormente com um payload diferente.");
    }

    private static String aparar(String valor) {
        return valor == null ? null : HashPayload.trim(valor);
    }

    private static String minusculo(String valor) {
        return HashPayload.trim(valor).toLowerCase(Locale.ROOT);
    }
}
