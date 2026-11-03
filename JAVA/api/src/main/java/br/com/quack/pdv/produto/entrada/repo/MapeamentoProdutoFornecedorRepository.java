package br.com.quack.pdv.produto.entrada.repo;

import br.com.quack.pdv.produto.entrada.domain.MapeamentoProdutoFornecedor;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MapeamentoProdutoFornecedorRepository extends JpaRepository<MapeamentoProdutoFornecedor, String> {

    Optional<MapeamentoProdutoFornecedor> findByCompanyIdAndFornecedorCnpjAndCodigoProdutoFornecedor(
            String companyId, String fornecedorCnpj, String codigoProdutoFornecedor);
}

