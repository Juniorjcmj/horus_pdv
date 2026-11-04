package br.com.quack.pdv.plataforma.sincronizacao.situacao;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * Situacao da sincronizacao (D11), a mesma forma nas duas pontas. Campos que uma ponta nao conhece ficam nulos: a nuvem
 * nao sabe a versao local nem o atraso da loja; a loja nao tem a fila de comandos nem a quarentena (le da nuvem, quando
 * ha internet, e indica em {@code nuvemAcessivel}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SituacaoSync(
        String origem,
        String companyId,
        OffsetDateTime geradaEm,
        Long versaoLocal,
        Long versaoConfirmada,
        OffsetDateTime ultimaSincronizacaoEm,
        Long atrasoSegundos,
        boolean pausada,
        String pausaMotivo,
        Integer comandosPendentes,
        Integer comandosParados,
        Integer quarentenaAberta,
        int nfcePendentesHaMaisDe10Min,
        JsonNode ultimaConferencia,
        JsonNode ultimoBackup,
        Boolean nuvemAcessivel,
        String servidorStatus) {

    /** NFC-e com {@code Status IN (1,2,8)} criadas ha mais que isto entram na contagem. */
    public static final long MINUTOS_NFCE_PENDENTE = 10;
}
