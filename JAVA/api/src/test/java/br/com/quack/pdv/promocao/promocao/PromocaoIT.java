package br.com.quack.pdv.promocao.promocao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.promocao.promocao.app.PromocaoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PromocaoIT extends BancoIT {
    @Autowired PromocaoService service;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword()).restaurar();
    }

    @Test
    void resultadoConfereVendaDoSeedESemVendasNaoSomaOutraPromocao() {
        var r = service.resultado("empresa-principal", "prm-ct-perc");
        assertThat(r.quantidadeVendas()).isEqualTo(1);
        assertThat(r.receitaBruta()).isEqualByComparingTo("6");
        assertThat(r.receitaLiquida()).isEqualByComparingTo("5.4");
        assertThat(r.descontoTotal()).isEqualByComparingTo("0.6");
        assertThat(r.margemLiquida()).isEqualByComparingTo("1.4");
        assertThat(service.obter("emp-contrato-aprovada", "prm-ct-perc")).isEmpty();
    }
}
