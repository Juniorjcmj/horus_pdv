package br.com.quack.pdv.empresa.repo;
import br.com.quack.pdv.empresa.domain.TreinamentoSecao;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TreinamentoSecaoRepository extends JpaRepository<TreinamentoSecao,String> { List<TreinamentoSecao> findAllByOrderByOrdemAscNomeAsc(); }
