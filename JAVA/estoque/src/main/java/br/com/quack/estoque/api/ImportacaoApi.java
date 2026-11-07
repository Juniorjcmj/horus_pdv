package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Importação de produtos por planilha (api/Produto/importacao): {@code simular} lê e valida sem gravar, {@code confirmar}
 * grava tudo ou nada. Só administrador e gerente. O arquivo vai em multipart no campo "arquivo".
 */
public class ImportacaoApi {
    /** Importar 3 mil produtos leva alguns segundos; o servidor da loja pode demorar mais (design I8: 5 minutos). */
    public static final java.time.Duration TIMEOUT = java.time.Duration.ofMinutes(5);

    public record Totais(int lidas, int novos, int atualizados, int recusados, int avisos, int categoriasNovas,
            int produtosComSaldo, BigDecimal quantidadeTotal, BigDecimal valorEstoque) {
    }

    public record ResumoFiscal(String cst, String csosn, String cfop, int quantidade) {
    }

    public record LinhaRelato(int linha, String codigo, String descricao, String motivo) {
    }

    public record Relatorio(Totais totais, List<ResumoFiscal> resumoFiscal, List<LinhaRelato> recusados,
            List<LinhaRelato> avisos, String hashArquivo, String gravadoEm) {
    }

    private final ApiClient api;

    public ImportacaoApi(ApiClient api) {
        this.api = api;
    }

    public Relatorio simular(String nomeArquivo, byte[] conteudo) {
        return relatorio(api.sendMultipart("/api/Produto/importacao/simular", "arquivo", nomeArquivo, conteudo, TIMEOUT));
    }

    public Relatorio confirmar(String nomeArquivo, byte[] conteudo) {
        return relatorio(api.sendMultipart("/api/Produto/importacao/confirmar", "arquivo", nomeArquivo, conteudo, TIMEOUT));
    }

    static Relatorio relatorio(JsonNode d) {
        if (d == null) {
            throw new ApiException(0, "A API não devolveu o relatório da importação.");
        }
        JsonNode t = d.path("totais");
        Totais totais = new Totais(t.path("lidas").asInt(), t.path("novos").asInt(), t.path("atualizados").asInt(),
                t.path("recusados").asInt(), t.path("avisos").asInt(), t.path("categoriasNovas").asInt(),
                t.path("produtosComSaldo").asInt(), decimal(t, "quantidadeTotal"), decimal(t, "valorEstoque"));
        List<ResumoFiscal> fiscal = new ArrayList<>();
        for (JsonNode f : d.path("resumoFiscal")) {
            fiscal.add(new ResumoFiscal(f.path("cst").asText(""), f.path("csosn").asText(""), f.path("cfop").asText(""),
                    f.path("quantidade").asInt()));
        }
        return new Relatorio(totais, fiscal, linhas(d.path("recusados")), linhas(d.path("avisos")),
                d.path("hashArquivo").asText(""), d.hasNonNull("gravadoEm") ? d.get("gravadoEm").asText() : null);
    }

    private static List<LinhaRelato> linhas(JsonNode lista) {
        List<LinhaRelato> out = new ArrayList<>();
        for (JsonNode n : lista) {
            out.add(new LinhaRelato(n.path("linha").asInt(), n.path("codigo").asText(""),
                    n.path("descricao").asText(""), n.path("motivo").asText("")));
        }
        return out;
    }

    private static BigDecimal decimal(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? BigDecimal.ZERO : v.decimalValue();
    }
}
