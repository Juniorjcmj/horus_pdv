package br.com.quack.pdv.produto.lote.repo;

import br.com.quack.pdv.produto.lote.domain.ProdutoLote;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProdutoLoteRepository extends JpaRepository<ProdutoLote, String> {

    List<ProdutoLote> findByCompanyIdAndProdutoIdOrderByDataValidadeDescCriadoEmDesc(String companyId, String produtoId);
}

