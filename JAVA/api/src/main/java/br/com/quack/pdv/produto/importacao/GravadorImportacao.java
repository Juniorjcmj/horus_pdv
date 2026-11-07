package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.importacao.PlanoImportacao.Item;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaNova;
import br.com.quack.pdv.produto.produto.app.ProdutoDados;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aplica o plano numa unica transacao (tudo ou nada), na ordem categorias, produtos, movimentacoes (I1/I6).
 * Reaproveita {@link ProdutoService#salvarDados} (mesmo INSERT/UPDATE do cadastro) e o {@link RegistradorEstoque}.
 */
@Component
public class GravadorImportacao {

    /** Tempo maximo da transacao de confirmacao (I2). */
    static final int TIMEOUT_SEGUNDOS = 300;

    private static final String SQL_CATEGORIA =
            "INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (?, ?, ?, ?, ?, 1);";
    private static final String SQL_CONTROLE =
            "INSERT INTO ImportacoesProduto (CompanyId, ProductCode, HashArquivo, QtdInicial) VALUES (?, ?, ?, ?);";

    private final JdbcTemplate jdbc;
    private final ProdutoService produtos;
    private final RegistradorEstoque registrador;
    private final TransactionTemplate tx;

    public GravadorImportacao(
            JdbcTemplate jdbc,
            ProdutoService produtos,
            RegistradorEstoque registrador,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.produtos = produtos;
        this.registrador = registrador;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(TIMEOUT_SEGUNDOS);
    }

    public void gravar(String companyId, PlanoImportacao plano, String hashArquivo, String usuarioId, String usuarioNome) {
        tx.executeWithoutResult(status -> {
            gravarCategorias(companyId, plano.categoriasNovas());
            Map<String, ProdutoDados> atuais = atuais(companyId);
            List<Object[]> controle = new ArrayList<>();
            for (Item item : plano.itens()) {
                gravarItem(companyId, item, atuais, hashArquivo, usuarioId, usuarioNome, controle);
            }
            if (!controle.isEmpty()) {
                jdbc.batchUpdate(SQL_CONTROLE, controle);
            }
        });
    }

    private void gravarCategorias(String companyId, List<CategoriaNova> novas) {
        if (novas.isEmpty()) {
            return;
        }
        List<Object[]> linhas = new ArrayList<>(novas.size());
        for (CategoriaNova c : novas) {
            linhas.add(new Object[] {c.id(), companyId, c.nome(), c.paiId(), c.ordem()});
        }
        jdbc.batchUpdate(SQL_CATEGORIA, linhas);
    }

    private Map<String, ProdutoDados> atuais(String companyId) {
        Map<String, ProdutoDados> mapa = new HashMap<>();
        for (ProdutoDados p : produtos.listarDados(companyId)) {
            mapa.put(PlanoImportacao.chaveCodigo(p.productCode()), p);
        }
        return mapa;
    }

    private void gravarItem(
            String companyId,
            Item item,
            Map<String, ProdutoDados> atuais,
            String hashArquivo,
            String usuarioId,
            String usuarioNome,
            List<Object[]> controle) {
        ProdutoImportado p = item.produto();
        String id = item.novo() ? "pr-" + UUID.randomUUID().toString().replace("-", "") : item.produtoIdExistente();
        ProdutoDados atual = item.novo() ? null : atuais.get(PlanoImportacao.chaveCodigo(p.codigo()));
        ProdutoDados dados = ProdutoDadosImportacao.montar(id, p, item.categoriaId(), atual);
        MovimentoOrigem origem = new MovimentoOrigem(
                "importacao", hashArquivo, "Saldo inicial da importação", usuarioId, usuarioNome);
        // saldo do cadastro nao muda aqui (delta zero); a entrada inicial vai pelo registrador logo abaixo
        produtos.salvarDados(companyId, dados, origem);
        if (item.lancaSaldo()) {
            registrador.aplicar(companyId, id, p.saldoInicial(), EstoqueTipos.ENTRADA, p.custoCompra(), origem);
            controle.add(new Object[] {companyId, p.codigo(), hashArquivo, p.saldoInicial()});
        }
    }
}
