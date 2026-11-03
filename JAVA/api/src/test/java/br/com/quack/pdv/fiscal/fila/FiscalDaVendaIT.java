package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.VendaRegistroIT;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Decisao de emissao e pendencia fiscal (4.1): seed com intervalo 3, Pix e CPF na nota emitindo sempre. */
@SpringBootTest
@ActiveProfiles("it")
class FiscalDaVendaIT {

    private static final String EMPRESA = "empresa-principal";

    static {
        BancoIT.criarBanco("fiscalvenda");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("fiscalvenda"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired VendaRegistroService vendas;
    @Autowired FiscalDaVenda fiscal;
    @Autowired PendenciaFiscal pendencia;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("fiscalvenda"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    private VendaRegistroResultado vender(String tipo, String cpf) {
        VendaRequests.Venda v = VendaRegistroIT.venda("3,00", List.of(VendaRegistroIT.item("CT001", "1", "3")),
                VendaRegistroIT.pag(tipo, "3"));
        v.paymentType = tipo;
        v.customerCpf = cpf;
        return vendas.registrar(EMPRESA, v, null);
    }

    private FiscalDaVenda.Resultado fiscalDe(VendaRegistroResultado r, String tipo, String cpf) {
        return fiscal.aposVenda(EMPRESA, r.vendaId(), r.saleNumber(), tipo, List.of(tipo), cpf);
    }

    private int contador() {
        return jdbc.queryForObject("SELECT ContadorVendas FROM RegrasEmissaoNfce WHERE CompanyId = ?", Integer.class,
                EMPRESA);
    }

    private int notasDaVenda(String vendaId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM DocumentosFiscais WHERE VendaId = ?", Integer.class, vendaId);
    }

    @Test
    void intervaloDeTresEmDinheiroSoATerceiraEmiteEOContadorZera() {
        VendaRegistroResultado v1 = vender("dinheiro", "");
        VendaRegistroResultado v2 = vender("dinheiro", "");
        VendaRegistroResultado v3 = vender("dinheiro", "");

        FiscalDaVenda.Resultado r1 = fiscalDe(v1, "dinheiro", "");
        assertThat(contador()).isEqualTo(1);
        FiscalDaVenda.Resultado r2 = fiscalDe(v2, "dinheiro", "");
        assertThat(contador()).isEqualTo(2);
        FiscalDaVenda.Resultado r3 = fiscalDe(v3, "dinheiro", "");

        assertThat(r1.emitirFiscal()).isFalse();
        assertThat(r2.emitirFiscal()).isFalse();
        assertThat(r3.emitirFiscal()).isTrue();
        assertThat(r3.fiscalQueued()).isTrue();
        assertThat(contador()).isZero();
        assertThat(notasDaVenda(v1.vendaId())).isZero();
        assertThat(notasDaVenda(v3.vendaId())).isEqualTo(1);
        // o seed ja tem a nota 1 da serie 2; a pendencia reserva a 2 e avanca a sequencia
        assertThat(jdbc.queryForObject("SELECT NumeroNf FROM DocumentosFiscais WHERE VendaId = ?", Integer.class,
                v3.vendaId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ProximoNumero FROM FiscalSequencias WHERE CompanyId = ? AND Modelo = 65",
                Integer.class, EMPRESA)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT Status FROM DocumentosFiscais WHERE VendaId = ?", Integer.class,
                v3.vendaId())).isEqualTo(1);
    }

    @Test
    void formaSempreEmitirEmiteSemMexerNoContador() {
        VendaRegistroResultado v = vender("pix", "");

        FiscalDaVenda.Resultado r = fiscalDe(v, "pix", "");

        assertThat(r.emitirFiscal()).isTrue();
        assertThat(r.fiscalQueued()).isTrue();
        assertThat(contador()).isZero();
    }

    @Test
    void cpfNaNotaEmiteSempre() {
        VendaRegistroResultado v = vender("dinheiro", "529.982.247-25");

        FiscalDaVenda.Resultado r = fiscalDe(v, "dinheiro", "529.982.247-25");

        assertThat(r.emitirFiscal()).isTrue();
        assertThat(contador()).isZero();
        assertThat(notasDaVenda(v.vendaId())).isEqualTo(1);
    }

    @Test
    void replayNaoAvancaOContadorNemDuplicaANota() {
        VendaRegistroResultado v = vender("dinheiro", "");

        FiscalDaVenda.Resultado primeira = fiscalDe(v, "dinheiro", "");
        FiscalDaVenda.Resultado repetida = fiscalDe(v, "dinheiro", "");

        assertThat(primeira).isEqualTo(repetida);
        assertThat(contador()).isEqualTo(1);

        VendaRegistroResultado p = vender("pix", "");
        fiscalDe(p, "pix", "");
        fiscalDe(p, "pix", "");
        assertThat(notasDaVenda(p.vendaId())).isEqualTo(1);
    }

    @Test
    void pendenciaUnicaPorVendaEVendaCanceladaERecusada() {
        VendaRegistroResultado v = vender("pix", "");

        String primeira = pendencia.enfileirar(EMPRESA, v.vendaId());
        String segunda = pendencia.enfileirar(EMPRESA, v.vendaId());

        assertThat(segunda).isEqualTo(primeira);
        assertThat(notasDaVenda(v.vendaId())).isEqualTo(1);

        VendaRegistroResultado c = vender("dinheiro", "");
        jdbc.update("UPDATE Vendas SET Status = 'cancelada' WHERE Id = ?", c.vendaId());
        assertThatThrownBy(() -> pendencia.enfileirar(EMPRESA, c.vendaId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Uma venda cancelada não pode gerar nova NFC-e.");
        assertThat(notasDaVenda(c.vendaId())).isZero();
    }
}
