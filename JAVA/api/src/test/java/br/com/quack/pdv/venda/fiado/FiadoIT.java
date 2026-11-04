package br.com.quack.pdv.venda.fiado;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.fiado.app.FiadoService;
import br.com.quack.pdv.venda.fiado.domain.FiadoDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Recebimento de fiado (6.2) com SQL Server real: saldo atomico, auditoria e recebimentos concorrentes. */
@SpringBootTest
@ActiveProfiles("it")
class FiadoIT {

    static final String EMPRESA = "empresa-principal";
    static final String CLIENTE = "cl-ct-devedor"; // saldo 50

    static {
        BancoIT.criarBanco("fiado");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("fiado"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.lote.retentativa.ativa", () -> "false");
        r.add("quack.fiscal.fila-minima.ativa", () -> "false");
    }

    @Autowired FiadoService fiado;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("fiado"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    private BigDecimal saldo() {
        return jdbc.queryForObject("SELECT SaldoDevedor FROM Clientes WHERE Id = ?", BigDecimal.class, CLIENTE);
    }

    private FiadoDtos.Movimento receber(String valor) {
        return fiado.receber(EMPRESA, "usr-ct-caixa", "Cai Caixa", CLIENTE, new BigDecimal(valor), "pix", "obs");
    }

    @Test
    void recebimentoAtualizaOSaldoGravaOMovimentoEAAuditoria() {
        FiadoDtos.Movimento m = receber("20");

        assertThat(m.saldoAnterior()).isEqualByComparingTo("50");
        assertThat(m.saldoAtual()).isEqualByComparingTo("30");
        assertThat(saldo()).isEqualByComparingTo("30");
        Map<String, Object> linha = jdbc.queryForMap("SELECT Tipo, Valor, SaldoAnterior, SaldoAtual, FormaPagamento, "
                + "Observacao, OperadorNome FROM FiadoMovimentos WHERE Id = ?", m.id());
        assertThat(((Number) linha.get("Tipo")).intValue()).isEqualTo(2);
        assertThat(linha).containsEntry("FormaPagamento", "pix").containsEntry("Observacao", "obs")
                .containsEntry("OperadorNome", "Cai Caixa");
        Map<String, Object> audit = jdbc.queryForMap("SELECT UserId, UserName, EntityType, EntityId, Description "
                + "FROM AuditLog WHERE EventType = 'FiadoRecebimento'");
        assertThat(audit).containsEntry("UserId", "usr-ct-caixa").containsEntry("EntityType", "Cliente")
                .containsEntry("EntityId", CLIENTE);
        assertThat((String) audit.get("Description"))
                .isEqualTo("Recebeu 20,00 do cliente Zelia Devedora via pix. Saldo atual: 30,00.");
    }

    @Test
    void recebimentoMaiorQueOSaldoNaoGravaNada() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> receber("50.50"))
                .isInstanceOfSatisfying(NegocioException.class, e -> assertThat(e.getMessage()).isEqualTo(
                        "Valor do recebimento (R$ 50.50) não pode ser maior que o saldo devedor (R$ 50.00)."));

        assertThat(saldo()).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FiadoMovimentos WHERE Tipo = 2 AND Valor = 50.50",
                Integer.class)).isZero();
    }

    @Test
    void doisRecebimentosConcorrentesSomamSemPerderAtualizacao() throws Exception {
        List<Throwable> falhas = receberEmParalelo(List.of("20", "20"));

        assertThat(falhas).isEmpty();
        assertThat(saldo()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FiadoMovimentos WHERE ClienteId = ? AND Tipo = 2 "
                + "AND SaldoAnterior IN (50, 30)", Integer.class, CLIENTE)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE EventType = 'FiadoRecebimento'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void doisRecebimentosConcorrentesQueEstouramOSaldoAceitamSoUm() throws Exception {
        List<Throwable> falhas = receberEmParalelo(List.of("30", "30"));

        assertThat(falhas).hasSize(1);
        assertThat(falhas.getFirst()).isInstanceOf(NegocioException.class);
        assertThat(saldo()).isEqualByComparingTo("20");
    }

    private List<Throwable> receberEmParalelo(List<String> valores) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(valores.size());
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> futuros = new ArrayList<>();
        for (String v : valores) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return receber(v);
            }));
        }
        largada.countDown();
        List<Throwable> falhas = new ArrayList<>();
        for (Future<?> f : futuros) {
            try {
                f.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                falhas.add(e.getCause());
            }
        }
        pool.shutdownNow();
        return falhas;
    }
}
