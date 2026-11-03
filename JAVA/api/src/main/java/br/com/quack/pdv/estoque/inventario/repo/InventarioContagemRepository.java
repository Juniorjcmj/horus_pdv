package br.com.quack.pdv.estoque.inventario.repo;

import br.com.quack.pdv.estoque.inventario.domain.InventarioContagem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventarioContagemRepository extends JpaRepository<InventarioContagem, String> {

    List<InventarioContagem> findByInventarioIdAndProdutoIdOrderByCriadoEmAsc(String inventarioId, String produtoId);
}

