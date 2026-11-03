package br.com.quack.pdv.venda.caixa.app;

import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.domain.CaixaMovimentoDto;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessao;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessaoDto;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import br.com.quack.pdv.venda.caixa.domain.TipoMovimentoCaixa;
import br.com.quack.pdv.venda.caixa.repo.CaixaRepository;
import br.com.quack.pdv.venda.registro.HashPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turno de caixa: porta do HorusCaixaService da API .NET (status, sessao, movimentos por periodo, abrir, fechar,
 * sangria/reforco, bloqueio de venda). A persistencia idempotente fica no {@link CaixaOperacoes}.
 */
@Service
public class CaixaService {

    private static final Logger LOG = LoggerFactory.getLogger(CaixaService.class);
    private static final Pattern ID_SESSAO_CLIENTE = Pattern.compile("^cx-[A-Za-z0-9-]{1,36}$");
    private static final ZoneOffset OFFSET_BRASILIA = ZoneOffset.ofHours(-3);

    private final CaixaRepository repo;
    private final CaixaStatusService status;
    private final CaixaOperacoes operacoes;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final TransactionTemplate txNova;

    public CaixaService(CaixaRepository repo, CaixaStatusService status, CaixaOperacoes operacoes,
                        AuditoriaService auditoria, Clock clock, PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.status = status;
        this.operacoes = operacoes;
        this.auditoria = auditoria;
        this.clock = clock;
        this.txNova = new TransactionTemplate(transactionManager);
        this.txNova.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- consultas

    public CaixaStatusDto status(UsuarioAutenticado usuario) {
        return status.statusDoUsuario(usuario);
    }

    public Optional<CaixaSessaoDto> sessao(UsuarioAutenticado usuario, String id) {
        return status.sessaoVisivel(usuario, id);
    }

    /** Data de hoje no fuso de Brasilia (HorusDateTime.Now.Date). */
    public LocalDate hoje() {
        return CaixaTempo.agora(clock).toLocalDate();
    }

    /** Sangrias e reforcos no periodo (datas inclusivas); so gerente/administrador ve os de todos os operadores. */
    public List<CaixaMovimentoDto> movimentosPorPeriodo(UsuarioAutenticado usuario, LocalDate de, LocalDate ate) {
        OffsetDateTime inicio = de.atStartOfDay().atOffset(OFFSET_BRASILIA);
        OffsetDateTime fim = ate.plusDays(1).atStartOfDay().atOffset(OFFSET_BRASILIA);
        String operador = Perfis.ehGerenteOuAdmin(usuario.role()) ? null : usuario.id();
        return repo.listarMovimentosPorPeriodo(usuario.companyId(), inicio, fim, operador).stream()
                .map(CaixaStatusService::paraDto).toList();
    }

    /** Turno aberto do operador (para vincular a venda ao caixa). */
    public Optional<CaixaSessao> sessaoAbertaDoOperador(String companyId, String operatorId) {
        return repo.obterSessaoAberta(companyId, operatorId);
    }

    /** Bloqueia a venda quando o caixa do operador nao permite vender (EnsureVendaPermitidaAsync). */
    public void garantirVendaPermitida(UsuarioAutenticado usuario, String ip) {
        CaixaStatusDto atual = status.montar(usuario.companyId(), usuario.id(), CaixaTempo.agora(clock));
        if (atual.canSell()) return;
        // A trilha precisa sobreviver ao erro, mesmo que o chamador esteja numa transacao que sera desfeita.
        txNova.executeWithoutResult(t -> auditoria.registrar(usuario.companyId(), usuario.id(), usuario.name(),
                "VendaBloqueada", "Tentou vender com o caixa " + atual.state() + " — " + atual.blockReason(), null,
                null, ip));
        throw negocio(atual.blockReason());
    }

    // ---------------------------------------------------------------- abrir

    public CaixaStatusDto abrir(CaixaComandos.Abrir req, UsuarioAutenticado usuario, String ip) {
        OffsetDateTime agora = CaixaTempo.agora(clock);
        BigDecimal valor = HashPayload.parseDecimal(req.openingAmount());
        String idCliente = normalizarIdSessao(req.clientSessionId());
        String id = idCliente != null ? idCliente : "cx-" + clock.millis();
        OffsetDateTime abertura = agora;
        if (idCliente != null) {
            CaixaTempo.Resolucao r = CaixaTempo.resolverHorario(req.openedAt(), agora);
            abertura = r.horario();
            if (r.foraDaJanela()) {
                LOG.warn("Abertura de caixa {}: horario '{}' fora da janela aceita; usando o horario do servidor.", id,
                        req.openedAt());
            }
        }
        CaixaStatusDto resultado = operacoes.abrir(id, usuario.companyId(), abertura, valor, usuario.id(),
                usuario.name(), req.eventId(), req.payloadHash(),
                () -> status.montar(usuario.companyId(), usuario.id(), CaixaTempo.agora(clock)), idCliente != null);
        if (!resultado.isReplay()) {
            auditoria.registrar(usuario.companyId(), usuario.id(), usuario.name(), "CaixaAbertura",
                    "Abriu o caixa com " + Dinheiro.formatarSemSimbolo(valor) + " de fundo de troco.", "CaixaSessao",
                    id, ip);
        }
        return resultado;
    }

    // ---------------------------------------------------------------- fechar

    public CaixaStatusDto fechar(CaixaComandos.Fechar req, UsuarioAutenticado usuario, String ip) {
        OffsetDateTime agora = CaixaTempo.agora(clock);
        String idCliente = normalizarIdSessao(req.clientSessionId());
        OffsetDateTime fechamento = agora;
        CaixaSessao aberta;
        if (idCliente != null) {
            CaixaSessao alvo = repo.obterSessaoPorId(usuario.companyId(), idCliente).orElseThrow(() -> negocio(
                    "Sessão de caixa " + idCliente + " não encontrada na nuvem."));
            if (alvo.closedAt() != null) {
                CaixaStatusDto existente = repeticaoSeHouver(usuario, req);
                if (existente != null) return existente;
                throw negocio("Sessão já fechada.");
            }
            aberta = alvo;
            CaixaTempo.Resolucao r = CaixaTempo.resolverHorario(req.closedAt(), agora);
            fechamento = r.horario();
            if (r.foraDaJanela()) {
                LOG.warn("Fechamento de caixa {}: horario '{}' fora da janela aceita; usando o horario do servidor.",
                        idCliente, req.closedAt());
            }
            if (fechamento.toInstant().isBefore(aberta.openedAt().toInstant())) {
                throw negocio("O horário de fechamento é anterior ao da abertura do caixa.");
            }
        } else {
            aberta = repo.obterSessaoAberta(usuario.companyId(), usuario.id()).orElse(null);
            if (aberta == null) {
                CaixaStatusDto existente = repeticaoSeHouver(usuario, req);
                if (existente != null) return existente;
                throw negocio("Você não possui caixa aberto para fechamento.");
            }
        }
        CaixaOperacoes.garantirResponsavel(aberta, usuario.id(), usuario.role());
        return fecharTurno(req, usuario, ip, aberta, fechamento);
    }

    private CaixaStatusDto fecharTurno(CaixaComandos.Fechar req, UsuarioAutenticado usuario, String ip,
                                       CaixaSessao aberta, OffsetDateTime fechamento) {
        BigDecimal contado = HashPayload.parseDecimal(req.closingAmount());
        BigDecimal esperado = status.dinheiroEsperado(usuario.companyId(), aberta);
        BigDecimal bruta = contado.subtract(esperado);
        // Math.Round(x, 2): ToEven, e so reduz a escala.
        BigDecimal diferenca = bruta.scale() > 2 ? bruta.setScale(2, RoundingMode.HALF_EVEN) : bruta;
        String motivo = req.differenceReason() == null ? null : HashPayload.trim(req.differenceReason());
        boolean semDiferenca = diferenca.signum() == 0;
        if (!semDiferenca && HashPayload.emBranco(motivo)) {
            throw negocio("Há uma diferença de " + Dinheiro.formatarSemSimbolo(diferenca.abs()) + " ("
                    + (diferenca.signum() > 0 ? "sobra" : "falta") + ") em relação ao esperado ("
                    + Dinheiro.formatarSemSimbolo(esperado) + "). Informe uma justificativa antes de fechar o caixa.");
        }
        String nota = req.note() == null ? "" : HashPayload.trim(req.note());
        CaixaStatusDto resultado = operacoes.fechar(aberta.id(), usuario.companyId(), fechamento, contado,
                usuario.id(), usuario.name(), nota, esperado, diferenca, semDiferenca ? null : motivo, req.eventId(),
                req.payloadHash(), () -> status.montar(usuario.companyId(), usuario.id(), CaixaTempo.agora(clock)));
        if (!resultado.isReplay()) {
            String descricao = "Fechou o caixa: esperado " + Dinheiro.formatarSemSimbolo(esperado) + ", contado "
                    + Dinheiro.formatarSemSimbolo(contado) + ", diferença " + Dinheiro.formatarSemSimbolo(diferenca)
                    + "." + (semDiferenca ? "" : " Motivo: " + motivo);
            auditoria.registrar(usuario.companyId(), usuario.id(), usuario.name(), "CaixaFechamento", descricao,
                    "CaixaSessao", aberta.id(), ip);
        }
        return resultado;
    }

    /** Repeticao de um fechamento sem turno aberto: o payloadHash enviado (sem recalculo) e o que vale. */
    private CaixaStatusDto repeticaoSeHouver(UsuarioAutenticado usuario, CaixaComandos.Fechar req) {
        if (HashPayload.emBranco(req.eventId())) return null;
        return operacoes.obterEventoProcessado(usuario.companyId(), req.eventId(), req.payloadHash());
    }

    // ---------------------------------------------------------------- movimento

    public CaixaStatusDto registrarMovimento(CaixaComandos.Movimento req, UsuarioAutenticado usuario, String ip) {
        OffsetDateTime agora = CaixaTempo.agora(clock);
        String evento = req.eventId() == null ? null : HashPayload.trim(req.eventId());
        String hash = !HashPayload.emBranco(req.payloadHash())
                ? HashPayload.trim(req.payloadHash()).toLowerCase(Locale.ROOT)
                : HashPayload.calcularMovimento(req.tipo(), req.valor(), req.motivo());
        if (!HashPayload.emBranco(evento)) {
            CaixaStatusDto existente = operacoes.obterEventoProcessado(usuario.companyId(), evento, hash);
            if (existente != null) return existente;
        }
        CaixaSessao sessao = repo.obterSessaoAberta(usuario.companyId(), usuario.id())
                .orElseThrow(() -> negocio("Você não possui caixa aberto para lançar movimento."));
        CaixaOperacoes.garantirResponsavel(sessao, usuario.id(), usuario.role());

        OptionalInt tipo = TipoMovimentoCaixa.tentarLer(req.tipo());
        if (tipo.isEmpty()) throw negocio("Tipo de movimento inválido — use \"Reforco\" ou \"Sangria\".");
        BigDecimal valor = HashPayload.parseDecimal(req.valor());
        if (valor.signum() <= 0) throw negocio("Valor do movimento deve ser maior que zero.");
        String motivo = req.motivo() == null ? "" : HashPayload.trim(req.motivo());
        if (motivo.length() < 3) throw negocio("Informe o motivo do movimento (mínimo 3 caracteres).");
        if (tipo.getAsInt() == TipoMovimentoCaixa.SANGRIA) {
            BigDecimal disponivel = status.dinheiroEsperado(usuario.companyId(), sessao);
            if (valor.compareTo(disponivel) > 0) {
                throw negocio("Sangria maior que o dinheiro em caixa (disponível: "
                        + Dinheiro.formatarSemSimbolo(disponivel) + ").");
            }
        }
        return operacoes.movimentar(new CaixaOperacoes.NovoMovimento(usuario.companyId(), sessao, tipo.getAsInt(),
                valor, motivo, usuario.id(), usuario.name(), ip, evento, hash), agora,
                () -> status.montar(usuario.companyId(), usuario.id(), agora));
    }

    // ---------------------------------------------------------------- auxiliares

    /** CaixaClientSession.NormalizeSessionId: id normalizado ou null; formato invalido e erro. */
    static String normalizarIdSessao(String valor) {
        if (valor == null) return null;
        String id = HashPayload.trim(valor);
        if (id.isEmpty()) return null;
        if (!ID_SESSAO_CLIENTE.matcher(id).matches()) {
            throw negocio("Identificador de sessão de caixa inválido.");
        }
        return id;
    }

    private static NegocioException negocio(String mensagem) {
        return new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
