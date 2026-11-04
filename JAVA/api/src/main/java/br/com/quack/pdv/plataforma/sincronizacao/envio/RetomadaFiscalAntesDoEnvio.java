package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Liga a retomada fiscal da loja ao envio (D4/D8): antes de montar cada lote, toda venda com {@code EmitirNfce = 1} e
 * sem NFC-e ganha a pendencia com o proximo numero da sequencia fiscal da loja. Assim a venda sobe sempre junto com a
 * nota e o numero reservado, e o worker da nuvem nao cria uma nota com a sequencia dela. Se alguma venda nao puder ser
 * retomada, o ciclo de envio e abortado e repetido no proximo intervalo.
 */
@Component
@Profile("loja")
public class RetomadaFiscalAntesDoEnvio implements AntesDoEnvio {

    private final ObjectProvider<FilaFiscalMinima> fila;

    public RetomadaFiscalAntesDoEnvio(ObjectProvider<FilaFiscalMinima> fila) {
        this.fila = fila;
    }

    @Override
    public void executar() {
        fila.getObject().retomarAntesDoEnvio();
    }
}
