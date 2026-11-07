package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.produto.importacao.LeitorCsvProdutos.LinhaCsv;
import br.com.quack.pdv.produto.importacao.RelatorioImportacao.LinhaRelato;
import br.com.quack.pdv.produto.importacao.RelatorioImportacao.ResumoFiscal;
import br.com.quack.pdv.produto.importacao.RelatorioImportacao.Totais;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaExistente;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaNova;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Junta as linhas mapeadas com o estado atual do banco e decide novo / atualizado / recusado (I1). E o relatorio da
 * simulacao e, ao mesmo tempo, o roteiro que o gravador executa: simular e confirmar partem do mesmo plano.
 */
public final class PlanoImportacao {

    /** Produto ja cadastrado (por codigo). */
    public record ProdutoExistente(String id, String codigo, String gtin, String categoriaId) {}

    /** Estado do banco lido antes de planejar. */
    public record EstadoBanco(
            List<ProdutoExistente> produtos,
            List<CategoriaExistente> categorias,
            Set<String> codigosComSaldoInicial) {}

    /** Linha aceita e o que fazer com ela. */
    public record Item(
            ProdutoImportado produto,
            String produtoIdExistente,
            String categoriaId,
            boolean lancaSaldo) {
        public boolean novo() {
            return produtoIdExistente == null;
        }
    }

    private final List<Item> itens;
    private final List<CategoriaNova> categoriasNovas;
    private final RelatorioImportacao relatorio;

    private PlanoImportacao(List<Item> itens, List<CategoriaNova> categoriasNovas, RelatorioImportacao relatorio) {
        this.itens = itens;
        this.categoriasNovas = categoriasNovas;
        this.relatorio = relatorio;
    }

    public List<Item> itens() {
        return itens;
    }

    public List<CategoriaNova> categoriasNovas() {
        return categoriasNovas;
    }

    public RelatorioImportacao relatorio() {
        return relatorio;
    }

    /** Chave de comparacao de codigo de produto (o banco nao diferencia maiusculas). */
    public static String chaveCodigo(String codigo) {
        return codigo.toUpperCase(Locale.ROOT);
    }

