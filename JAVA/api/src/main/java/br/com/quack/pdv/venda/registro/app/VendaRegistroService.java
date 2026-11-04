package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.fiado.app.CreditoGateway;
import br.com.quack.pdv.venda.fiado.app.FiadoDebitos;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda.Baixa;
import br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda.ItemRegistrado;
import br.com.quack.pdv.venda.registro.app.BaixaEstoqueVenda.LinhaVenda;
import br.com.quack.pdv.venda.registro.domain.PagamentoNormalizado;
import br.com.quack.pdv.venda.registro.domain.RupturaAviso;
import br.com.quack.pdv.venda.registro.domain.VendaHistoricoDto;
import br.com.quack.pdv.venda.registro.domain.VendaPagamentoDto;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.ClienteFiado;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.EventoProcessado;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoItem;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoTravado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registro de venda (HistoricoVendasAB.RegistrarAsync) na mesma ordem do .NET: hash, idempotencia, pedido, conferencias
 * de dinheiro, fiado, preco autorizado, baixa de estoque, numero e insercao, vinculos, debito de fiado, evento
 * processado e auditorias, tudo numa transacao. Lotes e auditoria de ruptura rodam depois do commit.
 */
@Service
public class VendaRegistroService {

    private static final Logger LOG = LoggerFactory.getLogger(VendaRegistroService.class);
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    /** Saida da transacao: o resultado e o que fazer depois do commit. */
    private record Saida(VendaRegistroResultado resultado, List<ItemRegistrado> itens, List<RupturaAviso> rupturas,
                         String operatorName) {}

    private final VendaRepository repo;
    private final AutorizacaoPrecoService precos;
    private final BaixaEstoqueVenda baixa;
    private final RegistradorEstoque registrador;
    private final NumeradorDocumentos numerador;
    private final FiadoDebitos fiado;
    private final CreditoGateway credito;
    private final LoteOperacoes lotes;
    private final AuditoriaService auditoria;
    private final Clock clock;
    private final TransactionTemplate tx;

