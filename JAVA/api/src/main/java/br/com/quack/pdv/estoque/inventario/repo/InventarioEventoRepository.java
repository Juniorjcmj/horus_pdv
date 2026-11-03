package br.com.quack.pdv.estoque.inventario.repo;

import br.com.quack.pdv.estoque.inventario.domain.InventarioEvento;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventarioEventoRepository extends JpaRepository<InventarioEvento, String> {

    List<InventarioEvento> findByInventarioIdOrderByOcorridoEmAsc(String inventarioId);
}

