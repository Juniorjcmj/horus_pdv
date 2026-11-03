package br.com.quack.pdv.venda.caixa.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * CaixaStatusDto da API .NET. E tambem o que fica em ProcessedEvents.ResponsePayload (JSON camelCase, nulos presentes),
 * lido pelas duas APIs.
 */
public record CaixaStatusDto(
        @JsonProperty("state") String state,
        @JsonProperty("canSell") boolean canSell,
        @JsonProperty("blockReason") String blockReason,
        @JsonProperty("serverNow") String serverNow,
        @JsonProperty("currentSession") CaixaSessaoDto currentSession,
        @JsonProperty("lastSession") CaixaSessaoDto lastSession,
        @JsonProperty("history") List<CaixaSessaoDto> history,
        @JsonProperty("openSessions") List<CaixaSessaoDto> openSessions,
        @JsonProperty("isReplay") boolean isReplay) {

    public CaixaStatusDto {
        history = history == null ? List.of() : history;
        openSessions = openSessions == null ? List.of() : openSessions;
        state = state == null ? "fechado" : state;
        blockReason = blockReason == null ? "" : blockReason;
        serverNow = serverNow == null ? "" : serverNow;
    }

    public CaixaStatusDto comReplay(boolean replay) {
        return new CaixaStatusDto(state, canSell, blockReason, serverNow, currentSession, lastSession, history,
                openSessions, replay);
    }
}
