package br.com.quack.pdv.produto.entrada.app;

import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.data;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.digitos;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.erro;
import static br.com.quack.pdv.produto.entrada.app.EntradaSuporte.vazio;

import br.com.quack.pdv.cadastro.fornecedor.domain.Fornecedor;
import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.FornecedorPreview;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.ItemPreview;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.PreviewModel;
import br.com.quack.pdv.produto.entrada.web.NfeImportModels.Resultado;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ConfirmRequest;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.DocumentoInput;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ItemInput;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.PreviewRequest;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.produto.app.ProdutoDados;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import br.com.quack.pdv.produto.produto.web.ProdutoRequest;
import br.com.quack.pdv.shared.Dinheiro;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class NfeImportService {
    private final ProdutoService produtos;
    private final FornecedorRepository fornecedores;
    private final NotaEntradaArquivoService arquivo;
    private final LoteOperacoes lotes;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public NfeImportService(
            ProdutoService produtos,
            FornecedorRepository fornecedores,
            NotaEntradaArquivoService arquivo,
            LoteOperacoes lotes,
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock,
            PlatformTransactionManager tm) {
        this.produtos = produtos;
        this.fornecedores = fornecedores;
        this.arquivo = arquivo;
        this.lotes = lotes;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(tm);
    }

    private Optional<Fornecedor> fornecedor(String empresa, String cnpj) {
        return fornecedores.findByCompanyIdOrderByFantasyName(empresa).stream()
                .filter(f -> digitos(f.cnpj).equals(digitos(cnpj)))
                .findFirst();
    }

    public PreviewModel preview(String empresa, PreviewRequest r) {
        byte[] xml =
                NfeXmlParser.decodificarBase64(
                        r.xmlBase64, "Arquivo XML inválido (base64 corrompido).");
        var p = NfeXmlParser.parse(xml);
        var e = p.emitente();
        var existente = fornecedor(empresa, e.cnpj());
        FornecedorPreview f =
                existente
                        .map(
                                x ->
                                        new FornecedorPreview(
                                                true,
                                                x.cnpj,
                                                x.companyName,
                                                x.fantasyName,
                                                x.cep,
                                                x.city,
                                                x.state,
                                                x.address,
                                                x.neighborhood,
                                                x.number,
                                                x.telephone))
                        .orElseGet(
                                () ->
                                        new FornecedorPreview(
                                                false,
                                                e.cnpj(),
                                                e.razaoSocial(),
                                                e.nomeFantasia() == null
                                                        ? e.razaoSocial()
                                                        : e.nomeFantasia(),
                                                e.cep(),
                                                e.municipio(),
                                                e.uf(),
                                                e.logradouro(),
                                                e.bairro(),
                                                e.numero(),
                                                e.telefone() == null ? "" : e.telefone()));
        List<ItemPreview> itens = new ArrayList<>();
        for (var i : p.itens()) {
            ProdutoDados produto = null;
            var ids =
                    jdbc.query(
                            "SELECT TOP 1 ProdutoId FROM MapeamentoProdutoFornecedor WHERE"
                                + " CompanyId=? AND FornecedorCnpj=? AND (CodigoProdutoFornecedor=?"
                                + " OR (? IS NOT NULL AND GtinFornecedor=?)) ORDER BY UpdatedAt"
                                + " DESC",
                            (rs, n) -> rs.getString(1),
                            empresa,
                            e.cnpj(),
                            i.codigoFornecedor(),
                            i.gtin(),
                            i.gtin());
            if (!ids.isEmpty()) produto = produtos.obterDados(empresa, ids.getFirst()).orElse(null);
            if (produto == null && i.gtin() != null)
                produto = produtos.obterPorGtin(empresa, i.gtin()).orElse(null);
            BigDecimal venda = produto == null ? i.valorUnitario() : produto.productSalePrice();
            if (produto != null
                    && produto.margemDesejadaPercentual() != null
                    && produto.margemDesejadaPercentual().signum() > 0)
                venda =
                        i.valorUnitario()
                                .multiply(
                                        BigDecimal.ONE.add(
                                                produto.margemDesejadaPercentual()
                                                        .movePointLeft(2)));
            itens.add(
                    new ItemPreview(
                            i.numeroItem(),
                            produto == null ? null : produto.id(),
                            produto == null ? null : produto.productName(),
                            i.codigoFornecedor(),
                            produto == null
                                    ? (i.gtin() == null ? i.codigoFornecedor() : i.gtin())
                                    : produto.productCode(),
                            i.descricao(),
                            i.gtin() == null ? "SEM GTIN" : i.gtin(),
                            i.ncm(),
                            i.cest(),
                            produto == null ? i.unidadeComercial() : produto.unidadeComercial(),
                            Dinheiro.formatarQuantidade(i.quantidade()),
                            preco(i.valorUnitario()),
                            preco(venda)));
        }
        DocumentoInput d = new DocumentoInput();
        d.xmlBase64 = Base64.getEncoder().encodeToString(xml);
        d.chaveAcesso = p.chaveAcesso();
        d.modelo = p.modelo();
        d.numeroNota = p.numeroNota();
        d.serie = p.serie();
        return new PreviewModel(d, p.modelo(), p.numeroNota(), p.serie(), f, itens);
    }

    record Lote(String produto, BigDecimal quantidade, LocalDate validade, String numero) {}

    private static String preco(BigDecimal valor) {
        var f =
                new java.text.DecimalFormat(
                        "#,##0.00",
                        java.text.DecimalFormatSymbols.getInstance(Locale.forLanguageTag("pt-BR")));
        f.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return f.format(valor);
    }

    public Resultado confirmar(
            String empresa, ConfirmRequest r, String usuarioId, String usuarioNome) {
        if (r.itens.isEmpty()) throw erro("Nenhum item para importar.");
        String cnpj = digitos(r.fornecedor.cnpj);
        if (cnpj.length() != 14) throw erro("CNPJ do fornecedor inválido.");
        if (vazio(r.fornecedor.companyName))
            throw erro("Razão social do fornecedor é obrigatória.");
        var documento = arquivo.preparar(r);
        List<Lote> pendentes = new ArrayList<>();
        Resultado res =
                tx.execute(
                        status -> {
                            arquivo.reservar(empresa, documento);
                            var atuais = produtos.listarDados(empresa);
                            Set<String> codigos = new HashSet<>();
                            for (var i : r.itens) {
                                if (vazio(i.produtoExistenteId))
                                    atuais.stream()
                                            .filter(
                                                    p ->
                                                            p.productCode()
                                                                            .equalsIgnoreCase(
                                                                                    i.productCode
                                                                                            .trim())
                                                                    || (!vazio(i.gtin)
                                                                            && !"SEM GTIN"
                                                                                    .equals(i.gtin)
                                                                            && i.gtin.trim()
                                                                                    .equalsIgnoreCase(
                                                                                            p
                                                                                                    .gtin())))
                                            .findFirst()
                                            .ifPresent(p -> i.produtoExistenteId = p.id());
                                if (vazio(i.produtoExistenteId)
                                        && !codigos.add(
                                                i.productCode.trim().toLowerCase(Locale.ROOT)))
                                    throw erro(
                                            "Código de produto \""
                                                    + i.productCode.trim()
                                                    + "\" repetido em mais de um item novo da nota"
                                                    + " — ajuste antes de confirmar.");
                            }
                            for (var i : r.itens) {
                                validade(i);
                                if (Dinheiro.tentarLer(i.quantidade).isEmpty()
                                        || Dinheiro.ler(i.quantidade).signum() <= 0)
                                    throw erro(
                                            "Quantidade inválida no item \""
                                                    + i.productName
                                                    + "\".");
                                if (Dinheiro.tentarLer(i.precoCusto).isEmpty()
                                        || Dinheiro.ler(i.precoCusto).signum() < 0)
                                    throw erro("Custo inválido no item \"" + i.productName + "\".");
                                if (!vazio(i.produtoExistenteId)) {
                                    if (atuais.stream()
                                            .noneMatch(p -> p.id().equals(i.produtoExistenteId)))
                                        throw erro(
                                                "Produto vinculado não encontrado no item "
                                                        + i.numeroItem
                                                        + ".");
                                    continue;
                                }
                                if (vazio(i.productCode))
                                    throw erro(
                                            "Código do produto é obrigatório no item "
                                                    + i.numeroItem
                                                    + ".");
                                if (vazio(i.productName) || i.productName.trim().length() < 3)
                                    throw erro(
                                            "Nome do produto muito curto no item "
                                                    + i.numeroItem
                                                    + ".");
                                if (Dinheiro.tentarLer(i.precoVenda).isEmpty()
                                        || Dinheiro.ler(i.precoVenda).signum() <= 0)
                                    throw erro(
                                            "Preço de venda deve ser maior que zero no item \""
                                                    + i.productName
                                                    + "\".");
                            }
                            var anterior = fornecedor(empresa, r.fornecedor.cnpj);
                            var f = anterior.orElseGet(Fornecedor::new);
                            boolean criado = anterior.isEmpty();
                            if (criado) {
                                f.id = "fr-" + clock.millis();
                                f.companyId = empresa;
                            }
                            f.companyName = r.fornecedor.companyName.trim();
                            f.fantasyName =
                                    vazio(r.fornecedor.fantasyName)
                                            ? f.companyName
                                            : r.fornecedor.fantasyName.trim();
                            f.cnpj = r.fornecedor.cnpj.trim();
                            f.cep = r.fornecedor.cep.trim();
                            f.city = r.fornecedor.city.trim();
                            f.state = r.fornecedor.state.trim();
                            f.address = r.fornecedor.address.trim();
                            f.neighborhood = r.fornecedor.neighborhood.trim();
                            f.number = r.fornecedor.number.trim();
                            if (!vazio(r.fornecedor.telephone))
                                f.telephone = r.fornecedor.telephone.trim();
                            if (criado) f.cellphone = r.fornecedor.telephone.trim();
                            fornecedores.saveAndFlush(f);
                            Resultado resultado = new Resultado();
                            resultado.fornecedorCriado = criado;
                            var origem =
                                    new MovimentoOrigem(
                                            "nfe",
                                            documento.chave() == null
                                                    ? documento.numero()
                                                    : documento.chave(),
                                            "Entrada por NF-e " + documento.numero(),
                                            vazio(usuarioId) ? "sistema" : usuarioId,
                                            vazio(usuarioNome) ? "Sistema" : usuarioNome);
                            int indice = 0;
                            for (var i : r.itens) {
                                indice++;
                                BigDecimal quantidade = Dinheiro.ler(i.quantidade),
                                        custo = Dinheiro.ler(i.precoCusto);
                                if (!vazio(i.produtoExistenteId)) {
                                    produtos.entradaEstoque(
                                            empresa,
                                            i.produtoExistenteId,
                                            quantidade,
                                            custo,
                                            false,
                                            null,
                                            origem);
                                    resultado.produtosAtualizados++;
                                    String cod =
                                            !vazio(i.codigoFornecedor)
                                                    ? i.codigoFornecedor
                                                    : !vazio(i.gtin) && !"SEM GTIN".equals(i.gtin)
                                                            ? i.gtin
                                                            : null;
                                    if (cod != null) salvarMapeamento(empresa, cnpj, cod, i);
                                } else {
                                    Map<String, Object> campos = new HashMap<>();
                                    campos.put("productCode", i.productCode.trim());
                                    campos.put("productName", i.productName.trim());
                                    campos.put("productSupplier", f.fantasyName);
                                    campos.put("productQnt", i.quantidade);
                                    campos.put("productUnitPrice", i.precoCusto);
                                    campos.put("productSalePrice", i.precoVenda);
                                    if (!vazio(i.margemPercentual)
                                            && Dinheiro.ler(i.margemPercentual).signum() > 0) {
                                        campos.put("margemDesejadaPercentual", i.margemPercentual);
                                        campos.put("markupCadastrado", i.margemPercentual);
                                    }
                                    campos.put("ncm", vazio(i.ncm) ? "00000000" : i.ncm.trim());
                                    campos.put("cest", vazio(i.cest) ? null : i.cest.trim());
                                    campos.put("cfop", "5102");
                                    String unidade =
                                            vazio(i.unidadeComercial)
                                                    ? "UN"
                                                    : i.unidadeComercial
                                                            .trim()
                                                            .toUpperCase(Locale.ROOT);
                                    campos.put("unidadeComercial", unidade);
                                    campos.put("unidadeTributavel", unidade);
                                    campos.put("gtin", vazio(i.gtin) ? "SEM GTIN" : i.gtin.trim());
                                    campos.put("csosnIcms", "102");
                                    campos.put("cstPis", "07");
                                    campos.put("cstCofins", "07");
                                    i.produtoExistenteId = "pr-" + clock.millis() + "-" + indice;
                                    produtos.salvarDados(
                                            empresa,
                                            ProdutoService.mapearRequest(
                                                    i.produtoExistenteId,
                                                    mapper.convertValue(
                                                            campos, ProdutoRequest.class)),
                                            origem);
                                    resultado.produtosCriados++;
                                }
                                pendentes.add(
                                        new Lote(
                                                i.produtoExistenteId,
                                                quantidade,
                                                validade(i),
                                                i.numeroLote));
                            }
                            resultado.notaEntradaId =
                                    arquivo.salvar(
                                            empresa,
                                            r,
                                            documento,
                                            usuarioId,
                                            usuarioNome,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null);
                            return resultado;
                        });
        for (var l : pendentes)
            lotes.registrarEntradaSegura(
                    empresa,
                    l.produto,
                    l.validade,
                    l.quantidade,
                    l.numero,
                    "nfe",
                    "Importação NF-e");
        return res;
    }

    private static LocalDate validade(ItemInput i) {
        return vazio(i.dataValidade)
                ? null
                : data(
                        i.dataValidade.trim(),
                        "Data de validade inválida no item \"" + i.productName + "\".");
    }

    private void salvarMapeamento(String empresa, String cnpj, String codigo, ItemInput i) {
        jdbc.update(
                """
MERGE MapeamentoProdutoFornecedor AS alvo
USING (SELECT ? AS CompanyId, ? AS FornecedorCnpj, ? AS CodigoProdutoFornecedor) AS fonte
ON alvo.CompanyId=fonte.CompanyId AND alvo.FornecedorCnpj=fonte.FornecedorCnpj AND alvo.CodigoProdutoFornecedor=fonte.CodigoProdutoFornecedor
WHEN MATCHED THEN UPDATE SET ProdutoId=?,GtinFornecedor=?,DescricaoFornecedor=?,UpdatedAt=SYSUTCDATETIME()
WHEN NOT MATCHED THEN INSERT (Id,CompanyId,FornecedorCnpj,CodigoProdutoFornecedor,ProdutoId,GtinFornecedor,DescricaoFornecedor,CreatedAt,UpdatedAt) VALUES (?,?,?,?,?,?,?,SYSUTCDATETIME(),SYSUTCDATETIME());
""",
                empresa,
                cnpj,
                codigo,
                i.produtoExistenteId,
                i.gtin,
                i.productName,
                "mpf-" + UUID.randomUUID().toString().replace("-", ""),
                empresa,
                cnpj,
                codigo,
                i.produtoExistenteId,
                i.gtin,
                i.productName);
    }

    public void estornar(
            String empresa, String id, String usuarioId, String usuarioNome, String justificativa) {
        if (vazio(justificativa) || justificativa.trim().length() < 5)
            throw erro(
                    "Informe uma justificativa com pelo menos 5 caracteres para estornar a nota.");
        var nota =
                arquivo.obter(empresa, id)
                        .filter(n -> !n.nota().estornada())
                        .orElseThrow(
                                () -> erro("Nota de entrada não encontrada ou já foi estornada."));
        var itens =
                nota.entrada().has("itens")
                        ? nota.entrada().path("itens")
                        : nota.entrada().path("Itens");
        for (var i : itens) {
            String pid =
                    i.path("produtoExistenteId").asText(i.path("ProdutoExistenteId").asText(null));
            String qtd = i.path("quantidade").asText(i.path("Quantidade").asText(null));
            var quantidade = Dinheiro.tentarLer(qtd);
            if (vazio(pid) || quantidade.isEmpty() || quantidade.get().signum() <= 0) continue;
            try {
                produtos.estornarEstoque(
                        empresa,
                        pid,
                        quantidade.get(),
                        new MovimentoOrigem(
                                "nota_entrada",
                                id,
                                "Estorno de nota de entrada: " + justificativa.trim(),
                                usuarioId,
                                usuarioNome));
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(NfeImportService.class)
                        .warn("Falha ao estornar produto {} da nota {}", pid, id, ex);
            }
        }
        if (!arquivo.marcarEstornada(empresa, id, usuarioId, usuarioNome, justificativa.trim()))
            throw erro("Não foi possível estornar esta nota. Ela pode já ter sido estornada.");
    }
}
