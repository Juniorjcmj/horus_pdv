package br.com.quack.pdv.compras.ordem.repo;

import br.com.quack.pdv.compras.ordem.domain.OrdemCompraItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdemCompraItemRepository extends JpaRepository<OrdemCompraItem, String> {

    List<OrdemCompraItem> findByOrdemCompraIdOrderByIdAsc(String ordemCompraId);
}

