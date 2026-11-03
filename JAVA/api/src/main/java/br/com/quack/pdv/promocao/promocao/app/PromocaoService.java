package br.com.quack.pdv.promocao.promocao.app;

import br.com.quack.pdv.cadastro.categoria.repo.CategoriaRepository;
import br.com.quack.pdv.promocao.promocao.web.PromocaoModels.Promocao;
import br.com.quack.pdv.promocao.promocao.web.PromocaoModels.Resultado;
import br.com.quack.pdv.promocao.promocao.web.PromocaoRequest;
import br.com.quack.pdv.shared.NegocioException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PromocaoService {
    private static final List<String> TIPOS =
            List.of(
                    "desconto_percentual",
                    "desconto_valor",
                    "preco_fixo",
                    "leve_x_pague_y",
                    "combo_quantidade",
                    "preco_atacado");
    private static final String CONSULTA =
            """
            SELECT p.*, c.Nome AS CategoriaNome FROM Promocoes p
            LEFT JOIN Categorias c ON c.Id=p.CategoriaId WHERE p.CompanyId=?
            """;
    private final JdbcTemplate jdbc;
    private final CategoriaRepository categorias;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PromocaoService(
            JdbcTemplate jdbc,
            CategoriaRepository categorias,
            Clock clock,
            PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.categorias = categorias;
        this.clock = clock.withZone(ZoneId.of("America/Sao_Paulo"));
        this.tx = new TransactionTemplate(transacoes);
    }

    public List<Promocao> listar(String empresa) {
        return jdbc.query(CONSULTA + " ORDER BY p.CriadoEm DESC", this::mapear, empresa).stream()
                .map(this::comProdutos)
                .toList();
    }

    public List<Promocao> ativas(String empresa) {
        var agora = OffsetDateTime.now(clock);
        return listar(empresa).stream().filter(p -> vigente(p, agora)).toList();
    }

    public static boolean vigente(Promocao p, OffsetDateTime agora) {
        return p.ativa() && !agora.isBefore(p.inicioVigencia()) && !agora.isAfter(p.fimVigencia());
    }

    public Optional<Promocao> obter(String empresa, String id) {
        return jdbc.query(CONSULTA + " AND p.Id=?", this::mapear, empresa, id).stream()
                .findFirst()
                .map(this::comProdutos);
    }

    public Promocao criar(String empresa, String operador, PromocaoRequest r) {
        validar(empresa, r);
        var p =
                modelo(
                        UUID.randomUUID().toString(),
                        empresa,
                        r,
                        null,
                        vazio(operador) ? "Operador" : operador.trim(),
                        OffsetDateTime.now(clock));
        salvar(p, true);
        return p;
    }

    public Promocao atualizar(String empresa, String id, PromocaoRequest r) {
        var anterior = exigir(empresa, id);
        validar(empresa, r);
        var p =
                modelo(
                        id,
                        empresa,
                        r,
                        anterior.categoriaNome(),
                        anterior.criadoPor(),
                        anterior.criadoEm());
        salvar(p, false);
        return p;
    }

    public void status(String empresa, String id, boolean ativa) {
        exigir(empresa, id);
        jdbc.update("UPDATE Promocoes SET Ativa=? WHERE Id=? AND CompanyId=?", ativa, id, empresa);
    }

    public void excluir(String empresa, String id) {
        exigir(empresa, id);
        jdbc.update("DELETE FROM Promocoes WHERE Id=? AND CompanyId=?", id, empresa);
    }

    public Resultado resultado(String empresa, String id) {
        // O controller .NET não captura KeyNotFound nesta rota (os CRUDs capturam).
        var p =
                obter(empresa, id)
                        .orElseThrow(
                                () ->
                                        new java.util.NoSuchElementException(
                                                "Promoção " + id + " não encontrada."));
        return jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT v.Id) AS QuantidadeVendas,
                    ISNULL(SUM(i.Quantity*i.UnitPrice),0) AS ReceitaBruta,
                    ISNULL(SUM(i.ItemTotal),0) AS ReceitaLiquida,
                    ISNULL(SUM(i.Desconto),0) AS DescontoTotal,
                    ISNULL(SUM(i.ItemTotal-(i.Quantity*p.ProductUnitPrice)),0) AS MargemLiquida
                FROM VendaItens i
                INNER JOIN Vendas v ON v.Id=i.VendaId AND v.CompanyId=?
                INNER JOIN Produtos p ON p.ProductCode=i.ProductCode AND p.CompanyId=?
                WHERE i.PromocaoId=?
                """,
                (rs, n) ->
                        new Resultado(
                                id,
                                p.nome(),
                                rs.getInt("QuantidadeVendas"),
                                rs.getBigDecimal("ReceitaBruta"),
                                rs.getBigDecimal("ReceitaLiquida"),
                                rs.getBigDecimal("DescontoTotal"),
                                rs.getBigDecimal("MargemLiquida")),
                empresa,
                empresa,
                id);
    }

    private Promocao exigir(String empresa, String id) {
        return obter(empresa, id)
                .orElseThrow(
                        () ->
                                new NegocioException(
                                        HttpStatus.NOT_FOUND, "Promoção não encontrada."));
    }

    public void validar(String empresa, PromocaoRequest r) {
        if (vazio(r.nome)) falhar("O nome da promoção é obrigatório.");
        String tipo = r.tipo == null ? "" : r.tipo.trim().toLowerCase(Locale.ROOT);
        if (!TIPOS.contains(tipo))
            falhar(
                    "Tipo de promoção '"
                            + (r.tipo == null ? "" : r.tipo)
                            + "' é inválido. Tipos suportados: "
                            + String.join(", ", TIPOS)
                            + ".");
        if (!r.fimVigencia.isAfter(r.inicioVigencia))
            falhar("A data final de vigência deve ser posterior à data inicial.");
        switch (tipo) {
            case "desconto_percentual" -> {
                if (!positivo(r.valorDesconto)
                        || r.valorDesconto.compareTo(BigDecimal.valueOf(100)) > 0)
                    falhar("Desconto percentual deve ser maior que 0 e no máximo 100%.");
            }
            case "desconto_valor" -> {
                if (!positivo(r.valorDesconto))
                    falhar("Valor de desconto deve ser maior que zero.");
            }
            case "preco_fixo" -> {
                if (!positivo(r.precoFixo)) falhar("Preço fixo deve ser maior que zero.");
            }
            case "leve_x_pague_y" -> {
                if (r.quantidadeLeva == null
                        || r.quantidadeLeva <= 0
                        || r.quantidadePaga == null
                        || r.quantidadePaga <= 0)
                    falhar("Quantidade 'leva' e 'paga' devem ser maiores que zero.");
                if (r.quantidadeLeva <= r.quantidadePaga)
                    falhar("A quantidade 'leva' deve ser maior que a quantidade 'paga'.");
            }
            case "combo_quantidade", "preco_atacado" -> {
                if (r.quantidadeMinima == null || r.quantidadeMinima <= 1)
                    falhar("Quantidade mínima deve ser maior que 1.");
                if (!positivo(r.precoFixo) && !positivo(r.valorDesconto))
                    falhar(
                            "Informe um preço unitário promocional ou valor de desconto para o"
                                    + " combo/atacado.");
            }
            default -> throw new IllegalStateException(tipo);
        }
        if (r.produtoIds.isEmpty() && vazio(r.categoriaId))
            falhar("A promoção deve estar vinculada a pelo menos um produto ou a uma categoria.");
        if (!vazio(r.categoriaId) && categorias.obter(empresa, r.categoriaId.trim()).isEmpty())
            falhar("Categoria informada não foi encontrada.");
    }

    private static boolean positivo(BigDecimal n) {
        return n != null && n.signum() > 0;
    }

    private static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    private static void falhar(String mensagem) {
        throw new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }

    private Promocao modelo(
            String id,
            String empresa,
            PromocaoRequest r,
            String categoriaNome,
            String operador,
            OffsetDateTime criado) {
        return new Promocao(
                id,
                empresa,
                r.nome.trim(),
                r.tipo.trim().toLowerCase(Locale.ROOT),
                r.valorDesconto,
                r.precoFixo,
                r.quantidadeLeva,
                r.quantidadePaga,
                r.quantidadeMinima,
                r.inicioVigencia,
                r.fimVigencia,
                r.ativa,
                vazio(r.categoriaId) ? null : r.categoriaId.trim(),
                categoriaNome,
                operador,
                criado,
                r.produtoIds.stream().filter(s -> !vazio(s)).map(String::trim).distinct().toList());
    }

    private void salvar(Promocao p, boolean novo) {
        tx.executeWithoutResult(
                status -> {
                    var parametros =
                            new MapSqlParameterSource()
                                    .addValue("id", p.id())
                                    .addValue("empresa", p.companyId())
                                    .addValue("nome", p.nome())
                                    .addValue("tipo", p.tipo())
                                    .addValue("desconto", p.valorDesconto())
                                    .addValue("preco", p.precoFixo())
                                    .addValue("leva", p.quantidadeLeva())
                                    .addValue("paga", p.quantidadePaga())
                                    .addValue("minima", p.quantidadeMinima())
                                    .addValue("inicio", p.inicioVigencia())
                                    .addValue("fim", p.fimVigencia())
                                    .addValue("ativa", p.ativa())
                                    .addValue("categoria", p.categoriaId())
                                    .addValue("operador", p.criadoPor())
                                    .addValue("criado", p.criadoEm());
                    var named = new NamedParameterJdbcTemplate(jdbc);
                    if (novo)
                        named.update(
                                """
INSERT INTO Promocoes(Id,CompanyId,Nome,Tipo,ValorDesconto,PrecoFixo,QuantidadeLeva,QuantidadePaga,QuantidadeMinima,InicioVigencia,FimVigencia,Ativa,CategoriaId,CriadoPor,CriadoEm)
VALUES(:id,:empresa,:nome,:tipo,:desconto,:preco,:leva,:paga,:minima,:inicio,:fim,:ativa,:categoria,:operador,:criado)
""",
                                parametros);
                    else
                        named.update(
                                """
UPDATE Promocoes SET Nome=:nome,Tipo=:tipo,ValorDesconto=:desconto,PrecoFixo=:preco,QuantidadeLeva=:leva,QuantidadePaga=:paga,QuantidadeMinima=:minima,InicioVigencia=:inicio,FimVigencia=:fim,Ativa=:ativa,CategoriaId=:categoria WHERE Id=:id AND CompanyId=:empresa
""",
                                parametros);
                    jdbc.update("DELETE FROM PromocaoProdutos WHERE PromocaoId=?", p.id());
                    for (String produto : p.produtoIds())
                        jdbc.update(
                                "INSERT INTO PromocaoProdutos(Id,PromocaoId,ProdutoId)"
                                        + " VALUES(?,?,?)",
                                UUID.randomUUID().toString().replace("-", ""),
                                p.id(),
                                produto);
                });
    }

    private Promocao comProdutos(Promocao p) {
        var ids =
                jdbc
                        .queryForList(
                                "SELECT ProdutoId FROM PromocaoProdutos WHERE PromocaoId=?",
                                String.class,
                                p.id())
                        .stream()
                        .map(String::trim)
                        .toList();
        return new Promocao(
                p.id(),
                p.companyId(),
                p.nome(),
                p.tipo(),
                p.valorDesconto(),
                p.precoFixo(),
                p.quantidadeLeva(),
                p.quantidadePaga(),
                p.quantidadeMinima(),
                p.inicioVigencia(),
                p.fimVigencia(),
                p.ativa(),
                p.categoriaId(),
                p.categoriaNome(),
                p.criadoPor(),
                p.criadoEm(),
                ids);
    }

    private Promocao mapear(ResultSet r, int indice) throws SQLException {
        return new Promocao(
                texto(r, "Id"),
                texto(r, "CompanyId"),
                texto(r, "Nome"),
                texto(r, "Tipo"),
                r.getBigDecimal("ValorDesconto"),
                r.getBigDecimal("PrecoFixo"),
                r.getObject("QuantidadeLeva", Integer.class),
                r.getObject("QuantidadePaga", Integer.class),
                r.getObject("QuantidadeMinima", Integer.class),
                r.getObject("InicioVigencia", OffsetDateTime.class),
                r.getObject("FimVigencia", OffsetDateTime.class),
                r.getBoolean("Ativa"),
                texto(r, "CategoriaId"),
                texto(r, "CategoriaNome"),
                texto(r, "CriadoPor"),
                r.getObject("CriadoEm", OffsetDateTime.class),
                List.of());
    }

    private static String texto(ResultSet r, String coluna) throws SQLException {
        String s = r.getString(coluna);
        return s == null ? null : s.trim();
    }
}
