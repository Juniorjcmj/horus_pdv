package br.com.quack.pdv.empresa.repo;
import br.com.quack.pdv.empresa.domain.TreinamentoVideo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TreinamentoVideoRepository extends JpaRepository<TreinamentoVideo,String> { List<TreinamentoVideo> findAllByOrderByOrdemAscCriadoEmAsc(); }
