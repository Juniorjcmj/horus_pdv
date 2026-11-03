package br.com.quack.pdv.produto.lote.repo;

import br.com.quack.pdv.produto.lote.domain.LoteConsumo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoteConsumoRepository extends JpaRepository<LoteConsumo, String> {

    List<LoteConsumo> findByCompanyIdAndRefId(String companyId, String refId);
}

