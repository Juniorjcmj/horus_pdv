package br.com.quack.pdv.cadastro.fornecedor.repo;
import br.com.quack.pdv.cadastro.fornecedor.domain.Fornecedor;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface FornecedorRepository extends JpaRepository<Fornecedor,String> {
    List<Fornecedor> findByCompanyIdOrderByFantasyName(String companyId);
    Optional<Fornecedor> findByCompanyIdAndId(String companyId,String id);
    @Query(value="SELECT CASE WHEN EXISTS (SELECT 1 FROM Produtos WHERE CompanyId=:companyId AND (LOWER(ProductSupplier)=LOWER(:fantasyName) OR LOWER(ProductSupplier)=LOWER(:companyName))) THEN 1 ELSE 0 END",nativeQuery=true)
    int produtosVinculados(@Param("companyId") String companyId,@Param("fantasyName") String fantasyName,@Param("companyName") String companyName);
}
