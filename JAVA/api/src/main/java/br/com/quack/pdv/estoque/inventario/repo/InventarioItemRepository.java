package br.com.quack.pdv.estoque.inventario.repo;

import br.com.quack.pdv.estoque.inventario.domain.InventarioItem;
import br.com.quack.pdv.estoque.inventario.domain.InventarioItemId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventarioItemRepository extends JpaRepository<InventarioItem, InventarioItemId> {

    List<InventarioItem> findByInventarioIdAndCompanyIdOrderByNomeAsc(String inventarioId, String companyId);
}

