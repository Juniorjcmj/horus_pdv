package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.app.EmpresaTemaService;
import br.com.quack.pdv.empresa.repo.EmpresaTemaRepository;
import br.com.quack.pdv.shared.NegocioException;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmpresaTemaServiceTest {
    private final EmpresaTemaRepository repo=mock(EmpresaTemaRepository.class);
    private final EmpresaTemaService service=new EmpresaTemaService(repo, Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"),ZoneOffset.UTC));
    @Test void semTema() { when(repo.findById("loja")).thenReturn(Optional.empty()); assertThat(service.obter("loja")).isNull(); }
    @Test void coresNormalizadasEBrancoRetornaPadrao() { when(repo.findById("loja")).thenReturn(Optional.empty()); when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); var t=service.salvar("loja","u"," #abCDEF "," "); assertThat(t.systemAccent()).isEqualTo("#abCDEF"); assertThat(t.pdvAccent()).isNull(); assertThat(t.updatedAt()).isEqualTo("2026-11-02T12:00:00.0000000"); }
    @Test void corInvalidaNuncaGrava() { for(String cor:new String[]{"azul","#123","#1234567","#GG0000"}) { assertThatThrownBy(()->service.salvar("loja","u",cor,null)).isInstanceOf(NegocioException.class).hasMessage("Cor inválida. Use formato hexadecimal #rrggbb (ex.: #0369a1) ou vazio para o padrão."); } verify(repo,never()).saveAndFlush(any()); }
}
