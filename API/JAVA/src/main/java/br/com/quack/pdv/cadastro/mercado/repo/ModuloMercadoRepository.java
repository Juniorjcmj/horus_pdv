package br.com.quack.pdv.cadastro.mercado.repo;
import br.com.quack.pdv.cadastro.mercado.domain.ModuloMercado;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
public interface ModuloMercadoRepository extends JpaRepository<ModuloMercado,String> {
    @Transactional @Modifying @Query(value="IF NOT EXISTS (SELECT 1 FROM ModulosMercado WHERE Id=:id) INSERT INTO ModulosMercado(Id,Title) VALUES(:id,:title)",nativeQuery=true)
    void garantir(@Param("id")String id,@Param("title")String title);
}