    public VendaRegistroService(VendaRepository repo, AutorizacaoPrecoService precos, BaixaEstoqueVenda baixa,
                                RegistradorEstoque registrador, NumeradorDocumentos numerador, FiadoDebitos fiado,
                                CreditoGateway credito, LoteOperacoes lotes, AuditoriaService auditoria, Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.precos = precos;
        this.baixa = baixa;
        this.registrador = registrador;
        this.numerador = numerador;
        this.fiado = fiado;
        this.credito = credito;
        this.lotes = lotes;
        this.auditoria = auditoria;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Registra a venda. Erros de regra do .NET (ArgumentException/InvalidOperationException) viram 400. */
    public VendaRegistroResultado registrar(String companyId, VendaRequests.Venda r, String caixaSessaoId) {
        Saida saida;
        try {
            saida = tx.execute(s -> executar(companyId, r, caixaSessaoId));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (saida.resultado().isReplay()) {
            return saida.resultado();
        }
        // Baixa FEFO por lote: depois do commit e sem nunca derrubar a venda ja gravada.
        lotes.consumirVendaSeguro(companyId, saida.resultado().vendaId(), saida.itens().stream()
                .map(i -> new LoteOperacoes.ItemVenda(i.productCode(), i.quantity())).toList());
        auditarRupturas(companyId, saida);
        return saida.resultado();
    }

    private void auditarRupturas(String companyId, Saida saida) {
        for (RupturaAviso a : saida.rupturas()) {
            try {
                auditoria.registrar(companyId, "", saida.operatorName(), "EstoqueRuptura",
                        "Ruptura de estoque na venda offline " + saida.resultado().saleNumber() + ": "
                                + a.productName() + " (Cód. " + a.productCode() + "). Estoque anterior: "
                                + Dinheiro.formatarQuantidade(a.estoqueAnterior()) + ", Vendido: "
                                + Dinheiro.formatarQuantidade(a.quantidadeVendida()) + ", Saldo resultante: "
                                + Dinheiro.formatarQuantidade(a.saldoResultante()) + ".",
                        "Produto", a.productCode(), null);
            } catch (RuntimeException e) {
                LOG.warn("Falha ao auditar a ruptura do produto {}: {}", a.productCode(), e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- transacao

    private Saida executar(String companyId, VendaRequests.Venda r, String caixaSessaoId) {
        String eventId = r.eventId == null ? null : HashPayload.trim(r.eventId);
        boolean comEvento = !HashPayload.emBranco(eventId);
        String identityHash = comEvento ? hashDeIdentidade(r) : null;

        if (comEvento) {
            Optional<EventoProcessado> existente = repo.buscarEventoComBloqueio(companyId, eventId);
            if (existente.isPresent()) {
                if (!existente.get().payloadHash().equalsIgnoreCase(identityHash)) {
                    throw new NegocioException(HttpStatus.CONFLICT, "Conflito de idempotência: o EventId '" + eventId
                            + "' já foi processado anteriormente com um payload diferente.");
                }
                VendaRegistroResultado cached = VendaPayload.ler(existente.get().responsePayload()).comReplay(true);
                return new Saida(cached, List.of(), List.of(), "");
            }
        }

        PedidoTravado pedido = null;
        List<PedidoItem> itensPedido = null;
        if (!HashPayload.emBranco(r.orderNumber)) {
            pedido = conferirPedido(companyId, r);
            itensPedido = new ArrayList<>(repo.itensDoPedido(pedido.id()));
            itensPedido.sort(Comparator.comparing(PedidoItem::productCode));
        }

        String customerName = HashPayload.emBranco(r.customerName) ? "Consumidor" : HashPayload.trim(r.customerName);
        String customerCpf = HashPayload.emBranco(r.customerCpf) ? "-" : HashPayload.trim(r.customerCpf);
        String paymentType = HashPayload.emBranco(r.paymentType) ? "-" : HashPayload.trim(r.paymentType);
        BigDecimal totalAmount = Dinheiro.lerEstrito(r.totalAmount, "TotalAmount");
        if (totalAmount.signum() < 0) {
            throw new IllegalArgumentException("O valor total da venda não pode ser negativo.");
        }
        String operatorName = HashPayload.emBranco(r.operatorName) ? "Operador" : HashPayload.trim(r.operatorName);
        totalAmount = SaleMoney.money(totalAmount);

        conferirItens(r, totalAmount);
        List<PagamentoNormalizado> pagamentos = SaleMoney.payments(totalAmount, paymentType, entradas(r));
        paymentType = pagamentos.size() == 1 ? pagamentos.getFirst().paymentType() : "Múltiplo";
        BigDecimal fiadoAmount = SaleMoney.fiado(pagamentos);

        String fiadoClienteId = null;
        if (fiadoAmount.signum() > 0) {
            if (HashPayload.emBranco(customerCpf) || customerCpf.equals("-")) {
                throw new IllegalArgumentException(
                        "Para compras a prazo / fiado, é obrigatório vincular um cliente cadastrado.");
            }
            ClienteFiado cliente = repo.travarClientePorDocumento(companyId, somenteDigitos(customerCpf))
                    .orElseThrow(() -> new IllegalArgumentException("Cliente com CPF/CNPJ " + HashPayload.trim(r.customerCpf)
                            + " não encontrado no cadastro."));
            fiadoClienteId = cliente.id();
            customerName = cliente.customerName();
            if (r.temCreditAuthorization()) {
                // Sem o canal do Gateway (Fase 5) a .NET recusa no inicio do ConsumeAsync.
                throw new IllegalArgumentException("INVALID_CREDIT_PROOF");
            }
            credito.exigirFiadoLegado(companyId, null);
            SaleMoney.credit(cliente.limiteCredito(),
                    cliente.saldoDevedor().add(credito.delegado(companyId, fiadoClienteId)), fiadoAmount);
        }

        boolean offline = comEvento || !HashPayload.emBranco(r.offlineReference) || r.occurredAt != null;
        String origem = RegistradorEstoque.novaOrigemProvisoria();
        AutorizacaoPrecoService.ValidacaoPrecos validacao;
        Baixa saidaEstoque;
        if (itensPedido == null) {
            baixa.travarEmOrdemDeCodigo(companyId, r.items.stream().map(i -> i.productCode).toList());
            validacao = precos.validarPrecosDaVenda(companyId, r.items.stream().map(i -> new AutorizacaoPrecoService
                    .ItemPreco(i.productCode, i.productName, i.getUnitPrice(), i.autorizacaoPrecoId)).toList(),
                    r.reenvioOffline);
            saidaEstoque = baixa.baixar(companyId, r.items.stream().map(i -> new LinhaVenda(i.productCode,
                    i.productName, i.quantity, i.getUnitPrice(), i.desconto, i.promocaoId)).toList(), offline,
                    origem);
        } else {
            validacao = new AutorizacaoPrecoService.ValidacaoPrecos(List.of(), List.of());
            saidaEstoque = baixa.baixarComPrecoFixo(companyId, itensPedido, origem);
        }

        VendaRegistroResultado resultado = inserirVenda(companyId, customerName, customerCpf, paymentType,
                totalAmount, operatorName, saidaEstoque.itens(), pagamentos, r.clientSaleId, r.offlineReference,
                r.occurredAt, caixaSessaoId, origem);

        precos.vincularVenda(companyId, validacao.usadas(), resultado.vendaId());
        if (pedido != null && repo.finalizarPedido(companyId, pedido.id(), resultado.vendaId()) != 1) {
            throw new IllegalArgumentException("Pedido alterado durante finalização.");
        }
        resultado = resultado.comWarnings(saidaEstoque.rupturas());

        FiadoDebitos.Movimento debito = null;
        if (fiadoAmount.signum() > 0 && fiadoClienteId != null) {
            debito = fiado.registrarDebito(companyId, fiadoClienteId, fiadoAmount, resultado.vendaId(), operatorName,
                    r.temCreditAuthorization());
            resultado = resultado.comFiado(debito.saldoAtual(), fiadoClienteId);
        }

        if (comEvento) {
            String tipo = r.eventType == null ? "SALE_CREATED" : HashPayload.trim(r.eventType);
            repo.inserirEvento("pe-" + UUID.randomUUID().toString().replace("-", ""), companyId, eventId, tipo,
                    r.clientSaleId, identityHash, agora(), VendaPayload.serializar(resultado));
        }

        if (!validacao.divergencias().isEmpty()) {
            repo.inserirAuditoria(companyId, operatorName, "PrecoOfflineAbaixo", "Venda #" + resultado.saleNumber()
                    + " sincronizada do modo offline com preço abaixo do cadastro, sem autorização de gerente: "
                    + String.join("; ", validacao.divergencias()) + ".", "Venda", resultado.vendaId());
        }
        if (debito != null) {
            repo.inserirAuditoria(companyId, operatorName, "FiadoDebito", "Venda fiado "
                    + Dinheiro.formatarSemSimbolo(debito.valor()) + " para " + debito.clienteNome()
                    + ". Saldo devedor: " + Dinheiro.formatarSemSimbolo(debito.saldoAtual()) + ".", "Cliente",
                    debito.clienteId());
        }
        return new Saida(resultado, saidaEstoque.itens(), saidaEstoque.rupturas(), operatorName);
    }

    // ---------------------------------------------------------------- pedido com precos fixos

    /** Finalizacao de pedido (RegistrarComPrecosFixosAsync): sem ProcessedEvents, replay pelo status do pedido. */
    public VendaRegistroResultado registrarComPrecosFixos(String companyId, String customerName, String customerCpf,
                                                          String paymentType, String operatorName,
                                                          List<PedidoItem> itens, List<SaleMoney.Entrada> pagamentos,
                                                          String caixaSessaoId, String pedidoId) {
        Saida saida;
        try {
            saida = tx.execute(s -> executarPrecosFixos(companyId, customerName, customerCpf, paymentType,
                    operatorName, itens, pagamentos, caixaSessaoId, pedidoId));
        } catch (IllegalArgumentException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!saida.resultado().isReplay()) {
            lotes.consumirVendaSeguro(companyId, saida.resultado().vendaId(), saida.itens().stream()
                    .map(i -> new LoteOperacoes.ItemVenda(i.productCode(), i.quantity())).toList());
        }
        return saida.resultado();
    }

    private Saida executarPrecosFixos(String companyId, String customerName, String customerCpf, String paymentType,
                                      String operatorName, List<PedidoItem> itens,
                                      List<SaleMoney.Entrada> entradas, String caixaSessaoId, String pedidoId) {
        var linha = repo.travarPedidoPorId(companyId, pedidoId)
                .orElseThrow(() -> new IllegalArgumentException("Pedido não encontrado."));
        if (linha.status() == 2) {
            throw new IllegalArgumentException("Pedido cancelado.");
        }
        if (linha.status() == 1) {
            if (linha.vendaId() == null) {
                throw new IllegalArgumentException("Pedido pago sem venda vinculada.");
            }
            List<VendaHistoricoDto> rows = repo.porVendaId(companyId, linha.vendaId());
            VendaRegistroResultado replay = new VendaRegistroResultado(rows.getFirst().saleNumber(), linha.vendaId(),
                    null, true, rows, repo.pagamentos(companyId, linha.vendaId()), List.of(), null, null);
            return new Saida(replay, List.of(), List.of(), operatorName);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (PedidoItem i : itens) {
            totalAmount = totalAmount.add(SaleMoney.itemNet(i.quantity(), i.unitPrice(), BigDecimal.ZERO));
        }
        List<PagamentoNormalizado> pagamentos = SaleMoney.payments(totalAmount, paymentType, entradas);
        String tipo = pagamentos.size() == 1 ? pagamentos.getFirst().paymentType() : "Múltiplo";
        BigDecimal fiadoAmount = SaleMoney.fiado(pagamentos);

        String nome = customerName;
        String fiadoClienteId = null;
        if (fiadoAmount.signum() > 0) {
            if (HashPayload.emBranco(customerCpf) || customerCpf.equals("-")) {
                throw new IllegalArgumentException(
                        "Para compras a prazo / fiado, é obrigatório vincular um cliente cadastrado.");
            }
            ClienteFiado cliente = repo.travarClientePorDocumento(companyId, somenteDigitos(customerCpf))
                    .orElseThrow(() -> new IllegalArgumentException("Cliente com CPF/CNPJ " + customerCpf
                            + " não encontrado no cadastro."));
            fiadoClienteId = cliente.id();
            nome = cliente.customerName();
            credito.exigirFiadoLegado(companyId, null);
            SaleMoney.credit(cliente.limiteCredito(),
                    cliente.saldoDevedor().add(credito.delegado(companyId, fiadoClienteId)), fiadoAmount);
        }

        String origem = RegistradorEstoque.novaOrigemProvisoria();
        Baixa saidaEstoque = baixa.baixarComPrecoFixo(companyId, itens, origem);
        VendaRegistroResultado resultado = inserirVenda(companyId, nome, customerCpf, tipo, totalAmount,
                operatorName, saidaEstoque.itens(), pagamentos, null, null, null, caixaSessaoId, origem);

        FiadoDebitos.Movimento debito = null;
        if (fiadoAmount.signum() > 0 && fiadoClienteId != null) {
            debito = fiado.registrarDebito(companyId, fiadoClienteId, fiadoAmount, resultado.vendaId(), operatorName,
                    false);
            resultado = resultado.comFiado(debito.saldoAtual(), fiadoClienteId);
        }
        if (repo.finalizarPedido(companyId, pedidoId, resultado.vendaId()) != 1) {
            throw new IllegalArgumentException("Pedido alterado durante finalização.");
        }
        if (debito != null) {
            repo.inserirAuditoria(companyId, operatorName, "FiadoDebito", "Venda fiado "
                    + Dinheiro.formatarSemSimbolo(debito.valor()) + " para " + debito.clienteNome()
                    + ". Saldo devedor: " + Dinheiro.formatarSemSimbolo(debito.saldoAtual()) + ".", "Cliente",
                    debito.clienteId());
        }
        return new Saida(resultado, saidaEstoque.itens(), List.of(), operatorName);
    }

    private String hashDeIdentidade(VendaRequests.Venda r) {
        if (!HashPayload.emBranco(r.payloadHash)) {
            return HashPayload.trim(r.payloadHash).toLowerCase(Locale.ROOT);
        }
        List<HashPayload.Item> itens = r.items.stream().map(i -> new HashPayload.Item(i.productCode, i.quantity,
                i.getUnitPrice(), i.desconto, i.promocaoId)).toList();
        List<HashPayload.Pagamento> pags = r.payments.stream().map(p -> new HashPayload.Pagamento(p.paymentType,
                p.amount, p.cashGiven, p.changeAmount)).toList();
        return HashPayload.calcular(new HashPayload.Venda(r.customerName, r.customerCpf, r.paymentType,
                r.totalAmount, r.operatorName, itens, pags, r.xmlNfceContingencia));
    }

    private PedidoTravado conferirPedido(String companyId, VendaRequests.Venda r) {
        PedidoTravado pedido = repo.travarPedido(companyId, r.orderNumber).orElse(null);
        if (pedido == null || pedido.status() != 0
                || !somenteDigitos(pedido.customerCpf()).equals(somenteDigitos(r.customerCpf))) {
            throw new IllegalArgumentException("Pedido indisponível ou cliente divergente.");
        }
        List<PedidoItem> congelados = new ArrayList<>(repo.itensDoPedido(pedido.id()));
        congelados.sort(Comparator.comparing(PedidoItem::productCode));
        List<VendaRequests.Item> reais = new ArrayList<>(r.items);
        reais.sort(Comparator.comparing(i -> i.productCode));
        boolean diverge = reais.size() != congelados.size();
        for (int i = 0; !diverge && i < reais.size(); i++) {
            VendaRequests.Item a = reais.get(i);
            PedidoItem c = congelados.get(i);
            diverge = !a.productCode.equals(c.productCode()) || a.quantity.compareTo(c.quantity()) != 0
                    || a.getUnitPrice().compareTo(c.unitPrice()) != 0 || a.desconto.signum() != 0;
        }
        if (diverge) {
            throw new IllegalArgumentException("Itens divergem dos preços congelados no pedido.");
        }
        return pedido;
    }

    private void conferirItens(VendaRequests.Venda r, BigDecimal totalAmount) {
        if (r.items.isEmpty()) throw new IllegalArgumentException("Venda sem itens.");
        if (r.items.stream().anyMatch(i -> !i.isUnitPriceSpecified())) {
            throw new IllegalArgumentException(
                    "Preço do item ausente. Atualize o cliente para enviar o preço de fechamento.");
        }
        BigDecimal netTotal = BigDecimal.ZERO;
        for (VendaRequests.Item i : r.items) {
            netTotal = netTotal.add(SaleMoney.itemNet(i.quantity, i.getUnitPrice(), i.desconto));
        }
        for (VendaRequests.Item i : r.items) {
            BigDecimal net = SaleMoney.itemNet(i.quantity, i.getUnitPrice(), i.desconto);
            if (i.itemTotal != null && SaleMoney.money(i.itemTotal).compareTo(net) != 0) {
                throw new IllegalArgumentException("Líquido informado difere do cálculo do item.");
            }
        }
        if (netTotal.compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException("Soma dos itens líquidos difere do total da venda.");
        }
    }

    private static List<SaleMoney.Entrada> entradas(VendaRequests.Venda r) {
        return r.payments.stream().map(p -> new SaleMoney.Entrada(p.paymentType, p.amount, p.cashGiven,
                p.changeAmount)).toList();
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(clock).atZoneSameInstant(BRASILIA).toOffsetDateTime();
    }

    private static String somenteDigitos(String s) {
        return s == null ? "" : s.chars().filter(Character::isDigit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    // ---------------------------------------------------------------- insercao (InserirVendaAsync)

    private VendaRegistroResultado inserirVenda(String companyId, String customerName, String customerCpf,
                                                String paymentType, BigDecimal totalAmount, String operatorName,
                                                List<ItemRegistrado> itens, List<PagamentoNormalizado> pagamentos,
                                                String clientSaleId, String offlineReference,
                                                OffsetDateTime occurredAt, String caixaSessaoId, String origem) {
        String saleNumber = numerador.proximoNumeroVenda(companyId);
        registrador.vincularOrigem(companyId, origem, saleNumber, "Venda #" + saleNumber);
        OffsetDateTime now = agora();
        OffsetDateTime saleDate = occurredAt != null ? occurredAt : now;
        String saleId = "sale-" + companyId + "-" + saleNumber;

        repo.inserirVenda(saleId, companyId, saleNumber, customerName, customerCpf, paymentType, totalAmount,
                operatorName, saleDate, clientSaleId, offlineReference, now, caixaSessaoId);

        List<VendaPagamentoDto> pagamentosDto = new ArrayList<>();
        for (int i = 0; i < pagamentos.size(); i++) {
            PagamentoNormalizado p = pagamentos.get(i);
            String pagId = saleId + "-pag-" + String.format("%03d", i + 1);
            repo.inserirPagamento(pagId, companyId, saleId, p.paymentType(), p.amount(), p.cashGiven(),
                    p.changeAmount(), now);
            pagamentosDto.add(new VendaPagamentoDto(pagId, companyId, saleId, p.paymentType(), p.amount(),
                    p.cashGiven(), p.changeAmount(), now));
        }

        List<VendaHistoricoDto> rows = new ArrayList<>();
        for (int i = 0; i < itens.size(); i++) {
            ItemRegistrado item = itens.get(i);
            String itemId = saleId + "-item-" + String.format("%03d", i + 1);
            BigDecimal itemTotal = SaleMoney.itemNet(item.quantity(), item.unitPrice(), item.desconto());
            repo.inserirItem(itemId, saleId, item.productCode(), item.productName(), item.quantity(),
                    item.unitPrice(), item.costPrice(), item.desconto(), itemTotal, item.promocaoId());
            rows.add(new VendaHistoricoDto(saleNumber, "finalizada", customerName, customerCpf, paymentType,
                    Dinheiro.formatarSemSimbolo(totalAmount), operatorName, item.productCode(), item.productName(),
                    item.quantity(), Dinheiro.formatarPrecoUnitario(item.unitPrice()), item.desconto(),
                    item.promocaoId(), Dinheiro.formatarSemSimbolo(itemTotal), VendaRepository.formatarData(saleDate),
                    clientSaleId, offlineReference, null, null, null, null, null, null, null, null, null, null,
                    null));
        }
        return new VendaRegistroResultado(saleNumber, saleId, clientSaleId, false, rows, pagamentosDto, List.of(),
                null, null);
    }
}
