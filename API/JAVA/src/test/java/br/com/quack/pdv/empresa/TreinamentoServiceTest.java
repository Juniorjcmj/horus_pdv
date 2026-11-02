package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.app.TreinamentoService;
import br.com.quack.pdv.empresa.repo.*;
import br.com.quack.pdv.empresa.domain.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TreinamentoServiceTest {
    private final TreinamentoSecaoRepository secoes=mock(TreinamentoSecaoRepository.class);
    private final TreinamentoVideoRepository videos=mock(TreinamentoVideoRepository.class);
    private final TreinamentoService service=new TreinamentoService(secoes,videos,Clock.systemUTC());
    @ParameterizedTest @ValueSource(strings={"dQw4w9WgXcQ","https://youtu.be/dQw4w9WgXcQ","https://youtube.com/watch?foo=1&v=dQw4w9WgXcQ&bar=2","https://youtube.com/embed/dQw4w9WgXcQ","https://youtube.com/shorts/dQw4w9WgXcQ","https://youtube.com/live/dQw4w9WgXcQ"})
    void extraiYoutube(String valor) {assertThat(TreinamentoService.extrairYoutubeId(valor)).isEqualTo("dQw4w9WgXcQ");}
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"https://vimeo.com/123","abc","https://youtube.com/watch?v=curto"}) void invalido(String valor) {assertThat(TreinamentoService.extrairYoutubeId(valor)).isNull();}
    @Test void secaoExigeNome() {assertThatThrownBy(()->service.salvarSecao(null,new TreinamentoService.SecaoRequest(" ",null,0))).hasMessage("Informe o nome da seção.");}
    @Test void videoExigeTituloESecaoAntesDoLink() {assertThatThrownBy(()->service.salvarVideo(null,new TreinamentoService.VideoRequest("","",null,null,"lixo",0))).hasMessage("Informe a seção e o título do vídeo.");}
    @Test void linkInvalidoNaoGrava() {assertThatThrownBy(()->service.salvarVideo(null,new TreinamentoService.VideoRequest("s","Titulo",null,null,"lixo",0))).hasMessage("Link do YouTube inválido.");verify(videos,never()).saveAndFlush(any());}
    @Test void idInexistenteNaoViraInsert() {assertThatThrownBy(()->service.salvarSecao("ausente",new TreinamentoService.SecaoRequest("Nome",null,0))).hasMessage("Seção não encontrada.");verify(secoes,never()).saveAndFlush(any());}
    @Test void novaSecaoTrimESemDescricao() {when(secoes.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));String id=service.salvarSecao(null,new TreinamentoService.SecaoRequest(" Nome "," ",3));assertThat(id).matches("trs-[a-f0-9]{32}");var cap=org.mockito.ArgumentCaptor.forClass(TreinamentoSecao.class);verify(secoes).saveAndFlush(cap.capture());assertThat(cap.getValue().nome).isEqualTo("Nome");assertThat(cap.getValue().descricao).isNull();}
    @Test void listaAgrupaSemExporDataCriacao() {var s=new TreinamentoSecao();s.id="s";s.nome="Secao";var v=new TreinamentoVideo();v.id="v";v.secaoId="s";v.titulo="Video";when(secoes.findAllByOrderByOrdemAscNomeAsc()).thenReturn(List.of(s));when(videos.findAllByOrderByOrdemAscCriadoEmAsc()).thenReturn(List.of(v));var lista=service.listar();assertThat(lista.getFirst().videos()).hasSize(1);assertThat(lista.getFirst().descricao()).isEmpty();}
    @Test void excluirSecaoUsaCascadeDoBanco() {var s=new TreinamentoSecao();when(secoes.findById("s")).thenReturn(Optional.of(s));service.excluirSecao("s");verify(secoes).delete(s);verifyNoInteractions(videos);}
}
