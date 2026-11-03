package br.com.quack.pdv.venda.registro.web;

import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.app.CaixaService;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.app.AutorizacaoPrecoService;
import br.com.quack.pdv.venda.registro.app.VendaConsultaService;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rotas de vendas de /api/HistoricoVendas (HistoricoVendasController.cs): registrar, listar, detalhe, imprimir. */
@RestController
@RequestMapping("api/HistoricoVendas")
public class VendaController {

    private static final Logger LOG = LoggerFactory.getLogger(VendaController.class);

    static final String MSG_CONTINGENCIA =
            "A importação da NFC-e de contingência do Gateway ainda não está disponível nesta versão.";

    private final VendaRegistroService registro;
    private final VendaConsultaService consulta;
    private final AutorizacaoPrecoService autorizacoes;
    private final CaixaService caixa;
    private final FiscalDaVenda fiscal;

    public VendaController(VendaRegistroService registro, VendaConsultaService consulta,
                           AutorizacaoPrecoService autorizacoes, CaixaService caixa, FiscalDaVenda fiscal) {
        this.registro = registro;
        this.consulta = consulta;
        this.autorizacoes = autorizacoes;
        this.caixa = caixa;
        this.fiscal = fiscal;
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<?> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    @PostMapping("autorizar-preco")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<?> autorizarPreco(@RequestBody VendaRequests.AutorizarPreco body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        AutorizacaoPrecoService.Autorizada a = autorizacoes.autorizar(u, body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("autorizacaoId", a.autorizacaoId());
        data.put("supervisorNome", a.supervisorNome());
        data.put("precoTabela", a.precoTabela());
        data.put("precoNovo", a.precoNovo());
        data.put("expiraEm", a.expiraEm());
        return ResponseEntity.ok(ApiResponse.ok("Alteração de preço autorizada.", data));
    }

    @GetMapping
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<?> listar(@RequestParam(required = false) String desde,
                                    @RequestParam(required = false) String caixaSessaoId, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return ResponseEntity.ok(ApiResponse.ok("Historico de vendas obtido com sucesso.",
                consulta.listar(u, desde, caixaSessaoId)));
    }

    @GetMapping("{saleNumber}")
    @PerfisPermitidos({"administrador", "gerente", "atendente", "caixa"})
    public ResponseEntity<?> detalhes(@PathVariable String saleNumber, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        Optional<Map<String, Object>> d = consulta.detalhe(u.companyId(), saleNumber);
        if (d.isEmpty()) return naoEncontrada();
        return ResponseEntity.ok(ApiResponse.ok("Detalhes da venda obtidos com sucesso.", d.get()));
    }

    @PostMapping
    public ResponseEntity<?> registrar(@RequestBody VendaRequests.Venda body, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        if (body.items.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Venda sem itens."));
        }
        // Divergencia consciente (D10): a importacao da nota de contingencia do Gateway e da Fase 7.
        if (!HashPayload.emBranco(body.xmlNfceContingencia)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, MSG_CONTINGENCIA);
        }
        boolean sincronizadaOffline = !HashPayload.emBranco(body.eventId) || body.occurredAt != null;
        if (!sincronizadaOffline) {
            caixa.garantirVendaPermitida(u, null);
        } else {
            try {
                caixa.garantirVendaPermitida(u, null);
            } catch (RuntimeException e) {
                LOG.warn("Venda sincronizada offline com aviso de caixa ({}). EventId: {}", e.getMessage(),
                        body.eventId);
            }
        }
        String caixaSessaoId = null;
        try {
            caixaSessaoId = caixa.sessaoAbertaDoOperador(u.companyId(), u.id()).map(s -> s.id()).orElse(null);
        } catch (RuntimeException e) {
            // venda offline ou caixa indisponivel: segue sem vinculo
        }

        VendaRegistroResultado result = registro.registrar(u.companyId(), body, caixaSessaoId);
        if (result.isReplay()) {
            LOG.info("Venda retornada via replay idempotente. EventId: {}, SaleNumber: {}", body.eventId,
                    result.saleNumber());
        }

        List<String> formas = new ArrayList<>();
        for (VendaRequests.Pagamento p : body.payments) {
            if (!HashPayload.emBranco(p.paymentType)) formas.add(p.paymentType);
        }
        if (formas.isEmpty() && !HashPayload.emBranco(body.paymentType)) {
            formas.add(body.paymentType);
        }
        FiscalDaVenda.Resultado f = fiscal.aposVenda(u.companyId(), result.vendaId(), result.saleNumber(),
                body.paymentType, formas, body.customerCpf);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saleNumber", result.saleNumber());
        data.put("clientSaleId", result.clientSaleId() != null ? result.clientSaleId() : body.clientSaleId);
        data.put("vendaId", result.vendaId());
        data.put("rows", result.rows());
        data.put("fiscalQueued", f.fiscalQueued());
        data.put("emitirFiscal", f.emitirFiscal());
        data.put("isReplay", result.isReplay());
        data.put("warnings", result.warnings());
        data.put("fiadoSaldoAtual", result.fiadoSaldoAtual());
        data.put("fiadoClienteId", result.fiadoClienteId());
        return ResponseEntity.status(result.isReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.ok("Venda registrada com sucesso.", data));
    }

    @PostMapping("{saleNumber}/imprimir")
    public ResponseEntity<?> imprimir(@PathVariable String saleNumber, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        Optional<Map<String, Object>> d = consulta.imprimir(u.companyId(), saleNumber);
        if (d.isEmpty()) return naoEncontrada();
        return ResponseEntity.ok(ApiResponse.ok("Impressao enviada para processamento.", d.get()));
    }

    private static ResponseEntity<?> naoEncontrada() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.erro("Venda não encontrada."));
    }
}
