package br.com.quack.pdv.produto.entrada.repo;

import br.com.quack.pdv.produto.entrada.domain.NotaEntrada;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotaEntradaRepository extends JpaRepository<NotaEntrada, String> {

    Optional<NotaEntrada> findByIdAndCompanyId(String id, String companyId);

    Optional<NotaEntrada> findByCompanyIdAndIdentidade(String companyId, String identidade);

    Optional<NotaEntrada> findByCompanyIdAndOperacaoManualId(String companyId, String operacaoManualId);
}

