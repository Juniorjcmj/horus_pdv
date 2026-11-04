package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import java.util.List;

/** Mudancas da nuvem desde uma versao (Change Tracking): linhas atuais e chaves excluidas, ate a versao {@code ate}. */
public record CargaDelta(long ate, List<BlocoTabela> blocos) {

    public boolean vazio() {
        return blocos.isEmpty();
    }
}
