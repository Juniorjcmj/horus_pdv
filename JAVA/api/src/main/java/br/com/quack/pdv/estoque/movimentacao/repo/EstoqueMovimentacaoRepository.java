package br.com.quack.pdv.estoque.movimentacao.repo;

import br.com.quack.pdv.estoque.movimentacao.domain.EstoqueMovimentacao;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EstoqueMovimentacaoRepository extends JpaRepository<EstoqueMovimentacao, String> {

    List<EstoqueMovimentacao> findByCompanyIdAndProdutoIdOrderByCriadoEmDesc(String companyId, String produtoId);
}

