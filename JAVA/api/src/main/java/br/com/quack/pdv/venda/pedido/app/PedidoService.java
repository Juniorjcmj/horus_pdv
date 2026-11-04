package br.com.quack.pdv.venda.pedido.app;

import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.pedido.domain.Pedido;
import br.com.quack.pdv.venda.pedido.domain.PedidoModel;
import br.com.quack.pdv.venda.pedido.repo.PedidoRepository;
import br.com.quack.pdv.venda.pedido.repo.PedidoRepository.ProdutoPreco;
import br.com.quack.pdv.venda.registro.HashPayload;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoItem;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pedidos (PedidoAB): o vendedor monta o pedido com preco congelado; nao reserva estoque. A baixa acontece so na
 * finalizacao (VendaRegistroService.registrarComPrecosFixos).
 */
@Service
public class PedidoService {

    private final PedidoRepository repo;
    private final Clock clock;

    public PedidoService(PedidoRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional
    public Pedido criar(String companyId, String vendedorId, String vendedorNome, String cliente, String cpf,
                        List<VendaRequests.Item> items) {
        if (items.isEmpty()) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Pedido sem itens.");
        }
        try {
            return gravar(companyId, vendedorId, vendedorNome, cliente, cpf, items);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private Pedido gravar(String companyId, String vendedorId, String vendedorNome, String cliente, String cpf,
                          List<VendaRequests.Item> items) {
        Map<String, BigDecimal> grupos = new LinkedHashMap<>();
        Map<String, String> codigos = new LinkedHashMap<>();
        for (VendaRequests.Item i : items) {
            String codigo = HashPayload.trim(i.productCode);
            String chave = codigo.toUpperCase(Locale.ROOT);
            codigos.putIfAbsent(chave, codigo);
            grupos.merge(chave, i.quantity, BigDecimal::add);
        }
        List<PedidoItem> pedidoItens = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> g : grupos.entrySet()) {
            String codigo = codigos.get(g.getKey());
            BigDecimal quantidade = g.getValue();
            if (quantidade.signum() <= 0) {
                throw new IllegalArgumentException("Quantidade do item deve ser maior que zero.");
            }
            ProdutoPreco p = repo.produto(companyId, codigo)
                    .orElseThrow(() -> new IllegalArgumentException("Produto " + codigo + " não encontrado."));
            BigDecimal preco = p.venda().signum() > 0 ? p.venda() : p.custo();
            BigDecimal total = preco.multiply(quantidade).setScale(2, RoundingMode.HALF_UP);
            pedidoItens.add(new PedidoItem(codigo, p.nome(), quantidade, preco, total));
        }

        String numero = repo.proximoNumero(companyId);
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String id = "ped-" + agora.toInstant().toEpochMilli();
        String nome = HashPayload.emBranco(cliente) ? "Consumidor" : HashPayload.trim(cliente);
        String documento = HashPayload.emBranco(cpf) ? "-" : HashPayload.trim(cpf);
        repo.inserir(id, companyId, numero, nome, documento, vendedorId, vendedorNome, agora);
        for (int i = 0; i < pedidoItens.size(); i++) {
            PedidoItem it = pedidoItens.get(i);
            repo.inserirItem(id + "-item-" + String.format("%03d", i + 1), id, it.productCode(), it.productName(),
                    it.quantity(), it.unitPrice(), it.itemTotal());
        }
        return new Pedido(id, numero, nome, documento, vendedorNome, Pedido.ABERTO, agora, pedidoItens);
    }

    @Transactional(readOnly = true)
    public Optional<Pedido> obter(String companyId, String numero) {
        return repo.porNumero(companyId, numero).map(p -> p.comItens(repo.itens(p.id())));
    }

    /** ListarAbertosAsync devolve so os cabecalhos: itens vazios e total 0,00 (esquisitice da .NET, D12). */
    @Transactional(readOnly = true)
    public List<Pedido> listarAbertos(String companyId) {
        return repo.abertos(companyId);
    }

    @Transactional
    public boolean cancelar(String companyId, String numero) {
        return repo.cancelar(companyId, numero, OffsetDateTime.now(clock)) > 0;
    }

    public static PedidoModel paraModelo(Pedido p) {
        String status = switch (p.status()) {
            case Pedido.FINALIZADO -> "finalizado";
            case Pedido.CANCELADO -> "cancelado";
            default -> "aberto";
        };
        BigDecimal soma = p.itens().stream().map(PedidoItem::itemTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PedidoModel(p.orderNumber(), p.customerName(), p.customerCpf(), p.sellerName(), status,
                VendaRepository.formatarData(p.createdAt()), Dinheiro.formatarSemSimbolo(soma),
                p.itens().stream().map(i -> new PedidoModel.Item(i.productCode(), i.productName(), i.quantity(),
                        Dinheiro.formatarSemSimbolo(i.unitPrice()), Dinheiro.formatarSemSimbolo(i.itemTotal())))
                        .toList());
    }
}
