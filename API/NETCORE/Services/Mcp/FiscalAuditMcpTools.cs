/**
 * Arquivo: API/NETCORE/Services/Mcp/FiscalAuditMcpTools.cs
 * Objetivo: ferramentas MCP de auditoria fiscal para agentes de IA consultarem dados tributários
 *           dos produtos cadastrados e notas fiscais de entrada. SOMENTE LEITURA.
 * Entradas esperadas: chamadas MCP já autenticadas pelo HorusAuthMiddleware com token de integração.
 * Isolamento: a empresa vem sempre do token (HttpContext.Items["CurrentUser"]); nunca de parâmetro.
 * Documentação: docs/MCP.md (ao criar/alterar ferramenta, atualize lá também).
 */
using System.ComponentModel;
using System.Text.Encodings.Web;
using System.Text.Json;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using ModelContextProtocol;
using ModelContextProtocol.Server;
using static HORUSPDV_API.Repositories.DatabaseAccess.ProdutoAB;

namespace HORUSPDV_API.Services.Mcp;

[McpServerToolType]
public class FiscalAuditMcpTools(
    IHttpContextAccessor httpContextAccessor,
    ProdutoAB produtoAB,
    EmpresaAB empresaAB,
    FiscalReferenceTables fiscalTables,
    FiscalCestReference cestReference,
    NotaEntradaArquivoService notaEntradaService)
{
    private const int MaxPageSize = 200;

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    // ---------------------------------------------------------------- Produtos fiscais

    [McpServerTool(Name = "listar_produtos_fiscais", Title = "Listar produtos fiscais",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista produtos com seus dados fiscais (NCM, CEST, CFOP, CST/CSOSN, alíquotas, PIS, COFINS, IBS/CBS). " +
        "Para a conferência fiscal detalhada de um produto use obter_fiscal_produto. " +
        "Paginado: confira temMais antes de concluir que viu tudo.")]
    public async Task<string> ListarProdutosFiscais(
        [Description("Trecho do nome, código ou GTIN. Ignora acentos e maiúsculas.")] string? busca = null,
        [Description("Id de departamento ou subcategoria (ver listar_categorias).")] string? categoriaId = null,
        [Description("true = só produtos com NCM ausente ou padrão (00000000).")] bool somenteSemNcm = false,
        [Description("true = só produtos sem CEST informado.")] bool somenteSemCest = false,
        [Description("Página, a partir de 1.")] int pagina = 1,
        [Description("Itens por página (1 a 200, padrão 100).")] int tamanhoPagina = 100)
    {
        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 100);
        var data = await produtoAB.ListarFiscalAsync(CurrentCompanyId(), busca, categoriaId,
            somenteSemNcm, somenteSemCest, page, size);

        return Serialize(new
        {
            total = data.Total,
            pagina = page,
            tamanhoPagina = size,
            temMais = page * size < data.Total,
            itens = data.Itens.Select(p => new
            {
                id = p.Id,
                codigo = p.ProductCode,
                nome = p.ProductName,
                gtin = p.Gtin,
                marca = p.Marca,
                categoriaId = p.CategoriaId,
                categoria = p.CategoriaNome,
                unidadeComercial = p.UnidadeComercial,
                unidadeTributavel = p.UnidadeTributavel,
                ncm = p.Ncm,
                cest = p.Cest,
                cfop = p.Cfop,
                origemMercadoria = p.OrigemMercadoria,
                csosnIcms = p.CsosnIcms,
                cstIcms = p.CstIcms,
                aliquotaIcms = p.AliquotaIcms,
                cstPis = p.CstPis,
                cstCofins = p.CstCofins,
                cstIbsCbs = p.CstIbsCbs,
                cClassTrib = p.CClassTrib
            })
        });
    }

    // ---------------------------------------------------------------- Conferência fiscal de um produto

    [McpServerTool(Name = "obter_fiscal_produto", Title = "Conferência fiscal de produto",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Retorna todos os dados fiscais de um produto e executa validação determinística contra " +
        "as tabelas oficiais (NCM, CEST, cClassTrib). Os apontamentos indicam o nível (erro, aviso, informação), " +
        "o campo e uma explicação objetiva. Não substitui revisão contábil.")]
    public async Task<string> ObterFiscalProduto(
        [Description("Id do produto, código interno ou código de barras (GTIN) exato.")] string idOuCodigo)
    {
        var chave = (idOuCodigo ?? string.Empty).Trim();
        if (chave.Length == 0) throw new McpException("Informe o id, código ou GTIN do produto.");

        var companyId = CurrentCompanyId();
        var produto = await produtoAB.ObterAsync(companyId, chave)
            ?? await produtoAB.ObterPorCodigoAsync(companyId, chave)
            ?? await produtoAB.ObterPorGtinAsync(companyId, chave);
        if (produto is null)
            throw new McpException($"Produto '{chave}' não encontrado. Use buscar_produtos para localizar pelo nome.");

        var empresa = await empresaAB.ObterExataAsync(companyId);
        if (empresa is null)
            throw new McpException("Não foi possível obter os dados fiscais da empresa.");

        var tables = await fiscalTables.GetAsync();
        var report = ProductFiscalReview.Check(produto, empresa, tables,
            HORUSPDV_API.Services.Shared.HorusDateTime.Now, cestReference.Items);

        return Serialize(new
        {
            produto = new
            {
                id = produto.Id,
                codigo = produto.ProductCode,
                nome = produto.ProductName,
                gtin = produto.Gtin,
                marca = produto.Marca,
                categoriaId = produto.CategoriaId,
                categoriaNome = produto.CategoriaNome,
                unidadeComercial = produto.UnidadeComercial,
                unidadeTributavel = produto.UnidadeTributavel,
                ncm = produto.Ncm,
                cest = produto.Cest,
                cfop = produto.Cfop,
                origemMercadoria = produto.OrigemMercadoria,
                csosnIcms = produto.CsosnIcms,
                cstIcms = produto.CstIcms,
                aliquotaIcms = produto.AliquotaIcms,
                cstPis = produto.CstPis,
                cstCofins = produto.CstCofins,
                cstIbsCbs = produto.CstIbsCbs,
                cClassTrib = produto.CClassTrib
            },
            empresa = new
            {
                crt = empresa.Crt,
                uf = empresa.Uf,
                ambienteFiscal = empresa.AmbienteFiscal
            },
            verificadoEm = report.VerificadoEm,
            fontesOnline = report.FontesOnline,
            dataBaseTabelas = report.DataBase,
            descricaoNcm = report.DescricaoNcm,
            descricaoClassificacao = report.DescricaoClassificacao,
            apontamentos = report.Apontamentos.Select(a => new
            {
                nivel = a.Nivel,
                campo = a.Campo,
                mensagem = a.Mensagem
            })
        });
    }

    // ---------------------------------------------------------------- Resumo de auditoria fiscal

    [McpServerTool(Name = "resumo_auditoria_fiscal", Title = "Resumo de auditoria fiscal",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Indicadores objetivos de qualidade do cadastro fiscal: total de produtos, quantos sem NCM, " +
        "sem GTIN, sem CFOP, sem CST/CSOSN de ICMS, sem PIS, sem COFINS, com formato inválido e com campos " +
        "fiscais inconsistentes. Diferencia erro estrutural confirmado de campo ausente. " +
        "Não implementa regras tributárias presumidas.")]
    public async Task<string> ResumoAuditoriaFiscal()
    {
        var companyId = CurrentCompanyId();
        var contadores = await produtoAB.ContarPendenciasFiscaisAsync(companyId);

        // Contagem de GTIN com checksum inválido requer cálculo em memória.
        // Carrega apenas Id e Gtin dos produtos que passam pelo filtro SQL de formato válido.
        var gtinCheckInvalido = 0;
        var produtos = await produtoAB.ListarAsync(companyId);
        foreach (var p in produtos)
        {
            if (p.Gtin != "SEM GTIN" && !string.IsNullOrWhiteSpace(p.Gtin)
                && p.Gtin.Length is 8 or 12 or 13 or 14 && p.Gtin.All(char.IsAsciiDigit)
                && !ProductFiscalReview.ValidGtin(p.Gtin))
            {
                gtinCheckInvalido++;
            }
        }

        return Serialize(new
        {
            totalProdutos = contadores.Total,
            errosEstruturais = new
            {
                ncmAusente = contadores.SemNcm,
                ncmFormatoInvalido = contadores.NcmFormatoInvalido,
                cfopAusente = contadores.SemCfop,
                cfopFormatoInvalido = contadores.CfopFormatoInvalido,
                icmsAusente = contadores.SemIcms,
                pisAusente = contadores.SemPis,
                cofinsAusente = contadores.SemCofins,
                cestFormatoInvalido = contadores.CestFormatoInvalido,
                origemInvalida = contadores.OrigemInvalida,
                ibsCbsIncompleto = contadores.IbsIncompleto,
                gtinChecksumInvalido = gtinCheckInvalido
            },
            camposAusentes = new
            {
                gtinNaoInformado = contadores.SemGtin,
                gtinFormatoInvalido = contadores.GtinFormatoInvalido
            },
            avisos = new
            {
                unidadeDivergente = contadores.UnidadeDivergente
            },
            observacao = "Ausência de CEST ou de GTIN não é automaticamente um erro tributário. " +
                "Use obter_fiscal_produto para conferir cada produto contra as tabelas oficiais. " +
                "O checksum do GTIN verifica o dígito verificador, não o vínculo GS1 com o produto."
        });
    }

    // ---------------------------------------------------------------- Pendências fiscais

    [McpServerTool(Name = "listar_pendencias_fiscais", Title = "Listar pendências fiscais",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista paginada de produtos com problemas fiscais estruturais. Cada item informa o produto, " +
        "o campo, o valor atual e o tipo de problema. Diferencia erro confirmado de campo ausente. " +
        "Para a conferência completa de um produto (com NCM oficial e CEST) use obter_fiscal_produto.")]
    public async Task<string> ListarPendenciasFiscais(
        [Description("Tipo de pendência: ncm_ausente, ncm_formato, gtin_ausente, gtin_formato, cfop_ausente, " +
            "cfop_formato, icms_ausente, pis_ausente, cofins_ausente, cest_formato, origem_invalida, " +
            "unidade_divergente, ibs_incompleto. Vazio = todas.")] string? tipoPendencia = null,
        [Description("Id de departamento ou subcategoria.")] string? categoriaId = null,
        [Description("Página, a partir de 1.")] int pagina = 1,
        [Description("Itens por página (1 a 200, padrão 50).")] int tamanhoPagina = 50)
    {
        var tipo = (tipoPendencia ?? "").Trim().ToLowerInvariant();
        var tiposValidos = new HashSet<string> { "", "todas", "ncm_ausente", "ncm_formato", "gtin_ausente",
            "gtin_formato", "cfop_ausente", "cfop_formato", "icms_ausente", "pis_ausente", "cofins_ausente",
            "cest_formato", "origem_invalida", "unidade_divergente", "ibs_incompleto" };
        if (!tiposValidos.Contains(tipo))
            throw new McpException($"tipoPendencia '{tipoPendencia}' inválido. Valores aceitos: {string.Join(", ", tiposValidos.Where(t => t.Length > 0))}.");

        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 50);
        var data = await produtoAB.ListarPendenciasAsync(CurrentCompanyId(), tipo, categoriaId, page, size);

        var pendencias = new List<object>();
        foreach (var p in data.Itens)
        {
            foreach (var (campo, valorAtual, tipoProblema, regra, explicacao) in ClassificarPendencias(p))
            {
                pendencias.Add(new
                {
                    produtoId = p.Id,
                    codigo = p.ProductCode,
                    nome = p.ProductName,
                    campo,
                    valorAtual,
                    tipoProblema,
                    regra,
                    explicacao
                });
            }
        }

        return Serialize(new
        {
            totalProdutos = data.Total,
            pagina = page,
            tamanhoPagina = size,
            temMais = page * size < data.Total,
            pendencias
        });
    }

    // ---------------------------------------------------------------- Notas de entrada

    [McpServerTool(Name = "listar_notas_entrada", Title = "Listar notas de entrada",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Lista notas fiscais de entrada (compras de fornecedores) registradas no sistema. " +
        "Cada nota informa chave de acesso, número, série, fornecedor, data de emissão, valor, " +
        "quantidade de itens e se há XML armazenado.")]
    public async Task<string> ListarNotasEntrada(
        [Description("Trecho do nome do fornecedor, CNPJ, número da nota ou chave de acesso.")] string? busca = null,
        [Description("Página, a partir de 1.")] int pagina = 1,
        [Description("Notas por página (1 a 100, padrão 20).")] int tamanhoPagina = 20)
    {
        var (page, size) = NormalizePaging(pagina, tamanhoPagina, 20);
        size = Math.Min(size, 100); // NotaEntradaArquivoService limita a 100.
        var data = await notaEntradaService.ListarAsync(CurrentCompanyId(), busca, page, size);

        return Serialize(new
        {
            total = data.Total,
            pagina = data.Pagina,
            tamanhoPagina = data.TamanhoPagina,
            temMais = data.Pagina * data.TamanhoPagina < data.Total,
            itens = data.Notas.Select(n => new
            {
                id = n.Id,
                chaveAcesso = n.ChaveAcesso,
                modelo = n.Modelo,
                numeroNota = n.NumeroNota,
                serie = n.Serie,
                fornecedorNome = n.FornecedorNome,
                fornecedorCnpj = n.FornecedorCnpj,
                dataEmissao = n.DataEmissao,
                valorNota = n.ValorNota,
                valorEntrada = n.ValorEntrada,
                quantidadeItens = n.QuantidadeItens,
                origem = n.Origem,
                temXml = n.TemXml,
                criadaEm = n.CriadaEm
            })
        });
    }

    [McpServerTool(Name = "obter_itens_nota_entrada", Title = "Itens de nota de entrada",
        ReadOnly = true, Idempotent = true, OpenWorld = false)]
    [Description("Retorna o cabeçalho e os itens de uma nota de entrada. Quando há XML armazenado, " +
        "extrai os dados fiscais completos de cada item: CFOP, origem, CST/CSOSN de ICMS com base " +
        "e alíquota, ICMS-ST, PIS e COFINS. Esses são os dados do FORNECEDOR — não devem ser " +
        "copiados diretamente para o cadastro de saída do varejista.")]
    public async Task<string> ObterItensNotaEntrada(
        [Description("Id da nota de entrada (campo 'id' de listar_notas_entrada).")] string notaId)
    {
        var id = (notaId ?? string.Empty).Trim();
        if (id.Length == 0) throw new McpException("Informe o id da nota de entrada.");

        var companyId = CurrentCompanyId();
        var detalhe = await notaEntradaService.ObterAsync(companyId, id);
        if (detalhe is null)
            throw new McpException($"Nota de entrada '{id}' não encontrada. Use listar_notas_entrada.");

        var nota = detalhe.Nota;

        // Tenta extrair dados fiscais do XML original (se disponível).
        List<NfeItemFiscalDetail>? itensFiscais = null;
        if (nota.TemXml)
        {
            var xml = await notaEntradaService.ObterXmlAsync(companyId, id);
            if (xml is { Length: > 0 })
            {
                try { itensFiscais = NfeXmlParser.ParseFiscalDetails(xml); }
                catch { /* XML corrompido ou incompatível; usa o EntradaJson. */ }
            }
        }

        object itensResult;
        if (itensFiscais is { Count: > 0 })
        {
            itensResult = itensFiscais.Select(i => new
            {
                numeroItem = i.NumeroItem,
                codigoFornecedor = i.CodigoFornecedor,
                gtin = i.Gtin,
                descricao = i.Descricao,
                ncm = i.Ncm,
                cest = i.Cest,
                cfop = i.Cfop,
                unidadeComercial = i.UnidadeComercial,
                quantidade = i.Quantidade,
                valorUnitario = i.ValorUnitario,
                valorTotal = i.ValorTotal,
                desconto = i.Desconto,
                icms = new
                {
                    origem = i.Origem,
                    cst = i.CstIcms,
                    csosn = i.Csosn,
                    baseCalculo = i.BaseCalculoIcms,
                    aliquota = i.AliquotaIcms,
                    valor = i.ValorIcms,
                    percentualReducaoBc = i.PercentualReducaoBc
                },
                icmsSt = new
                {
                    baseCalculo = i.BaseCalculoIcmsSt,
                    aliquota = i.AliquotaIcmsSt,
                    valor = i.ValorIcmsSt
                },
                pis = new
                {
                    cst = i.CstPis,
                    baseCalculo = i.BaseCalculoPis,
                    aliquota = i.AliquotaPis,
                    valor = i.ValorPis
                },
                cofins = new
                {
                    cst = i.CstCofins,
                    baseCalculo = i.BaseCalculoCofins,
                    aliquota = i.AliquotaCofins,
                    valor = i.ValorCofins
                }
            });
        }
        else
        {
            // Fallback: dados do EntradaJson (sem detalhes tributários completos).
            itensResult = detalhe.Entrada;
        }

        return Serialize(new
        {
            nota = new
            {
                id = nota.Id,
                chaveAcesso = nota.ChaveAcesso,
                modelo = nota.Modelo,
                numeroNota = nota.NumeroNota,
                serie = nota.Serie,
                fornecedorNome = nota.FornecedorNome,
                fornecedorCnpj = nota.FornecedorCnpj,
                dataEmissao = nota.DataEmissao,
                valorNota = nota.ValorNota,
                valorEntrada = nota.ValorEntrada,
                quantidadeItens = nota.QuantidadeItens,
                origem = nota.Origem,
                temXml = nota.TemXml
            },
            fonteDosItens = itensFiscais is { Count: > 0 } ? "xml" : "registro",
            avisoFonte = itensFiscais is { Count: > 0 }
                ? "Dados extraídos do XML original da NF-e do fornecedor. CFOP, CST e alíquotas são da ENTRADA " +
                  "e NÃO devem ser copiados diretamente para o cadastro de SAÍDA do varejista."
                : "XML não disponível; usando dados do registro de entrada (sem detalhes tributários completos).",
            itens = itensResult
        });
    }

    // ---------------------------------------------------------------- Apoio

    private string CurrentCompanyId()
    {
        if (httpContextAccessor.HttpContext?.Items["CurrentUser"] is not AuthenticatedUser { Role: HorusRoles.Integracao } user
            || string.IsNullOrWhiteSpace(user.CompanyId))
        {
            throw new McpException("Token de integração não identificado.");
        }
        return user.CompanyId;
    }

    private static string Serialize(object? value) => JsonSerializer.Serialize(value, Json);

    private static (int Page, int Size) NormalizePaging(int pagina, int tamanhoPagina, int defaultSize)
        => (Math.Max(1, pagina), tamanhoPagina <= 0 ? defaultSize : Math.Min(tamanhoPagina, MaxPageSize));

    private static IEnumerable<(string Campo, string? ValorAtual, string TipoProblema, string Regra, string Explicacao)>
        ClassificarPendencias(ProdutoFiscalRow p)
    {
        // NCM
        if (string.IsNullOrEmpty(p.Ncm) || p.Ncm == "00000000")
            yield return ("NCM", p.Ncm, "erro_estrutural", "ncm_ausente",
                "NCM não informado ou padrão. Necessário para emissão de NFC-e.");
        else if (p.Ncm.Length != 8 || !p.Ncm.All(char.IsAsciiDigit))
            yield return ("NCM", p.Ncm, "erro_estrutural", "ncm_formato",
                "NCM deve ter exatamente 8 dígitos numéricos sem pontuação.");

        // GTIN
        if (string.IsNullOrEmpty(p.Gtin) || p.Gtin == "SEM GTIN")
        {
            // Não é erro: SEM GTIN é válido.
        }
        else if (p.Gtin.Length is not (8 or 12 or 13 or 14) || !p.Gtin.All(char.IsAsciiDigit))
            yield return ("GTIN", p.Gtin, "erro_estrutural", "gtin_formato",
                "GTIN deve ter 8, 12, 13 ou 14 dígitos, ou 'SEM GTIN'.");
        else if (!ProductFiscalReview.ValidGtin(p.Gtin))
            yield return ("GTIN", p.Gtin, "erro_estrutural", "gtin_checksum",
                "Dígito verificador do GTIN inválido. Verifique o código de barras.");

        // CFOP
        if (string.IsNullOrEmpty(p.Cfop))
            yield return ("CFOP", p.Cfop, "erro_estrutural", "cfop_ausente",
                "CFOP não informado. Necessário para emissão de NFC-e.");
        else if (p.Cfop.Length != 4 || !p.Cfop.All(char.IsAsciiDigit) || !p.Cfop.StartsWith('5'))
            yield return ("CFOP", p.Cfop, "erro_estrutural", "cfop_formato",
                "Para venda interna por NFC-e, o CFOP deve ter 4 dígitos e iniciar com 5.");

        // ICMS (CST ou CSOSN)
        if (string.IsNullOrEmpty(p.CsosnIcms) && string.IsNullOrEmpty(p.CstIcms))
            yield return ("ICMS", null, "erro_estrutural", "icms_ausente",
                "Nem CST nem CSOSN de ICMS informados. Preencha conforme o regime tributário.");

        // PIS
        if (string.IsNullOrEmpty(p.CstPis))
            yield return ("CST PIS", p.CstPis, "erro_estrutural", "pis_ausente",
                "CST de PIS não informado. Necessário para emissão de NFC-e.");

        // COFINS
        if (string.IsNullOrEmpty(p.CstCofins))
            yield return ("CST COFINS", p.CstCofins, "erro_estrutural", "cofins_ausente",
                "CST de COFINS não informado. Necessário para emissão de NFC-e.");

        // CEST (formato, não obrigatoriedade)
        if (!string.IsNullOrEmpty(p.Cest) && (p.Cest.Length != 7 || !p.Cest.All(char.IsAsciiDigit)))
            yield return ("CEST", p.Cest, "erro_estrutural", "cest_formato",
                "CEST deve ter exatamente 7 dígitos numéricos.");

        // Origem
        if (p.OrigemMercadoria > 8)
            yield return ("Origem", p.OrigemMercadoria.ToString(), "erro_estrutural", "origem_invalida",
                "Origem da mercadoria deve estar entre 0 e 8.");

        // Unidades
        if (p.UnidadeComercial != p.UnidadeTributavel)
            yield return ("Unidade", $"{p.UnidadeComercial}/{p.UnidadeTributavel}", "aviso", "unidade_divergente",
                "Unidade comercial difere da tributável. O emissor atual usa a mesma para ambas; confira a conversão.");

        // IBS/CBS
        if (!string.IsNullOrEmpty(p.CstIbsCbs) && string.IsNullOrEmpty(p.CClassTrib))
            yield return ("IBS/CBS", $"CST={p.CstIbsCbs} sem cClassTrib", "erro_estrutural", "ibs_incompleto",
                "CST IBS/CBS preenchido sem cClassTrib. Preencha em conjunto.");
        else if (string.IsNullOrEmpty(p.CstIbsCbs) && !string.IsNullOrEmpty(p.CClassTrib))
            yield return ("IBS/CBS", $"cClassTrib={p.CClassTrib} sem CST", "erro_estrutural", "ibs_incompleto",
                "cClassTrib preenchido sem CST IBS/CBS. Preencha em conjunto.");
    }
}
