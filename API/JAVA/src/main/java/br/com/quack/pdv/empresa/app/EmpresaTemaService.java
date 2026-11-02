package br.com.quack.pdv.empresa.app;
import br.com.quack.pdv.empresa.domain.EmpresaTema;
import br.com.quack.pdv.empresa.repo.EmpresaTemaRepository;
import br.com.quack.pdv.shared.NegocioException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class EmpresaTemaService {
    public record Tema(String systemAccent, String pdvAccent, String updatedAt) {}
    private final EmpresaTemaRepository temas;
    private final Clock clock;
    public EmpresaTemaService(EmpresaTemaRepository temas, Clock clock) { this.temas=temas; this.clock=clock; }
    public Tema obter(String companyId) { return temas.findById(companyId).map(this::resposta).orElse(null); }
    @Transactional
    public Tema salvar(String companyId, String userId, String system, String pdv) {
        system = normalizar(system); pdv = normalizar(pdv);
        if (!valida(system) || !valida(pdv)) throw new NegocioException(HttpStatus.BAD_REQUEST, "Cor inválida. Use formato hexadecimal #rrggbb (ex.: #0369a1) ou vazio para o padrão.");
        EmpresaTema tema = temas.findById(companyId).orElseGet(EmpresaTema::new);
        tema.setCompanyId(companyId); tema.setSystemAccent(system); tema.setPdvAccent(pdv);
        tema.setUpdatedBy(userId); tema.setUpdatedAt(LocalDateTime.now(clock));
        return resposta(temas.saveAndFlush(tema));
    }
    private Tema resposta(EmpresaTema t) { return new Tema(t.getSystemAccent(), t.getPdvAccent(), t.getUpdatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS"))); }
    private String normalizar(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private boolean valida(String v) { return v == null || v.matches("^#[0-9a-fA-F]{6}$"); }
}
