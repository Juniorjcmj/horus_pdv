package br.com.quack.pdv.cadastro.mercado.repo;
import br.com.quack.pdv.cadastro.mercado.domain.ModuloMercadoRegistro;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ModuloMercadoRegistroRepository extends JpaRepository<ModuloMercadoRegistro,String> {
    List<ModuloMercadoRegistro> findByCompanyIdAndModuleIdOrderById(String companyId,String moduleId);
    Optional<ModuloMercadoRegistro> findByCompanyIdAndModuleIdAndId(String companyId,String moduleId,String id);
}
