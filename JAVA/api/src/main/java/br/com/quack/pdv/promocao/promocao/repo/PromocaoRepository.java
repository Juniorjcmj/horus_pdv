package br.com.quack.pdv.promocao.promocao.repo;

import br.com.quack.pdv.promocao.promocao.domain.Promocao;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromocaoRepository extends JpaRepository<Promocao, String> {

    Optional<Promocao> findByIdAndCompanyId(String id, String companyId);
}

