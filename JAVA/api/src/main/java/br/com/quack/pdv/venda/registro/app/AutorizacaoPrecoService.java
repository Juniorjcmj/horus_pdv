package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.ValidadorSupervisor;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Autorizacao de gerente para vender um produto por preco diferente do cadastrado (porta de AutorizacaoPrecoAB):
 * criada com a senha do gerente, de uso unico, valida por 2 horas e consumida na transacao da propria venda.
 */
@Service
public class AutorizacaoPrecoService {

    private static final Duration VALIDADE = Duration.ofHours(2);
    private static final BigDecimal TOLERANCIA = new BigDecimal("0.005");

    /** Item da venda visto pela conferencia de preco. */
    public record ItemPreco(String productCode, String productName, BigDecimal unitPrice, String autorizacaoPrecoId) {}

    /** Autorizacoes consumidas e itens offline vendidos abaixo do cadastro sem autorizacao. */
    public record ValidacaoPrecos(List<String> usadas, List<String> divergencias) {}

    /** Autorizacao criada. */
    public record Criada(String id, OffsetDateTime expiraEm) {}

    private final JdbcTemplate jdbc;
    private static final Logger LOG = LoggerFactory.getLogger(AutorizacaoPrecoService.class);

    private final ValidadorSupervisor validador;
    private final AuditoriaService auditoria;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AutorizacaoPrecoService(JdbcTemplate jdbc, Clock clock, ValidadorSupervisor validador,
                                   AuditoriaService auditoria, PlatformTransactionManager transactionManager) {
        this.validador = validador;
        this.auditoria = auditoria;
        this.tx = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Criada criar(String companyId, String productCode, BigDecimal precoTabela, BigDecimal precoNovo,
                        String supervisorId, String supervisorNome, String operadorId, String operadorNome,
                        String motivo) {
        String id = "ap-" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiraEm = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).plus(VALIDADE);
        jdbc.update("""
                INSERT INTO AutorizacoesPreco
                    (Id, CompanyId, ProductCode, PrecoTabela, PrecoNovo, SupervisorId, SupervisorNome,
                     OperadorId, OperadorNome, Motivo, ExpiraEm)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """, id, companyId, productCode, precoTabela, precoNovo, supervisorId, supervisorNome,
                vazioParaNulo(operadorId), vazioParaNulo(operadorNome),
                motivo == null || motivo.isBlank() ? null : motivo.trim(), expiraEm);
        return new Criada(id, expiraEm);
    }

    /** Confere os precos da venda na transacao dela (ValidarPrecosDaVendaAsync). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ValidacaoPrecos validarPrecosDaVenda(String companyId, List<ItemPreco> itens, boolean vendaOffline) {
        List<String> usadas = new ArrayList<>();
        List<String> divergencias = new ArrayList<>();
        for (ItemPreco item : itens) {
            if (item.unitPrice().signum() <= 0) continue;
            String codigo = item.productCode().trim();
            Optional<BigDecimal> tabela = precoTabela(companyId, codigo);
            if (tabela.isEmpty()) continue;
            BigDecimal preco = tabela.get();
            if (item.unitPrice().subtract(preco).abs().compareTo(TOLERANCIA) <= 0) continue;
            String nome = item.productName() == null || item.productName().isBlank() ? codigo
                    : item.productName().trim();
            if (item.autorizacaoPrecoId() != null && !item.autorizacaoPrecoId().isBlank()) {
                String autorizacaoId = item.autorizacaoPrecoId().trim();
                consumir(companyId, autorizacaoId, codigo, item.unitPrice(), nome, vendaOffline);
                usadas.add(autorizacaoId);
                continue;
            }
            boolean abaixo = item.unitPrice().compareTo(preco) < 0;
            if (!vendaOffline && abaixo) {
                throw new IllegalArgumentException("O preço de \"" + nome + "\" foi alterado (R$ "
                        + SaleMoney.n2(item.unitPrice()) + " em vez de R$ " + SaleMoney.n2(preco)
                        + ") sem autorização de gerente. Peça a senha do gerente para alterar o preço ou atualize "
                        + "o catálogo do caixa.");
            }
            if (vendaOffline && abaixo) {
                divergencias.add(nome + " (" + codigo + "): R$ " + Dinheiro.formatarSemSimbolo(item.unitPrice())
                        + " em vez de R$ " + Dinheiro.formatarSemSimbolo(preco));
            }
        }
        return new ValidacaoPrecos(usadas, divergencias);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void vincularVenda(String companyId, List<String> autorizacaoIds, String vendaId) {
        for (String id : autorizacaoIds) {
            jdbc.update("UPDATE AutorizacoesPreco SET VendaId = ? WHERE Id = ? AND CompanyId = ?;",
                    vendaId, id, companyId);
        }
    }


    /** Dados do produto para a autorizacao. */
    private record ProdutoPreco(String code, String name, BigDecimal salePrice, BigDecimal unitPrice) {}

    /** Resposta de autorizar-preco (Data do .NET). */
    public record Autorizada(String autorizacaoId, String supervisorNome, BigDecimal precoTabela,
                             BigDecimal precoNovo, OffsetDateTime expiraEm) {}

