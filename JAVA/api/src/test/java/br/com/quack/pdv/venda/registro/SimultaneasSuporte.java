package br.com.quack.pdv.venda.registro;

import static br.com.quack.pdv.venda.registro.VendaRegistroIT.item;
import static br.com.quack.pdv.venda.registro.VendaRegistroIT.pag;
import static br.com.quack.pdv.venda.registro.VendaRegistroIT.venda;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Carga de caixas simultaneos (a loja real tem 7): N threads x M vendas na mesma empresa, produtos sobrepostos (em
 * ordens opostas) e disjuntos, SEM retentativa. Falha se qualquer venda levantar excecao; numeros distintos e
 * consecutivos; saldos exatos.
 */
public final class SimultaneasSuporte {

    static final String EMPRESA = "empresa-principal";
    /** Preco de cadastro de cada produto do cenario (a venda a preco diferente exigiria autorizacao). */
    private static final String[][] PRODUTOS = {{"CT001", "3"}, {"CT002", "3"}, {"CT007", "4.5"}, {"CT004", "7.5"}};

    private SimultaneasSuporte() {}

    /** Itens de cada thread: 0..3 um produto disjunto cada; as demais, trios sobrepostos em ordens opostas. */
    static List<String[]> itensDaThread(int t) {
        if (t < 4) {
            return java.util.Collections.singletonList(PRODUTOS[t]);
        }
        return t % 2 == 0 ? List.of(PRODUTOS[0], PRODUTOS[1], PRODUTOS[2])
                : List.of(PRODUTOS[2], PRODUTOS[1], PRODUTOS[0]);
    }

    public static void rodar(VendaRegistroService vendas, JdbcTemplate jdbc, int threads, int vendasPorThread)
            throws Exception {
        jdbc.update("UPDATE Produtos SET ProductQnt = 100000 WHERE CompanyId = ? AND ProductCode IN "
                + "('CT001','CT002','CT004','CT007')", EMPRESA);
        int antes = jdbc.queryForObject("SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", Integer.class, EMPRESA);
        int[] saidas = new int[PRODUTOS.length];
        for (int t = 0; t < threads; t++) {
            for (String[] p : itensDaThread(t)) {
                saidas[indice(p[0])] += vendasPorThread;
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<List<String>>> futuros = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<String[]> itens = itensDaThread(t);
            futuros.add(pool.submit(() -> {
                largada.await();
                List<String> numeros = new ArrayList<>();
                for (int i = 0; i < vendasPorThread; i++) {
                    BigDecimal total = BigDecimal.ZERO;
                    List<VendaRequests.Item> itensReq = new ArrayList<>();
                    for (String[] p : itens) {
                        itensReq.add(item(p[0], "1", p[1]));
                        total = total.add(new BigDecimal(p[1]));
                    }
                    VendaRegistroResultado r = vendas.registrar(EMPRESA, venda(total.toPlainString().replace('.', ','),
                            itensReq, pag("dinheiro", total.toPlainString())), null);
                    numeros.add(r.saleNumber());
                }
                return numeros;
            }));
        }
        largada.countDown();
        List<Throwable> erros = new ArrayList<>();
        TreeSet<Integer> todos = new TreeSet<>();
        int total = 0;
        for (Future<List<String>> f : futuros) {
            try {
                for (String n : f.get()) {
                    todos.add(Integer.parseInt(n));
                    total++;
                }
            } catch (ExecutionException e) {
                erros.add(e.getCause());
            }
        }
        pool.shutdownNow();

        assertThat(erros).as("vendas simultaneas nao podem falhar (sem retentativa): %s", erros).isEmpty();
        assertThat(todos).as("numeros distintos").hasSize(total);
        assertThat(total).isEqualTo(threads * vendasPorThread);
        assertThat(todos.last() - todos.first() + 1).as("numeros consecutivos").isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Vendas WHERE CompanyId = ?", Integer.class, EMPRESA))
                .isEqualTo(antes + total);
        for (int i = 0; i < PRODUTOS.length; i++) {
            BigDecimal qnt = jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = ?",
                    BigDecimal.class, EMPRESA, PRODUTOS[i][0]);
            assertThat(qnt).as("saldo de " + PRODUTOS[i][0]).isEqualByComparingTo(String.valueOf(100000 - saidas[i]));
        }
    }

    private static int indice(String codigo) {
        for (int i = 0; i < PRODUTOS.length; i++) {
            if (PRODUTOS[i][0].equals(codigo)) return i;
        }
        throw new IllegalArgumentException(codigo);
    }
}
