package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.produto.importacao.LeitorCsvProdutos.LinhaCsv;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Converte uma linha do CSV do sistema anterior em {@link ProdutoImportado} (I3/I4), ou a recusa com o motivo.
 * Puro: sem banco. Os avisos (CFOP assumido, GTIN descartado, saldo negativo...) acompanham o resultado.
 */
public final class MapeadorProdutoLegado {

    /** Resultado de uma linha: produto OU motivo de recusa, mais os avisos. */
    public record Resultado(ProdutoImportado produto, String recusa, List<String> avisos) {
        public boolean recusada() {
            return produto == null;
        }
    }

    private static final BigDecimal LIM_PRECO = new BigDecimal("10000000000"); // DECIMAL(15,4)
    private static final BigDecimal LIM_PERCENTUAL = new BigDecimal("100000"); // DECIMAL(9,4)
    private static final BigDecimal LIM_ALIQUOTA = new BigDecimal("1000"); // DECIMAL(7,4)
    private static final BigDecimal LIM_DIMENSAO = new BigDecimal("100000000"); // DECIMAL(10,2)
    private static final Pattern OITO_DIGITOS = Pattern.compile("^\\d{8}$");
    private static final Pattern SETE_DIGITOS = Pattern.compile("^\\d{7}$");
    private static final Pattern QUATRO_DIGITOS = Pattern.compile("^\\d{4}$");
    private static final Pattern PALAVRA_MT = Pattern.compile("\\bMT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEPARADOR_FORNECEDORES = Pattern.compile("[,;|]");
    private static final BigDecimal MIL_MILHAO_PADRAO = BigDecimal.ONE;

    private MapeadorProdutoLegado() {}

    /** Recusa de linha (interna: vira {@link Resultado}). */
    private static final class Recusa extends RuntimeException {
        Recusa(String motivo) {
            super(motivo, null, false, false);
        }
    }

    public static Resultado mapear(LinhaCsv linha) {
        List<String> avisos = new ArrayList<>();
        try {
            return new Resultado(converter(linha, avisos), null, List.copyOf(avisos));
        } catch (Recusa r) {
            return new Resultado(null, r.getMessage(), List.copyOf(avisos));
        }
    }

    private static ProdutoImportado converter(LinhaCsv l, List<String> avisos) {
        String codigo = l.valor("Código");
        if (codigo.isEmpty()) {
            throw new Recusa("Código do produto ausente");
        }
        if (codigo.length() > 80) {
            throw new Recusa("Código do produto com mais de 80 caracteres");
        }
        String descricao = l.valor("Descrição Completa");
        if (descricao.isEmpty()) {
            throw new Recusa("Descrição ausente");
        }
        if (descricao.length() > 180) {
            descricao = Textos.cortar(descricao, 180);
            avisos.add("Descrição cortada em 180 caracteres");
        }
        String unidade = l.valor("Unidade Venda").toUpperCase(Locale.ROOT);
        if (unidade.isEmpty()) {
            throw new Recusa("Unidade de venda ausente");
        }
        if (unidade.length() > 6) {
            throw new Recusa("Unidade de venda com mais de 6 caracteres");
        }
        String unidadeCompra = l.valor("Unidade Compra").toUpperCase(Locale.ROOT);
        if (unidadeCompra.isEmpty() || unidadeCompra.length() > 6) {
            unidadeCompra = unidade;
        }
        BigDecimal embalagem = decimal(l, "Qtd.Embalagem", LIM_PRECO, 4);
        if (embalagem.signum() <= 0) {
            embalagem = MIL_MILHAO_PADRAO;
        }

        String ncm = l.valor("Código NCM");
        if (!OITO_DIGITOS.matcher(ncm).matches()) {
            throw new Recusa("NCM inválido (precisa ter 8 dígitos)");
        }
        String cest = l.valor("Código CEST");
        if (cest.isEmpty() || !SETE_DIGITOS.matcher(cest).matches()) {
            if (!cest.isEmpty()) {
                avisos.add("CEST inválido ignorado: " + cest);
            }
            cest = null;
        }
        String cst = l.valor("CST");
        String csosn = l.valor("Cód. CSOSN");
        if (cst.length() > 3) {
            throw new Recusa("CST inválido");
        }
        if (csosn.length() > 4) {
            throw new Recusa("CSOSN inválido");
        }
        String cfop = l.valor("CFOP");
        if (cfop.isEmpty()) {
            cfop = List.of("060", "010", "070").contains(cst) ? "5405" : "5102";
            avisos.add("CFOP vazio: assumido " + cfop);
        } else if (!QUATRO_DIGITOS.matcher(cfop).matches()) {
            throw new Recusa("CFOP inválido");
        }
        int origem = origem(l);
        String cstPis = l.valor("CST do PIS");
        String cstCofins = l.valor("CST do COFINS");
        if (cstPis.length() > 3 || cstCofins.length() > 3) {
            throw new Recusa("CST de PIS/COFINS inválido");
        }
        if (cstPis.isEmpty() || cstCofins.isEmpty()) {
            avisos.add("CST de PIS/COFINS vazio: assumido 99");
            cstPis = cstPis.isEmpty() ? "99" : cstPis;
            cstCofins = cstCofins.isEmpty() ? "99" : cstCofins;
        }

        BigDecimal preco = decimal(l, "Preço Venda Tab.3", LIM_PRECO, 4);
        if (preco.signum() == 0) {
            avisos.add("sem preço de venda");
        }
        BigDecimal custo = decimal(l, "Vl.Custo Compra (C/Impostos)", LIM_PRECO, 4);
        BigDecimal custoMedio = decimal(l, "Vl.Custo Médio", LIM_PRECO, 4);
        BigDecimal custoSem = decimal(l, "Vl.Custo Compra (S/Impostos)", LIM_PRECO, 4);

        BigDecimal saldo = saldo(l, avisos);
        if ("M2".equals(unidade) && PALAVRA_MT.matcher(descricao).find()) {
            avisos.add("Unidade M2 em produto que parece vendido por metro (MT): revisar");
        }

        return new ProdutoImportado(
                l.numero(),
                codigo,
                descricao,
                gtin(l, codigo, avisos),
                unidade,
                unidadeCompra,
                embalagem,
                Textos.cortar(Textos.vazioParaNulo(l.valor("Marca")), 120),
                Textos.cortar(Textos.vazioParaNulo(l.valor("Fabricante")), 120),
                Textos.cortar(Textos.vazioParaNulo(l.valor("Ref.Fabricante")), 80),
                fornecedor(l),
                ncm,
                cest,
                cfop,
                origem,
                Textos.vazioParaNulo(cst),
                Textos.vazioParaNulo(csosn),
                decimal(l, "Alíquota ICMS%", LIM_ALIQUOTA, 4),
                cstPis,
                cstCofins,
                preco,
                custo,
                custoMedio,
                custoSem,
                decimal(l, "Estoque Mínimo", LIM_PRECO, 4),
                decimal(l, "Estoque Máximo", LIM_PRECO, 4),
                localizacao(l),
                decimal(l, "Comissão%", LIM_PERCENTUAL, 4),
                decimal(l, "Desconto Máximo%", LIM_PERCENTUAL, 4),
                decimal(l, "Markup Cadastrado %", LIM_PERCENTUAL, 4),
                decimal(l, "Markup Praticado%", LIM_PERCENTUAL, 4),
                decimal(l, "Peso Líquido KG", LIM_PRECO, 4),
                decimal(l, "Peso Bruto KG", LIM_PRECO, 4),
                decimal(l, "Dimensão - Largura CM", LIM_DIMENSAO, 2),
                decimal(l, "Dimensão - Altura CM", LIM_DIMENSAO, 2),
                decimal(l, "Dimensão - Comprimento CM", LIM_DIMENSAO, 2),
                saldo,
                Textos.cortar(Textos.vazioParaNulo(l.valor("Classe")), 80),
                Textos.cortar(Textos.vazioParaNulo(l.valor("Grupo")), 80),
                Textos.cortar(Textos.vazioParaNulo(l.valor("Sub-Grupo")), 80));
    }

    /** Numero nao negativo, limitado ao tamanho da coluna do banco; texto que nao e numero recusa a linha. */
    private static BigDecimal decimal(LinhaCsv l, String coluna, BigDecimal limite, int casas) {
        BigDecimal v = lerNumero(l, coluna);
        if (v.signum() < 0) {
            throw new Recusa("Valor negativo na coluna '" + coluna + "'");
        }
        if (v.compareTo(limite) >= 0) {
            throw new Recusa("Valor fora do limite na coluna '" + coluna + "'");
        }
        return v.setScale(casas, RoundingMode.HALF_UP);
    }

    private static BigDecimal lerNumero(LinhaCsv l, String coluna) {
        try {
            return NumerosBr.ler(l.valor(coluna));
        } catch (NumerosBr.NumeroInvalido e) {
            throw new Recusa("Valor inválido na coluna '" + coluna + "': " + l.valor(coluna));
        }
    }

    /** Saldo fisico: negativo entra como zero (com aviso); fracao com ate 3 casas (coluna da movimentacao). */
    private static BigDecimal saldo(LinhaCsv l, List<String> avisos) {
        BigDecimal v = lerNumero(l, "Qtd.Estoque (Física)");
        if (v.signum() < 0) {
            avisos.add("Saldo negativo (" + v.stripTrailingZeros().toPlainString() + ") entrou como zero");
            return BigDecimal.ZERO.setScale(3);
        }
        if (v.compareTo(LIM_PRECO) >= 0) {
            throw new Recusa("Valor fora do limite na coluna 'Qtd.Estoque (Física)'");
        }
        return v.setScale(3, RoundingMode.HALF_UP);
    }

    private static int origem(LinhaCsv l) {
        String o = l.valor("Origem Mercadoria");
        if (o.isEmpty()) {
            return 0;
        }
        try {
            int n = Integer.parseInt(o);
            if (n < 0 || n > 8) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new Recusa("Valor inválido na coluna 'Origem Mercadoria': " + o);
        }
    }

    /** GTIN valido ou SEM GTIN; aviso quando o codigo de barras original era outra coisa (I4). */
    private static String gtin(LinhaCsv l, String codigo, List<String> avisos) {
        String original = l.valor("Código de Barra");
        if (original.isEmpty()) {
            original = l.valor("Código de Barra EAN14");
        }
        if (Gtin.valido(original)) {
            return original;
        }
        if (!original.isEmpty() && !Gtin.SEM_GTIN.equalsIgnoreCase(original) && !original.equals(codigo)) {
            avisos.add("Código de barras inválido ignorado (GTIN = SEM GTIN): " + original);
        }
        return Gtin.SEM_GTIN;
    }

    private static String fornecedor(LinhaCsv l) {
        String f = l.valor("Fornecedor Exclusivo");
        if (f.isEmpty()) {
            String[] varios = SEPARADOR_FORNECEDORES.split(l.valor("Fornecedores Relacionados"));
            f = varios.length == 0 ? "" : varios[0].strip();
        }
        return Textos.cortar(f, 180);
    }

    /** Tira os tracos e espacos sobrando de "A - B1 -    -   - X". */
    static String localizacao(LinhaCsv l) {
        String bruto = l.valor("Localização Estoque");
        List<String> partes = new ArrayList<>();
        for (String p : bruto.split("-")) {
            String t = p.strip();
            if (!t.isEmpty()) {
                partes.add(t);
            }
        }
        return partes.isEmpty() ? null : Textos.cortar(String.join(" - ", partes), 100);
    }
}
