package br.com.quack.pdv.sessao.app;

import br.com.quack.pdv.sessao.repo.SessaoRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validacao de toda requisicao autenticada em uma consulta (sessao, usuario, perfis e empresa). */
@Service
public class ValidadorSessao {

    public record Validacao(boolean sessaoAtiva, boolean usuarioAtivo, String statusEmpresa, String perfisAdicionais) {}

    private final SessaoRepository sessoes;

    public ValidadorSessao(SessaoRepository sessoes) {
        this.sessoes = sessoes;
    }

    @Transactional(readOnly = true)
    public Validacao validar(String sessionId, String userId, String companyId) {
        Optional<SessaoRepository.ValidacaoRequisicaoView> linha =
                sessoes.validarRequisicao(sessionId, userId, blankToNull(companyId));
        if (linha.isEmpty()) return new Validacao(false, false, "aprovada", "");
        SessaoRepository.ValidacaoRequisicaoView v = linha.get();
        boolean sessaoAtiva = v.getSessionActive() != null && v.getSessionActive() > 0;
        String statusEmpresa = "empresa-principal".equalsIgnoreCase(companyId) ? "aprovada"
                : (v.getCompanyStatus() != null ? v.getCompanyStatus() : "aprovada");
        return new Validacao(sessaoAtiva, "ativo".equals(v.getUserStatus()), statusEmpresa,
                v.getPerfisAdicionais() == null ? "" : v.getPerfisAdicionais());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
