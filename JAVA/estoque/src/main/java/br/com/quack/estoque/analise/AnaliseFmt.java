package br.com.quack.estoque.analise;

import br.com.quack.estoque.domain.Fmt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Formatação dos números e textos de tooltip da análise (pt-BR). Funções puras, testadas sem JavaFX. */
public final class AnaliseFmt {
    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DIA_MES_ANO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private AnaliseFmt() {
    }

    private static String num(double v, String padrao) {
        return new DecimalFormat(padrao, DecimalFormatSymbols.getInstance(PT_BR)).format(v);
    }

    /** Valor de KPI pela unidade da API: "R$", "itens", "x", "dias", "%". Vazio vira "—". */
    public static String valor(String unidade, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "—";
        }
        double d = v.asDouble();
        return switch (unidade == null ? "" : unidade) {
            case "R$" -> Fmt.money(BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
            case "x" -> num(d, "#,##0.0#") + "x";
            case "dias" -> num(d, "#,##0") + (Math.round(d) == 1 ? " dia" : " dias");
            case "%" -> num(d, "#,##0.0") + "%";
            default -> num(d, "#,##0");
        };
    }

    /** "▲ 12,5% vs período anterior"; sem variação (anterior zero ou ausente) devolve vazio. */
    public static String variacao(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        double d = v.asDouble();
        String seta = d > 0.05 ? "▲ " : d < -0.05 ? "▼ " : "● ";
        return seta + num(Math.abs(d), "#,##0.0") + "% vs período anterior";
    }

    /** "2026-10-05" vira "05/10". */
    public static String diaMes(String iso) {
        return LocalDate.parse(iso.substring(0, 10)).format(DIA_MES);
    }

    /** Tooltip de um ponto de série no tempo: "05/10/2026" e, na linha de baixo, "Valor em estoque: R$ 1.234,00". */
    public static String tooltipSerie(String iso, String rotulo, String unidade, double valor) {
        String v = switch (unidade) {
            case "R$" -> Fmt.money(BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP));
            case "%" -> num(valor, "#,##0.0") + "%";
            default -> num(valor, "#,##0.###");
        };
        return LocalDate.parse(iso.substring(0, 10)).format(DIA_MES_ANO) + "\n" + rotulo + ": " + v;
    }

    /** Tooltip de barra: "Frios" e, na linha de baixo, "Cobertura: 323,7 dias". */
    public static String tooltipItem(String nome, String rotulo, String unidade, double valor) {
        return nome + "\n" + rotulo + ": " + valor(unidade, DoubleNode.valueOf(valor));
    }

    /** Fatia de rosca: "Classe A: 69 produtos · R$ 41.331,89 (29,4%)". */
    public static String tooltipFatia(String classe, int produtos, JsonNode valor, JsonNode percentual) {
        StringBuilder sb = new StringBuilder("Classe ").append(classe).append(": ").append(produtos)
                .append(produtos == 1 ? " produto" : " produtos");
        if (valor != null && !valor.isNull()) {
            sb.append(" · ").append(valor("R$", valor));
        }
        if (percentual != null && !percentual.isNull()) {
            sb.append(" (").append(num(percentual.asDouble(), "#,##0.0")).append("%)");
        }
        return sb.toString();
    }

    /** Nome curto para eixos: corta com reticências. */
    public static String curto(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** "Calculado em 31/10/2026 22:10" a partir do instante ISO (UTC) devolvido pela API. */
    public static String calculadoEm(String iso) {
        return iso == null ? "" : "Calculado em " + Fmt.dateTime(iso);
    }
}
