package br.com.quack.pdv.sessao.app;

import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.sessao.web.SessaoDto;
import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Listar e encerrar sessoes do proprio usuario. */
@Service
public class SessaoService {

    private final SessaoRepository sessoes;

    public SessaoService(SessaoRepository sessoes) {
        this.sessoes = sessoes;
    }

    @Transactional(readOnly = true)
    public List<SessaoDto> listar(String userId, String sessaoAtualId) {
        return sessoes.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> paraDto(s, s.getId().equals(sessaoAtualId))).toList();
    }

    /** Encerra outra sessao do usuario e devolve a lista atualizada. */
    @Transactional
    public List<SessaoDto> encerrar(String userId, String id, String sessaoAtualId) {
        if (id.equals(sessaoAtualId)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A sessão atual não pode ser encerrada por esta ação.");
        }
        if (sessoes.apagarDoUsuario(id, userId) <= 0) {
            throw new NegocioException(HttpStatus.NOT_FOUND, "Sessão não encontrada.");
        }
        return listar(userId, sessaoAtualId);
    }

    @Transactional
    public List<SessaoDto> encerrarOutras(String userId, String sessaoAtualId) {
        sessoes.apagarOutras(userId, sessaoAtualId);
        return listar(userId, sessaoAtualId);
    }

    @Transactional
    public void encerrarAtual(String sessaoAtualId) {
        sessoes.apagarPorId(sessaoAtualId);
    }

    private static SessaoDto paraDto(Sessao s, boolean atual) {
        return new SessaoDto(s.getId(), s.getDevice(), s.getLocation(), s.getIp(),
                atual ? "Agora mesmo" : s.getLastActive(), atual, s.getPlatform());
    }
}
