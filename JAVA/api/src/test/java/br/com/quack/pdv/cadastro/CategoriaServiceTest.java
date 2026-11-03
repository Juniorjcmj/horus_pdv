package br.com.quack.pdv.cadastro;
import br.com.quack.pdv.cadastro.categoria.app.CategoriaService;
import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.cadastro.categoria.web.CategoriaRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CategoriaServiceTest {
    private final CategoriaRepository repo=mock(CategoriaRepository.class);
    private final CategoriaService service=new CategoriaService(repo);
    private CategoriaRequest request(String nome,String pai) {var r=new CategoriaRequest();r.nome=nome;r.categoriaPaiId=pai;return r;}
    private CategoriaRepository.Leitura item(String id,String pai,int count) {
        return new CategoriaRepository.Leitura() {
            public String getId(){return id;}public String getNome(){return id;}
            public String getCategoriaPaiId(){return pai;}public String getCategoriaPaiNome(){return null;}
            public int getOrdem(){return 0;}public boolean getAtiva(){return true;}public int getQuantidadeProdutos(){return count;}
        };
    }
    @Test void exigeNome() {assertThatThrownBy(()->service.criar("loja",request(" ",null))).hasMessage("O nome da categoria \u00e9 obrigat\u00f3rio.");}
    @Test void nomeMaximo80() {assertThatThrownBy(()->service.criar("loja",request("x".repeat(81),null))).hasMessage("O nome da categoria deve ter no m\u00e1ximo 80 caracteres.");}
    @Test void paiDeveExistir() {assertThatThrownBy(()->service.criar("loja",request("Sub","nao-existe"))).hasMessage("Departamento pai especificado n\u00e3o foi encontrado.");}
    @Test void rejeitaTerceiroNivel() {when(repo.obter("loja","sub")).thenReturn(Optional.of(item("sub","pai",0)));assertThatThrownBy(()->service.criar("loja",request("Terceiro","sub"))).hasMessage("A hierarquia de categorias permite apenas 2 n\u00edveis (Departamento -> Subcategoria).");}
    @Test void rejeitaPropriaCategoriaComoPai() {when(repo.obter("loja","id")).thenReturn(Optional.of(item("id",null,0)));assertThatThrownBy(()->service.atualizar("loja","id",request("Nome"," ID "))).hasMessage("Uma categoria n\u00e3o pode ser subcategoria dela mesma.");}
    @Test void criaDepartamentoComUuid() {var dto=service.criar("loja",request("  Nome  ",null));assertThat(dto.id).matches("[a-f0-9-]{36}");assertThat(dto.nome).isEqualTo("Nome");verify(repo).gravar("loja",dto.id,"Nome",null,0,true);}
    @Test void criaSubcategoria() {when(repo.obter("loja","pai")).thenReturn(Optional.of(item("pai",null,0)));var dto=service.criar("loja",request("Sub","pai"));assertThat(dto.categoriaPaiId).isEqualTo("pai");}
    @Test void arvoreSomaProdutosDosFilhosEPlanaNaoSoma() {when(repo.listar("loja",1)).thenReturn(List.of(item("pai",null,2),item("sub","pai",3)));var tree=service.listar("loja",false,true);assertThat(tree).hasSize(1);assertThat(tree.getFirst().quantidadeProdutos).isEqualTo(5);assertThat(tree.getFirst().subcategorias).hasSize(1);var flat=service.listar("loja",true,true);assertThat(flat).hasSize(2);assertThat(flat.getFirst().quantidadeProdutos).isEqualTo(2);}
    @Test void excluiNaoPermiteFilhos() {when(repo.filhos("loja","pai")).thenReturn(1);assertThatThrownBy(()->service.excluir("loja","pai")).hasMessage("N\u00e3o \u00e9 poss\u00edvel excluir uma categoria que possui subcategorias vinculadas.");verify(repo,never()).excluir(any(),any());}
    @Test void excluiNaoPermiteProdutos() {when(repo.produtos("loja","pai")).thenReturn(1);assertThatThrownBy(()->service.excluir("loja","pai")).hasMessage("N\u00e3o \u00e9 poss\u00edvel excluir uma categoria que possui produtos vinculados. Remova ou altere a categoria dos produtos primeiro.");}
    @Test void excluiLivre() {when(repo.excluir("loja","id")).thenReturn(1);service.excluir("loja","id");verify(repo).excluir("loja","id");}
    @Test void excluiAusente() {assertThatThrownBy(()->service.excluir("loja","outro")).hasMessage("Categoria n\u00e3o encontrada.");}
}
