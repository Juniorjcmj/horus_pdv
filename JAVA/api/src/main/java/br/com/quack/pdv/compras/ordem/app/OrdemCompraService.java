package br.com.quack.pdv.compras.ordem.app;

import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraModels.Item;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraModels.Ordem;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraModels.Sugestao;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraRequests.Pedido;
import br.com.quack.pdv.compras.ordem.web.OrdemCompraRequests.Receber;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.NegocioException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class OrdemCompraService {
    private static final String COLUNAS =
            "Id,CompanyId,OrderNumber,SupplierId,SupplierName,SupplierCnpj,Status,CreatedBy,CreatedByName,CreatedAt,ReceivedAt,CanceledAt,PrevisaoEntrega,CondicaoPagamento,FormaPagamento,ValorFrete,ValorDesconto,MotivoCancelamento,ReceivedBy,ReceivedByName,Note,TotalEstimado";
    private final JdbcTemplate jdbc;
    private final ProdutoService produtos;
    private final FornecedorRepository fornecedores;
    private final LoteOperacoes lotes;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrdemCompraService(
            JdbcTemplate jdbc,
            ProdutoService produtos,
            FornecedorRepository fornecedores,
            LoteOperacoes lotes,
            Clock clock,
            PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.produtos = produtos;
        this.fornecedores = fornecedores;
        this.lotes = lotes;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    private static NegocioException erro(String mensagem) {
        return new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }

    private static String nuloSeVazio(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String texto(ResultSet rs, String coluna) throws SQLException {
        String s = rs.getString(coluna);
        return s == null ? "" : s;
    }

    private static BigDecimal decimal(ResultSet rs, String coluna) throws SQLException {
        BigDecimal n = rs.getBigDecimal(coluna);
        return n == null ? BigDecimal.ZERO : n;
    }

    private static OffsetDateTime data(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, OffsetDateTime.class);
    }

    public List<Ordem> listar(String empresa, Integer status) {
        return status == null
                ? jdbc.query(
                        "SELECT "
                                + COLUNAS
                                + " FROM OrdemCompra WHERE CompanyId=? ORDER BY CreatedAt DESC",
                        (rs, n) -> cabecalho(rs, List.of()),
                        empresa)
                : jdbc.query(
                        "SELECT "
                                + COLUNAS
                                + " FROM OrdemCompra WHERE CompanyId=? AND Status=? ORDER BY"
                                + " CreatedAt DESC",
                        (rs, n) -> cabecalho(rs, List.of()),
                        empresa,
                        status);
    }

    public Optional<Ordem> obter(String empresa, String numero) {
        var linhas =
                jdbc.query(
                        "SELECT "
                                + COLUNAS
                                + " FROM OrdemCompra WHERE CompanyId=? AND OrderNumber=?",
                        (rs, n) -> cabecalho(rs, List.of()),
                        empresa,
                        numero);
        if (linhas.isEmpty()) return Optional.empty();
        var h = linhas.getFirst();
        return Optional.of(comItens(h, itens(h.id())));
    }

    private static Ordem comItens(Ordem h, List<Item> itens) {
        return new Ordem(
                h.id(),
                h.companyId(),
                h.orderNumber(),
                h.supplierId(),
                h.supplierName(),
                h.supplierCnpj(),
                h.status(),
                h.createdBy(),
                h.createdByName(),
                h.createdAt(),
                h.receivedAt(),
                h.canceledAt(),
                h.previsaoEntrega(),
                h.condicaoPagamento(),
                h.formaPagamento(),
                h.valorFrete(),
                h.valorDesconto(),
                h.motivoCancelamento(),
                h.receivedBy(),
                h.receivedByName(),
                h.note(),
                h.totalEstimado(),
                itens);
    }

    private static Ordem cabecalho(ResultSet r, List<Item> itens) throws SQLException {
        return new Ordem(
                texto(r, "Id"),
                texto(r, "CompanyId"),
                texto(r, "OrderNumber"),
                r.getString("SupplierId"),
                texto(r, "SupplierName"),
                texto(r, "SupplierCnpj"),
                r.getInt("Status"),
                r.getString("CreatedBy"),
                texto(r, "CreatedByName"),
                data(r, "CreatedAt"),
                data(r, "ReceivedAt"),
                data(r, "CanceledAt"),
                data(r, "PrevisaoEntrega"),
                r.getString("CondicaoPagamento"),
                r.getString("FormaPagamento"),
                decimal(r, "ValorFrete"),
                decimal(r, "ValorDesconto"),
                r.getString("MotivoCancelamento"),
                r.getString("ReceivedBy"),
                r.getString("ReceivedByName"),
                texto(r, "Note"),
                decimal(r, "TotalEstimado"),
                itens);
    }

    private List<Item> itens(String id) {
        return jdbc.query(
                "SELECT"
                    + " ProductCode,ProductName,Quantity,UnitCost,ItemTotal,QuantityReceived,DataValidade"
                    + " FROM OrdemCompraItens WHERE OrdemCompraId=? ORDER BY Id",
                (r, n) ->
                        new Item(
                                texto(r, "ProductCode"),
                                texto(r, "ProductName"),
                                r.getBigDecimal("Quantity"),
                                r.getBigDecimal("UnitCost"),
                                r.getBigDecimal("ItemTotal"),
                                r.getBigDecimal("QuantityReceived"),
                                data(r, "DataValidade")),
                id);
    }

    private record Preparado(
            String fornecedorNome, String fornecedorCnpj, List<Item> itens, BigDecimal total) {}

    private Preparado preparar(String empresa, Pedido r) {
        if (r.items.isEmpty()) throw erro("Ordem de compra sem itens.");
        var f =
                nuloSeVazio(r.supplierId) == null
                        ? Optional.<br.com.quack.pdv.cadastro.fornecedor.domain.Fornecedor>empty()
                        : fornecedores.findByCompanyIdAndId(empresa, r.supplierId);
        String nome =
                f.map(x -> x.fantasyName == null ? x.companyName : x.fantasyName)
                        .orElse("Fornecedor não informado");
        String cnpj = f.map(x -> x.cnpj == null ? "" : x.cnpj).orElse("");
        List<Item> itens = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var i : r.items) {
            if (i.quantity.signum() <= 0)
                throw erro("Quantidade do item " + i.productCode + " deve ser maior que zero.");
            var p = produtos.obterPorCodigo(empresa, i.productCode);
            BigDecimal total = i.unitCost.multiply(i.quantity).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(total);
            itens.add(
                    new Item(
                            i.productCode,
                            p.map(x -> x.productName()).orElse(i.productCode),
                            i.quantity,
                            i.unitCost,
                            total,
                            BigDecimal.ZERO,
                            null));
        }
        return new Preparado(
                nome,
                cnpj,
                itens,
                subtotal.add(r.valorFrete).subtract(r.valorDesconto).max(BigDecimal.ZERO));
    }

    public String criar(String empresa, Pedido r, UsuarioAutenticado u) {
        var p = preparar(empresa, r);
        return tx.execute(
                s -> {
                    String numero =
                            jdbc.queryForObject(
                                    "SELECT"
                                        + " CONVERT(NVARCHAR(20),ISNULL(MAX(TRY_CONVERT(INT,OrderNumber)),0)+1)"
                                        + " FROM OrdemCompra WITH (UPDLOCK,HOLDLOCK) WHERE"
                                        + " CompanyId=?",
                                    String.class,
                                    empresa);
                    String id = "oc-" + clock.millis();
                    var agora = OffsetDateTime.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
                    jdbc.update(
                            """
INSERT INTO OrdemCompra (Id,CompanyId,OrderNumber,SupplierId,SupplierName,SupplierCnpj,Status,CreatedBy,CreatedByName,CreatedAt,PrevisaoEntrega,CondicaoPagamento,FormaPagamento,ValorFrete,ValorDesconto,Note,TotalEstimado)
VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?,?)
""",
                            id,
                            empresa,
                            numero,
                            nuloSeVazio(r.supplierId),
                            p.fornecedorNome,
                            nuloSeVazio(p.fornecedorCnpj),
                            nuloSeVazio(u.id()),
                            u.name(),
                            agora,
                            r.previsaoEntrega,
                            nuloSeVazio(r.condicaoPagamento),
                            nuloSeVazio(r.formaPagamento),
                            r.valorFrete,
                            r.valorDesconto,
                            nuloSeVazio(r.note),
                            p.total);
                    inserirItens(id, p.itens);
                    return numero;
                });
    }

    public Ordem atualizar(String empresa, String numero, Pedido r) {
        var p = preparar(empresa, r);
        tx.executeWithoutResult(
                s -> {
                    var atual =
                            obter(empresa, numero)
                                    .orElseThrow(() -> erro("Ordem de compra não encontrada."));
                    if (atual.status() != 0)
                        throw erro("Apenas ordens com status Pendente podem ser editadas.");
                    jdbc.update(
                            "UPDATE OrdemCompra SET"
                                + " SupplierId=?,SupplierName=?,SupplierCnpj=?,PrevisaoEntrega=?,CondicaoPagamento=?,FormaPagamento=?,ValorFrete=?,ValorDesconto=?,Note=?,TotalEstimado=?"
                                + " WHERE Id=?",
                            nuloSeVazio(r.supplierId),
                            p.fornecedorNome,
                            nuloSeVazio(p.fornecedorCnpj),
                            r.previsaoEntrega,
                            nuloSeVazio(r.condicaoPagamento),
                            nuloSeVazio(r.formaPagamento),
                            r.valorFrete,
                            r.valorDesconto,
                            nuloSeVazio(r.note),
                            p.total,
                            atual.id());
                    jdbc.update("DELETE FROM OrdemCompraItens WHERE OrdemCompraId=?", atual.id());
                    inserirItens(atual.id(), p.itens);
                });
        return obter(empresa, numero)
                .orElseThrow(() -> erro("Erro ao recuperar ordem atualizada."));
    }

    private void inserirItens(String id, List<Item> itens) {
        int indice = 0;
        for (var i : itens)
            jdbc.update(
                    "INSERT INTO OrdemCompraItens"
                        + " (Id,OrdemCompraId,ProductCode,ProductName,Quantity,UnitCost,ItemTotal,QuantityReceived)"
                        + " VALUES (?,?,?,?,?,?,?,0)",
                    id + String.format("-item-%03d", ++indice),
                    id,
                    i.productCode(),
                    i.productName(),
                    i.quantity(),
                    i.unitCost(),
                    i.itemTotal());
    }

    public int receber(String empresa, String numero, Receber r, UsuarioAutenticado u) {
        if (r.itens.isEmpty()) throw erro("Nenhum item para receber.");
        var oc =
                obter(empresa, numero)
                        .filter(x -> x.status() == 0 || x.status() == 3)
                        .orElseThrow(
                                () ->
                                        erro(
                                                "Ordem de compra não encontrada ou já"
                                                    + " finalizada/cancelada."));
        Map<String, br.com.quack.pdv.compras.ordem.web.OrdemCompraRequests.ItemRecebido> recebidos =
                new HashMap<>();
        for (var i : r.itens)
            if (recebidos.putIfAbsent(i.productCode.toLowerCase(Locale.ROOT), i) != null)
                throw erro(
                        "An item with the same key has already been added. Key: " + i.productCode);
        // O .NET confirma cada entrada individual antes de atualizar a ordem; preserva o
        // recebimento parcial em falhas posteriores.
        for (var item : oc.itens()) {
            var recebido = recebidos.get(item.productCode().toLowerCase(Locale.ROOT));
            if (recebido == null || recebido.quantityReceived.signum() <= 0) continue;
            var produto = produtos.obterPorCodigo(empresa, item.productCode());
            if (produto.isEmpty()) continue;
            var p = produto.get();
            var origem =
                    new MovimentoOrigem(
                            "pedido_compra",
                            numero,
                            "Recebimento do pedido de compra " + numero,
                            nuloSeVazio(u.id()) == null ? "sistema" : u.id(),
                            nuloSeVazio(u.name()) == null ? "Sistema" : u.name());
            produtos.entradaEstoque(
                    empresa,
                    p.id(),
                    recebido.quantityReceived,
                    item.unitCost(),
                    false,
                    null,
                    origem);
            lotes.registrarEntradaSegura(
                    empresa,
                    p.id(),
                    recebido.dataValidade == null ? null : recebido.dataValidade.toLocalDate(),
                    recebido.quantityReceived,
                    recebido.numeroLote,
                    "compra",
                    u.name());
            jdbc.update(
                    "UPDATE OrdemCompraItens SET"
                        + " QuantityReceived=QuantityReceived+?,DataValidade=ISNULL(?,DataValidade)"
                        + " WHERE OrdemCompraId=? AND ProductCode=?",
                    recebido.quantityReceived,
                    recebido.dataValidade,
                    oc.id(),
                    item.productCode());
        }
        int status = calcularStatus(itens(oc.id()));
        jdbc.update(
                "UPDATE OrdemCompra SET Status=?,ReceivedAt=CASE WHEN ? IN (1,3) AND ReceivedAt IS"
                    + " NULL THEN SYSDATETIMEOFFSET() ELSE ReceivedAt"
                    + " END,ReceivedBy=ISNULL(?,ReceivedBy),ReceivedByName=ISNULL(?,ReceivedByName)"
                    + " WHERE Id=?",
                status,
                status,
                nuloSeVazio(u.id()),
                nuloSeVazio(u.name()),
                oc.id());
        return status;
    }

    public static int calcularStatus(List<Item> itens) {
        if (itens.stream().allMatch(i -> i.quantityReceived().compareTo(i.quantity()) >= 0))
            return 1;
        return itens.stream().anyMatch(i -> i.quantityReceived().signum() > 0) ? 3 : 0;
    }

    public boolean cancelar(String empresa, String numero, String motivo) {
        return jdbc.update(
                        "UPDATE OrdemCompra SET"
                            + " Status=2,CanceledAt=SYSDATETIMEOFFSET(),MotivoCancelamento=? WHERE"
                            + " CompanyId=? AND OrderNumber=? AND Status=0",
                        nuloSeVazio(motivo),
                        empresa,
                        numero)
                > 0;
    }

    public List<Sugestao> sugestoes(String empresa) {
        return jdbc.query(
                """
SELECT p.ProductCode,p.ProductName,
       CAST(REPLACE(REPLACE(p.ProductQnt,'.',''),',','.') AS DECIMAL(15,4)) AS CurrentStock,
       p.EstoqueMinimo AS MinStock,ISNULL(p.EstoqueMaximo,0) AS MaxStock,p.SupplierId,
       ISNULL(f.FantasyName,ISNULL(f.CompanyName,p.ProductSupplier)) AS SupplierName,
       ISNULL(CAST(REPLACE(REPLACE(p.ProductUnitPrice,'.',''),',','.') AS DECIMAL(15,4)),0) AS UnitCost
FROM Produtos p LEFT JOIN Fornecedores f ON f.Id=p.SupplierId AND f.CompanyId=p.CompanyId
WHERE p.CompanyId=? AND p.EstoqueMinimo>0
  AND CAST(REPLACE(REPLACE(p.ProductQnt,'.',''),',','.') AS DECIMAL(15,4)) < p.EstoqueMinimo
ORDER BY SupplierName,p.ProductName
""",
                (r, n) ->
                        new Sugestao(
                                texto(r, "ProductCode"),
                                texto(r, "ProductName"),
                                r.getBigDecimal("CurrentStock"),
                                r.getBigDecimal("MinStock"),
                                r.getBigDecimal("MaxStock"),
                                r.getString("SupplierId"),
                                texto(r, "SupplierName"),
                                r.getBigDecimal("UnitCost")),
                empresa);
    }
}