    /** AutorizarPreco do controller: valida, confere a senha do gerente, cria a autorizacao e audita. */
    public Autorizada autorizar(br.com.quack.pdv.security.UsuarioAutenticado usuario,
                                br.com.quack.pdv.venda.registro.web.VendaRequests.AutorizarPreco req) {
        if (req.productCode == null || req.productCode.isBlank()) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Informe o produto.");
        }
        if (req.precoNovo.signum() <= 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "O novo preço deve ser maior que zero.");
        }
        ValidadorSupervisor.Resultado v = validador.validar(req.supervisorId, req.supervisorPassword,
                usuario.companyId());
        if (!v.valido() || v.supervisor() == null) {
            throw new NegocioException(HttpStatus.FORBIDDEN, v.mensagem());
        }
        ProdutoPreco p = jdbc.query("SELECT ProductCode, ProductName, ProductSalePrice, ProductUnitPrice FROM Produtos "
                        + "WHERE CompanyId = ? AND ProductCode = ?;",
                (rs, n) -> new ProdutoPreco(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4)), usuario.companyId(), req.productCode.trim()).stream().findFirst()
                .orElseThrow(() -> new NegocioException(HttpStatus.NOT_FOUND, "Produto não encontrado."));
        BigDecimal precoTabela = p.salePrice().signum() > 0 ? p.salePrice() : p.unitPrice();
        Criada criada = tx.execute(s -> criar(usuario.companyId(), p.code(), precoTabela, req.precoNovo,
                v.supervisor().id(), v.supervisor().name(), usuario.id(), usuario.name(), req.motivo));
        try {
            auditoria.registrar(usuario.companyId(), v.supervisor().id(), v.supervisor().name(), "AlteracaoPreco",
                    v.supervisor().name() + " autorizou alterar o preço de \"" + p.name() + "\" (cód. " + p.code()
                            + ") de " + Dinheiro.formatarSemSimbolo(precoTabela) + " para "
                            + Dinheiro.formatarSemSimbolo(req.precoNovo) + " no caixa de " + usuario.name() + "."
                            + (req.motivo == null || req.motivo.isBlank() ? "" : " Motivo: " + req.motivo.trim()),
                    "Produto", p.code(), null);
        } catch (RuntimeException e) {
            LOG.warn("Falha ao auditar a autorizacao de preco: {}", e.toString());
        }
        return new Autorizada(criada.id(), v.supervisor().name(), precoTabela, req.precoNovo, criada.expiraEm());
    }

    private Optional<BigDecimal> precoTabela(String companyId, String codigo) {
        return jdbc.query("SELECT ProductSalePrice, ProductUnitPrice FROM Produtos WHERE CompanyId = ? "
                        + "AND ProductCode = ?;",
                (rs, n) -> rs.getBigDecimal(1).signum() > 0 ? rs.getBigDecimal(1) : rs.getBigDecimal(2),
                companyId, codigo).stream().findFirst();
    }

    private record Autorizacao(String productCode, BigDecimal precoNovo, OffsetDateTime expiraEm, boolean usada) {}

    private void consumir(String companyId, String id, String productCode, BigDecimal preco, String nomeProduto,
                          boolean vendaOffline) {
        Autorizacao a = jdbc.query("""
                SELECT ProductCode, PrecoNovo, ExpiraEm, UsadoEm
                FROM AutorizacoesPreco WITH (UPDLOCK, ROWLOCK)
                WHERE Id = ? AND CompanyId = ?;
                """, (rs, n) -> new Autorizacao(rs.getString("ProductCode"), rs.getBigDecimal("PrecoNovo"),
                rs.getObject("ExpiraEm", OffsetDateTime.class), rs.getObject("UsadoEm") != null),
                id, companyId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                "Autorização de preço de \"" + nomeProduto + "\" não encontrada."));
        if (!a.productCode().equalsIgnoreCase(productCode)
                || a.precoNovo().subtract(preco).abs().compareTo(TOLERANCIA) > 0) {
            throw new IllegalArgumentException("A autorização de preço apresentada não corresponde ao "
                    + "produto/preço de \"" + nomeProduto + "\".");
        }
        if (a.usada()) {
            throw new IllegalArgumentException("A autorização de preço de \"" + nomeProduto
                    + "\" já foi utilizada em outra venda.");
        }
        if (!vendaOffline && a.expiraEm().toInstant().isBefore(clock.instant())) {
            throw new IllegalArgumentException("A autorização de preço de \"" + nomeProduto
                    + "\" expirou. Peça a senha do gerente novamente.");
        }
        int n = jdbc.update("UPDATE AutorizacoesPreco SET UsadoEm = SYSDATETIMEOFFSET() "
                + "WHERE Id = ? AND CompanyId = ? AND UsadoEm IS NULL;", id, companyId);
        if (n != 1) {
            throw new IllegalArgumentException("A autorização de preço de \"" + nomeProduto
                    + "\" já foi utilizada em outra venda.");
        }
    }

    private static String vazioParaNulo(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
