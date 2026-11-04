package br.com.quack.pdv.venda.pedido.web;

import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.security.AutenticacaoFilter;
import br.com.quack.pdv.security.PerfisPermitidos;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.ApiResponse;
import br.com.quack.pdv.shared.ErroDeModelo;
import br.com.quack.pdv.venda.caixa.app.CaixaService;
import br.com.quack.pdv.venda.pedido.app.PedidoService;
import br.com.quack.pdv.venda.pedido.domain.Pedido;
import br.com.quack.pdv.venda.pedido.domain.PedidoModel;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.app.SaleMoney;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RestController;

/** Rotas de /api/Pedido (PedidoController.cs): criar, listar abertos, consultar, finalizar e cancelar. */
@RestController
@RequestMapping("api/Pedido")
public class PedidoController {

    private final PedidoService pedidos;
    private final VendaRegistroService registro;
    private final CaixaService caixa;
    private final FiscalDaVenda fiscal;

    public PedidoController(PedidoService pedidos, VendaRegistroService registro, CaixaService caixa,
                            FiscalDaVenda fiscal) {
        this.pedidos = pedidos;
        this.registro = registro;
        this.caixa = caixa;
        this.fiscal = fiscal;
    }

    private static UsuarioAutenticado atual(HttpServletRequest r) {
        return (UsuarioAutenticado) r.getAttribute(AutenticacaoFilter.ATRIBUTO_USUARIO);
    }

    private static ResponseEntity<?> semSessao() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.erro("Sessão não encontrada."));
    }

    private static ResponseEntity<?> naoEncontrado() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.erro("Pedido não encontrado."));
    }

    @PostMapping
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ResponseEntity<?> criar(@RequestBody PedidoRequests.Criar body, HttpServletRequest req) {
        ResponseEntity<?> invalido = ErroDeModelo.validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        Pedido p = pedidos.criar(u.companyId(), u.id(), u.name(), body.customerName, body.customerCpf, body.items);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Pedido criado com sucesso.", PedidoService.paraModelo(p)));
    }

    @GetMapping
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ResponseEntity<?> listarAbertos(HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        List<PedidoModel> lista = pedidos.listarAbertos(u.companyId()).stream().map(PedidoService::paraModelo)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok("Pedidos obtidos com sucesso.", lista));
    }

    @GetMapping("{orderNumber}")
    public ResponseEntity<?> obter(@PathVariable String orderNumber, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        return pedidos.obter(u.companyId(), orderNumber)
                .<ResponseEntity<?>>map(p -> ResponseEntity
                        .ok(ApiResponse.ok("Pedido obtido com sucesso.", PedidoService.paraModelo(p))))
                .orElseGet(PedidoController::naoEncontrado);
    }

    @PostMapping("{orderNumber}/finalizar")
    public ResponseEntity<?> finalizar(@PathVariable String orderNumber, @RequestBody PedidoRequests.Finalizar body,
                                       HttpServletRequest req) {
        ResponseEntity<?> invalido = ErroDeModelo.validar(body.camposObrigatoriosAusentes(), req);
        if (invalido != null) return invalido;
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();

        caixa.garantirVendaPermitida(u, null);

        Pedido pedido = pedidos.obter(u.companyId(), orderNumber).orElse(null);
        if (pedido == null) return naoEncontrado();
        if (pedido.status() == Pedido.CANCELADO) {
            return ResponseEntity.badRequest().body(ApiResponse.erro("Este pedido foi cancelado."));
        }

        String paymentType = HashPayload.emBranco(body.paymentType) ? "-" : HashPayload.trim(body.paymentType);
        String caixaSessaoId = null;
        try {
            caixaSessaoId = caixa.sessaoAbertaDoOperador(u.companyId(), u.id()).map(s -> s.id()).orElse(null);
        } catch (RuntimeException e) {
            // sem vinculo de turno: a venda segue
        }
        List<SaleMoney.Entrada> entradas = new ArrayList<>();
        for (var p : body.payments) {
            entradas.add(new SaleMoney.Entrada(p.paymentType, p.amount, p.cashGiven, p.changeAmount));
        }

        VendaRegistroResultado r = registro.registrarComPrecosFixos(u.companyId(), pedido.customerName(),
                pedido.customerCpf(), paymentType, u.name(), pedido.itens(), entradas, caixaSessaoId, pedido.id());

        List<String> formas = new ArrayList<>();
        for (var p : body.payments) {
            if (!HashPayload.emBranco(p.paymentType)) formas.add(p.paymentType);
        }
        if (formas.isEmpty() && !HashPayload.emBranco(paymentType)) {
            formas.add(paymentType);
        }
        FiscalDaVenda.Resultado f = fiscal.aposPedido(u.companyId(), r.vendaId(), r.isReplay(), paymentType, formas,
                pedido.customerCpf());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saleNumber", r.saleNumber());
        data.put("rows", r.rows());
        data.put("fiscalQueued", f.fiscalQueued());
        data.put("emitirFiscal", f.emitirFiscal());
        return ResponseEntity.ok(ApiResponse.ok("Pedido finalizado com sucesso.", data));
    }

    @PostMapping("{orderNumber}/cancelar")
    @PerfisPermitidos({"administrador", "gerente", "atendente"})
    public ResponseEntity<?> cancelar(@PathVariable String orderNumber, HttpServletRequest req) {
        UsuarioAutenticado u = atual(req);
        if (u == null) return semSessao();
        if (pedidos.cancelar(u.companyId(), orderNumber)) {
            return ResponseEntity.ok(ApiResponse.ok("Pedido cancelado com sucesso."));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.erro("Pedido não encontrado ou não está mais aberto."));
    }
}
