package br.com.quack.pdv.cadastro.cliente.repo;
import br.com.quack.pdv.cadastro.cliente.domain.Cliente;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface ClienteRepository extends JpaRepository<Cliente,String> {
    List<Cliente> findByCompanyIdOrderByCustomerName(String companyId);
    Optional<Cliente> findByCompanyIdAndId(String companyId,String id);
    @Query(value="SELECT SaldoDevedor FROM Clientes WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=:companyId AND Id=:id",nativeQuery=true)
    Optional<BigDecimal> saldoBloqueado(@Param("companyId")String companyId,@Param("id")String id);
    @Query(value="SELECT COALESCE(SUM(AmountCents-ConsumedCents),0) FROM GatewayCreditGrants WHERE CompanyId=:companyId AND CustomerId=:id AND Closed=0",nativeQuery=true)
    BigDecimal delegadoCentavos(@Param("companyId")String companyId,@Param("id")String id);
    @Query(value="SELECT * FROM Clientes WITH(UPDLOCK,HOLDLOCK) WHERE CompanyId=:companyId ORDER BY Id",nativeQuery=true)
    List<Cliente> snapshotBloqueado(@Param("companyId")String companyId);
    @Query(value="SELECT DISTINCT ClientSaleId FROM Vendas WHERE CompanyId=:companyId AND ClientSaleId IN(SELECT value FROM OPENJSON(:ids))",nativeQuery=true)
    List<String> vendasAceitas(@Param("companyId")String companyId,@Param("ids")String ids);
}
