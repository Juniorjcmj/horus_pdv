package br.com.quack.pdv.produto.importacao;

import java.math.BigDecimal;
import java.util.List;

/** Resposta da simulacao e da confirmacao da importacao (I2). {@code gravadoEm} so vem na confirmacao. */
public record RelatorioImportacao(
        Totais totais,
        List<ResumoFiscal> resumoFiscal,
        List<LinhaRelato> recusados,
        List<LinhaRelato> avisos,
        String hashArquivo,
        String gravadoEm) {

    public record Totais(
            int lidas,
            int novos,
            int atualizados,
            int recusados,
            int avisos,
            int categoriasNovas,
            int produtosComSaldo,
            BigDecimal quantidadeTotal,
            BigDecimal valorEstoque) {}

    public record ResumoFiscal(String cst, String csosn, String cfop, int quantidade) {}

    public record LinhaRelato(int linha, String codigo, String descricao, String motivo) {}

    public RelatorioImportacao comGravadoEm(String instante) {
        return new RelatorioImportacao(totais, resumoFiscal, recusados, avisos, hashArquivo, instante);
    }
}