    public static PlanoImportacao montar(List<LinhaCsv> linhas, EstadoBanco estado, String hashArquivo) {
        Map<String, ProdutoExistente> porCodigo = new HashMap<>();
        Map<String, String> codigoPorGtin = new HashMap<>();
        for (ProdutoExistente p : estado.produtos()) {
            porCodigo.put(chaveCodigo(p.codigo()), p);
            if (p.gtin() != null && !p.gtin().isBlank() && !Gtin.SEM_GTIN.equalsIgnoreCase(p.gtin().trim())) {
                codigoPorGtin.putIfAbsent(p.gtin().trim(), p.codigo());
            }
        }
        Set<String> comSaldo = new HashSet<>();
        estado.codigosComSaldoInicial().forEach(c -> comSaldo.add(chaveCodigo(c)));

        ResolvedorCategorias categorias = new ResolvedorCategorias(estado.categorias());
        Map<String, Integer> codigosVistos = new HashMap<>();
        Map<String, String> gtinsVistos = new HashMap<>();
        Map<String, Integer> fiscal = new HashMap<>();
        List<Item> itens = new ArrayList<>();
        List<LinhaRelato> recusados = new ArrayList<>();
        List<LinhaRelato> avisos = new ArrayList<>();
        int novos = 0;
        int atualizados = 0;
        int comSaldoCount = 0;
        BigDecimal quantidade = BigDecimal.ZERO;
        BigDecimal valor = BigDecimal.ZERO;

        for (LinhaCsv linha : linhas) {
            MapeadorProdutoLegado.Resultado r = MapeadorProdutoLegado.mapear(linha);
            String codigoBruto = linha.valor("Código");
            String descBruta = linha.valor("Descrição Completa");
            if (r.recusada()) {
                recusados.add(new LinhaRelato(linha.numero(), codigoBruto, descBruta, r.recusa()));
                continue;
            }
            ProdutoImportado p = r.produto();
            String motivo = conflito(p, linha, codigosVistos, gtinsVistos, codigoPorGtin);
            if (motivo != null) {
                recusados.add(new LinhaRelato(p.linha(), p.codigo(), p.descricao(), motivo));
                continue;
            }
            codigosVistos.put(chaveCodigo(p.codigo()), p.linha());
            if (!Gtin.SEM_GTIN.equals(p.gtin())) {
                gtinsVistos.put(p.gtin(), p.codigo());
            }

            List<String> avisosLinha = new ArrayList<>(r.avisos());
            String categoriaId = categorias.resolver(p.classe(), p.grupo(), p.subGrupo(), avisosLinha::add);
            avisosLinha.forEach(a -> avisos.add(new LinhaRelato(p.linha(), p.codigo(), p.descricao(), a)));

            ProdutoExistente existente = porCodigo.get(chaveCodigo(p.codigo()));
            boolean lanca = existente == null
                    && p.saldoInicial().signum() > 0
                    && !comSaldo.contains(chaveCodigo(p.codigo()));
            itens.add(new Item(p, existente == null ? null : existente.id(), categoriaId, lanca));
            if (existente == null) {
                novos++;
            } else {
                atualizados++;
            }
            if (lanca) {
                comSaldoCount++;
                quantidade = quantidade.add(p.saldoInicial());
                valor = valor.add(p.saldoInicial().multiply(p.custoCompra()));
            }
            fiscal.merge(String.join("\u0000", nvl(p.cstIcms()), nvl(p.csosnIcms()), p.cfop()), 1, Integer::sum);
        }

        List<CategoriaNova> novas = categorias.novas();
        Totais totais = new Totais(
                linhas.size(), novos, atualizados, recusados.size(), avisos.size(), novas.size(), comSaldoCount,
                quantidade, valor.setScale(2, java.math.RoundingMode.HALF_UP));
        RelatorioImportacao rel = new RelatorioImportacao(
                totais, resumoFiscal(fiscal), List.copyOf(recusados), List.copyOf(avisos), hashArquivo, null);
        return new PlanoImportacao(List.copyOf(itens), novas, rel);
    }

    private static String conflito(
            ProdutoImportado p,
            LinhaCsv linha,
            Map<String, Integer> codigosVistos,
            Map<String, String> gtinsVistos,
            Map<String, String> codigoPorGtin) {
        Integer primeira = codigosVistos.get(chaveCodigo(p.codigo()));
        if (primeira != null) {
            return "Código repetido na planilha (já usado na linha " + primeira + ")";
        }
        if (Gtin.SEM_GTIN.equals(p.gtin())) {
            return null;
        }
        String noArquivo = gtinsVistos.get(p.gtin());
        if (noArquivo != null && !noArquivo.equalsIgnoreCase(p.codigo())) {
            return "GTIN já usado pelo código " + noArquivo;
        }
        String noBanco = codigoPorGtin.get(p.gtin());
        if (noBanco != null && !noBanco.equalsIgnoreCase(p.codigo())) {
            return "GTIN já usado pelo código " + noBanco + " (já cadastrado)";
        }
        return null;
    }

    private static List<ResumoFiscal> resumoFiscal(Map<String, Integer> contagem) {
        List<ResumoFiscal> lista = new ArrayList<>();
        contagem.forEach((k, n) -> {
            String[] f = k.split("\u0000", -1);
            lista.add(new ResumoFiscal(f[0], f[1], f[2], n));
        });
        lista.sort(Comparator.comparingInt(ResumoFiscal::quantidade).reversed()
                .thenComparing(ResumoFiscal::cst).thenComparing(ResumoFiscal::csosn).thenComparing(ResumoFiscal::cfop));
        return List.copyOf(lista);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
