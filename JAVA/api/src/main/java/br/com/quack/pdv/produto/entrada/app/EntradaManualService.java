package br.com.quack.pdv.produto.entrada.app;

import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.data;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.digitos;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.erro;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.hash;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.numero;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.vazio;

import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.Resultado;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ComprovanteInput;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ConfirmRequest;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.EntradaManualRequest;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.FornecedorInput;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ManualItem;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoDados;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import br.com.quack.pdv.produto.produto.web.ProdutoRequest;
import br.com.quack.pdv.shared.Dinheiro;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EntradaManualService {
    private final ProdutoService produtos;
    private final FornecedorRepository fornecedores;
    private final NotaEntradaArquivoService arquivo;
    private final LoteOperacoes lotes;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public EntradaManualService(
            ProdutoService produtos,
            FornecedorRepository fornecedores,
            NotaEntradaArquivoService arquivo,
            LoteOperacoes lotes,
            ObjectMapper mapper,
            PlatformTransactionManager tm) {
        this.produtos = produtos;
        this.fornecedores = fornecedores;
        this.arquivo = arquivo;
        this.lotes = lotes;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
    }

    record Validado(
            ManualItem item,
            BigDecimal quantidade,
            BigDecimal custo,
            BigDecimal venda,
            LocalDate validade) {}

    record Lote(String produto, BigDecimal quantidade, LocalDate validade, String numero) {}

    public Resultado confirmar(
            String empresa, EntradaManualRequest r, String usuarioId, String usuarioNome) {
        String operacao;
        try {
            String limpa = r.operacaoId.replace("{", "").replace("}", "").replace("-", "");
            if (!limpa.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException();
            operacao = limpa.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            throw erro("Identificação da entrada inválida. Abra uma nova entrada.");
        }
        data(r.dataCompra, "Informe uma data de compra válida.");
        if (r.itens == null || r.itens.isEmpty() || r.itens.size() > 200)
            throw erro("Adicione entre 1 e 200 produtos à entrada.");
        if (!Set.of("cupom", "recibo", "outro").contains(r.tipoDocumento))
            throw erro("Tipo de comprovante inválido.");
        if (r.numeroDocumento.length() > 30 || r.observacao.length() > 1000)
            throw erro("Número do documento ou observação muito longo.");
        String fingerprint = hash(JsonWeb.escrever(r.paraMapa()));
        String chave = vazio(r.chaveAcesso) ? null : NfeAccessKey.validateInvoiceKey(r.chaveAcesso);
        String identidade = hash(chave == null ? "manual:" + operacao : chave);
        Comprovante anexo = prepararComprovante(r.comprovante);
        List<Lote> pendentes = new ArrayList<>();
        Resultado resultado =
                tx.execute(
                        status -> {
                            arquivo.travar(
                                    "entrada-manual:" + empresa + ":" + operacao,
                                    "Esta entrada está em processamento. Tente novamente.");
                            arquivo.travar(
                                    "nota-entrada:" + empresa + ":" + identidade,
                                    "Esta entrada está em processamento. Tente novamente.");
                            var anterior = arquivo.anterior(empresa, operacao, identidade);
                            if (anterior.isPresent()) {
                                var salva = anterior.get();
                                var entrada = salva.entrada();
                                if (operacao.equals(entrada.path("operacaoId").asText())) {
                                    if (!fingerprint.equals(entrada.path("fingerprint").asText()))
                                        throw erro(
                                                "Esta entrada já foi confirmada com outros dados."
                                                    + " Consulte o histórico e inicie uma nova"
                                                    + " entrada.");
                                    Resultado replay =
                                            mapper.convertValue(
                                                    entrada.path("resultado"), Resultado.class);
                                    replay.notaEntradaId = salva.id();
                                    return replay;
                                }
                                throw erro(
                                        "Esta nota já deu entrada no sistema. Consulte Notas de"
                                            + " entrada; o estoque não foi alterado.");
                            }
                            var fornecedor =
                                    vazio(r.fornecedorId)
                                            ? null
                                            : fornecedores
                                                    .findByCompanyIdAndId(empresa, r.fornecedorId)
                                                    .orElse(null);
                            if (!vazio(r.fornecedorId) && fornecedor == null)
                                throw erro("Fornecedor não encontrado nesta empresa.");
                            if (chave != null
                                    && fornecedor != null
                                    && !chave.substring(6, 20).equals(digitos(fornecedor.cnpj)))
                                throw erro("A chave não corresponde ao fornecedor selecionado.");
                            List<Validado> validacoes = new ArrayList<>();
                            Set<String> codigos = new HashSet<>();
                            BigDecimal total = BigDecimal.ZERO;
                            for (int indice = 0; indice < r.itens.size(); indice++) {
                                ManualItem i = r.itens.get(indice);
                                if (i == null) throw erro("Item inválido na entrada.");
                                BigDecimal compra =
                                        positivo(
                                                i.quantidadeCompra,
                                                "Quantidade da compra",
                                                "1000000");
                                BigDecimal fator =
                                        positivo(i.fatorConversao, "Fator de conversão", "1000000");
                                BigDecimal custoCompra =
                                        positivo(i.custoCompra, "Custo da compra", "1000000000");
                                BigDecimal quantidade = compra.multiply(fator);
                                if (quantidade.compareTo(new BigDecimal("1000000000")) > 0
                                        || quantidade.stripTrailingZeros().scale() > 4)
                                    throw erro(
                                            "Quantidade convertida deve ter no máximo quatro casas"
                                                + " decimais e estar dentro do limite de estoque.");
                                BigDecimal custo =
                                        custoCompra.divide(fator, 4, RoundingMode.HALF_UP);
                                if (custo.signum() <= 0)
                                    throw erro(
                                            "O custo convertido por unidade deve ser maior que"
                                                + " zero.");
                                BigDecimal venda =
                                        i.alterarPrecoVenda || vazio(i.produtoExistenteId)
                                                ? positivo(
                                                        i.precoVenda,
                                                        "Preço de venda",
                                                        "1000000000")
                                                : null;
                                if (venda != null && venda.stripTrailingZeros().scale() > 2)
                                    throw erro(
                                            "Preço de venda deve ter no máximo duas casas"
                                                + " decimais.");
                                if (i.unidadeCompra.trim().isEmpty()
                                        || i.unidadeCompra.trim().length() > 6
                                        || i.unidadeComercial.trim().isEmpty()
                                        || i.unidadeComercial.trim().length() > 6
                                        || (i.numeroLote != null && i.numeroLote.length() > 60))
                                    throw erro("Unidade ou lote inválido.");
                                LocalDate validade =
                                        vazio(i.dataValidade)
                                                ? null
                                                : data(
                                                        i.dataValidade,
                                                        "Data de validade inválida.");
                                if (vazio(i.produtoExistenteId)) {
                                    i.productCode =
                                            vazio(i.productCode)
                                                    ? "EM-"
                                                            + operacao.substring(0, 12)
                                                            + "-"
                                                            + (indice + 1)
                                                    : i.productCode.trim();
                                    if (i.productCode.length() > 50
                                            || vazio(i.productName)
                                            || i.productName.length() > 250)
                                        throw erro(
                                                "Informe uma descrição de até 250 caracteres e"
                                                    + " código de até 50 caracteres.");
                                    if (!codigos.add(i.productCode.toLowerCase(Locale.ROOT))
                                            || produtos.obterPorCodigo(empresa, i.productCode)
                                                    .isPresent())
                                        throw erro(
                                                "Código "
                                                        + i.productCode
                                                        + " já cadastrado ou repetido. Selecione o"
                                                        + " produto existente.");
                                    if (!i.ncm.matches("[0-9]{8}"))
                                        throw erro(
                                                "NCM deve conter oito números, quando informado.");
                                }
                                validacoes.add(new Validado(i, quantidade, custo, venda, validade));
                                total =
                                        total.add(
                                                compra.multiply(custoCompra)
                                                        .setScale(2, RoundingMode.HALF_UP));
                            }
                            Resultado res = new Resultado();
                            var origem =
                                    new MovimentoOrigem(
                                            "nota_entrada",
                                            operacao,
                                            "Entrada manual",
                                            vazio(usuarioId) ? "sistema" : usuarioId,
                                            vazio(usuarioNome) ? "Sistema" : usuarioNome);
                            validacoes.sort(
                                    Comparator.comparing(
                                            v ->
                                                    v.item.produtoExistenteId == null
                                                            ? v.item.productCode
                                                            : v.item.produtoExistenteId));
                            for (var v : validacoes) {
                                var i = v.item;
                                ProdutoDados p;
                                BigDecimal antes;
                                if (!vazio(i.produtoExistenteId)) {
                                    p =
                                            produtos.entradaEstoque(
                                                    empresa,
                                                    i.produtoExistenteId,
                                                    v.quantidade,
                                                    v.custo,
                                                    true,
                                                    v.venda,
                                                    origem);
                                    if (!i.unidadeComercial
                                            .trim()
                                            .equalsIgnoreCase(p.unidadeComercial()))
                                        throw erro(
                                                "A unidade de venda do produto mudou. Atualize o"
                                                    + " produto selecionado antes de confirmar.");
                                    antes = p.productQnt().subtract(v.quantidade);
                                    res.produtosAtualizados++;
                                } else {
                                    Map<String, Object> campos = new HashMap<>();
                                    campos.put("productCode", i.productCode);
                                    campos.put("productName", i.productName.trim());
                                    campos.put("productQnt", numero(v.quantidade));
                                    campos.put("productUnitPrice", numero(v.custo));
                                    campos.put("productSalePrice", numero(v.venda));
                                    campos.put(
                                            "productSupplier",
                                            fornecedor == null
                                                    ? ""
                                                    : vazio(fornecedor.fantasyName)
                                                            ? fornecedor.companyName
                                                            : fornecedor.fantasyName);
                                    campos.put(
                                            "unidadeCompra",
                                            i.unidadeCompra.trim().toUpperCase(Locale.ROOT));
                                    campos.put("fatorConversao", i.fatorConversao);
                                    campos.put("qtdEmbalagem", i.fatorConversao);
                                    campos.put(
                                            "unidadeComercial",
                                            i.unidadeComercial.trim().toUpperCase(Locale.ROOT));
                                    campos.put(
                                            "unidadeTributavel",
                                            i.unidadeComercial.trim().toUpperCase(Locale.ROOT));
                                    campos.put("ncm", i.ncm);
                                    campos.put("gtin", "SEM GTIN");
                                    campos.put("csosnIcms", "102");
                                    campos.put("custoMedio", numero(v.custo));
                                    campos.put("custoComImposto", numero(v.custo));
                                    campos.put("custoSemImposto", numero(v.custo));
                                    p =
                                            produtos.salvarDados(
                                                    empresa,
                                    ProdutoService.mapearEntradaManual(
                                                            "pr-"
                                                                    + UUID.randomUUID()
                                                                            .toString()
                                                                            .replace("-", ""),
                                                            mapper.convertValue(
                                                                    campos, ProdutoRequest.class)),
                                                    origem);
                                    antes = BigDecimal.ZERO;
                                    res.produtosCriados++;
                                }
                                i.produtoExistenteId = p.id();
                                i.productCode = p.productCode();
                                i.productName = p.productName();
                                i.unidadeComercial = p.unidadeComercial();
                                i.quantidade = numero(v.quantidade);
                                i.precoCusto = numero(v.custo);
                                i.precoVenda = numero(p.productSalePrice());
                                i.estoqueAntes = numero(antes);
                                i.estoqueDepois = numero(p.productQnt());
                                pendentes.add(
                                        new Lote(p.id(), v.quantidade, v.validade, i.numeroLote));
                            }
                            FornecedorInput f = new FornecedorInput();
                            if (fornecedor != null) {
                                f.companyName = fornecedor.companyName;
                                f.fantasyName = fornecedor.fantasyName;
                                f.cnpj = fornecedor.cnpj;
                            }
                            ConfirmRequest nf = new ConfirmRequest();
                            nf.fornecedor = f;
                            nf.itens = new ArrayList<>(r.itens);
                            var documento =
                                    new NotaEntradaArquivoService.Documento(
                                            identidade,
                                            chave,
                                            chave == null
                                                    ? 0
                                                    : Integer.parseInt(chave.substring(20, 22)),
                                            r.numeroDocumento.trim(),
                                            chave == null
                                                    ? ""
                                                    : Integer.toString(
                                                            Integer.parseInt(
                                                                    chave.substring(22, 25))),
                                            r.dataCompra,
                                            total,
                                            "manual",
                                            null);
                            Map<String, Object> entrada = new LinkedHashMap<>();
                            entrada.put("operacaoId", operacao);
                            entrada.put("fingerprint", fingerprint);
                            entrada.put("dataCompra", r.dataCompra);
                            entrada.put("tipoDocumento", r.tipoDocumento);
                            entrada.put("observacao", r.observacao);
                            entrada.put("fornecedor", f.paraMapa());
                            entrada.put(
                                    "itens", r.itens.stream().map(ManualItem::paraMapa).toList());
                            Map<String, Object> recibo = new LinkedHashMap<>();
                            recibo.put("notaEntradaId", "");
                            recibo.put("fornecedorCriado", false);
                            recibo.put("produtosCriados", res.produtosCriados);
                            recibo.put("produtosAtualizados", res.produtosAtualizados);
                            entrada.put("resultado", recibo);
                            res.notaEntradaId =
                                    arquivo.salvar(
                                            empresa,
                                            nf,
                                            documento,
                                            usuarioId,
                                            usuarioNome,
                                            entrada,
                                            anexo == null ? null : anexo.bytes,
                                            anexo == null ? null : anexo.nome,
                                            anexo == null ? null : anexo.tipo,
                                            total,
                                            operacao);
                            return res;
                        });
        for (var l : pendentes)
            lotes.registrarEntradaSegura(
                    empresa, l.produto, l.validade, l.quantidade, l.numero, "manual", usuarioNome);
        return resultado;
    }

    static BigDecimal positivo(String valor, String campo, String maximo) {
        var n = Dinheiro.tentarLer(valor);
        if (n.isEmpty()
                || n.get().signum() <= 0
                || n.get().compareTo(new BigDecimal(maximo)) > 0
                || n.get().stripTrailingZeros().scale() > 4)
            throw erro(
                    campo
                            + " deve ser maior que zero, com até quatro casas decimais e dentro do"
                            + " limite permitido.");
        return n.get();
    }

    record Comprovante(byte[] bytes, String nome, String tipo) {}

    static Comprovante prepararComprovante(ComprovanteInput c) {
        if (c == null) return null;
        if (c.base64.length() > 14000000) throw erro("Comprovante deve ter no máximo 10 MB.");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(c.base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException ex) {
            throw erro("Comprovante inválido. Selecione o arquivo novamente.");
        }
        String nome = c.nome.replace('\\', '/');
        nome = nome.substring(nome.lastIndexOf('/') + 1);
        if (bytes.length == 0
                || bytes.length > 10485760
                || nome.isEmpty()
                || nome.length() > 180
                || nome.chars().anyMatch(Character::isISOControl))
            throw erro("Nome ou tamanho do comprovante inválido.");
        String ext = nome.substring(Math.max(0, nome.lastIndexOf('.'))).toLowerCase(Locale.ROOT);
        String tipo =
                switch (ext) {
                    case ".pdf" -> "application/pdf";
                    case ".png" -> "image/png";
                    case ".jpg", ".jpeg" -> "image/jpeg";
                    case ".xml" -> "application/xml";
                    default -> null;
                };
        byte[] prefixo =
                switch (ext) {
                    case ".pdf" -> new byte[] {37, 80, 68, 70, 45};
                    case ".png" -> new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
                    case ".jpg", ".jpeg" -> new byte[] {(byte) 255, (byte) 216, (byte) 255};
                    case ".xml" -> new byte[0];
                    default -> null;
                };
        if (tipo == null
                || bytes.length < prefixo.length
                || !Arrays.equals(Arrays.copyOf(bytes, prefixo.length), prefixo))
            throw erro("Selecione uma imagem JPG/PNG, PDF ou XML válido.");
        return new Comprovante(bytes, nome, tipo);
    }
}
