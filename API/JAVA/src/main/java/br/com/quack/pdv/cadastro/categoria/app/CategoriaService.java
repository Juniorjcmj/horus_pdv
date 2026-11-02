package br.com.quack.pdv.cadastro.categoria.app;
import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.cadastro.categoria.web.*;
import br.com.quack.pdv.shared.NegocioException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class CategoriaService {
    private final CategoriaRepository categorias;
    public CategoriaService(CategoriaRepository categorias) { this.categorias=categorias; }
    public List<CategoriaDto> listar(String companyId,boolean plana,boolean ativas) {
        var lista=categorias.listar(companyId,ativas?1:0).stream().map(this::dto).toList();
        if(plana)return lista;
        var roots=lista.stream().filter(c->c.categoriaPaiId==null||c.categoriaPaiId.isEmpty()).toList();
        for(var r:roots) anexar(r,lista,new HashSet<>());
        return roots;
    }
    private void anexar(CategoriaDto pai,List<CategoriaDto> lista,Set<String> caminho) {
        if(!caminho.add(pai.id))return;
        pai.subcategorias=lista.stream().filter(c->pai.id.equals(c.categoriaPaiId)).toList();
        for(var c:pai.subcategorias) {anexar(c,lista,new HashSet<>(caminho));pai.quantidadeProdutos+=c.quantidadeProdutos;}
    }
    public CategoriaDto obter(String companyId,String id) {return categorias.obter(companyId,id).map(this::dto).orElseThrow(()->new NegocioException(HttpStatus.NOT_FOUND,"Categoria n\u00e3o encontrada."));}
    @Transactional public CategoriaDto criar(String companyId,CategoriaRequest r) {validar(r);pai(companyId,null,r.categoriaPaiId);var e=new CategoriaDto();e.id=UUID.randomUUID().toString();copiar(r,e);gravar(companyId,e);return e;}
    @Transactional public CategoriaDto atualizar(String companyId,String id,CategoriaRequest r) {validar(r);var e=obter(companyId,id);pai(companyId,id,r.categoriaPaiId);copiar(r,e);gravar(companyId,e);return e;}
    @Transactional public void status(String companyId,String id,boolean ativa) {obter(companyId,id);if(categorias.status(companyId,id,ativa)==0)ausente();}
    @Transactional public void excluir(String companyId,String id) {if(categorias.filhos(companyId,id)>0)falhar("Não é possível excluir uma categoria que possui subcategorias vinculadas.");if(categorias.produtos(companyId,id)>0)falhar("Não é possível excluir uma categoria que possui produtos vinculados. Remova ou altere a categoria dos produtos primeiro.");if(categorias.excluir(companyId,id)==0)ausente();}
    private void gravar(String companyId,CategoriaDto e) {categorias.gravar(companyId,e.id,e.nome,e.categoriaPaiId,e.ordem,e.ativa);}
    private void validar(CategoriaRequest r) {if(r.nome==null||r.nome.isBlank())falhar("O nome da categoria é obrigatório.");if(r.nome.trim().length()>80)falhar("O nome da categoria deve ter no máximo 80 caracteres.");}
    private void pai(String companyId,String id,String parent) {if(parent==null||parent.isBlank())return;if(parent.trim().equalsIgnoreCase(id))falhar("Uma categoria não pode ser subcategoria dela mesma.");var p=categorias.obter(companyId,parent).orElseThrow(()->new NegocioException(HttpStatus.BAD_REQUEST,"Departamento pai especificado não foi encontrado."));if(p.getCategoriaPaiId()!=null&&!p.getCategoriaPaiId().isEmpty())falhar("A hierarquia de categorias permite apenas 2 níveis (Departamento -> Subcategoria).");}
    private void copiar(CategoriaRequest r,CategoriaDto e) {e.nome=r.nome.trim();e.categoriaPaiId=r.categoriaPaiId==null||r.categoriaPaiId.isBlank()?null:r.categoriaPaiId.trim();e.ordem=r.ordem;e.ativa=r.ativa;}
    private CategoriaDto dto(CategoriaRepository.Leitura p) {var r=new CategoriaDto();r.id=p.getId().trim();r.nome=p.getNome().trim();r.categoriaPaiId=p.getCategoriaPaiId()==null?null:p.getCategoriaPaiId().trim();r.categoriaPaiNome=p.getCategoriaPaiNome()==null?null:p.getCategoriaPaiNome().trim();r.ordem=p.getOrdem();r.ativa=p.getAtiva();r.quantidadeProdutos=p.getQuantidadeProdutos();return r;}
    private static void falhar(String m) {throw new NegocioException(HttpStatus.BAD_REQUEST,m);}
    private static void ausente() {throw new NegocioException(HttpStatus.NOT_FOUND,"Categoria n\u00e3o encontrada.");}
}
