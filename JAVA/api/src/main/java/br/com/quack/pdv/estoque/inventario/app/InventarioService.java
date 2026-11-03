package br.com.quack.pdv.estoque.inventario.app;

import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inventario de estoque (porta do InventarioAB do .NET): abrir por escopo, contar, anular, recontar, aplicar e
 * cancelar. O acerto usa a diferenca no momento da contagem somada ao saldo atual e passa pelo registrador unico.
 */
@Service
public class InventarioService {

    private static final Logger LOG = LoggerFactory.getLogger(InventarioService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String EM_CONTAGEM = "em_contagem";
    public static final String APLICADO = "aplicado";
    public static final String CANCELADO = "cancelado";

    private static final String SQL_STATUS_LOCK =
            "SELECT Status FROM Inventarios WITH (UPDLOCK, ROWLOCK) WHERE Id = ? AND CompanyId = ?";
    private static final String SQL_CABECALHO_LOCK =
            "SELECT Numero, Status FROM Inventarios WITH (UPDLOCK, ROWLOCK) WHERE Id = ? AND CompanyId = ?";
    private static final String COL_PRODUTOS =
            "SELECT p.Id FROM Produtos p WHERE p.CompanyId = ?";

    private record Cabecalho(int numero, String status) {}

    private record Ajustes(BigDecimal sobra, BigDecimal falta, int ajustados, List<Map.Entry<String, BigDecimal>> lotes) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final InventarioConsultas consultas;
    private final RegistradorEstoque registrador;
    private final LoteOperacoes lotes;
    private final AuditoriaService auditoria;

    public InventarioService(
            JdbcTemplate jdbc,
            PlatformTransactionManager tm,
            InventarioConsultas consultas,
            RegistradorEstoque registrador,
            LoteOperacoes lotes,
            AuditoriaService auditoria) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.consultas = consultas;
        this.registrador = registrador;
        this.lotes = lotes;
        this.auditoria = auditoria;
    }

    // ------------------------------------------------------------------ abertura

    public InventarioResultado abrir(
            String companyId, InventarioAtor ator, String descricao, String escopoBruto,
            List<String> categoriaIds, List<String> produtoIds) {
        String escopo = (escopoBruto == null ? "" : escopoBruto).trim().toLowerCase(Locale.ROOT);
        if (!escopo.equals("loja") && !escopo.equals("categorias") && !escopo.equals("produtos")) {
            return InventarioResultado.falha("ESCOPO_INVALIDO", "Escopo deve ser 'loja', 'categorias' ou 'produtos'.");
        }
        String desc = corta((descricao == null ? "" : descricao).trim(), 200);
        if (desc.isEmpty()) desc = escopo.equals("loja") ? "Inventário geral" : "Inventário por " + escopo;
        if (escopo.equals("categorias") && vazia(categoriaIds)) {
            return InventarioResultado.falha("ESCOPO_VAZIO", "Informe ao menos uma categoria.");
        }
        if (escopo.equals("produtos") && vazia(produtoIds)) {
            return InventarioResultado.falha("ESCOPO_VAZIO", "Informe ao menos um produto.");
        }
        final String descricaoFinal = desc;
        List<String> escopoIds = escopo.equals("categorias") ? categoriaIds : produtoIds;
        return tx.execute(s -> abrirNaTransacao(companyId, ator, descricaoFinal, escopo, escopoIds));
    }

