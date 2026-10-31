package br.com.quack.estoque.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação e leitura no padrão brasileiro (vírgula decimal, R$, dd/MM/yyyy). */
public final class Fmt {
    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private Fmt() {
    }

    private static DecimalFormat df(String pattern) {
        return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(PT_BR));
    }

    /** "R$ 1.234,56". */
    public static String money(BigDecimal v) {
        return v == null ? "" : "R$ " + df("#,##0.00").format(v);
    }

    /** Dinheiro sem o símbolo, para CSV e campos: "1.234,56". */
    public static String dec2(BigDecimal v) {
        return v == null ? "" : df("#,##0.00").format(v);
    }

    /** Quantidade com até 3 casas, sem zeros à direita: "12", "0,452". */
    public static String qty(BigDecimal v) {
        return v == null ? "" : df("#,##0.###").format(v);
    }

    /** Custo unitário com 4 casas para exibir: "4,1667". */
    public static String cost4(BigDecimal v) {
        return v == null ? "" : df("#,##0.0000").format(v);
    }

    /** Quantidade para enviar à API (vírgula decimal, sem milhar): "1234,5". */
    public static String qtyApi(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Valor monetário para enviar à API: "12,90". */
    public static String moneyApi(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Custo unitário com 4 casas para a API: "0,4167". */
    public static String cost4Api(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    public static BigDecimal parse(String text) {
        return Money.parseBr(text);
    }

    public static BigDecimal parseOrZero(String text) {
        return Money.parseBrOrZero(text);
    }

    public static String date(LocalDate d) {
        return d == null ? "" : DATA.format(d);
    }

    /** Aceita "2026-10-31", "2026-10-31T10:20:30Z", "2026-10-31T10:20:30-03:00" e "2026-10-31T10:20:30". */
    public static String dateTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        String v = iso.trim();
        try {
            return DATA_HORA.format(OffsetDateTime.parse(v).atZoneSameInstant(SAO_PAULO));
        } catch (RuntimeException e) {
            try {
                return DATA_HORA.format(java.time.LocalDateTime.parse(v));
            } catch (RuntimeException e2) {
                try {
                    return DATA.format(LocalDate.parse(v.substring(0, Math.min(10, v.length()))));
                } catch (RuntimeException e3) {
                    return v;
                }
            }
        }
    }

    /** Só a data de "2026-11-05" ou "2026-11-05T00:00:00" (validade): "05/11/2026". */
    public static String dateOnly(String iso) {
        if (iso == null || iso.length() < 10) {
            return iso == null ? "" : iso;
        }
        try {
            return DATA.format(LocalDate.parse(iso.substring(0, 10)));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** Datas sem fuso gravadas em UTC pela API (EstoqueMovimentacoes.CriadoEm) são convertidas para Brasília. */
    public static String dateTimeUtc(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            java.time.LocalDateTime utc = java.time.LocalDateTime.parse(iso.trim());
            return DATA_HORA.format(utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(SAO_PAULO));
        } catch (RuntimeException e) {
            return dateTime(iso);
        }
    }

    /** Remove acentos e põe em minúsculas, para busca. */
    public static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(PT_BR).trim();
    }
}
