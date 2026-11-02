package br.com.quack.pdv.teste;

import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Rotas so de teste (ficam no classpath de teste): erros, JSON, roteamento e perfis. */
@RestController
@RequestMapping("api/Teste")
public class ControladorDeTeste {

    public record Corpo(String nome, Integer idade) {}

    @GetMapping("eco")
    public ResponseEntity<ApiResponse<Object>> eco() {
        return ResponseEntity.ok(ApiResponse.ok("ok", Map.of("a", 1)));
    }

    @GetMapping("negocio")
    public ResponseEntity<ApiResponse<Object>> negocio() {
        throw new NegocioException(HttpStatus.CONFLICT, "Regra violada.", "DETALHE_X", Map.of("k", "v"));
    }

    @GetMapping("quebra")
    public ResponseEntity<ApiResponse<Object>> quebra() {
        throw new IllegalStateException("segredo interno que nao pode vazar");
    }

    @PostMapping("corpo")
    public ResponseEntity<Corpo> corpo(@RequestBody Corpo corpo) {
        return ResponseEntity.ok(corpo);
    }

    @GetMapping("json")
    public Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nulo", null);
        m.put("inteira", OffsetDateTime.of(2026, 11, 1, 22, 30, 0, 0, ZoneOffset.UTC));
        m.put("fracao", OffsetDateTime.of(2026, 11, 1, 22, 30, 0, 123456700, ZoneOffset.UTC));
        m.put("fracaoCurta", OffsetDateTime.of(2026, 11, 1, 22, 30, 0, 120000000, ZoneOffset.UTC));
        m.put("comOffset", OffsetDateTime.of(2026, 11, 1, 19, 30, 0, 0, ZoneOffset.ofHours(-3)));
        m.put("acentos", "Ação café");
        return m;
    }

    @GetMapping("admin")
    @PerfisPermitidos({"administrador"})
    public ResponseEntity<ApiResponse<Object>> soAdmin() {
        return ResponseEntity.ok(ApiResponse.ok("admin"));
    }

    @GetMapping("financeiro")
    @PerfisPermitidos({"administrador", "financeiro"})
    public ResponseEntity<ApiResponse<Object>> financeiro() {
        return ResponseEntity.ok(ApiResponse.ok("financeiro"));
    }
}