    private InventarioResultado abrirNaTransacao(
            String companyId, InventarioAtor ator, String descricao, String escopo, List<String> escopoIds) {
        if (travar("inventario:" + companyId) < 0) {
            return InventarioResultado.falha("OCUPADO", "Outro inventário está sendo aberto. Tente novamente.");
        }
        String json = escopo.equals("loja") ? null : paraJson(escopoIds);
        List<String> produtos = produtosDoEscopo(companyId, escopo, json);
        if (produtos.isEmpty()) {
            return InventarioResultado.falha("ESCOPO_VAZIO", "Nenhum produto encontrado no escopo informado.");
        }
        String idsJson = paraJson(produtos);
        List<Integer> abertos = jdbc.query("""
                SELECT TOP 1 i.Numero FROM Inventarios i
                WHERE i.CompanyId = ? AND i.Status = N'em_contagem'
                  AND (i.Escopo = N'loja' OR ? = 1
                       OR EXISTS (SELECT 1 FROM InventarioItens it WHERE it.InventarioId = i.Id
                                  AND it.ProdutoId IN (SELECT value FROM OPENJSON(?))))
                ORDER BY i.Numero
                """, (rs, n) -> rs.getInt(1), companyId, escopo.equals("loja") ? 1 : 0, idsJson);
        if (!abertos.isEmpty()) {
            Map<String, Object> detalhe = new LinkedHashMap<>();
            detalhe.put("numeroAberto", abertos.getFirst());
            return InventarioResultado.falha("ESCOPO_SOBREPOSTO",
                    "Já existe o inventário #" + abertos.getFirst() + " em contagem que se sobrepõe a este escopo.",
                    detalhe);
        }
        Integer numero = jdbc.queryForObject(
                "SELECT ISNULL(MAX(Numero), 0) + 1 FROM Inventarios WHERE CompanyId = ?", Integer.class, companyId);
        String id = "inv-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO Inventarios (Id, CompanyId, Numero, Descricao, Escopo, EscopoJson, Status, AbertoPorId, AbertoPorNome)
                VALUES (?, ?, ?, ?, ?, ?, N'em_contagem', ?, ?)
                """, id, companyId, numero, descricao, escopo, json, corta(ator.id(), 40), corta(ator.nome(), 180));
        jdbc.update("""
                INSERT INTO InventarioItens (InventarioId, ProdutoId, CompanyId, Codigo, Nome, Unidade, Custo)
                SELECT ?, p.Id, ?, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductUnitPrice
                FROM Produtos p WHERE p.CompanyId = ? AND p.Id IN (SELECT value FROM OPENJSON(?))
                """, id, companyId, companyId, idsJson);
        String texto = "Inventário #" + numero + " aberto (" + escopo + ", " + produtos.size() + " produtos): " + descricao;
        evento(id, companyId, "aberto", ator, null, null, texto);
        auditoria.registrar(companyId, ator.id(), ator.nome(), "InventarioAberto", texto, "Inventarios", id, ator.ip());

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("id", id);
        dados.put("numero", numero);
        dados.put("status", EM_CONTAGEM);
        dados.put("itens", produtos.size());
        return InventarioResultado.ok(dados, "Inventário aberto.");
    }

    private List<String> produtosDoEscopo(String companyId, String escopo, String json) {
        return switch (escopo) {
            case "loja" -> jdbc.query(COL_PRODUTOS, (rs, n) -> rs.getString(1), companyId);
            case "categorias" -> jdbc.query("""
                    SELECT p.Id FROM Produtos p
                    LEFT JOIN Categorias c ON c.Id = p.CategoriaId
                    WHERE p.CompanyId = ? AND (p.CategoriaId IN (SELECT value FROM OPENJSON(?))
                                            OR c.CategoriaPaiId IN (SELECT value FROM OPENJSON(?)))
                    """, (rs, n) -> rs.getString(1), companyId, json, json);
            default -> jdbc.query(COL_PRODUTOS + " AND p.Id IN (SELECT value FROM OPENJSON(?))",
                    (rs, n) -> rs.getString(1), companyId, json);
        };
    }

    // ------------------------------------------------------------------ contagem

    public InventarioResultado contar(
            String companyId, String inventarioId, InventarioAtor ator, String produtoId, String codigo,
            BigDecimal quantidade, String local) {
        if (quantidade.signum() < 0) {
            return InventarioResultado.falha("QUANTIDADE_INVALIDA", "A quantidade contada não pode ser negativa.");
        }
        return tx.execute(s -> contarNaTransacao(companyId, inventarioId, ator, produtoId, codigo, quantidade, local));
    }

    private InventarioResultado contarNaTransacao(
            String companyId, String inventarioId, InventarioAtor ator, String produtoIdBruto, String codigo,
            BigDecimal quantidade, String local) {
        String status = statusComLock(companyId, inventarioId);
        if (status == null) return InventarioResultado.falha("NAO_ENCONTRADO", "Inventário não encontrado.");
        if (!status.equals(EM_CONTAGEM)) {
            return InventarioResultado.falha("INVENTARIO_ENCERRADO",
                    "O inventário está " + rotulo(status) + " e não aceita contagens.");
        }
        String produtoId = produtoIdBruto;
        if (produtoId == null || produtoId.isBlank()) {
            if (codigo == null || codigo.isBlank()) {
                return InventarioResultado.falha("PRODUTO_OBRIGATORIO", "Informe o produto ou o código.");
            }
            String chave = codigo.trim();
            List<String> achados = jdbc.query("""
                    SELECT TOP 1 Id FROM Produtos WHERE CompanyId = ? AND (ProductCode = ? OR (Gtin = ? AND Gtin <> N'SEM GTIN'))
                    """, (rs, n) -> rs.getString(1), companyId, chave, chave);
            if (achados.isEmpty()) {
                return InventarioResultado.falha("CODIGO_DESCONHECIDO", "Nenhum produto com o código '" + chave + "'.");
            }
            produtoId = achados.getFirst();
        }
        List<String[]> itens = jdbc.query(
                "SELECT Nome, Unidade, Codigo FROM InventarioItens WITH (UPDLOCK, ROWLOCK) WHERE InventarioId = ? AND ProdutoId = ?",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, inventarioId, produtoId);
        if (itens.isEmpty()) {
            return InventarioResultado.falha("FORA_DO_ESCOPO", "O produto está fora do escopo deste inventário.");
        }
        String[] item = itens.getFirst();
        if (item[1].trim().equalsIgnoreCase("UN") && quantidade.stripTrailingZeros().scale() > 0) {
            return InventarioResultado.falha("QUANTIDADE_INVALIDA", "Produto em UN não aceita quantidade fracionada.");
        }
        BigDecimal saldo = jdbc.queryForObject(
                "SELECT ProductQnt FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = ? AND CompanyId = ?",
                BigDecimal.class, produtoId, companyId);
        String contagemId = "ic-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO InventarioContagens (Id, InventarioId, CompanyId, ProdutoId, Quantidade, Local, UsuarioId, UsuarioNome, Terminal, Ip, SaldoSistema)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, contagemId, inventarioId, companyId, produtoId, quantidade, nuloSeVazio(corta(local, 80)),
                corta(ator.id(), 40), corta(ator.nome(), 180), nuloSeVazio(corta(ator.terminal(), 80)),
                nuloSeVazio(corta(ator.ip(), 64)), saldo);
        jdbc.update("""
                UPDATE InventarioItens
                   SET Contado = ISNULL(Contado, 0) + ?,
                       SaldoNa1aContagem = ISNULL(SaldoNa1aContagem, ?),
                       PrimeiraContagemEm = ISNULL(PrimeiraContagemEm, SYSDATETIMEOFFSET()),
                       UltimaContagemEm = SYSDATETIMEOFFSET(),
                       Recontar = 0
                 WHERE InventarioId = ? AND ProdutoId = ?
                """, quantidade, saldo, inventarioId, produtoId);
        BigDecimal total = jdbc.queryForObject(
                "SELECT Contado FROM InventarioItens WHERE InventarioId = ? AND ProdutoId = ?",
                BigDecimal.class, inventarioId, produtoId);

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("contagemId", contagemId);
        dados.put("produtoId", produtoId);
        dados.put("codigo", item[2]);
        dados.put("nome", item[0]);
        dados.put("unidade", item[1]);
        dados.put("quantidade", quantidade);
        dados.put("totalContado", total);
        dados.put("local", local);
        return InventarioResultado.ok(dados, "Contagem registrada.");
    }

    public InventarioResultado anularContagem(
            String companyId, String inventarioId, String contagemId, InventarioAtor ator, String motivo,
            boolean apenasPropria) {
        if (motivo == null || motivo.isBlank()) {
            return InventarioResultado.falha("MOTIVO_OBRIGATORIO", "Informe o motivo da anulação.");
        }
        return tx.execute(s -> anularNaTransacao(companyId, inventarioId, contagemId, ator, motivo, apenasPropria));
    }

    private InventarioResultado anularNaTransacao(
            String companyId, String inventarioId, String contagemId, InventarioAtor ator, String motivo,
            boolean apenasPropria) {
        String status = statusComLock(companyId, inventarioId);
        if (status == null) return InventarioResultado.falha("NAO_ENCONTRADO", "Inventário não encontrado.");
        if (!status.equals(EM_CONTAGEM)) {
            return InventarioResultado.falha("INVENTARIO_ENCERRADO", "O inventário está " + rotulo(status) + ".");
        }
        List<Object[]> lidas = jdbc.query("""
                SELECT ProdutoId, UsuarioId, CASE WHEN AnuladaEm IS NULL THEN 0 ELSE 1 END
                FROM InventarioContagens WHERE Id = ? AND InventarioId = ? AND CompanyId = ?
                """, (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3)},
                contagemId, inventarioId, companyId);
        if (lidas.isEmpty()) return InventarioResultado.falha("NAO_ENCONTRADO", "Contagem não encontrada.");
        String produtoId = (String) lidas.getFirst()[0];
        String donoId = (String) lidas.getFirst()[1];
        if ((Integer) lidas.getFirst()[2] == 1) {
            return InventarioResultado.falha("JA_ANULADA", "Esta contagem já foi anulada.");
        }
        if (apenasPropria && !donoId.equals(ator.id())) {
            return InventarioResultado.falha("SEM_PERMISSAO", "Você só pode anular os seus próprios lançamentos.");
        }
        jdbc.update("""
                UPDATE InventarioContagens SET AnuladaPorId = ?, AnuladaPorNome = ?, AnuladaEm = SYSDATETIMEOFFSET(), AnuladaMotivo = ?
                WHERE Id = ? AND AnuladaEm IS NULL
                """, corta(ator.id(), 40), corta(ator.nome(), 180), corta(motivo.trim(), 300), contagemId);
        recalcularItem(inventarioId, produtoId);

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("contagemId", contagemId);
        dados.put("produtoId", produtoId);
        return InventarioResultado.ok(dados, "Contagem anulada.");
    }

    /** Refaz Contado/SaldoNa1aContagem a partir das contagens validas (NULL se nao sobrou nenhuma). */
    private void recalcularItem(String inventarioId, String produtoId) {
        String validas = "FROM InventarioContagens WHERE InventarioId = ? AND ProdutoId = ? AND AnuladaEm IS NULL";
        jdbc.update("""
                UPDATE InventarioItens SET
                   Contado = (SELECT SUM(Quantidade) %1$s),
                   SaldoNa1aContagem = (SELECT TOP 1 SaldoSistema %1$s ORDER BY CriadoEm),
                   PrimeiraContagemEm = (SELECT TOP 1 CriadoEm %1$s ORDER BY CriadoEm),
                   UltimaContagemEm = (SELECT MAX(CriadoEm) %1$s)
                 WHERE InventarioId = ? AND ProdutoId = ?
                """.formatted(validas),
                inventarioId, produtoId, inventarioId, produtoId, inventarioId, produtoId, inventarioId, produtoId,
                inventarioId, produtoId);
    }

    public InventarioResultado recontar(
            String companyId, String inventarioId, InventarioAtor ator, List<String> produtoIds) {
        if (vazia(produtoIds)) {
            return InventarioResultado.falha("PRODUTO_OBRIGATORIO", "Informe os produtos a recontar.");
        }
        return tx.execute(s -> recontarNaTransacao(companyId, inventarioId, ator, produtoIds));
    }

    private InventarioResultado recontarNaTransacao(
            String companyId, String inventarioId, InventarioAtor ator, List<String> produtoIds) {
        String status = statusComLock(companyId, inventarioId);
        if (status == null) return InventarioResultado.falha("NAO_ENCONTRADO", "Inventário não encontrado.");
        if (!status.equals(EM_CONTAGEM)) {
            return InventarioResultado.falha("INVENTARIO_ENCERRADO", "O inventário está " + rotulo(status) + ".");
        }
        String ids = paraJson(produtoIds);
        jdbc.update("""
                UPDATE InventarioContagens
                   SET AnuladaPorId = ?, AnuladaPorNome = ?, AnuladaEm = SYSDATETIMEOFFSET(), AnuladaMotivo = N'Recontagem solicitada'
                 WHERE InventarioId = ? AND AnuladaEm IS NULL AND ProdutoId IN (SELECT value FROM OPENJSON(?))
                """, corta(ator.id(), 40), corta(ator.nome(), 180), inventarioId, ids);
        int n = jdbc.update("""
                UPDATE InventarioItens
                   SET Recontar = 1, Contado = NULL, SaldoNa1aContagem = NULL, PrimeiraContagemEm = NULL, UltimaContagemEm = NULL
                 WHERE InventarioId = ? AND ProdutoId IN (SELECT value FROM OPENJSON(?))
                """, inventarioId, ids);
        if (n == 0) return InventarioResultado.falha("FORA_DO_ESCOPO", "Nenhum dos produtos pertence ao inventário.");
        evento(inventarioId, companyId, "recontagem", ator, null, null,
                "Recontagem pedida para " + n + " produto(s): "
                        + String.join(", ", produtoIds.stream().limit(10).toList()));
        return InventarioResultado.ok(Map.of("produtos", n), "Recontagem solicitada.");
    }

    // ------------------------------------------------------------------ aplicacao

    public InventarioResultado aplicar(
            String companyId, String inventarioId, InventarioAtor ator, String supervisorId, String supervisorNome,
            String justificativa, boolean zerarNaoContados) {
        List<Map.Entry<String, BigDecimal>> paraLotes = new ArrayList<>();
        InventarioResultado resultado = tx.execute(s -> aplicarNaTransacao(
                companyId, inventarioId, ator, supervisorId, supervisorNome, justificativa, zerarNaoContados, paraLotes));
        if (resultado != null && resultado.sucesso()) {
            refletirNosLotes(companyId, ator, paraLotes);
        }
        return resultado;
    }

    private InventarioResultado aplicarNaTransacao(
            String companyId, String inventarioId, InventarioAtor ator, String supervisorId, String supervisorNome,
            String justificativa, boolean zerar, List<Map.Entry<String, BigDecimal>> paraLotes) {
        Cabecalho cab = cabecalhoComLock(companyId, inventarioId);
        if (cab == null) return InventarioResultado.falha("NAO_ENCONTRADO", "Inventário não encontrado.");
        if (!cab.status().equals(EM_CONTAGEM)) {
            boolean aplicado = cab.status().equals(APLICADO);
            return InventarioResultado.falha(aplicado ? "JA_APLICADO" : "INVENTARIO_ENCERRADO",
                    aplicado ? "Este inventário já foi aplicado." : "Este inventário foi cancelado e não pode ser aplicado.");
        }
        String just = justificativa.trim();
        String motivo = corta("Inventário #" + cab.numero() + ": " + just, 500);
        MovimentoOrigem origem = new MovimentoOrigem("inventario", inventarioId, motivo, corta(supervisorId, 36),
                corta(supervisorNome + " (inventário)", 200));
        Ajustes aj = ajustarItens(companyId, inventarioId, zerar, origem);
        paraLotes.addAll(aj.lotes());
        BigDecimal liquido = aj.sobra().add(aj.falta());

        jdbc.update("""
                UPDATE Inventarios
                   SET Status = N'aplicado', AplicadoPorId = ?, AplicadoPorNome = ?, AplicadoEm = SYSDATETIMEOFFSET(),
                       SupervisorId = ?, SupervisorNome = ?, Justificativa = ?, ZerarNaoContados = ?,
                       TotalSobra = ?, TotalFalta = ?, TotalLiquido = ?
                 WHERE Id = ?
                """, corta(ator.id(), 40), corta(ator.nome(), 180), corta(supervisorId, 40), corta(supervisorNome, 180),
                corta(just, 500), zerar, aj.sobra(), aj.falta(), liquido, inventarioId);

        String desc = "Inventário #" + cab.numero() + " aplicado por " + supervisorNome + ": " + aj.ajustados()
                + " produto(s) ajustado(s), sobra " + n2(aj.sobra()) + ", falta " + n2(aj.falta()) + ", líquido "
                + n2(liquido) + ". " + just;
        evento(inventarioId, companyId, "aplicado", ator, supervisorId, supervisorNome, desc);
        auditoria.registrar(companyId, ator.id(), ator.nome(), "InventarioAplicado", desc, "Inventarios",
                inventarioId, ator.ip());

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("id", inventarioId);
        dados.put("numero", cab.numero());
        dados.put("ajustados", aj.ajustados());
        dados.put("totalSobra", aj.sobra());
        dados.put("totalFalta", aj.falta());
        dados.put("totalLiquido", liquido);
        return InventarioResultado.ok(dados, "Inventário aplicado.");
    }

    private Ajustes ajustarItens(String companyId, String inventarioId, boolean zerar, MovimentoOrigem origem) {
        BigDecimal sobra = BigDecimal.ZERO;
        BigDecimal falta = BigDecimal.ZERO;
        int ajustados = 0;
        List<Map.Entry<String, BigDecimal>> paraLotes = new ArrayList<>();
        for (ItemLinha item : consultas.lerItens(companyId, inventarioId)) {
            if (item.recontar()) continue;
            if (item.contado() == null && !zerar) continue;
            List<BigDecimal[]> lido = jdbc.query(
                    "SELECT ProductQnt, ProductUnitPrice FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = ? AND CompanyId = ?",
                    (rs, n) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, item.produtoId(), companyId);
            if (lido.isEmpty()) continue;
            BigDecimal atual = lido.getFirst()[0];
            BigDecimal custoAtual = lido.getFirst()[1];

            BigDecimal delta = item.contado() == null
                    ? atual.negate()
                    : item.contado().subtract(item.saldoNa1aContagem() == null ? BigDecimal.ZERO : item.saldoNa1aContagem());
            BigDecimal novo = atual.add(delta);
            BigDecimal piso = BigDecimal.ZERO;
            if (novo.signum() < 0) {
                piso = novo.negate();
                novo = BigDecimal.ZERO;
            }
            BigDecimal deltaReal = novo.subtract(atual);
            BigDecimal valor = deltaReal.multiply(item.custo()).setScale(2, RoundingMode.HALF_EVEN);
            if (deltaReal.signum() != 0) {
                registrador.aplicar(companyId, item.produtoId(), deltaReal, EstoqueTipos.INVENTARIO, custoAtual, origem);
                paraLotes.add(Map.entry(item.produtoId(), deltaReal));
                ajustados++;
            }
            if (valor.signum() > 0) sobra = sobra.add(valor); else falta = falta.add(valor);
            jdbc.update("""
                    UPDATE InventarioItens
                       SET SaldoAntes = ?, Delta = ?, SaldoDepois = ?, ValorDelta = ?, PisoAplicado = ?,
                           Contado = ISNULL(Contado, 0)
                     WHERE InventarioId = ? AND ProdutoId = ?
                    """, atual, deltaReal, novo, valor, piso.signum() > 0 ? piso : null, inventarioId, item.produtoId());
        }
        return new Ajustes(sobra, falta, ajustados, paraLotes);
    }

    /** Lotes (validade): rotinas seguras com transacao propria, depois do acerto de saldo. */
    private void refletirNosLotes(String companyId, InventarioAtor ator, List<Map.Entry<String, BigDecimal>> ajustes) {
        for (Map.Entry<String, BigDecimal> a : ajustes) {
            try {
                if (a.getValue().signum() > 0) {
                    lotes.registrarEntradaSegura(companyId, a.getKey(), null, a.getValue(), null, "inventario", ator.nome());
                } else {
                    lotes.consumirAjusteSeguro(companyId, a.getKey(), a.getValue().negate());
                }
            } catch (RuntimeException ex) {
                LOG.error("Falha ao refletir o inventário nos lotes do produto {}.", a.getKey(), ex);
            }
        }
    }

    public InventarioResultado cancelar(String companyId, String inventarioId, InventarioAtor ator, String motivo) {
        if (motivo == null || motivo.isBlank() || motivo.trim().length() < 3) {
            return InventarioResultado.falha("MOTIVO_OBRIGATORIO", "Informe o motivo do cancelamento.");
        }
        return tx.execute(s -> cancelarNaTransacao(companyId, inventarioId, ator, motivo.trim()));
    }

    private InventarioResultado cancelarNaTransacao(
            String companyId, String inventarioId, InventarioAtor ator, String motivo) {
        Cabecalho cab = cabecalhoComLock(companyId, inventarioId);
        if (cab == null) return InventarioResultado.falha("NAO_ENCONTRADO", "Inventário não encontrado.");
        if (!cab.status().equals(EM_CONTAGEM)) {
            return InventarioResultado.falha("INVENTARIO_ENCERRADO", "O inventário já está " + rotulo(cab.status()) + ".");
        }
        jdbc.update("""
                UPDATE Inventarios SET Status = N'cancelado', CanceladoPorId = ?, CanceladoPorNome = ?,
                       CanceladoEm = SYSDATETIMEOFFSET(), MotivoCancelamento = ? WHERE Id = ?
                """, corta(ator.id(), 40), corta(ator.nome(), 180), corta(motivo, 500), inventarioId);
        String desc = "Inventário #" + cab.numero() + " cancelado: " + motivo;
        evento(inventarioId, companyId, "cancelado", ator, null, null, desc);
        auditoria.registrar(companyId, ator.id(), ator.nome(), "InventarioCancelado", desc, "Inventarios",
                inventarioId, ator.ip());
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("id", inventarioId);
        dados.put("numero", cab.numero());
        return InventarioResultado.ok(dados, "Inventário cancelado.");
    }

    /** Tentativa de aplicacao negada (senha errada etc.): fora de transacao, nunca lanca. */
    public void registrarNegada(
            String companyId, String inventarioId, InventarioAtor ator, String supervisorId, String motivo) {
        try {
            String supervisorNome = null;
            if (supervisorId != null && !supervisorId.isBlank()) {
                List<String> nomes = jdbc.query("SELECT Name FROM Usuarios WHERE CompanyId = ? AND Id = ?",
                        (rs, n) -> rs.getString(1), companyId, supervisorId.trim());
                supervisorNome = nomes.isEmpty() ? null : nomes.getFirst();
            }
            List<Integer> numeros = jdbc.query("SELECT Numero FROM Inventarios WHERE CompanyId = ? AND Id = ?",
                    (rs, n) -> rs.getInt(1), companyId, inventarioId);
            if (numeros.isEmpty()) return;
            String informado = supervisorNome != null ? supervisorNome : supervisorId;
            String desc = corta("Aplicação do inventário #" + numeros.getFirst() + " negada: " + motivo
                    + (informado == null || informado.isBlank() ? "" : " Gerente informado: " + informado + ".")
                    + (ator.terminal() == null || ator.terminal().isBlank() ? "" : " Terminal: " + ator.terminal() + "."),
                    500);
            evento(inventarioId, companyId, "aplicacao_negada", ator, supervisorId, supervisorNome, desc);
            auditoria.registrar(companyId, ator.id(), ator.nome(), "InventarioAplicacaoNegada", desc, "Inventarios",
                    inventarioId, ator.ip());
        } catch (RuntimeException ex) {
            LOG.error("Tentativa negada de aplicação do inventário {} não registrada.", inventarioId, ex);
        }
    }

    // ------------------------------------------------------------------ helpers

    private String statusComLock(String companyId, String id) {
        List<String> s = jdbc.query(SQL_STATUS_LOCK, (rs, n) -> rs.getString(1), id, companyId);
        return s.isEmpty() ? null : s.getFirst();
    }

    private Cabecalho cabecalhoComLock(String companyId, String id) {
        List<Cabecalho> c = jdbc.query(SQL_CABECALHO_LOCK,
                (rs, n) -> new Cabecalho(rs.getInt(1), rs.getString(2)), id, companyId);
        return c.isEmpty() ? null : c.getFirst();
    }

    private void evento(String inventarioId, String companyId, String tipo, InventarioAtor ator,
                        String supervisorId, String supervisorNome, String descricao) {
        jdbc.update("""
                INSERT INTO InventarioEventos (Id, InventarioId, CompanyId, Tipo, UsuarioId, UsuarioNome, SupervisorId, SupervisorNome, Ip, Terminal, Descricao)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "ie-" + UUID.randomUUID().toString().replace("-", ""), inventarioId, companyId, tipo,
                corta(ator.id(), 40), corta(ator.nome(), 180), nuloSeVazio(corta(supervisorId, 40)),
                nuloSeVazio(corta(supervisorNome, 180)), nuloSeVazio(corta(ator.ip(), 64)),
                nuloSeVazio(corta(ator.terminal(), 80)), nuloSeVazio(corta(descricao, 500)));
    }

    /** sp_getapplock exclusivo na transacao (10 s); devolve o codigo (menor que 0 = nao obteve). */
    private int travar(String recurso) {
        Integer codigo = jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (var inicio = con.createStatement()) {
                inicio.execute("IF @@TRANCOUNT=0 BEGIN TRANSACTION;");
            }
            try (var stmt = con.prepareCall("{? = call sys.sp_getapplock(?, ?, ?, ?)}")) {
                stmt.registerOutParameter(1, java.sql.Types.INTEGER);
                stmt.setNString(2, recurso);
                stmt.setString(3, "Exclusive");
                stmt.setString(4, "Transaction");
                stmt.setInt(5, 10000);
                stmt.execute();
                return stmt.getInt(1);
            }
        });
        return codigo == null ? -1 : codigo;
    }

    private static String paraJson(List<String> ids) {
        try {
            return JSON.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean vazia(List<String> l) {
        return l == null || l.isEmpty();
    }

    private static String rotulo(String status) {
        return switch (status) {
            case APLICADO -> "aplicado";
            case CANCELADO -> "cancelado";
            default -> "em contagem";
        };
    }

    private static String nuloSeVazio(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static String corta(String v, int max) {
        if (v == null) return "";
        return v.length() <= max ? v : v.substring(0, max);
    }

    /** Equivalente ao formato "N2" invariante do .NET (milhar com virgula, ponto decimal). */
    private static String n2(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
    }
}
