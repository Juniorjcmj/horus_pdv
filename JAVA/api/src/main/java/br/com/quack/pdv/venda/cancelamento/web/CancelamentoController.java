package br.com.quack.pdv.venda.cancelamento.web;

import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.ClienteIpResolver;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.usuario.app.ValidadorSupervisor;
import br.com.quack.pdv.venda.cancelamento.app.CancelamentoConsultas;
import br.com.quack.pdv.venda.cancelamento.app.CancelamentoVendaService;
import br.com.quack.pdv.venda.cancelamento.app.CorrecaoVendaService;
import br.com.quack.pdv.venda.cancelamento.domain.CancelamentoResultado;
import br.com.quack.pdv.venda.cancelamento.domain.CorrecaoResultado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
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

/**
 * Cancelamento, trilha e correcao de venda em /api/HistoricoVendas (HistoricoVendasController.cs):
 * cancelar, cancelamentos, cancelamentos/auditoria e corrigir.
 */
@RestController
@RequestMapping("api/HistoricoVendas")
public class CancelamentoController {

    static final String MSG_JUSTIFICATIVA = "Informe uma justificativa de no mínimo 5 caracteres.";
    static final int JUSTIFICATIVA_MINIMA = 5;

    private final CancelamentoVendaService cancelamento;
    private final CorrecaoVendaService correcao;
    private final CancelamentoConsultas consultas;
    private final ValidadorSupervisor validador;
    private final FiscalDaVenda fiscal;
    private final ClienteIpResolver ips;

    public CancelamentoController(CancelamentoVendaService cancelamento, CorrecaoVendaService correcao,
                                  CancelamentoConsultas consultas, ValidadorSupervisor validador,
                                  FiscalDaVenda fiscal, ClienteIpResolver ips) {
        this.cancelamento = cancelamento;
        this.correcao = correcao;
        this.consultas = consultas;
        this.validador = validador;
        this.fiscal = fiscal;
        this.ips = ips;
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<?> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    private static String texto(String s) {
        return s == null ? "" : s;
    }

    @PostMapping("{saleNumber}/cancelar")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<?> cancelar(@PathVariable String saleNumber, @RequestBody CancelamentoRequests.Cancelamento body,
                                      HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();

        String ip = ips.resolver(req);
        String justificativa = texto(body.justificativa).strip();
        String terminal = body.terminal == null || body.terminal.isBlank() ? null : body.terminal.strip();
        String supervisorId = body.supervisorId == null || body.supervisorId.isBlank() ? null
                : body.supervisorId.strip();
        CancelamentoVendaService.Pedido semGerente = new CancelamentoVendaService.Pedido(u.companyId(), saleNumber,
                supervisorId, null, u.id(), u.name(), justificativa, terminal, ip);

        // Toda recusa entra na trilha (VendaCancelamentos + AuditLog CancelamentoVendaNegado).
        if (justificativa.length() < JUSTIFICATIVA_MINIMA) {
            cancelamento.registrarNegada(semGerente, MSG_JUSTIFICATIVA, null);
            return ResponseEntity.badRequest().body(ApiResponse.erro(MSG_JUSTIFICATIVA));
        }

        ValidadorSupervisor.Resultado v = validador.validar(texto(body.supervisorId), texto(body.supervisorPassword),
                u.companyId());
        if (!v.valido() || v.supervisor() == null) {
            cancelamento.registrarNegada(semGerente, v.mensagem(), null);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.erro(v.mensagem()));
        }

        var supervisor = v.supervisor();
        CancelamentoVendaService.Pedido pedido = new CancelamentoVendaService.Pedido(u.companyId(), saleNumber,
                supervisor.id(), supervisor.name(), u.id(), u.name(), justificativa, terminal, ip);
        CancelamentoResultado r = cancelamento.cancelar(pedido);
        if (!r.sucesso()) {
            cancelamento.registrarNegada(semGerente, r.mensagem(), supervisor.name());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("codigo", r.codigo());
            data.put("documentoFiscalId", r.documentoFiscalId());
            data.put("documentoModelo", r.documentoModelo());
            HttpStatus status = switch (r.codigo()) {
                case "NAO_ENCONTRADA" -> HttpStatus.NOT_FOUND;
                case "NOTA_AUTORIZADA", "NOTA_PENDENTE", "JA_CANCELADA", "STATUS_INVALIDO" -> HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(ApiResponse.erro(r.mensagem(), null, data));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saleNumber", saleNumber);
        data.put("canceladoEm", r.canceladoEm());
        data.put("supervisorNome", supervisor.name());
        data.put("itensEstornados", r.itensEstornados());
        data.put("fiadoEstornado", r.fiadoEstornado());
        return ResponseEntity.ok(ApiResponse.ok(r.mensagem(), data));
    }

    /** Trilha completa (autorizados e negados) com a fotografia de cada venda. So gerencia. */
    @GetMapping("cancelamentos/auditoria")
    @PerfisPermitidos({"administrador", "gerente"})
    public ResponseEntity<?> auditoria(@RequestParam(required = false) String de,
                                       @RequestParam(required = false) String ate,
                                       @RequestParam(required = false) String venda, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Trilha de cancelamentos.",
                consultas.trilha(u.companyId(), de, ate, venda)));
    }

    @GetMapping("cancelamentos")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<?> listar(@RequestParam(required = false) String de,
                                    @RequestParam(required = false) String ate, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Cancelamentos obtidos com sucesso.",
                consultas.cancelamentos(u.companyId(), de, ate)));
    }

    @PostMapping("{saleNumber}/corrigir")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa", "financeiro"})
    public ResponseEntity<?> corrigir(@PathVariable String saleNumber, @RequestBody CancelamentoRequests.Correcao body,
                                      HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();

        // Responsavel: o gerente que autorizou com a senha ou, sem gerente, o proprio usuario (perfil financeiro).
        String responsavelId = u.id();
        String responsavelNome = u.name();
        boolean comGerente = !texto(body.supervisorId).isBlank() || !texto(body.supervisorPassword).isBlank();
        if (comGerente) {
            ValidadorSupervisor.Resultado v = validador.validar(texto(body.supervisorId),
                    texto(body.supervisorPassword), u.companyId());
            if (!v.valido() || v.supervisor() == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.erro(v.mensagem()));
            }
            responsavelId = v.supervisor().id();
            responsavelNome = v.supervisor().name();
        } else if (!u.temAcessoFinanceiro()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.erro("Apenas usuários com perfil financeiro podem corrigir vendas."));
        }

        if (texto(body.justificativa).isBlank() || body.justificativa.strip().length() < JUSTIFICATIVA_MINIMA) {
            return ResponseEntity.badRequest().body(ApiResponse.erro(MSG_JUSTIFICATIVA));
        }
        List<String> remover = body.produtosParaRemover == null ? List.of() : body.produtosParaRemover;
        if (remover.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Selecione ao menos um produto para remover."));
        }

        CorrecaoResultado resultado = correcao.corrigir(u.companyId(), saleNumber, remover, responsavelId,
                responsavelNome, body.justificativa.strip());
        boolean fila = fiscal.aposCorrecao(u.companyId(), "sale-" + u.companyId() + "-" + resultado.novaVendaNumber());
        return ResponseEntity.ok(ApiResponse.ok("Venda #" + saleNumber + " corrigida com sucesso. Nova venda: #"
                + resultado.novaVendaNumber() + ".", resultado.comFiscalQueued(fila)));
    }
}
