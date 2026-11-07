package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.produto.importacao.PlanoImportacao.EstadoBanco;
import br.com.quack.pdv.produto.importacao.PlanoImportacao.ProdutoExistente;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaExistente;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Importacao de produtos por planilha em duas etapas (I1): {@code simular} le, valida e devolve o relatorio sem gravar;
 * {@code confirmar} refaz o mesmo plano e o grava numa transacao. O plano nao fica guardado entre as chamadas.
 */
@Service
public class ImportacaoProdutosService {

    private final JdbcTemplate jdbc;
    private final GravadorImportacao gravador;
    private final Clock clock;

    public ImportacaoProdutosService(JdbcTemplate jdbc, GravadorImportacao gravador, Clock clock) {
        this.jdbc = jdbc;
        this.gravador = gravador;
        this.clock = clock;
    }

    public RelatorioImportacao simular(String companyId, byte[] arquivo) {
        return planejar(companyId, arquivo).relatorio();
    }

    public RelatorioImportacao confirmar(String companyId, byte[] arquivo, String usuarioId, String usuarioNome) {
        PlanoImportacao plano = planejar(companyId, arquivo);
        String hash = plano.relatorio().hashArquivo();
        gravador.gravar(companyId, plano, hash, usuarioId, usuarioNome);
        return plano.relatorio().comGravadoEm(Instant.now(clock).toString());
    }

    PlanoImportacao planejar(String companyId, byte[] arquivo) {
        LeitorCsvProdutos.Planilha planilha = LeitorCsvProdutos.ler(arquivo);
        return PlanoImportacao.montar(planilha.linhas(), carregarEstado(companyId), sha256(arquivo));
    }

    private EstadoBanco carregarEstado(String companyId) {
        List<ProdutoExistente> produtos = jdbc.query(
                "SELECT Id, ProductCode, Gtin, CategoriaId FROM Produtos WHERE CompanyId = ?",
                (rs, n) -> new ProdutoExistente(
                        rs.getString(1).trim(), rs.getString(2), rs.getString(3), rs.getString(4)),
                companyId);
        List<CategoriaExistente> categorias = jdbc.query(
                "SELECT Id, Nome, CategoriaPaiId, Ordem FROM Categorias WHERE CompanyId = ?",
                (rs, n) -> new CategoriaExistente(
                        rs.getString(1).trim(), rs.getString(2), trimOuNulo(rs.getString(3)), rs.getInt(4)),
                companyId);
        Set<String> codigos = new HashSet<>(jdbc.queryForList(
                "SELECT ProductCode FROM ImportacoesProduto WHERE CompanyId = ?", String.class, companyId));
        return new EstadoBanco(produtos, categorias, codigos);
    }

    private static String trimOuNulo(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
