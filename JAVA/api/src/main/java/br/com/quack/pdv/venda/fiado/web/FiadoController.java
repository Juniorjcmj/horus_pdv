package br.com.quack.pdv.venda.fiado.web;

import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.ErroDeModelo;
import br.com.quack.pdv.venda.fiado.app.FiadoService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rotas de /api/Fiado (FiadoController.cs): receber, extrato, devedores e resumo. */
@RestController
@RequestMapping("api/Fiado")
@PerfisPermitidos({"administrador", "gerente", "caixa"})
public class FiadoController {

    private final FiadoService fiado;

    public FiadoController(FiadoService fiado) {
        this.fiado = fiado;
    }

    /** Corpo de RecebimentoFiadoRequest (padroes do .NET; null explicito em campo nao anulavel e recusado). */
    public static class Receber {
        public String clienteId = "";
        public BigDecimal valor = BigDecimal.ZERO;
        public String formaPagamento = "dinheiro";
        public String observacao;

        Map<String, String> camposObrigatoriosAusentes() {
            Map<String, String> m = new LinkedHashMap<>();
            if (clienteId == null) m.put("ClienteId", "The ClienteId field is required.");
            if (formaPagamento == null) m.put("FormaPagamento", "The FormaPagamento field is required.");
            return m;
        }
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<?> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    // Receber altera o crediario do cliente: gerente so consulta.
    @PostMapping("receber")
    @PerfisPermitidos({"administrador", "caixa"})
    public ResponseEntity<?> receber(@RequestBody Receber body, HttpServletRequest req) {
        ResponseEntity<?> invalido = ErroDeModelo.validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Recebimento registrado com sucesso.",
                fiado.receber(u.companyId(), u.id(), u.name(), body.clienteId, body.valor, body.formaPagamento,
                        body.observacao)));
    }

    @GetMapping("extrato/{clienteId}")
    public ResponseEntity<?> extrato(@PathVariable String clienteId,
                                     @RequestParam(required = false) String dataInicio,
                                     @RequestParam(required = false) String dataFim, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        Map<String, String> erros = new LinkedHashMap<>();
        OffsetDateTime inicio = lerData("dataInicio", dataInicio, erros);
        OffsetDateTime fim = lerData("dataFim", dataFim, erros);
        ResponseEntity<?> invalido = ErroDeModelo.validar(erros, req);
        if (invalido != null) return invalido;
        return ResponseEntity.ok(ApiResponse.ok("Extrato obtido com sucesso.",
                fiado.extrato(u.companyId(), clienteId, inicio, fim)));
    }

    @GetMapping("devedores")
    public ResponseEntity<?> devedores(@RequestParam(required = false) String busca, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Lista de devedores obtida com sucesso.",
                fiado.devedores(u.companyId(), busca)));
    }

    @GetMapping("resumo")
    public ResponseEntity<?> resumo(HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Resumo financeiro de fiado obtido com sucesso.",
                fiado.resumo(u.companyId())));
    }

    /** DateTimeOffset? do model binder: vazio vira null; sem offset vale o fuso local do servidor. */
    private static OffsetDateTime lerData(String campo, String texto, Map<String, String> erros) {
        if (texto == null || texto.isBlank()) return null;
        String t = texto.strip();
        ZoneId local = ZoneId.systemDefault();
        try {
            return OffsetDateTime.parse(t);
        } catch (DateTimeParseException e) {
            // tenta sem offset
        }
        try {
            return LocalDateTime.parse(t).atZone(local).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            // tenta so a data
        }
        try {
            return LocalDate.parse(t).atStartOfDay(local).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            erros.put(campo, "The value '" + texto + "' is not valid.");
            return null;
        }
    }
}
