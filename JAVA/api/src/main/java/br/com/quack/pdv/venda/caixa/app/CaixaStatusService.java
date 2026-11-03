package br.com.quack.pdv.venda.caixa.app;

import br.com.quack.pdv.security.Perfis;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.venda.caixa.domain.CaixaMovimento;
import br.com.quack.pdv.venda.caixa.domain.CaixaMovimentoDto;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessao;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessaoDto;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import br.com.quack.pdv.venda.caixa.domain.PagamentoTotalDto;
import br.com.quack.pdv.venda.caixa.domain.TipoMovimentoCaixa;
import br.com.quack.pdv.venda.caixa.repo.CaixaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Montagem do status do caixa e do dinheiro esperado (BuildStatusAsync, ToDtoAsync e ComputeExpectedCashAsync). */
@Service
public class CaixaStatusService {

    private static final Duration PERIODO_MAXIMO_ABERTO = Duration.ofHours(24);
    private static final String FORMA_DINHEIRO = "dinheiro";

    private final CaixaRepository repo;
    private final Clock clock;

    public CaixaStatusService(CaixaRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** GetStatusAsync: gerente e administrador veem tudo; os demais so o proprio turno. */
    public CaixaStatusDto statusDoUsuario(UsuarioAutenticado usuario) {
        CaixaStatusDto status = montar(usuario.companyId(), usuario.id(), CaixaTempo.agora(clock));
        if (Perfis.ehGerenteOuAdmin(usuario.role())) return status;

        List<CaixaSessaoDto> historico = status.history().stream().filter(s -> doUsuario(s, usuario.id())).toList();
        CaixaSessaoDto ultima = status.lastSession();
        if (ultima != null && !doUsuario(ultima, usuario.id())) ultima = null;
        List<CaixaSessaoDto> abertas = status.openSessions().stream().filter(s -> doUsuario(s, usuario.id())).toList();
        return new CaixaStatusDto(status.state(), status.canSell(), status.blockReason(), status.serverNow(),
                status.currentSession(), ultima, historico, abertas, status.isReplay());
    }

    private static boolean doUsuario(CaixaSessaoDto sessao, String usuarioId) {
        return usuarioId.equals(sessao.operatorId()) || usuarioId.equals(sessao.closedById());
    }

    /** BuildStatusAsync (sem filtrar por perfil). */
    public CaixaStatusDto montar(String companyId, String operatorId, OffsetDateTime agora) {
        List<CaixaSessao> sessoes = repo.listarSessoesParaStatus(companyId, operatorId);
        Optional<CaixaSessao> propria = sessoes.stream()
                .filter(s -> s.closedAt() == null && s.operatorId().equals(operatorId)).findFirst();
        List<CaixaSessao> abertas = sessoes.stream().filter(s -> s.closedAt() == null).toList();
        CaixaSessao ultima = propria
                .or(() -> sessoes.stream().filter(s -> s.operatorId().equals(operatorId)).findFirst())
                .or(() -> sessoes.stream().findFirst())
                .orElse(null);

        boolean podeVender = false;
        String motivo = "Abra o caixa do dia antes de iniciar vendas.";
        String estado = "fechado";
        if (propria.isPresent()) {
            CaixaSessao aberta = propria.get();
            boolean abertoHoje = aberta.openedAt().toLocalDate().equals(agora.toLocalDate());
            boolean dentroDoPeriodo = Duration.between(aberta.openedAt(), agora).compareTo(PERIODO_MAXIMO_ABERTO) <= 0;
            if (abertoHoje && dentroDoPeriodo) {
                podeVender = true;
                motivo = "";
                estado = "aberto";
            } else {
                estado = "expirado";
                motivo = !abertoHoje
                        ? "O caixa aberto pertence a outro dia. Feche o caixa atual e abra o caixa do dia."
                        : "O caixa aberto ultrapassou 24 horas. Feche o caixa atual e abra um novo.";
            }
        }

        CaixaSessaoDto atual = propria.map(s -> paraDto(companyId, s, agora, true)).orElse(null);
        List<CaixaSessaoDto> abertasDto = new ArrayList<>();
        for (CaixaSessao s : abertas) {
            boolean ehAtual = propria.isPresent() && s.id().equals(propria.get().id());
            abertasDto.add(ehAtual && atual != null ? atual : paraDto(companyId, s, agora, true));
        }
        CaixaSessaoDto ultimaDto = null;
        if (ultima != null) {
            boolean ehAtual = propria.isPresent() && ultima.id().equals(propria.get().id());
            ultimaDto = ehAtual && atual != null ? atual : paraDto(companyId, ultima, agora, ultima.closedAt() == null);
        }
        List<CaixaSessaoDto> historico = new ArrayList<>();
        for (CaixaSessao s : sessoes.stream().limit(12).toList()) {
            boolean ehAtual = propria.isPresent() && s.id().equals(propria.get().id());
            historico.add(ehAtual && atual != null ? atual : paraDtoHistorico(s));
        }
        return new CaixaStatusDto(estado, podeVender, motivo, CaixaTempo.formatarIso(agora), atual, ultimaDto,
                historico, abertasDto, false);
    }

    /** ObterSessaoAsync: dono, quem fechou, ou gerente/administrador; vazio caso contrario. */
    public Optional<CaixaSessaoDto> sessaoVisivel(UsuarioAutenticado usuario, String sessionId) {
        return repo.obterSessaoPorId(usuario.companyId(), sessionId)
                .filter(s -> Perfis.ehGerenteOuAdmin(usuario.role()) || s.operatorId().equals(usuario.id())
                        || s.closedById().equals(usuario.id()))
                .map(s -> paraDto(usuario.companyId(), s, CaixaTempo.agora(clock), s.closedAt() == null));
    }

    /** ComputeExpectedCashAsync: fundo + vendas em dinheiro do turno + reforcos - sangrias. */
    public BigDecimal dinheiroEsperado(String companyId, CaixaSessao sessao) {
        BigDecimal vendasDinheiro = repo.totaisPorFormaPagamento(companyId, sessao.id()).stream()
                .filter(t -> t.tipo().equalsIgnoreCase(FORMA_DINHEIRO))
                .map(CaixaRepository.TotalPagamento::total).findFirst().orElse(BigDecimal.ZERO);
        List<CaixaMovimento> movimentos = repo.listarMovimentos(companyId, sessao.id());
        BigDecimal reforcos = somar(movimentos, TipoMovimentoCaixa.REFORCO);
        BigDecimal sangrias = somar(movimentos, TipoMovimentoCaixa.SANGRIA);
        return sessao.openingAmount().add(vendasDinheiro).add(reforcos).subtract(sangrias);
    }

    private static BigDecimal somar(List<CaixaMovimento> movimentos, int tipo) {
        return movimentos.stream().filter(m -> m.tipo() == tipo).map(CaixaMovimento::valor)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CaixaSessaoDto paraDtoHistorico(CaixaSessao s) {
        return montarDto(s, minutos(s, CaixaTempo.agora(clock)), formatar(s.expectedCashAmount()), List.of(), null);
    }

    private CaixaSessaoDto paraDto(String companyId, CaixaSessao s, OffsetDateTime agora, boolean atual) {
        List<CaixaMovimentoDto> movimentos = repo.listarMovimentos(companyId, s.id()).stream()
                .map(CaixaStatusService::paraDto).toList();
        List<PagamentoTotalDto> totais = repo.totaisPorFormaPagamento(companyId, s.id()).stream()
                .map(t -> new PagamentoTotalDto(t.tipo(), Dinheiro.formatarSemSimbolo(t.total()))).toList();
        String previa = null;
        if (atual && s.closedAt() == null) previa = Dinheiro.formatarSemSimbolo(dinheiroEsperado(companyId, s));
        String esperado = previa != null ? previa : formatar(s.expectedCashAmount());
        return montarDto(s, minutos(s, agora), esperado, movimentos, totais);
    }

    private static CaixaSessaoDto montarDto(CaixaSessao s, int minutos, String esperado,
                                            List<CaixaMovimentoDto> movimentos, List<PagamentoTotalDto> totais) {
        return new CaixaSessaoDto(s.id(), s.closedAt() == null ? "Aberto" : "Fechado",
                CaixaTempo.formatarIso(s.openedAt()),
                s.closedAt() == null ? null : CaixaTempo.formatarIso(s.closedAt()),
                Dinheiro.formatarSemSimbolo(s.openingAmount()), Dinheiro.formatarSemSimbolo(s.closingAmount()),
                s.operatorId(), s.operatorName(), s.closedById(), s.closedByName(), s.note(), minutos, esperado,
                formatar(s.differenceAmount()), s.differenceReason(), movimentos, totais);
    }

    public static CaixaMovimentoDto paraDto(CaixaMovimento m) {
        return new CaixaMovimentoDto(m.id(), TipoMovimentoCaixa.nome(m.tipo()),
                Dinheiro.formatarSemSimbolo(m.valor()), m.motivo(), CaixaTempo.formatarIso(m.createdAt()),
                m.operatorName());
    }

    private static int minutos(CaixaSessao s, OffsetDateTime agora) {
        OffsetDateTime fim = s.closedAt() != null ? s.closedAt() : agora;
        return (int) Math.max(0, Duration.between(s.openedAt(), fim).toMinutes());
    }

    private static String formatar(BigDecimal valor) {
        return valor == null ? null : Dinheiro.formatarSemSimbolo(valor);
    }
}
