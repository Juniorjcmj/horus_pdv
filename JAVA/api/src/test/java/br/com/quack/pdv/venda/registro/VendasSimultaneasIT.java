package br.com.quack.pdv.venda.registro;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Perfil padrao: 8 caixas x 20 vendas na mesma empresa, sem retentativa, repetido varias rodadas. */
@SpringBootTest
@ActiveProfiles("it")
class VendasSimultaneasIT {

    static {
        BancoIT.criarBanco("simultaneas");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("simultaneas"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired VendaRegistroService vendas;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("simultaneas"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    @RepeatedTest(5)
    void oitoCaixasSimultaneosNaoDaoDeadlock() throws Exception {
        SimultaneasSuporte.rodar(vendas, jdbc, 8, 20);
    }
}
