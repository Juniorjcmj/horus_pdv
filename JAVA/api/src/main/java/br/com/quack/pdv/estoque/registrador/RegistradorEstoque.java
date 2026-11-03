package br.com.quack.pdv.estoque.registrador;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registrador unico de saldo (D2): soma delta ao saldo do produto e grava a movimentacao na MESMA transacao.
 * E o unico caminho autorizado a alterar Produtos.ProductQnt. Anterior/novo vem do OUTPUT do UPDATE (exatos sob
 * concorrencia). Delta zero devolve os saldos sem gravar movimentacao. Retorna vazio se o produto nao existe.
 */
@Component
public class RegistradorEstoque {

    public record Saldos(BigDecimal anterior, BigDecimal novo) {}

    private record ResultadoUpdate(BigDecimal anterior, BigDecimal novo, BigDecimal custoProduto) {}

    private static final String SQL_ATUALIZAR_SALDO = """
            SET NOCOUNT ON;
            DECLARE @r TABLE (Anterior DECIMAL(18,3), Novo DECIMAL(18,3), Custo DECIMAL(18,4));
            UPDATE Produtos
               SET ProductQnt = ProductQnt + ?,
                   TotalPriceOnProduct = ProductUnitPrice * (ProductQnt + ?)
            OUTPUT deleted.ProductQnt, inserted.ProductQnt, inserted.ProductUnitPrice INTO @r
             WHERE Id = ? AND CompanyId = ?;
            SELECT Anterior, Novo, Custo FROM @r;
            """;

    private static final String SQL_INSERIR_MOVIMENTO = """
            INSERT INTO EstoqueMovimentacoes
                (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome,
                 CustoUnitario, OrigemTipo, OrigemId, DepositoId)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String SQL_BUSCAR_POR_CODIGO =
            "SELECT Id FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE CompanyId = ? AND ProductCode = ?;";

    private final JdbcTemplate jdbc;

    public RegistradorEstoque(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Saldos> aplicar(
            String companyId,
            String produtoId,
            BigDecimal delta,
            String tipo,
            BigDecimal custoUnitario,
            MovimentoOrigem origem) {
        return aplicar(companyId, produtoId, delta, tipo, custoUnitario, origem, "loja");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Saldos> aplicar(
            String companyId,
            String produtoId,
            BigDecimal delta,
            String tipo,
            BigDecimal custoUnitario,
            MovimentoOrigem origem,
            String depositoId) {
        List<ResultadoUpdate> lidos = jdbc.query(
                SQL_ATUALIZAR_SALDO,
                (rs, rowNum) -> new ResultadoUpdate(
                        rs.getBigDecimal(1),
                        rs.getBigDecimal(2),
                        rs.getBigDecimal(3)),
                delta, delta, produtoId, companyId);

        if (lidos.isEmpty()) {
            return Optional.empty();
        }

        ResultadoUpdate r = lidos.getFirst();
        Saldos saldos = new Saldos(r.anterior(), r.novo());
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
            return Optional.of(saldos);
        }

        String id = "mov-" + UUID.randomUUID().toString().replace("-", "");
        String motivo = truncar(origem.motivo() == null ? "" : origem.motivo(), 500);
        String usuarioId = truncar(
                origem.usuarioId() == null || origem.usuarioId().isBlank() ? "sistema" : origem.usuarioId(), 36);
        String usuarioNome = truncar(
                origem.usuarioNome() == null || origem.usuarioNome().isBlank() ? "Sistema" : origem.usuarioNome(), 200);
        BigDecimal custo = custoUnitario != null ? custoUnitario : r.custoProduto();
        String origemTipo = truncar(origem.origemTipo(), 20);
        String origemId = truncar(origem.origemId(), 80);
        String deposito = depositoId == null || depositoId.isBlank() ? "loja" : depositoId;

        jdbc.update(
                SQL_INSERIR_MOVIMENTO,
                id,
                companyId,
                produtoId,
                tipo,
                delta.abs(),
                r.anterior(),
                r.novo(),
                motivo,
                usuarioId,
                usuarioNome,
                custo,
                origemTipo,
                origemId,
                deposito);

        return Optional.of(saldos);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Saldos> aplicarPorCodigo(
            String companyId,
            String productCode,
            BigDecimal delta,
            String tipo,
            BigDecimal custoUnitario,
            MovimentoOrigem origem) {
        List<String> ids = jdbc.query(
                SQL_BUSCAR_POR_CODIGO,
                (rs, rowNum) -> rs.getString(1),
                companyId, productCode);
        if (ids.isEmpty() || ids.getFirst() == null) {
            return Optional.empty();
        }
        return aplicar(companyId, ids.getFirst(), delta, tipo, custoUnitario, origem, "loja");
    }

    /** Identificador provisorio das movimentacoes de uma venda ainda sem numero: {@code pend-<uuid sem hifens>}. */
    public static String novaOrigemProvisoria() {
        return "pend-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Troca o identificador provisorio das movimentacoes de uma venda pelo numero definitivo, na mesma transacao
     * (porta de {@code VincularOrigemAsync}).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int vincularOrigem(String companyId, String origemProvisoria, String origemDefinitiva, String motivo) {
        return jdbc.update(
                "UPDATE EstoqueMovimentacoes SET OrigemId = ?, Motivo = ? WHERE CompanyId = ? AND OrigemId = ?;",
                origemDefinitiva, motivo, companyId, origemProvisoria);
    }

    static String truncar(String valor, int max) {
        if (valor == null) return null;
        return valor.length() <= max ? valor : valor.substring(0, max);
    }
}
