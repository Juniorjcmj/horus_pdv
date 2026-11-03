package br.com.quack.pdv.estoque.inventario.repo;

import br.com.quack.pdv.estoque.inventario.domain.Inventario;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventarioRepository extends JpaRepository<Inventario, String> {

    Optional<Inventario> findByIdAndCompanyId(String id, String companyId);
}

