package br.com.quack.pdv.sessao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.sessao.app.SessaoService;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.sessao.repo.SessaoRepository;
import br.com.quack.pdv.sessao.web.SessaoDto;
import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SessaoServiceTest {

    SessaoRepository repo = mock(SessaoRepository.class);
    SessaoService service = new SessaoService(repo);

    Sessao sessao(String id, String ultimaAtividade) {
        Sessao s = new Sessao();
        s.setId(id);
        s.setUserId("usr-1");
        s.setDevice("Navegador - Chrome");
        s.setLocation("Localização indisponível");
        s.setIp("1.1.1.1");
        s.setLastActive(ultimaAtividade);
        s.setPlatform("desktop");
        return s;
    }

    @Test
    void listarMarcaAAtualComAgoraMesmo() {
        when(repo.findByUserIdOrderByCreatedAtDesc("usr-1"))
                .thenReturn(List.of(sessao("sess-1", "Há 2 horas"), sessao("sess-2", "Há 3 dias")));
        List<SessaoDto> lista = service.listar("usr-1", "sess-1");
        assertThat(lista.get(0).current()).isTrue();
        assertThat(lista.get(0).lastActive()).isEqualTo("Agora mesmo");
        assertThat(lista.get(1).current()).isFalse();
        assertThat(lista.get(1).lastActive()).isEqualTo("Há 3 dias");
    }

    @Test
    void encerrarRecusaASessaoAtualSemTocarNoBanco() {
        assertThatThrownBy(() -> service.encerrar("usr-1", "sess-1", "sess-1")).isInstanceOfSatisfying(NegocioException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getMessage()).isEqualTo("A sessão atual não pode ser encerrada por esta ação.");
        });
        verify(repo, never()).apagarDoUsuario("sess-1", "usr-1");
    }

    @Test
    void encerrarInexistenteOuDeOutroUsuarioRecebe404() {
        when(repo.apagarDoUsuario("sess-x", "usr-1")).thenReturn(0);
        assertThatThrownBy(() -> service.encerrar("usr-1", "sess-x", "sess-1")).isInstanceOfSatisfying(NegocioException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("Sessão não encontrada.");
        });
    }

    @Test
    void encerrarOutraDevolveAListaAtualizada() {
        when(repo.apagarDoUsuario("sess-2", "usr-1")).thenReturn(1);
        when(repo.findByUserIdOrderByCreatedAtDesc("usr-1")).thenReturn(List.of(sessao("sess-1", "x")));
        assertThat(service.encerrar("usr-1", "sess-2", "sess-1")).hasSize(1);
    }

    @Test
    void encerrarOutrasApagaTodasMenosAAtual() {
        when(repo.findByUserIdOrderByCreatedAtDesc("usr-1")).thenReturn(List.of(sessao("sess-1", "x")));
        assertThat(service.encerrarOutras("usr-1", "sess-1")).hasSize(1);
        verify(repo).apagarOutras("usr-1", "sess-1");
    }
}
