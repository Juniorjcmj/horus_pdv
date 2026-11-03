package br.com.quack.pdv.venda.caixa.app;

import br.com.quack.pdv.shared.FormatoO;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Datas do caixa no fuso de Brasilia (HorusDateTime e CaixaClientSession da API .NET). */
public final class CaixaTempo {

    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    static final Duration MAX_PASSADO = Duration.ofDays(30);
    static final Duration MAX_FUTURO = Duration.ofMinutes(5);

    private static final DateTimeFormatter[] DATAS = {
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
        DateTimeFormatter.ofPattern("yyyy/M/d"),
    };

    private CaixaTempo() {}

    /** HorusDateTime.Now. */
    static OffsetDateTime agora(Clock clock) {
        return OffsetDateTime.now(clock).atZoneSameInstant(BRASILIA).toOffsetDateTime();
    }

    /** HorusDateTime.FormatIso: ToString("o") no fuso de Brasilia. */
    static String formatarIso(OffsetDateTime data) {
        return FormatoO.comOffset(data.atZoneSameInstant(BRASILIA).toOffsetDateTime());
    }

    /** Interpreta o horario do cliente; vazio quando ausente, invalido ou fora da janela (agora-30 dias a agora+5 min). */
    static Resolucao resolverHorario(String valor, OffsetDateTime agora) {
        if (br.com.quack.pdv.venda.registro.HashPayload.emBranco(valor)) return new Resolucao(agora, false);
        Optional<OffsetDateTime> lido = lerHorario(valor.strip());
        if (lido.isEmpty()) return new Resolucao(agora, true);
        OffsetDateTime p = lido.get();
        if (p.toInstant().isBefore(agora.minus(MAX_PASSADO).toInstant())
                || p.toInstant().isAfter(agora.plus(MAX_FUTURO).toInstant())) {
            return new Resolucao(agora, true);
        }
        return new Resolucao(p.atZoneSameInstant(BRASILIA).toOffsetDateTime(), false);
    }

    record Resolucao(OffsetDateTime horario, boolean foraDaJanela) {}

    /** DateTimeOffset.TryParse: ISO com offset, sem offset (UTC) ou so a data (UTC). */
    public static Optional<OffsetDateTime> lerDataHora(String texto) {
        return texto == null ? Optional.empty() : lerHorario(texto.strip());
    }

    private static Optional<OffsetDateTime> lerHorario(String texto) {
        try {
            return Optional.of(OffsetDateTime.parse(texto));
        } catch (DateTimeParseException e) {
            // tenta sem offset (assume UTC, como DateTimeStyles.AssumeUniversal)
        }
        try {
            return Optional.of(LocalDateTime.parse(texto).atOffset(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            // tenta so a data
        }
        return lerData(texto).map(d -> d.atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    /** DateTime.TryParse(...).Date com a cultura invariante, nos formatos usuais. */
    public static Optional<LocalDate> lerData(String texto) {
        if (texto == null) return Optional.empty();
        String t = texto.strip();
        try {
            return Optional.of(LocalDateTime.parse(t).toLocalDate());
        } catch (DateTimeParseException e) {
            // proximo formato
        }
        try {
            return Optional.of(OffsetDateTime.parse(t).toLocalDate());
        } catch (DateTimeParseException e) {
            // proximo formato
        }
        for (DateTimeFormatter f : DATAS) {
            try {
                return Optional.of(LocalDate.parse(t, f));
            } catch (DateTimeParseException e) {
                // proximo formato
            }
        }
        return Optional.empty();
    }
}
