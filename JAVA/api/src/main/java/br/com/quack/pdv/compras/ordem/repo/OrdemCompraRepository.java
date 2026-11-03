package br.com.quack.pdv.compras.ordem.repo;

import br.com.quack.pdv.compras.ordem.domain.OrdemCompra;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdemCompraRepository extends JpaRepository<OrdemCompra, String> {

    Optional<OrdemCompra> findByIdAndCompanyId(String id, String companyId);
}

