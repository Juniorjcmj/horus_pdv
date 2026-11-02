package br.com.quack.pdv.cadastro.categoria.repo;
import br.com.quack.pdv.cadastro.categoria.domain.Categoria;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CategoriaRepository extends JpaRepository<Categoria,String> {
    interface Leitura { String getId();String getNome();String getCategoriaPaiId();String getCategoriaPaiNome();int getOrdem();boolean getAtiva();int getQuantidadeProdutos(); }
    String BASE="SELECT c.Id as id,c.Nome as nome,c.CategoriaPaiId as categoriaPaiId,pai.Nome as categoriaPaiNome,c.Ordem as ordem,c.Ativa as ativa,COUNT(p.Id) as quantidadeProdutos FROM Categorias c LEFT JOIN Categorias pai ON pai.Id=c.CategoriaPaiId LEFT JOIN Produtos p ON p.CategoriaId=c.Id AND p.CompanyId=:companyId ";
    String GRUPO=" GROUP BY c.Id,c.CompanyId,c.Nome,c.CategoriaPaiId,pai.Nome,c.Ordem,c.Ativa ";
    @Query(value=BASE+" WHERE (c.CompanyId=:companyId OR c.CompanyId='empresa-principal') AND (:ativas=0 OR c.Ativa=1) "+GRUPO+" ORDER BY c.Ordem,c.Nome",nativeQuery=true)
    List<Leitura> listar(@Param("companyId")String companyId,@Param("ativas")int ativas);
    @Query(value=BASE+" WHERE c.Id=:id AND (c.CompanyId=:companyId OR c.CompanyId='empresa-principal') "+GRUPO,nativeQuery=true)
    Optional<Leitura> obter(@Param("companyId")String companyId,@Param("id")String id);
    @Modifying
    @Query(value="IF EXISTS (SELECT 1 FROM Categorias WHERE Id=:id AND CompanyId=:companyId) UPDATE Categorias SET Nome=:nome,CategoriaPaiId=:pai,Ordem=:ordem,Ativa=:ativa WHERE Id=:id AND CompanyId=:companyId ELSE INSERT INTO Categorias(Id,CompanyId,Nome,CategoriaPaiId,Ordem,Ativa) VALUES(:id,:companyId,:nome,:pai,:ordem,:ativa)",nativeQuery=true)
    void gravar(@Param("companyId")String companyId,@Param("id")String id,@Param("nome")String nome,@Param("pai")String pai,@Param("ordem")int ordem,@Param("ativa")boolean ativa);
    @Query(value="SELECT COUNT(*) FROM Categorias WHERE CompanyId=:companyId AND CategoriaPaiId=:id",nativeQuery=true)
    int filhos(@Param("companyId")String companyId,@Param("id")String id);
    @Query(value="SELECT COUNT(*) FROM Produtos WHERE CompanyId=:companyId AND CategoriaId=:id",nativeQuery=true)
    int produtos(@Param("companyId")String companyId,@Param("id")String id);
    @Modifying @Query(value="UPDATE Categorias SET Ativa=:ativa WHERE CompanyId=:companyId AND Id=:id",nativeQuery=true)
    int status(@Param("companyId")String companyId,@Param("id")String id,@Param("ativa")boolean ativa);
    @Modifying @Query(value="DELETE FROM Categorias WHERE CompanyId=:companyId AND Id=:id",nativeQuery=true)
    int excluir(@Param("companyId")String companyId,@Param("id")String id);
}
