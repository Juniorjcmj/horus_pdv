package br.com.quack.pdv.promocao.promocao.repo;

import br.com.quack.pdv.promocao.promocao.domain.PromocaoProduto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromocaoProdutoRepository extends JpaRepository<PromocaoProduto, String> {

    List<PromocaoProduto> findByPromocaoId(String promocaoId);
}

