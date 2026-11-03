package br.com.quack.pdv.produto.produto.app;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.web.ProdutoModel;
import br.com.quack.pdv.produto.produto.web.ProdutoPrecoRequest;
import br.com.quack.pdv.produto.produto.web.ProdutoRequest;
import br.com.quack.pdv.produto.produto.web.VencimentoResumoModel;
import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.shared.NegocioException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Regras de negocio e persistencia de produtos (porta de ProdutoService + ProdutoAB da API .NET).
 * Toda mudanca de ProductQnt passa exclusivamente pelo RegistradorEstoque (D2/D3).
 */
@Service
public class ProdutoService {

    private static final BigDecimal CEM = new BigDecimal("100");
    private static final BigDecimal UM_BILHAO = new BigDecimal("1000000000");
    private static final BigDecimal LIMITE_MARKUP = new BigDecimal("99999.9999");

    private static final Set<String> TIPOS_AJUSTE =
            Set.of("entrada", "saida", "uso_interno", "perda");

    private static final DateTimeFormatter DATA_ISO_ESTRITA =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final Pattern DECIMAL_PONTO_SEM_SINAL =
            Pattern.compile("^(?:\\d+\\.?\\d*|\\.\\d+)$");

    private static final Pattern LINHA_GO =
            Pattern.compile("^\\s*GO\\s*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final String COLUNAS = """
            p.Id, p.ProductImageUrl, p.ProductImageName, p.ProductName, p.ProductCode, p.ProductSupplier,
            p.ProductDescription, p.ProductQnt, p.EstoqueMinimo, p.ProductUnitPrice, p.ProductSalePrice, p.TotalPriceOnProduct,
            p.Lucro, p.MargemDesejadaPercentual, p.CategoriaId, c.Nome AS CategoriaNome,
            p.DataValidade, p.ControlaValidade, p.DiasAlertaValidade,
            p.UnidadeCompra, p.FatorConversao, p.QtdEmbalagem,
            p.Marca, p.Fabricante, p.ReferenciaFabricante,
            p.PesoLiquidoKg, p.PesoBrutoKg, p.LarguraCm, p.AlturaCm, p.ComprimentoCm,
            p.EstoqueMaximo, p.LocalizacaoEstoque,
            p.CustoMedio, p.CustoComImposto, p.CustoSemImposto,
            p.DescontoMaximoPercentual, p.ComissaoPercentual, p.MarkupCadastrado, p.MarkupPraticado,
            p.Ncm, p.Cest, p.Cfop, p.OrigemMercadoria, p.UnidadeComercial, p.UnidadeTributavel, p.Gtin,
            p.CsosnIcms, p.CstIcms, p.AliquotaIcms, p.CstPis, p.CstCofins, p.CstIbsCbs, p.CClassTrib
            """;

    private static final String SQL_LISTAR = """
            SELECT %s
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = ?
            ORDER BY p.ProductName;
            """.formatted(COLUNAS);

    private static final String SQL_OBTER = """
            SELECT %s
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.Id = ? AND p.CompanyId = ?;
            """.formatted(COLUNAS);

    private static final String SQL_OBTER_BLOQUEADO = """
            SELECT %s
            FROM Produtos p WITH (UPDLOCK, ROWLOCK)
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.Id = ? AND p.CompanyId = ?;
            """.formatted(COLUNAS);

    private static final String SQL_OBTER_POR_CODIGO = """
            SELECT %s
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = ? AND p.ProductCode = ?;
            """.formatted(COLUNAS);

    private static final String SQL_OBTER_POR_GTIN = """
            SELECT TOP 1 %s
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = ? AND p.Gtin = ?;
            """.formatted(COLUNAS);

    private static final String SQL_UPDATE_CADASTRO = """
            UPDATE Produtos
               SET ProductImageUrl = ?, ProductImageName = ?, ProductName = ?, ProductCode = ?,
                   ProductSupplier = ?, SupplierId = ?, ProductDescription = ?, EstoqueMinimo = ?,
                   ProductUnitPrice = ?, ProductSalePrice = ?, TotalPriceOnProduct = ?, Lucro = ?,
                   MargemDesejadaPercentual = ?, CategoriaId = ?, DataValidade = ?, ControlaValidade = ?,
                   DiasAlertaValidade = ?, UnidadeCompra = ?, FatorConversao = ?, QtdEmbalagem = ?,
                   Marca = ?, Fabricante = ?, ReferenciaFabricante = ?, PesoLiquidoKg = ?, PesoBrutoKg = ?,
                   LarguraCm = ?, AlturaCm = ?, ComprimentoCm = ?, EstoqueMaximo = ?, LocalizacaoEstoque = ?,
                   CustoMedio = ?, CustoComImposto = ?, CustoSemImposto = ?, DescontoMaximoPercentual = ?,
                   ComissaoPercentual = ?, MarkupCadastrado = ?, MarkupPraticado = ?, Ncm = ?, Cest = ?,
                   Cfop = ?, OrigemMercadoria = ?, UnidadeComercial = ?, UnidadeTributavel = ?, Gtin = ?,
                   CsosnIcms = ?, CstIcms = ?, AliquotaIcms = ?, CstPis = ?, CstCofins = ?,
                   CstIbsCbs = ?, CClassTrib = ?
             WHERE Id = ? AND CompanyId = ?;
            """;

    private static final String SQL_INSERT_CADASTRO = """
            INSERT INTO Produtos
                (ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, SupplierId,
                 ProductDescription, EstoqueMinimo, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct, Lucro,
                 MargemDesejadaPercentual, CategoriaId, DataValidade, ControlaValidade, DiasAlertaValidade,
                 UnidadeCompra, FatorConversao, QtdEmbalagem, Marca, Fabricante, ReferenciaFabricante,
                 PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm, EstoqueMaximo, LocalizacaoEstoque,
                 CustoMedio, CustoComImposto, CustoSemImposto, DescontoMaximoPercentual, ComissaoPercentual,
                 MarkupCadastrado, MarkupPraticado, Ncm, Cest, Cfop, OrigemMercadoria, UnidadeComercial,
                 UnidadeTributavel, Gtin, CsosnIcms, CstIcms, AliquotaIcms, CstPis, CstCofins, CstIbsCbs, CClassTrib,
                 Id, CompanyId, ProductQnt)
            VALUES
                (?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?, ?,
                 ?, ?, ?, ?, ?, ?, ?, ?, ?,
                 ?, ?, 0);
            """;

    private final JdbcTemplate jdbc;
    private final RegistradorEstoque registrador;
    private final LoteOperacoes loteOperacoes;
    private final TransactionOperations tx;
    private final Clock clock;

    @Autowired
    public ProdutoService(
            JdbcTemplate jdbc,
            RegistradorEstoque registrador,
            LoteOperacoes loteOperacoes,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this(jdbc, registrador, loteOperacoes, new TransactionTemplate(transactionManager), clock);
    }

    public ProdutoService(
            JdbcTemplate jdbc,
            RegistradorEstoque registrador,
            LoteOperacoes loteOperacoes,
            TransactionOperations tx,
            Clock clock) {
        this.jdbc = jdbc;
        this.registrador = registrador;
        this.loteOperacoes = loteOperacoes;
        this.tx = tx;
        this.clock = clock;
    }

    public static String versaoPreco(ProdutoDados p) {
        return Dinheiro.formatarInvarianteAte4Casas(p.productUnitPrice())
                + "|"
                + Dinheiro.formatarInvarianteAte4Casas(p.productSalePrice());
    }

    public List<ProdutoModel> listar(String companyId) {
        return listarDados(companyId).stream().map(this::paraModel).toList();
    }

    public List<ProdutoDados> listarDados(String companyId) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(SQL_LISTAR);
            ps.setNString(1, companyId);
            return ps;
        }, (rs, rowNum) -> lerDados(rs));
    }

    public Optional<ProdutoModel> obter(String companyId, String id) {
        return obterDados(companyId, id).map(this::paraModel);
    }

    public Optional<ProdutoDados> obterDados(String companyId, String id) {
        List<ProdutoDados> lista = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(SQL_OBTER);
            ps.setNString(1, id);
            ps.setNString(2, companyId);
            return ps;
        }, (rs, rowNum) -> lerDados(rs));
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.getFirst());
    }

    public Optional<ProdutoDados> obterPorCodigo(String companyId, String productCode) {
        List<ProdutoDados> lista = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(SQL_OBTER_POR_CODIGO);
            ps.setNString(1, companyId);
            ps.setNString(2, productCode);
            return ps;
        }, (rs, rowNum) -> lerDados(rs));
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.getFirst());
    }

    public Optional<ProdutoDados> obterPorGtin(String companyId, String gtin) {
        if (gtin == null || gtin.isBlank() || "SEM GTIN".equalsIgnoreCase(gtin.trim())) {
            return Optional.empty();
        }
        List<ProdutoDados> lista = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(SQL_OBTER_POR_GTIN);
            ps.setNString(1, companyId);
            ps.setNString(2, gtin.trim());
            return ps;
        }, (rs, rowNum) -> lerDados(rs));
        return lista.isEmpty() ? Optional.empty() : Optional.of(lista.getFirst());
    }

    public ProdutoModel criar(String companyId, ProdutoRequest request, String usuarioId, String usuarioNome) {
        ProdutoRequest norm = normalizar(request, null);
        validar(norm);
        validarRegrasNegocio(companyId, norm, null, null);
        String id = "pr-" + clock.millis();
        ProdutoDados dados = mapearRequest(id, norm);
        ProdutoDados salvo = salvarDados(companyId, dados, origemCadastro(id, usuarioId, usuarioNome));

        LocalDate validade = tentarLerDataFlexivel(norm.dataValidade());
        loteOperacoes.registrarEntradaSegura(
                companyId,
                salvo.id(),
                validade,
                Dinheiro.ler(norm.productQnt()),
                norm.numeroLote(),
                "cadastro",
                "Cadastro de produto");

        return obterDados(companyId, salvo.id()).map(this::paraModel).orElseGet(() -> paraModel(salvo));
    }

    public Optional<ProdutoModel> atualizar(
            String companyId, String id, ProdutoRequest request, String usuarioId, String usuarioNome) {
        Optional<ProdutoDados> atualOpt = obterDados(companyId, id);
        if (atualOpt.isEmpty()) {
            return Optional.empty();
        }
        ProdutoDados atual = atualOpt.get();
        ProdutoRequest norm = normalizar(request, atual.productCode());
        validar(norm);
        validarRegrasNegocio(companyId, norm, id, atual.productSupplier());
        ProdutoDados salvo = salvarDados(companyId, mapearRequest(id, norm), origemCadastro(id, usuarioId, usuarioNome));
        return Optional.of(obterDados(companyId, id).map(this::paraModel).orElseGet(() -> paraModel(salvo)));
    }

    public Optional<ProdutoModel> atualizarPreco(String companyId, String id, ProdutoPrecoRequest request) {
        String precoTexto = request == null ? null : request.precoVenda();
        Optional<BigDecimal> saleOpt = Dinheiro.tentarLer(precoTexto);
        if (saleOpt.isEmpty()) {
            falhar("Informe um preço de venda maior que zero, com até duas casas decimais e até R$ 1 bilhão.");
        }
        BigDecimal sale = saleOpt.get();
        if (sale.compareTo(BigDecimal.ZERO) <= 0
                || sale.compareTo(UM_BILHAO) > 0
                || sale.setScale(2, RoundingMode.HALF_EVEN).compareTo(sale) != 0) {
            falhar("Informe um preço de venda maior que zero, com até duas casas decimais e até R$ 1 bilhão.");
        }

        String versao = request.versaoConsultada() == null ? "" : request.versaoConsultada();
        String[] partes = versao.split("\\|", -1);
        if (partes.length != 2) {
            falhar("Atualize a consulta do produto antes de salvar o preço.");
        }
        BigDecimal expectedCost = lerDecimalPontoSemSinal(partes[0]);
        BigDecimal expectedSale = lerDecimalPontoSemSinal(partes[1]);
        if (expectedCost == null || expectedSale == null) {
            falhar("Atualize a consulta do produto antes de salvar o preço.");
        }

        ProdutoDados atualizado = tx.execute(status -> {
            List<ProdutoDados> lidos = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(SQL_OBTER_BLOQUEADO);
                ps.setNString(1, id);
                ps.setNString(2, companyId);
                return ps;
            }, (rs, rowNum) -> lerDados(rs));
            if (lidos.isEmpty()) {
                return null;
            }
            ProdutoDados product = lidos.getFirst();
            if (product.productUnitPrice().compareTo(expectedCost) != 0
                    || (product.productSalePrice().compareTo(expectedSale) != 0
                            && product.productSalePrice().compareTo(sale) != 0)) {
                throw new NegocioException(
                        HttpStatus.CONFLICT,
                        "O custo ou preço de venda mudou desde esta consulta. Atualize os preços e confira o ajuste antes de salvar novamente.");
            }
            BigDecimal profit = sale.subtract(product.productUnitPrice());
            BigDecimal markup = product.productUnitPrice().compareTo(BigDecimal.ZERO) > 0
                    ? profit.divide(product.productUnitPrice(), MathContext.DECIMAL128)
                            .multiply(CEM)
                            .setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            if (markup.compareTo(LIMITE_MARKUP) > 0) {
                falhar("O markup calculado excede o limite de 99.999,9999%. Confira o custo e o preço de venda.");
            }
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "UPDATE Produtos SET ProductSalePrice = ?, Lucro = ?, MarkupPraticado = ? WHERE Id = ? AND CompanyId = ?;");
                ps.setBigDecimal(1, sale);
                ps.setBigDecimal(2, profit);
                ps.setBigDecimal(3, markup);
                ps.setNString(4, id);
                ps.setNString(5, companyId);
                return ps;
            });
            return product.comPrecoVenda(sale, profit, markup);
        });

        return Optional.ofNullable(atualizado).map(this::paraModel);
    }

    public boolean excluir(String companyId, String id) {
        int linhas = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("DELETE FROM Produtos WHERE Id = ? AND CompanyId = ?;");
            ps.setNString(1, id);
            ps.setNString(2, companyId);
            return ps;
        });
        return linhas > 0;
    }

    public List<ProdutoModel> listarVencimentos(String companyId, int dias) {
        String sql = """
                SELECT %s
                FROM Produtos p
                LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
                WHERE p.CompanyId = ?
                  AND p.ControlaValidade = 1
                  AND p.DataValidade IS NOT NULL
                  AND p.DataValidade <= DATEADD(day, ?, ?)
                ORDER BY p.DataValidade ASC, p.ProductName ASC;
                """.formatted(COLUNAS);
        Date hoje = Date.valueOf(hojeUtc());
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setNString(1, companyId);
            ps.setInt(2, dias);
            ps.setDate(3, hoje);
            return ps;
        }, (rs, rowNum) -> paraModel(lerDados(rs)));
    }

    public VencimentoResumoModel obterResumoVencimentos(String companyId) {
        String sql = """
                SELECT
                    COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade < ? THEN 1 END) AS Vencidos,
                    COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= ? AND DataValidade <= DATEADD(day, 7, ?) THEN 1 END) AS VenceEm7Dias,
                    COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= ? AND DataValidade <= DATEADD(day, 15, ?) THEN 1 END) AS VenceEm15Dias,
                    COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= ? AND DataValidade <= DATEADD(day, 30, ?) THEN 1 END) AS VenceEm30Dias,
                    COUNT(CASE WHEN ControlaValidade = 1 THEN 1 END) AS TotalControlados,
                    COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade IS NULL THEN 1 END) AS SemDataInformada
                FROM Produtos
                WHERE CompanyId = ?;
                """;
        Date hoje = Date.valueOf(hojeUtc());
        List<VencimentoResumoModel> res = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            for (int i = 1; i <= 7; i++) {
                ps.setDate(i, hoje);
            }
            ps.setNString(8, companyId);
            return ps;
        }, (rs, rowNum) -> new VencimentoResumoModel(
                rs.getInt("Vencidos"),
                rs.getInt("VenceEm7Dias"),
                rs.getInt("VenceEm15Dias"),
                rs.getInt("VenceEm30Dias"),
                rs.getInt("TotalControlados"),
                rs.getInt("SemDataInformada")));
        return res.isEmpty() ? new VencimentoResumoModel(0, 0, 0, 0, 0, 0) : res.getFirst();
    }

    public boolean atualizarValidade(String companyId, String id, String dataValidade) {
        LocalDate parsed = tentarLerDataFlexivel(dataValidade);
        int linhas = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE Produtos SET DataValidade = ? WHERE Id = ? AND CompanyId = ?;");
            if (parsed == null) {
                ps.setNull(1, Types.DATE);
            } else {
                ps.setDate(1, Date.valueOf(parsed));
            }
            ps.setNString(2, id);
            ps.setNString(3, companyId);
            return ps;
        });
        return linhas > 0;
    }

    public boolean ajustarEstoque(
            String companyId,
            String id,
            String tipo,
            BigDecimal quantidade,
            String motivo,
            String usuarioId,
            String usuarioNome,
            String dataValidade,
            String numeroLote) {
        if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) <= 0) {
            falhar("Quantidade deve ser maior que zero.");
        }
        String tipoNorm = (tipo == null ? "" : tipo.trim()).toLowerCase(Locale.ROOT);
        if (!TIPOS_AJUSTE.contains(tipoNorm)) {
            falhar("Tipo deve ser 'entrada', 'saida', 'uso_interno' ou 'perda'.");
        }
        if (motivo == null || motivo.isBlank()) {
            falhar("O motivo é obrigatório para toda movimentação de estoque.");
        }
        boolean entrada = "entrada".equals(tipoNorm);
        LocalDate validade = null;
        if (entrada && dataValidade != null && !dataValidade.isBlank()) {
            validade = lerDataEstritaIso(dataValidade.trim());
        }

        String motivoLimpo = motivo.trim();
        String uid = usuarioId == null || usuarioId.isBlank() ? "sistema" : usuarioId;
        String unome = usuarioNome == null || usuarioNome.isBlank() ? "Sistema" : usuarioNome;

        Boolean aplicado = tx.execute(status -> {
            record SaldoCusto(BigDecimal saldo, BigDecimal custo) {}
            List<SaldoCusto> lidos = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        SELECT ProductQnt, ProductUnitPrice
                        FROM Produtos WITH (UPDLOCK, ROWLOCK)
                        WHERE Id = ? AND CompanyId = ?;
                        """);
                ps.setNString(1, id);
                ps.setNString(2, companyId);
                return ps;
            }, (rs, rowNum) -> new SaldoCusto(rs.getBigDecimal("ProductQnt"), rs.getBigDecimal("ProductUnitPrice")));
            if (lidos.isEmpty()) {
                return false;
            }
            SaldoCusto sc = lidos.getFirst();
            if (!entrada && sc.saldo().subtract(quantidade).compareTo(BigDecimal.ZERO) < 0) {
                falhar("Estoque insuficiente. Disponível: "
                        + sc.saldo().toPlainString()
                        + ", solicitado: "
                        + quantidade.toPlainString()
                        + ".");
            }
            BigDecimal delta = entrada ? quantidade : quantidade.negate();
            return registrador.aplicar(
                            companyId,
                            id,
                            delta,
                            tipoNorm,
                            sc.custo(),
                            new MovimentoOrigem("ajuste", null, motivoLimpo, uid, unome))
                    .isPresent();
        });

        if (!Boolean.TRUE.equals(aplicado)) {
            return false;
        }

        if (entrada) {
            loteOperacoes.registrarEntradaSegura(companyId, id, validade, quantidade, numeroLote, "ajuste", usuarioNome);
        } else {
            loteOperacoes.consumirAjusteSeguro(companyId, id, quantidade);
        }
        return true;
    }

    /** Porta de ProdutoAB.SalvarAsync: salva o cadastro e aplica o delta pelo RegistradorEstoque na mesma transacao. */
    public ProdutoDados salvarDados(String companyId, ProdutoDados product, MovimentoOrigem origem) {
        String supplierId = resolverFornecedorId(companyId, product.productSupplier());
        return tx.execute(status -> {
            List<BigDecimal> saldos = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "SELECT ProductQnt FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = ? AND CompanyId = ?;");
                ps.setNString(1, product.id());
                ps.setNString(2, companyId);
                return ps;
            }, (rs, rowNum) -> rs.getBigDecimal(1));

            boolean existe = !saldos.isEmpty();
            BigDecimal saldoAtual = existe && saldos.getFirst() != null ? saldos.getFirst() : BigDecimal.ZERO;
            ProdutoDados alvo = product.estoqueConsultado() != null
                    ? product.comQuantidade(saldoAtual.add(product.productQnt().subtract(product.estoqueConsultado())))
                    : product;

            String sql = existe ? SQL_UPDATE_CADASTRO : SQL_INSERT_CADASTRO;
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql);
                preencherParametrosCadastro(ps, companyId, alvo, supplierId);
                return ps;
            });

            BigDecimal delta = alvo.productQnt().subtract(saldoAtual);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                MovimentoOrigem org = origem != null
                        ? origem
                        : MovimentoOrigem.sistema("cadastro", alvo.id(), "Cadastro/edição de produto");
                registrador.aplicar(
                        companyId,
                        alvo.id(),
                        delta,
                        delta.signum() > 0 ? EstoqueTipos.ENTRADA : EstoqueTipos.SAIDA,
                        null,
                        org);
            }
            return alvo;
        });
    }

    /** Porta de ProdutoAB.EntradaEstoqueAsync (usada por NF-e, entrada manual e recebimento de ordem de compra). */
    public ProdutoDados entradaEstoque(
            String companyId,
            String productId,
            BigDecimal quantidadeRecebida,
            BigDecimal custoUnitario,
            boolean preservarPrecoVenda,
            BigDecimal precoVendaExplicito,
            MovimentoOrigem origem) {
        return tx.execute(status -> {
            List<ProdutoDados> lidos = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(SQL_OBTER_BLOQUEADO);
                ps.setNString(1, productId);
                ps.setNString(2, companyId);
                return ps;
            }, (rs, rowNum) -> lerDados(rs));
            if (lidos.isEmpty()) {
                falhar("Produto " + productId + " não encontrado.");
            }
            ProdutoDados current = lidos.getFirst();
            BigDecimal nextQuantity = current.productQnt().add(quantidadeRecebida);
            BigDecimal margem = current.margemDesejadaPercentual() != null
                            && current.margemDesejadaPercentual().compareTo(BigDecimal.ZERO) > 0
                    ? current.margemDesejadaPercentual()
                    : null;
            BigDecimal nextSalePrice;
            if (precoVendaExplicito != null) {
                nextSalePrice = precoVendaExplicito;
            } else if (preservarPrecoVenda || margem == null) {
                nextSalePrice = current.productSalePrice();
            } else {
                nextSalePrice = custoUnitario.multiply(
                        BigDecimal.ONE.add(margem.divide(CEM, MathContext.DECIMAL128)));
            }
            BigDecimal nextLucro = nextSalePrice.subtract(custoUnitario);

            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        UPDATE Produtos
                           SET ProductUnitPrice = ?,
                               ProductSalePrice = ?,
                               Lucro = ?
                         WHERE Id = ? AND CompanyId = ?;
                        """);
                ps.setBigDecimal(1, custoUnitario);
                ps.setBigDecimal(2, nextSalePrice);
                ps.setBigDecimal(3, nextLucro);
                ps.setNString(4, productId);
                ps.setNString(5, companyId);
                return ps;
            });

            registrador.aplicar(
                    companyId,
                    productId,
                    quantidadeRecebida,
                    EstoqueTipos.ENTRADA,
                    custoUnitario,
                    origem != null ? origem : MovimentoOrigem.sistema("nfe", null, "Entrada de estoque"));

            return new ProdutoDados(
                    current.id(),
                    current.estoqueConsultado(),
                    current.productImageUrl(),
                    current.productImageName(),
                    current.productName(),
                    current.productCode(),
                    current.productSupplier(),
                    current.productDescription(),
                    nextQuantity,
                    current.estoqueMinimo(),
                    custoUnitario,
                    nextSalePrice,
                    custoUnitario.multiply(nextQuantity),
                    nextLucro,
                    current.margemDesejadaPercentual(),
                    current.categoriaId(),
                    current.categoriaNome(),
                    current.dataValidade(),
                    current.controlaValidade(),
                    current.diasAlertaValidade(),
                    current.unidadeCompra(),
                    current.fatorConversao(),
                    current.qtdEmbalagem(),
                    current.marca(),
                    current.fabricante(),
                    current.referenciaFabricante(),
                    current.pesoLiquidoKg(),
                    current.pesoBrutoKg(),
                    current.larguraCm(),
                    current.alturaCm(),
                    current.comprimentoCm(),
                    current.estoqueMaximo(),
                    current.localizacaoEstoque(),
                    current.custoMedio(),
                    current.custoComImposto(),
                    current.custoSemImposto(),
                    current.descontoMaximoPercentual(),
                    current.comissaoPercentual(),
                    current.markupCadastrado(),
                    current.markupPraticado(),
                    current.ncm(),
                    current.cest(),
                    current.cfop(),
                    current.origemMercadoria(),
                    current.unidadeComercial(),
                    current.unidadeTributavel(),
                    current.gtin(),
                    current.csosnIcms(),
                    current.cstIcms(),
                    current.aliquotaIcms(),
                    current.cstPis(),
                    current.cstCofins(),
                    current.cstIbsCbs(),
                    current.cClassTrib());
        });
    }

    /** Porta de ProdutoAB.EstornarEstoqueAsync (permite saldo negativo no estorno de nota de entrada). */
    public void estornarEstoque(String companyId, String productId, BigDecimal quantidade, MovimentoOrigem origem) {
        tx.executeWithoutResult(status -> registrador.aplicar(
                companyId,
                productId,
                quantidade.negate(),
                EstoqueTipos.SAIDA,
                null,
                origem != null ? origem : MovimentoOrigem.sistema("nota_entrada", null, "Estorno de nota de entrada")));
    }

    public int importarCargaLegado(String companyId) throws IOException {
        String script;
        try (InputStream in = ProdutoService.class.getResourceAsStream("/db/legado/06_produtos_mercado_completo.sql")) {
            if (in == null) {
                throw new IOException("Script de migração 06_produtos_mercado_completo.sql não encontrado.");
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!script.isEmpty() && script.charAt(0) == '\uFEFF') {
            script = script.substring(1);
        }
        String limpo = LINHA_GO.matcher(script).replaceAll("")
                .replace("VALUES (N'empresa-principal');", "VALUES (?);");

        Integer resultado = jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(limpo)) {
                ps.setQueryTimeout(300);
                ps.setNString(1, companyId);
                boolean hasResult = ps.execute();
                int total = 0;
                boolean viuContagem = false;
                while (true) {
                    if (!hasResult) {
                        int uc = ps.getUpdateCount();
                        if (uc == -1) {
                            break;
                        }
                        total += uc;
                        viuContagem = true;
                    }
                    hasResult = ps.getMoreResults();
                }
                return viuContagem ? total : -1;
            }
        });
        return resultado == null ? -1 : resultado;
    }

    public ProdutoModel paraModel(ProdutoDados s) {
        Integer diasRestantes = s.dataValidade() != null
                ? (int) ChronoUnit.DAYS.between(hojeUtc(), s.dataValidade())
                : null;
        return new ProdutoModel(
                versaoPreco(s),
                s.id(),
                s.productImageUrl(),
                s.productImageName(),
                s.productName(),
                s.productCode(),
                s.productSupplier(),
                s.productDescription(),
                Dinheiro.formatarQuantidade(s.productQnt()),
                Dinheiro.formatarQuantidade(s.estoqueMinimo()),
                Dinheiro.formatarSemSimbolo(s.productUnitPrice()),
                Dinheiro.formatarSemSimbolo(s.productSalePrice()),
                Dinheiro.formatarSemSimbolo(s.totalPriceOnProduct()),
                Dinheiro.formatarSemSimbolo(s.lucro()),
                s.margemDesejadaPercentual() != null ? Dinheiro.formatarSemSimbolo(s.margemDesejadaPercentual()) : null,
                s.categoriaId(),
                s.categoriaNome(),
                s.dataValidade() != null ? s.dataValidade().toString() : null,
                s.controlaValidade(),
                s.diasAlertaValidade(),
                diasRestantes,
                s.unidadeCompra(),
                Dinheiro.formatarQuantidade(s.fatorConversao()),
                Dinheiro.formatarQuantidade(s.qtdEmbalagem()),
                s.marca(),
                s.fabricante(),
                s.referenciaFabricante(),
                Dinheiro.formatarQuantidade(s.pesoLiquidoKg()),
                Dinheiro.formatarQuantidade(s.pesoBrutoKg()),
                Dinheiro.formatarQuantidade(s.larguraCm()),
                Dinheiro.formatarQuantidade(s.alturaCm()),
                Dinheiro.formatarQuantidade(s.comprimentoCm()),
                Dinheiro.formatarQuantidade(s.estoqueMaximo()),
                s.localizacaoEstoque(),
                Dinheiro.formatarSemSimbolo(s.custoMedio()),
                Dinheiro.formatarSemSimbolo(s.custoComImposto()),
                Dinheiro.formatarSemSimbolo(s.custoSemImposto()),
                Dinheiro.formatarQuantidade(s.descontoMaximoPercentual()),
                Dinheiro.formatarQuantidade(s.comissaoPercentual()),
                Dinheiro.formatarQuantidade(s.markupCadastrado()),
                Dinheiro.formatarQuantidade(s.markupPraticado()),
                s.ncm(),
                s.cest(),
                s.cfop(),
                s.origemMercadoria(),
                s.unidadeComercial(),
                s.unidadeTributavel(),
                s.gtin(),
                s.csosnIcms(),
                s.cstIcms(),
                Dinheiro.formatarSemSimbolo(s.aliquotaIcms()),
                s.cstPis(),
                s.cstCofins(),
                s.cstIbsCbs(),
                s.cClassTrib());
    }

    static ProdutoRequest normalizar(ProdutoRequest r, String existingCode) {
        if (r == null) {
            r = new ProdutoRequest(
                    "", "", "", "", "", "", "0", null, "0", "", "", "", null, null, null, null, false, 15,
                    "UN", "1", "1", null, null, null, "0", "0", "0", "0", "0", "0", null, "0,00", "0,00", "0,00",
                    "0", "0", "0", "0", "00000000", null, "5102", 0, "UN", "UN", "SEM GTIN", null, null, "0,00",
                    "07", "07", null, null);
        }
        String desc = limparOuVazio(r.productDescription());
        String nome = r.productName() == null || r.productName().isBlank() ? desc : r.productName().trim();
        String forn = limparOuVazio(r.productSupplier());
        String codigo;
        if (r.productCode() == null || r.productCode().isBlank()) {
            codigo = existingCode == null || existingCode.isBlank()
                    ? "P-" + UUID.randomUUID().toString().replace("-", "")
                    : existingCode;
        } else {
            codigo = r.productCode().trim();
        }
        String qnt = r.productQnt() == null || r.productQnt().isBlank() ? "0" : r.productQnt();

        return new ProdutoRequest(
                r.productImageUrl() == null ? "" : r.productImageUrl(),
                r.productImageName() == null ? "" : r.productImageName(),
                nome,
                codigo,
                forn,
                desc,
                qnt,
                r.estoqueConsultado(),
                r.estoqueMinimo(),
                r.productUnitPrice(),
                r.productSalePrice(),
                r.totalPriceOnProduct(),
                r.margemDesejadaPercentual(),
                r.categoriaId(),
                r.dataValidade(),
                r.numeroLote(),
                r.controlaValidade(),
                r.diasAlertaValidade(),
                r.unidadeCompra(),
                r.fatorConversao(),
                r.qtdEmbalagem(),
                r.marca(),
                r.fabricante(),
                r.referenciaFabricante(),
                r.pesoLiquidoKg(),
                r.pesoBrutoKg(),
                r.larguraCm(),
                r.alturaCm(),
                r.comprimentoCm(),
                r.estoqueMaximo(),
                r.localizacaoEstoque(),
                r.custoMedio(),
                r.custoComImposto(),
                r.custoSemImposto(),
                r.descontoMaximoPercentual(),
                r.comissaoPercentual(),
                r.markupCadastrado(),
                r.markupPraticado(),
                r.ncm(),
                r.cest(),
                r.cfop(),
                r.origemMercadoria(),
                r.unidadeComercial(),
                r.unidadeTributavel(),
                r.gtin(),
                r.csosnIcms(),
                r.cstIcms(),
                r.aliquotaIcms(),
                r.cstPis(),
                r.cstCofins(),
                r.cstIbsCbs(),
                r.cClassTrib());
    }

    static void validar(ProdutoRequest r) {
        if (r.productName() == null || r.productName().isBlank()) {
            falhar("Descrição do produto é obrigatória.");
        }
        Optional<BigDecimal> qnt = Dinheiro.tentarLer(r.productQnt());
        if (qnt.isEmpty() || qnt.get().compareTo(BigDecimal.ZERO) < 0) {
            falhar("Quantidade do produto deve ser um número maior ou igual a zero.");
        }
        if (Dinheiro.ler(r.productUnitPrice()).compareTo(BigDecimal.ZERO) <= 0) {
            falhar("Preco de custo deve ser maior que zero.");
        }
        if (Dinheiro.ler(r.productSalePrice()).compareTo(BigDecimal.ZERO) <= 0) {
            falhar("Preco de venda deve ser maior que zero.");
        }
    }

    private void validarRegrasNegocio(
            String companyId, ProdutoRequest r, String currentId, String existingSupplier) {
        List<ProdutoDados> products = listarDados(companyId);
        String code = r.productCode().trim();
        boolean codigoRepetido = products.stream()
                .anyMatch(item -> !item.id().equals(currentId) && item.productCode().equalsIgnoreCase(code));
        if (codigoRepetido) {
            falhar("Já existe produto com este código.");
        }

        String gtin = r.gtin() == null ? "" : r.gtin().trim();
        if (!gtin.isEmpty() && !"SEM GTIN".equalsIgnoreCase(gtin)) {
            ProdutoDados atual = currentId == null
                    ? null
                    : products.stream().filter(i -> i.id().equals(currentId)).findFirst().orElse(null);
            String gtinAtual = atual == null || atual.gtin() == null ? "" : atual.gtin().trim();
            boolean mudou = atual == null || !gtinAtual.equalsIgnoreCase(gtin);
            if (mudou) {
                boolean gtinRepetido = products.stream()
                        .anyMatch(item -> !item.id().equals(currentId)
                                && item.gtin() != null
                                && item.gtin().trim().equalsIgnoreCase(gtin));
                if (gtinRepetido) {
                    falhar("Já existe produto com este GTIN (código de barras).");
                }
            }
        }

        String supplierName = r.productSupplier() == null ? "" : r.productSupplier().trim();
        if (supplierName.isEmpty()) {
            return;
        }
        if (currentId != null
                && existingSupplier != null
                && supplierName.equalsIgnoreCase(existingSupplier.trim())) {
            return;
        }
        Integer existeFornecedor = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT COUNT(1)
                    FROM Fornecedores
                    WHERE CompanyId = ?
                      AND (UPPER(FantasyName) = UPPER(?) OR UPPER(CompanyName) = UPPER(?));
                    """);
            ps.setNString(1, companyId);
            ps.setNString(2, supplierName);
            ps.setNString(3, supplierName);
            return ps;
        }, (rs, rowNum) -> rs.getInt(1)).stream().findFirst().orElse(0);
        if (existeFornecedor <= 0) {
            falhar("Fornecedor informado não está cadastrado.");
        }
    }

    public static ProdutoDados mapearRequest(String id, ProdutoRequest r) {
        return mapearRequest(id, r, true);
    }

    /** Entrada manual já valida a conversão positiva e admite embalagens fracionárias, como o .NET. */
    public static ProdutoDados mapearEntradaManual(String id, ProdutoRequest r) {
        return mapearRequest(id, r, false);
    }

    private static ProdutoDados mapearRequest(String id, ProdutoRequest r, boolean normalizarEmbalagem) {
        BigDecimal custoUnitario = Dinheiro.ler(r.productUnitPrice());
        BigDecimal precoVenda = Dinheiro.ler(r.productSalePrice());
        BigDecimal custoMedio = Dinheiro.ler(r.custoMedio());
        if (custoMedio.compareTo(BigDecimal.ZERO) == 0) {
            custoMedio = custoUnitario;
        }
        BigDecimal markupPraticado = custoMedio.compareTo(BigDecimal.ZERO) > 0
                ? precoVenda.subtract(custoMedio)
                        .divide(custoMedio, MathContext.DECIMAL128)
                        .multiply(CEM)
                        .setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal lucro = precoVenda.subtract(custoUnitario);
        BigDecimal qnt = Dinheiro.ler(r.productQnt());
        BigDecimal fator = Dinheiro.ler(r.fatorConversao());
        BigDecimal qtdEmb = Dinheiro.ler(r.qtdEmbalagem());
        if (normalizarEmbalagem) {
            fator = maximoUm(fator);
            qtdEmb = maximoUm(qtdEmb);
        }
        int diasAlerta = r.diasAlertaValidade() == null || r.diasAlertaValidade() <= 0 ? 15 : r.diasAlertaValidade();
        int origemMerc = r.origemMercadoria() == null ? 0 : (r.origemMercadoria() & 0xFF);

        return new ProdutoDados(
                id,
                r.estoqueConsultado() == null || r.estoqueConsultado().isBlank()
                        ? null
                        : Dinheiro.ler(r.estoqueConsultado()),
                r.productImageUrl() == null ? "" : r.productImageUrl(),
                r.productImageName() == null ? "" : r.productImageName(),
                r.productName().trim(),
                r.productCode().trim(),
                limparOuVazio(r.productSupplier()),
                limparOuVazio(r.productDescription()),
                qnt,
                Dinheiro.ler(r.estoqueMinimo()),
                custoUnitario,
                precoVenda,
                custoUnitario.multiply(qnt),
                lucro,
                r.margemDesejadaPercentual() == null || r.margemDesejadaPercentual().isBlank()
                        ? null
                        : Dinheiro.ler(r.margemDesejadaPercentual()),
                limparOuNulo(r.categoriaId()),
                null,
                tentarLerDataFlexivel(r.dataValidade()),
                Boolean.TRUE.equals(r.controlaValidade()),
                diasAlerta,
                padraoMaiusculo(r.unidadeCompra(), "UN"),
                fator,
                qtdEmb,
                limparOuNulo(r.marca()),
                limparOuNulo(r.fabricante()),
                limparOuNulo(r.referenciaFabricante()),
                Dinheiro.ler(r.pesoLiquidoKg()),
                Dinheiro.ler(r.pesoBrutoKg()),
                Dinheiro.ler(r.larguraCm()),
                Dinheiro.ler(r.alturaCm()),
                Dinheiro.ler(r.comprimentoCm()),
                Dinheiro.ler(r.estoqueMaximo()),
                limparOuNulo(r.localizacaoEstoque()),
                custoMedio,
                Dinheiro.ler(r.custoComImposto()),
                Dinheiro.ler(r.custoSemImposto()),
                Dinheiro.ler(r.descontoMaximoPercentual()),
                Dinheiro.ler(r.comissaoPercentual()),
                Dinheiro.ler(r.markupCadastrado()),
                markupPraticado,
                padraoTexto(r.ncm(), "00000000"),
                limparOuNulo(r.cest()),
                padraoTexto(r.cfop(), "5102"),
                origemMerc,
                padraoMaiusculo(r.unidadeComercial(), "UN"),
                padraoMaiusculo(r.unidadeTributavel(), "UN"),
                padraoTexto(r.gtin(), "SEM GTIN"),
                limparOuNulo(r.csosnIcms()),
                limparOuNulo(r.cstIcms()),
                Dinheiro.ler(r.aliquotaIcms()),
                padraoTexto(r.cstPis(), "07"),
                padraoTexto(r.cstCofins(), "07"),
                limparOuNulo(r.cstIbsCbs()),
                limparOuNulo(r.cClassTrib()));
    }

    private String resolverFornecedorId(String companyId, String supplierName) {
        if (supplierName == null || supplierName.isBlank()) {
            return null;
        }
        List<String> ids = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    SELECT TOP 1 Id
                    FROM Fornecedores
                    WHERE CompanyId = ? AND (FantasyName = ? OR CompanyName = ?);
                    """);
            ps.setNString(1, companyId);
            ps.setNString(2, supplierName);
            ps.setNString(3, supplierName);
            return ps;
        }, (rs, rowNum) -> rs.getString(1));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static void preencherParametrosCadastro(
            PreparedStatement ps, String companyId, ProdutoDados p, String supplierId) throws SQLException {
        ps.setNString(1, p.productImageUrl());
        ps.setNString(2, p.productImageName());
        ps.setNString(3, p.productName());
        ps.setNString(4, p.productCode());
        ps.setNString(5, p.productSupplier());
        setNullableNString(ps, 6, supplierId);
        ps.setNString(7, p.productDescription());
        ps.setBigDecimal(8, p.estoqueMinimo());
        ps.setBigDecimal(9, p.productUnitPrice());
        ps.setBigDecimal(10, p.productSalePrice());
        ps.setBigDecimal(11, p.totalPriceOnProduct());
        ps.setBigDecimal(12, p.lucro());
        setNullableDecimal(ps, 13, p.margemDesejadaPercentual());
        setNullableNString(ps, 14, p.categoriaId());
        if (p.dataValidade() == null) ps.setNull(15, Types.DATE); else ps.setDate(15, Date.valueOf(p.dataValidade()));
        ps.setBoolean(16, p.controlaValidade());
        ps.setInt(17, p.diasAlertaValidade());
        ps.setNString(18, p.unidadeCompra());
        ps.setBigDecimal(19, p.fatorConversao());
        ps.setBigDecimal(20, p.qtdEmbalagem());
        setNullableNString(ps, 21, p.marca());
        setNullableNString(ps, 22, p.fabricante());
        setNullableNString(ps, 23, p.referenciaFabricante());
        ps.setBigDecimal(24, p.pesoLiquidoKg());
        ps.setBigDecimal(25, p.pesoBrutoKg());
        ps.setBigDecimal(26, p.larguraCm());
        ps.setBigDecimal(27, p.alturaCm());
        ps.setBigDecimal(28, p.comprimentoCm());
        ps.setBigDecimal(29, p.estoqueMaximo());
        setNullableNString(ps, 30, p.localizacaoEstoque());
        ps.setBigDecimal(31, p.custoMedio());
        ps.setBigDecimal(32, p.custoComImposto());
        ps.setBigDecimal(33, p.custoSemImposto());
        ps.setBigDecimal(34, p.descontoMaximoPercentual());
        ps.setBigDecimal(35, p.comissaoPercentual());
        ps.setBigDecimal(36, p.markupCadastrado());
        ps.setBigDecimal(37, p.markupPraticado());
        ps.setNString(38, p.ncm());
        setNullableNString(ps, 39, p.cest());
        ps.setNString(40, p.cfop());
        ps.setInt(41, p.origemMercadoria());
        ps.setNString(42, p.unidadeComercial());
        ps.setNString(43, p.unidadeTributavel());
        ps.setNString(44, p.gtin());
        setNullableNString(ps, 45, p.csosnIcms());
        setNullableNString(ps, 46, p.cstIcms());
        ps.setBigDecimal(47, p.aliquotaIcms());
        ps.setNString(48, p.cstPis());
        ps.setNString(49, p.cstCofins());
        setNullableNString(ps, 50, p.cstIbsCbs());
        setNullableNString(ps, 51, p.cClassTrib());
        ps.setNString(52, p.id());
        ps.setNString(53, companyId);
    }

    private static ProdutoDados lerDados(ResultSet rs) throws SQLException {
        Date dv = rs.getDate("DataValidade");
        return new ProdutoDados(
                lerTexto(rs, "Id"),
                null,
                lerTexto(rs, "ProductImageUrl"),
                lerTexto(rs, "ProductImageName"),
                lerTexto(rs, "ProductName"),
                lerTexto(rs, "ProductCode"),
                lerTexto(rs, "ProductSupplier"),
                lerTexto(rs, "ProductDescription"),
                lerDecimal(rs, "ProductQnt"),
                lerDecimal(rs, "EstoqueMinimo"),
                lerDecimal(rs, "ProductUnitPrice"),
                lerDecimal(rs, "ProductSalePrice"),
                lerDecimal(rs, "TotalPriceOnProduct"),
                lerDecimal(rs, "Lucro"),
                rs.getBigDecimal("MargemDesejadaPercentual"),
                lerTextoOuNulo(rs, "CategoriaId"),
                lerTextoOuNulo(rs, "CategoriaNome"),
                dv == null ? null : dv.toLocalDate(),
                rs.getBoolean("ControlaValidade"),
                rs.getInt("DiasAlertaValidade"),
                lerTexto(rs, "UnidadeCompra"),
                lerDecimal(rs, "FatorConversao"),
                lerDecimal(rs, "QtdEmbalagem"),
                lerTextoOuNulo(rs, "Marca"),
                lerTextoOuNulo(rs, "Fabricante"),
                lerTextoOuNulo(rs, "ReferenciaFabricante"),
                lerDecimal(rs, "PesoLiquidoKg"),
                lerDecimal(rs, "PesoBrutoKg"),
                lerDecimal(rs, "LarguraCm"),
                lerDecimal(rs, "AlturaCm"),
                lerDecimal(rs, "ComprimentoCm"),
                lerDecimal(rs, "EstoqueMaximo"),
                lerTextoOuNulo(rs, "LocalizacaoEstoque"),
                lerDecimal(rs, "CustoMedio"),
                lerDecimal(rs, "CustoComImposto"),
                lerDecimal(rs, "CustoSemImposto"),
                lerDecimal(rs, "DescontoMaximoPercentual"),
                lerDecimal(rs, "ComissaoPercentual"),
                lerDecimal(rs, "MarkupCadastrado"),
                lerDecimal(rs, "MarkupPraticado"),
                lerTexto(rs, "Ncm"),
                lerTextoOuNulo(rs, "Cest"),
                lerTexto(rs, "Cfop"),
                rs.getInt("OrigemMercadoria"),
                lerTexto(rs, "UnidadeComercial"),
                lerTexto(rs, "UnidadeTributavel"),
                lerTexto(rs, "Gtin"),
                lerTextoOuNulo(rs, "CsosnIcms"),
                lerTextoOuNulo(rs, "CstIcms"),
                lerDecimal(rs, "AliquotaIcms"),
                lerTexto(rs, "CstPis"),
                lerTexto(rs, "CstCofins"),
                lerTextoOuNulo(rs, "CstIbsCbs"),
                lerTextoOuNulo(rs, "CClassTrib"));
    }

    private LocalDate hojeUtc() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static MovimentoOrigem origemCadastro(String produtoId, String usuarioId, String usuarioNome) {
        return new MovimentoOrigem(
                "cadastro",
                produtoId,
                "Cadastro/edição de produto",
                usuarioId == null || usuarioId.isBlank() ? "sistema" : usuarioId,
                usuarioNome == null || usuarioNome.isBlank() ? "Sistema" : usuarioNome);
    }

    public static LocalDate tentarLerDataFlexivel(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String s = valor.trim();
        try {
            return LocalDate.parse(s, DATA_ISO_ESTRITA);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public static LocalDate lerDataEstritaIso(String valor) {
        try {
            return LocalDate.parse(valor, DATA_ISO_ESTRITA);
        } catch (DateTimeParseException ex) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Data de validade inválida.");
        }
    }

    private static BigDecimal lerDecimalPontoSemSinal(String valor) {
        if (valor == null || !DECIMAL_PONTO_SEM_SINAL.matcher(valor).matches()) {
            return null;
        }
        try {
            return new BigDecimal(valor);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal maximoUm(BigDecimal valor) {
        return valor == null || valor.compareTo(BigDecimal.ONE) < 0 ? BigDecimal.ONE : valor;
    }

    private static String limparOuVazio(String valor) {
        return valor == null ? "" : valor.trim();
    }

    private static String limparOuNulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private static String padraoTexto(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor.trim();
    }

    private static String padraoMaiusculo(String valor, String padrao) {
        return valor == null || valor.isBlank() ? padrao : valor.trim().toUpperCase(Locale.ROOT);
    }

    private static void setNullableNString(PreparedStatement ps, int idx, String valor) throws SQLException {
        if (valor == null) ps.setNull(idx, Types.NVARCHAR); else ps.setNString(idx, valor);
    }

    private static void setNullableDecimal(PreparedStatement ps, int idx, BigDecimal valor) throws SQLException {
        if (valor == null) ps.setNull(idx, Types.DECIMAL); else ps.setBigDecimal(idx, valor);
    }

    private static String lerTexto(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? "" : s.trim();
    }

    private static String lerTextoOuNulo(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : s.trim();
    }

    private static BigDecimal lerDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal d = rs.getBigDecimal(col);
        return d == null ? BigDecimal.ZERO : d;
    }

    private static void falhar(String mensagem) {
        throw new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
