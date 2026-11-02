package br.com.quack.pdv.empresa.repo;
import br.com.quack.pdv.empresa.domain.EmpresaTema;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EmpresaTemaRepository extends JpaRepository<EmpresaTema, String> {}
