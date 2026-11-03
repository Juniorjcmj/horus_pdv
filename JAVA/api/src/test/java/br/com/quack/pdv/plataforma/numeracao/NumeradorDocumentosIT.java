package br.com.quack.pdv.plataforma.numeracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("it")
class NumeradorDocumentosIT {

    static {
        BancoIT.criarBanco("numeracao");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("numeracao"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired NumeradorDocumentos numerador;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("numeracao"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    @Test
    void exigeTransacaoAbertaMandatory() {
        assertThatThrownBy(() -> numerador.proximoNumeroVenda("empresa-principal"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> numerador.proximoNumeroFiscal("empresa-principal", (short) 65, 2, (byte) 2))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> numerador.proximoNumeroInventario("empresa-principal"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void vendasConcorrentesRecebemNumerosDistintosEConsecutivos() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        int threads = 2;
        CountDownLatch prontas = new CountDownLatch(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int indice = i;
                futuros.add(pool.submit(() -> {
                    prontas.countDown();
                    largada.await(10, TimeUnit.SECONDS);
                    return tx.execute(s -> {
                        String numero = numerador.proximoNumeroVenda("empresa-principal");
                        jdbc.update("INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf,"
                                + " PaymentType, OperatorName, SaleDate, TotalAmount)"
                                + " VALUES (?, N'empresa-principal', ?, N'Consumidor', N'', N'Dinheiro', N'Teste',"
                                + " SYSDATETIMEOFFSET(), 1)", "vd-num-" + indice, numero);
                        return numero;
                    });
                }));
            }
            prontas.await(10, TimeUnit.SECONDS);
            largada.countDown();
            List<Integer> numeros = new ArrayList<>();
            for (Future<String> f : futuros) {
                numeros.add(Integer.parseInt(f.get(30, TimeUnit.SECONDS)));
            }
            numeros.sort(Integer::compare);
            // o seed de contrato deixa 15040 a 15042; a proxima e a 15043
            assertThat(numeros).containsExactly(15043, 15044);
        }
    }

    @Test
    void empresaPrincipalComecaNaBase15039EOutraEmpresaEmUm() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM DocumentosFiscais WHERE CompanyId IN (N'empresa-principal', N'emp-contrato-aprovada')");
        jdbc.update("DELETE FROM Vendas WHERE CompanyId IN (N'empresa-principal', N'emp-contrato-aprovada')");

        String outraEmpresa = tx.execute(s -> numerador.proximoNumeroVenda("emp-contrato-aprovada"));
        String principal = tx.execute(s -> numerador.proximoNumeroVenda("empresa-principal"));
        assertThat(outraEmpresa).isEqualTo("1");
        assertThat(principal).isEqualTo("15040");
    }

    @Test
    void sequenciaFiscalCriaALinhaEAlocaUmEDois() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM FiscalSequencias WHERE CompanyId = N'emp-contrato-aprovada'");

        Integer primeiro =
                tx.execute(s -> numerador.proximoNumeroFiscal("emp-contrato-aprovada", (short) 65, 1, (byte) 2));
        Integer segundo =
                tx.execute(s -> numerador.proximoNumeroFiscal("emp-contrato-aprovada", (short) 65, 1, (byte) 2));

        assertThat(primeiro).isEqualTo(1);
        assertThat(segundo).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ProximoNumero FROM FiscalSequencias WHERE CompanyId ="
                + " N'emp-contrato-aprovada' AND Modelo = 65 AND Serie = 1 AND Ambiente = 2", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void numeroDeInventarioEOMaiorMaisUm() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Integer maximo = jdbc.queryForObject(
                "SELECT ISNULL(MAX(Numero), 0) FROM Inventarios WHERE CompanyId = N'empresa-principal'", Integer.class);

        Integer proximo = tx.execute(s -> numerador.proximoNumeroInventario("empresa-principal"));
        assertThat(proximo).isEqualTo(maximo + 1);
    }
}
