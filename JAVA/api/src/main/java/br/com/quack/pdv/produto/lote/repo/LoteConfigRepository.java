package br.com.quack.pdv.produto.lote.repo;

import br.com.quack.pdv.produto.lote.domain.LoteConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoteConfigRepository extends JpaRepository<LoteConfig, String> {
}

