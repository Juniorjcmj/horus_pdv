package br.com.quack.estoque.analise;

import br.com.quack.estoque.api.ApiClient;
import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.persistence.JsonFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Filtros comuns das telas de análise (período com atalhos, comparação, categoria e fornecedor). Guardados no terminal
 * (analise.json) para a próxima abertura. O período sempre termina em {@code ate}, inclusive.
 */
public final class FiltrosAnalise {
    public static final String PERSONALIZADO = "Personalizado";
    public static final List<String> ATALHOS = List.of("Últimos 7 dias", "Últimos 30 dias", "Últimos 90 dias", "Mês atual",
            "Mês anterior", "Últimos 12 meses", PERSONALIZADO);

    public String atalho = "Últimos 30 dias";
    public LocalDate de;
    public LocalDate ate;
    public String categoriaId;
    public String fornecedorId;
    public boolean comparar;

    public FiltrosAnalise() {
        aplicarAtalho(atalho, LocalDate.now());
    }

    /** Ajusta de/ate pelo atalho (não mexe nas datas quando é "Personalizado"). */
    public void aplicarAtalho(String nome, LocalDate hoje) {
        atalho = nome;
        switch (nome) {
            case "Últimos 7 dias" -> intervalo(hoje.minusDays(6), hoje);
            case "Últimos 30 dias" -> intervalo(hoje.minusDays(29), hoje);
            case "Últimos 90 dias" -> intervalo(hoje.minusDays(89), hoje);
            case "Mês atual" -> intervalo(hoje.withDayOfMonth(1), hoje);
            case "Mês anterior" -> {
                LocalDate primeiro = hoje.withDayOfMonth(1).minusMonths(1);
                intervalo(primeiro, primeiro.withDayOfMonth(primeiro.lengthOfMonth()));
            }
            case "Últimos 12 meses" -> intervalo(hoje.minusDays(364), hoje);
            default -> {
                // personalizado: mantém as datas
            }
        }
    }

    private void intervalo(LocalDate d, LocalDate a) {
        de = d;
        ate = a;
    }

    public int dias() {
        return (int) (ate.toEpochDay() - de.toEpochDay()) + 1;
    }

    /** Texto para a barra de filtros: "01/10/2026 a 31/10/2026 (31 dias)". */
    public String resumoPeriodo() {
        return Fmt.date(de) + " a " + Fmt.date(ate) + " (" + dias() + " dias)";
    }

    /** Querystring da API: ?de=...&ate=...&categoriaId=...&fornecedorId=...&comparar=true. */
    public String query() {
        StringBuilder q = new StringBuilder("?de=").append(de).append("&ate=").append(ate);
        if (categoriaId != null && !categoriaId.isBlank()) {
            q.append("&categoriaId=").append(ApiClient.q(categoriaId));
        }
        if (fornecedorId != null && !fornecedorId.isBlank()) {
            q.append("&fornecedorId=").append(ApiClient.q(fornecedorId));
        }
        if (comparar) {
            q.append("&comparar=true");
        }
        return q.toString();
    }

    public FiltrosAnalise copia() {
        FiltrosAnalise c = new FiltrosAnalise();
        c.atalho = atalho;
        c.de = de;
        c.ate = ate;
        c.categoriaId = categoriaId;
        c.fornecedorId = fornecedorId;
        c.comparar = comparar;
        return c;
    }

    // ---------- memória no terminal ----------

    /** Grava o atalho (e não só as datas) para que "Últimos 30 dias" continue sendo os 30 dias contados de hoje. */
    private record Gravado(String atalho, String de, String ate, String categoriaId, String fornecedorId, boolean comparar) {
    }

    public void salvar(Path arquivo) {
        try {
            JsonFiles.writeAtomic(arquivo, new Gravado(atalho, String.valueOf(de), String.valueOf(ate), categoriaId, fornecedorId, comparar));
        } catch (IOException | RuntimeException e) {
            // a memória é só conforto; falhar ao gravar não pode atrapalhar a tela
        }
    }

    /** Lê os últimos filtros; arquivo ausente ou ilegível devolve os padrões. */
    public static FiltrosAnalise carregar(Path arquivo, LocalDate hoje) {
        FiltrosAnalise f = new FiltrosAnalise();
        f.aplicarAtalho(f.atalho, hoje);
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            return f;
        }
        try {
            Gravado g = JsonFiles.read(arquivo, Gravado.class);
            if (g.atalho() != null && ATALHOS.contains(g.atalho())) {
                if (PERSONALIZADO.equals(g.atalho())) {
                    f.atalho = PERSONALIZADO;
                    f.de = LocalDate.parse(g.de());
                    f.ate = LocalDate.parse(g.ate());
                    if (f.de.isAfter(f.ate)) {
                        f.aplicarAtalho("Últimos 30 dias", hoje);
                    }
                } else {
                    f.aplicarAtalho(g.atalho(), hoje);
                }
            }
            f.categoriaId = vazioNulo(g.categoriaId());
            f.fornecedorId = vazioNulo(g.fornecedorId());
            f.comparar = g.comparar();
        } catch (IOException | RuntimeException e) {
            FiltrosAnalise padrao = new FiltrosAnalise();
            padrao.aplicarAtalho(padrao.atalho, hoje);
            return padrao;
        }
        return f;
    }

    private static String vazioNulo(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
