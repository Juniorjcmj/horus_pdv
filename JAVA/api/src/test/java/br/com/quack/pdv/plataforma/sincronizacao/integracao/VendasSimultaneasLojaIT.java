package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import br.com.quack.pdv.venda.registro.SimultaneasSuporte;
import br.com.quack.pdv.venda.registro.app.VendaRegistroService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;

/** Perfil loja ativado (snapshot + Change Tracking ligados, envio rodando): 8 caixas x 20 vendas, sem retentativa. */
class VendasSimultaneasLojaIT {

    static AmbienteSync amb;

    @BeforeAll
    static void subir() {
        amb = AmbienteSync.iniciar("sim");
    }

    @AfterAll
    static void descer() {
        if (amb != null) amb.close();
    }

    @RepeatedTest(5)
    void oitoCaixasComTrackingNaoDaoDeadlock() throws Exception {
        SimultaneasSuporte.rodar(amb.loja.getBean(VendaRegistroService.class), amb.jdbcLoja, 8, 20);
    }
}
