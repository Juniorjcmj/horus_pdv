package br.com.quack.pdv.empresa.app;
import br.com.quack.pdv.empresa.domain.*;
import br.com.quack.pdv.empresa.repo.*;
import br.com.quack.pdv.shared.NegocioException;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class TreinamentoService {
    public record SecaoRequest(String nome,String descricao,int ordem) {}
    public record VideoRequest(String secaoId,String titulo,String descricao,String instrucoes,String url,int ordem) {}
    public record Video(String id,String secaoId,String titulo,String descricao,String instrucoes,String youtubeId,int ordem) {}
    public record Secao(String id,String nome,String descricao,int ordem,List<Video> videos) {}
    private static final Pattern LINK=Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|live/))([A-Za-z0-9_-]{11})");
    private final TreinamentoSecaoRepository secoes;
    private final TreinamentoVideoRepository videos;
    private final Clock clock;
    public TreinamentoService(TreinamentoSecaoRepository secoes,TreinamentoVideoRepository videos,Clock clock) {this.secoes=secoes;this.videos=videos;this.clock=clock;}
    public List<Secao> listar() {
        var todos=videos.findAllByOrderByOrdemAscCriadoEmAsc();
        return secoes.findAllByOrderByOrdemAscNomeAsc().stream().map(s->new Secao(s.id,s.nome,texto(s.descricao),s.ordem,todos.stream().filter(v->s.id.equals(v.secaoId)).map(v->new Video(v.id,v.secaoId,v.titulo,texto(v.descricao),texto(v.instrucoes),v.youtubeId,v.ordem)).toList())).toList();
    }
    @Transactional public String salvarSecao(String id,SecaoRequest r) {
        if(r==null||r.nome()==null||r.nome().isBlank()) falhar("Informe o nome da se\u00e7\u00e3o.");
        TreinamentoSecao s;
        if(id==null||id.isBlank()) {s=new TreinamentoSecao();s.id="trs-"+UUID.randomUUID().toString().replace("-","");s.criadoEm=OffsetDateTime.now(clock);}
        else s=secoes.findById(id).orElseThrow(()->ausente("Se\u00e7\u00e3o n\u00e3o encontrada."));
        s.nome=r.nome().trim();s.descricao=normalizar(r.descricao());s.ordem=r.ordem();return secoes.saveAndFlush(s).id;
    }
    @Transactional public String salvarVideo(String id,VideoRequest r) {
        if(r==null||r.titulo()==null||r.titulo().isBlank()||r.secaoId()==null||r.secaoId().isBlank())falhar("Informe a se\u00e7\u00e3o e o t\u00edtulo do v\u00eddeo.");
        String youtube=extrairYoutubeId(r.url());if(youtube==null)falhar("Link do YouTube inv\u00e1lido.");
        TreinamentoVideo v;
        if(id==null||id.isBlank()) {v=new TreinamentoVideo();v.id="trv-"+UUID.randomUUID().toString().replace("-","");v.criadoEm=OffsetDateTime.now(clock);}
        else v=videos.findById(id).orElseThrow(()->ausente("V\u00eddeo n\u00e3o encontrado."));
        v.secaoId=r.secaoId();v.titulo=r.titulo().trim();v.descricao=normalizar(r.descricao());v.instrucoes=normalizar(r.instrucoes());v.youtubeId=youtube;v.ordem=r.ordem();return videos.saveAndFlush(v).id;
    }
    @Transactional public void excluirSecao(String id) {var s=secoes.findById(id).orElseThrow(()->ausente("Se\u00e7\u00e3o n\u00e3o encontrada."));secoes.delete(s);secoes.flush();}
    @Transactional public void excluirVideo(String id) {var v=videos.findById(id).orElseThrow(()->ausente("V\u00eddeo n\u00e3o encontrado."));videos.delete(v);videos.flush();}
    public static String extrairYoutubeId(String valor) {if(valor==null||valor.isBlank())return null;String t=valor.trim();if(t.matches("^[A-Za-z0-9_-]{11}$"))return t;var m=LINK.matcher(t);return m.find()?m.group(1):null;}
    private static String normalizar(String v) {return v==null||v.isBlank()?null:v.trim();}
    private static String texto(String v) {return v==null?"":v;}
    private static void falhar(String m) {throw new NegocioException(HttpStatus.BAD_REQUEST,m);}
    private static NegocioException ausente(String m) {return new NegocioException(HttpStatus.NOT_FOUND,m);}
}
