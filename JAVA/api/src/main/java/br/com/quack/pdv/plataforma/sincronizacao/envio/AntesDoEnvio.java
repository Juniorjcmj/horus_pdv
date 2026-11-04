package br.com.quack.pdv.plataforma.sincronizacao.envio;

/**
 * Ponto de extensao do envio (D4/D8): roda antes de montar cada lote. A tarefa 5.1 liga aqui a retomada fiscal
 * (venda com EmitirNfce sem nota ganha a pendencia antes de subir). Sem implementacao, nada acontece.
 */
@FunctionalInterface
public interface AntesDoEnvio {
    void executar();
}
