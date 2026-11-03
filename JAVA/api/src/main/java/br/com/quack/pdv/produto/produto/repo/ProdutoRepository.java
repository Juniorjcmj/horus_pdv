package br.com.quack.pdv.produto.produto.repo;

import br.com.quack.pdv.produto.produto.domain.Produto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProdutoRepository extends JpaRepository<Produto, String> {

    Optional<Produto> findByIdAndCompanyId(String id, String companyId);

    Optional<Produto> findByCompanyIdAndProductCode(String companyId, String productCode);

    List<Produto> findByCompanyIdOrderByProductNameAsc(String companyId);
}

