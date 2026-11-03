package br.com.quack.pdv.shared;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Formato "o" do .NET (round-trip): sempre 7 casas de fracao. */
public final class FormatoO {

    private static final DateTimeFormatter UTC_Z = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter COM_OFFSET = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSxxx");

    private FormatoO() {}

    /** DateTime UTC com ToString("o"): 2026-11-01T22:30:00.1234567Z. */
    public static String utcZ(Instant instante) {
        return UTC_Z.format(instante);
    }

    /** DateTimeOffset com ToString("o"): 2026-11-01T22:30:00.1234567+00:00. */
    public static String comOffset(OffsetDateTime data) {
        return COM_OFFSET.format(data);
    }
}
