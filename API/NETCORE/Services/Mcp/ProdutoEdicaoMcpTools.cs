/**
 * Arquivo: API/NETCORE/Services/Mcp/ProdutoEdicaoMcpTools.cs
 * Objetivo: ferramentas de ESCRITA do servidor MCP: editar o cadastro de um produto e ajustar o estoque.
 * Regras:
 *   - Só funcionam com token de integração gerado com "Pode editar produtos e ajustar estoque"
 *     (IntegracaoTokens.PermiteEscritaProdutos). Os demais tokens continuam somente leitura.
 *   - Simular antes de aplicar: com confirmar=false (padrão) a ferramenta só mostra o antes → depois, sem gravar.
 *     O agente deve mostrar a simulação ao lojista e chamar de novo com confirmar=true após a confirmação.
 *   - O estoque nunca muda pelo cadastro: editar_produto manda o saldo visto (EstoqueConsultado) e a API não
 *     mexe nele. Mudança de saldo só por ajustar_estoque_produto, que grava a movimentação (com motivo).
 *   - Toda alteração aplicada vai para o AuditLog (quem: o nome do token).
 * Isolamento: a empresa vem sempre do token (HttpContext.Items["CurrentUser"]); nunca de parâmetro.
 * Documentação: docs/MCP.md.
 */
using System.ComponentModel;
using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Categorias;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using ModelContextProtocol;
using ModelContextProtocol.Server;

namespace HORUSPDV_API.Services.Mcp;

[McpServerToolType]
public class ProdutoEdicaoMcpTools(
    IHttpContextAccessor httpContextAccessor,
    IProdutoService produtoService,
    ICategoriaService categoriaService,
    AuditLogAB auditLogAB)
{
    private static readonly CultureInfo PtBr = new("pt-BR");

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    private static readonly string[] TiposAjuste = ["entrada", "saida", "perda", "uso_interno"];

    private sealed record Alteracao(string Campo, string De, string Para);

    // ---------------------------------------------------------------- editar_produto

    [McpServerTool(Name = "editar_produto", Title = "Editar produto", ReadOnly = false, Destructive = false, Idempotent = true, OpenWorld = false)]
    [Description("Edita o cadastro de um produto (nome, código, GTIN, unidade, preço de venda, custo, estoque mínimo/máximo, " +
        "categoria, fornecedor, marca, localização, controle de validade). Informe só os campos que mudam. " +
        "Por padrão SÓ SIMULA (confirmar=false) e devolve o antes → depois: mostre ao lojista e, depois que ele " +
        "confirmar, chame de novo com os mesmos dados e confirmar=true. Não altera o saldo de estoque: para isso use " +
        "ajustar_estoque_produto. Exige token com permissão de edição.")]
    public async Task<string> EditarProduto(
        [Description("Id do produto, código interno ou código de barras (GTIN) exato.")] string idOuCodigo,
        [Description("false (padrão) = só simula; true = grava a alteração. Só use true depois da confirmação do lojista.")] bool confirmar = false,
        [Description("Novo nome (descrição) do produto, mínimo 3 letras.")] string? nome = null,
        [Description("Novo código interno.")] string? codigo = null,
        [Description("Novo código de barras (GTIN/EAN) ou 'SEM GTIN'.")] string? gtin = null,
        [Description("Nova unidade de venda, ex.: UN, KG, CX, L, PCT.")] string? unidade = null,
        [Description("Novo preço de venda em reais (ex.: 12.9 = R$ 12,90). Maior que zero.")] decimal? precoVenda = null,
        [Description("Novo preço de custo em reais. Maior que zero.")] decimal? custo = null,
        [Description("Novo estoque mínimo (0 = sem mínimo).")] decimal? estoqueMinimo = null,
        [Description("Novo estoque máximo (0 = sem máximo).")] decimal? estoqueMaximo = null,
        [Description("Id ou nome exato da categoria (ver listar_categorias). Texto vazio \"\" remove a categoria.")] string? categoria = null,
        [Description("Novo fornecedor (texto).")] string? fornecedor = null,
        [Description("Nova marca.")] string? marca = null,
        [Description("Nova localização no estoque (ex.: Corredor 3, prateleira B).")] string? localizacao = null,
        [Description("Liga/desliga o controle de validade por lote.")] bool? controlaValidade = null)
    {
        var user = UsuarioComEscrita();
        var produto = await LocalizarAsync(user.CompanyId, idOuCodigo);

        var request = ParaRequest(produto);
        var alteracoes = new List<Alteracao>();

        void Texto(string campo, string? novo, string atual, Action<string> aplicar, int minimo = 0)
        {
            if (novo is null) return;
            var valor = novo.Trim();
            if (valor.Length < minimo) throw new McpException($"{campo}: informe pelo menos {minimo} caracteres.");
            if (valor.Length > 200) throw new McpException($"{campo}: no máximo 200 caracteres.");
            if (string.Equals(valor, atual?.Trim() ?? "", StringComparison.Ordinal)) return;
            aplicar(valor);
            alteracoes.Add(new Alteracao(campo, Mostrar(atual), Mostrar(valor)));
        }

        Texto("nome", nome, produto.ProductName, v => request.ProductName = v, minimo: 3);
        if (codigo is not null && codigo.Trim().Length == 0) throw new McpException("O código não pode ficar vazio.");
        Texto("codigo", codigo, produto.ProductCode, v => request.ProductCode = v);
        Texto("gtin", gtin is null ? null : (gtin.Trim().Length == 0 ? "SEM GTIN" : gtin), produto.Gtin, v => request.Gtin = v);
        Texto("unidade", unidade?.Trim().ToUpperInvariant(), produto.UnidadeComercial, v =>
        {
            request.UnidadeComercial = v;
            request.UnidadeTributavel = v;
        });
        Texto("fornecedor", fornecedor, produto.ProductSupplier, v => request.ProductSupplier = v);
        Texto("marca", marca, produto.Marca ?? "", v => request.Marca = v);
        Texto("localizacao", localizacao, produto.LocalizacaoEstoque ?? "", v => request.LocalizacaoEstoque = v);

        if (precoVenda is { } preco)
        {
            if (preco <= 0 || preco > 1_000_000_000m) throw new McpException("O preço de venda deve ser maior que zero.");
            preco = decimal.Round(preco, 2);
            var atual = Numero(produto.ProductSalePrice);
            if (preco != atual)
            {
                request.ProductSalePrice = HorusMoneyFormat.Format(preco);
                alteracoes.Add(new Alteracao("precoVenda", Reais(atual), Reais(preco)));
            }
        }

        if (custo is { } c)
        {
            if (c <= 0 || c > 1_000_000_000m) throw new McpException("O preço de custo deve ser maior que zero.");
            c = decimal.Round(c, 4);
            var atual = Numero(produto.ProductUnitPrice);
            if (c != atual)
            {
                request.ProductUnitPrice = HorusMoneyFormat.FormatUnitPrice(c);
                alteracoes.Add(new Alteracao("custo", Reais(atual), Reais(c)));
            }
        }

        var minimoAtual = Numero(produto.EstoqueMinimo);
        var maximoAtual = Numero(produto.EstoqueMaximo);
        var minimoNovo = estoqueMinimo ?? minimoAtual;
        var maximoNovo = estoqueMaximo ?? maximoAtual;
        if (minimoNovo < 0 || maximoNovo < 0) throw new McpException("Estoque mínimo/máximo não pode ser negativo.");
        if (maximoNovo > 0 && maximoNovo < minimoNovo) throw new McpException("O estoque máximo não pode ser menor que o mínimo.");
        if (minimoNovo != minimoAtual)
        {
            request.EstoqueMinimo = HorusMoneyFormat.FormatQuantity(minimoNovo);
            alteracoes.Add(new Alteracao("estoqueMinimo", HorusMoneyFormat.FormatQuantity(minimoAtual), HorusMoneyFormat.FormatQuantity(minimoNovo)));
        }
        if (maximoNovo != maximoAtual)
        {
            request.EstoqueMaximo = HorusMoneyFormat.FormatQuantity(maximoNovo);
            alteracoes.Add(new Alteracao("estoqueMaximo", HorusMoneyFormat.FormatQuantity(maximoAtual), HorusMoneyFormat.FormatQuantity(maximoNovo)));
        }

        if (categoria is not null)
        {
            var chave = categoria.Trim();
            string? novoId = null;
            string novoNome = "(sem categoria)";
            if (chave.Length > 0)
            {
                var todas = await categoriaService.ListarTodasAsync(user.CompanyId, apenasAtivas: true);
                var cat = todas.FirstOrDefault(x => string.Equals(x.Id, chave, StringComparison.OrdinalIgnoreCase))
                    ?? todas.FirstOrDefault(x => string.Equals(x.Nome, chave, StringComparison.OrdinalIgnoreCase))
                    ?? throw new McpException($"Categoria '{chave}' não encontrada (ou inativa). Use listar_categorias.");
                novoId = cat.Id;
                novoNome = cat.Nome;
            }
            if (!string.Equals(novoId ?? "", produto.CategoriaId ?? "", StringComparison.OrdinalIgnoreCase))
            {
                request.CategoriaId = novoId;
                alteracoes.Add(new Alteracao("categoria", Mostrar(produto.CategoriaNome ?? (produto.CategoriaId is null ? "(sem categoria)" : produto.CategoriaId)), novoNome));
            }
        }

        if (controlaValidade is { } cv && cv != produto.ControlaValidade)
        {
            request.ControlaValidade = cv;
            alteracoes.Add(new Alteracao("controlaValidade", produto.ControlaValidade ? "sim" : "não", cv ? "sim" : "não"));
        }

        var resumoProduto = Resumo(produto);
        if (alteracoes.Count == 0)
        {
            return Serialize(new { simulacao = !confirmar, aplicado = false, produto = resumoProduto, alteracoes, mensagem = "Nada a alterar: os valores informados já são os atuais." });
        }

        if (!confirmar)
        {
            return Serialize(new
            {
                simulacao = true,
                aplicado = false,
                produto = resumoProduto,
                alteracoes,
                mensagem = "SIMULAÇÃO: nada foi gravado. Mostre as alterações ao lojista e, se ele confirmar, chame editar_produto de novo com os mesmos dados e confirmar=true."
            });
        }

        ProdutoModel? salvo;
        try
        {
            salvo = await produtoService.AtualizarAsync(user.CompanyId, produto.Id, request, user.Id, NomeAgente(user));
        }
        catch (InvalidOperationException ex)
        {
            throw new McpException(ex.Message);
        }
        if (salvo is null) throw new McpException("O produto não existe mais.");

        var texto = string.Join("; ", alteracoes.Select(a => $"{a.Campo}: {a.De} → {a.Para}"));
        await AuditarAsync(user, "ProdutoEditadoMcp", $"Produto {produto.ProductCode} \"{produto.ProductName}\" editado pelo agente: {texto}", produto.Id);

        return Serialize(new { simulacao = false, aplicado = true, produto = Resumo(salvo), alteracoes, mensagem = "Produto atualizado." });
    }

    // ---------------------------------------------------------------- ajustar_estoque_produto

    [McpServerTool(Name = "ajustar_estoque_produto", Title = "Ajustar estoque de produto", ReadOnly = false, Destructive = false, Idempotent = false, OpenWorld = false)]
    [Description("Lança uma movimentação de estoque num produto: entrada, saida, perda ou uso_interno, com motivo obrigatório. " +
        "Use novoSaldo para acertar o estoque para um valor contado (a ferramenta calcula a entrada/saída da diferença) " +
        "OU tipo + quantidade. Por padrão SÓ SIMULA (confirmar=false) e mostra o saldo antes → depois: confirme com o " +
        "lojista e chame de novo com confirmar=true. Cada chamada confirmada grava uma movimentação nova (não repita). " +
        "Exige token com permissão de edição.")]
    public async Task<string> AjustarEstoqueProduto(
        [Description("Id do produto, código interno ou código de barras (GTIN) exato.")] string idOuCodigo,
        [Description("Motivo do ajuste (obrigatório), ex.: 'Contagem do corredor 3', 'Avaria no recebimento'.")] string motivo,
        [Description("false (padrão) = só simula; true = grava a movimentação. Só use true depois da confirmação do lojista.")] bool confirmar = false,
        [Description("Saldo contado: acerta o estoque para este valor (não informe tipo/quantidade junto).")] decimal? novoSaldo = null,
        [Description("'entrada', 'saida', 'perda' ou 'uso_interno' (com quantidade).")] string? tipo = null,
        [Description("Quantidade da movimentação, maior que zero (com tipo).")] decimal? quantidade = null)
    {
        var user = UsuarioComEscrita();
        var produto = await LocalizarAsync(user.CompanyId, idOuCodigo);
        var motivoTxt = (motivo ?? "").Trim();
        if (motivoTxt.Length < 3) throw new McpException("Informe o motivo do ajuste (mínimo 3 letras).");
        if (motivoTxt.Length > 200) motivoTxt = motivoTxt[..200];

        var saldoAtual = Numero(produto.ProductQnt);
        string tipoNorm;
        decimal qtd;
        if (novoSaldo is { } alvo)
        {
            if (tipo is not null || quantidade is not null)
                throw new McpException("Use novoSaldo OU tipo + quantidade, não os dois.");
            if (alvo < 0) throw new McpException("O saldo não pode ser negativo.");
            var delta = alvo - saldoAtual;
            if (delta == 0)
            {
                return Serialize(new { simulacao = !confirmar, aplicado = false, produto = Resumo(produto), mensagem = $"O estoque já está em {Qtd(saldoAtual)} {produto.UnidadeComercial}: nada a ajustar." });
            }
            tipoNorm = delta > 0 ? "entrada" : "saida";
            qtd = Math.Abs(delta);
        }
        else
        {
            tipoNorm = (tipo ?? "").Trim().ToLowerInvariant().Replace("saída", "saida").Replace(' ', '_');
            if (!TiposAjuste.Contains(tipoNorm))
                throw new McpException("Informe novoSaldo, ou tipo ('entrada', 'saida', 'perda', 'uso_interno') + quantidade.");
            qtd = quantidade ?? 0;
            if (qtd <= 0) throw new McpException("A quantidade deve ser maior que zero.");
        }

        if (!EstoqueIndicadoresAB.UnidadeFracionada(produto.UnidadeComercial) && decimal.Truncate(qtd) != qtd)
            throw new McpException($"Produto vendido por {produto.UnidadeComercial} não aceita quantidade fracionada.");

        var entrada = tipoNorm == "entrada";
        var saldoDepois = entrada ? saldoAtual + qtd : saldoAtual - qtd;
        var ajuste = new
        {
            tipo = tipoNorm,
            quantidade = Qtd(qtd),
            saldoAntes = Qtd(saldoAtual),
            saldoDepois = Qtd(saldoDepois),
            unidade = produto.UnidadeComercial,
            motivo = motivoTxt
        };

        if (!confirmar)
        {
            return Serialize(new
            {
                simulacao = true,
                aplicado = false,
                produto = Resumo(produto),
                ajuste,
                aviso = saldoDepois < 0 ? "O saldo ficaria negativo." : null,
                mensagem = "SIMULAÇÃO: nada foi gravado. Mostre o ajuste ao lojista e, se ele confirmar, chame ajustar_estoque_produto de novo com os mesmos dados e confirmar=true."
            });
        }

        try
        {
            var ok = await produtoService.AjustarEstoqueAsync(user.CompanyId, produto.Id, tipoNorm, qtd,
                $"{motivoTxt} (agente MCP: {user.Name})", user.Id, NomeAgente(user));
            if (!ok) throw new McpException("O produto não existe mais.");
        }
        catch (InvalidOperationException ex)
        {
            throw new McpException(ex.Message);
        }

        var depois = await LocalizarAsync(user.CompanyId, produto.Id);
        await AuditarAsync(user, "EstoqueAjustadoMcp",
            $"Estoque de {produto.ProductCode} \"{produto.ProductName}\": {tipoNorm} de {Qtd(qtd)} {produto.UnidadeComercial} " +
            $"({Qtd(saldoAtual)} → {Qtd(Numero(depois.ProductQnt))}). Motivo: {motivoTxt}", produto.Id);

        return Serialize(new
        {
            simulacao = false,
            aplicado = true,
            produto = Resumo(depois),
            ajuste,
            saldoFinal = Qtd(Numero(depois.ProductQnt)),
            mensagem = "Movimentação registrada."
        });
    }

    // ---------------------------------------------------------------- Apoio

    private AuthenticatedUser UsuarioComEscrita()
    {
        if (httpContextAccessor.HttpContext?.Items["CurrentUser"] is not AuthenticatedUser { Role: HorusRoles.Integracao } user
            || string.IsNullOrWhiteSpace(user.CompanyId))
        {
            throw new McpException("Token de integração não identificado.");
        }
        if (!user.PermiteEscritaProdutos)
        {
            throw new McpException("Este token é somente leitura. Para editar produtos, gere em Configurações → Integrações " +
                "um token com a opção \"Pode editar produtos e ajustar estoque\".");
        }
        return user;
    }

    private async Task<ProdutoModel> LocalizarAsync(string companyId, string idOuCodigo)
    {
        var chave = (idOuCodigo ?? string.Empty).Trim();
        if (chave.Length == 0) throw new McpException("Informe o id, código ou GTIN do produto.");
        var produtos = await produtoService.ListarAsync(companyId);
        return produtos.FirstOrDefault(p => string.Equals(p.Id, chave, StringComparison.OrdinalIgnoreCase))
            ?? produtos.FirstOrDefault(p => string.Equals(p.ProductCode, chave, StringComparison.OrdinalIgnoreCase))
            ?? produtos.FirstOrDefault(p => string.Equals(p.Gtin, chave, StringComparison.OrdinalIgnoreCase))
            ?? throw new McpException($"Produto '{chave}' não encontrado. Use buscar_produtos para localizar pelo nome.");
    }

    /// <summary>Cadastro atual como requisição de edição; o saldo visto vai em EstoqueConsultado (o PUT não mexe no estoque).</summary>
    private static ProdutoRequest ParaRequest(ProdutoModel produto)
    {
        var request = JsonSerializer.Deserialize<ProdutoRequest>(JsonSerializer.Serialize(produto, Json), Json)
            ?? throw new McpException("Não foi possível ler o cadastro do produto.");
        request.EstoqueConsultado = produto.ProductQnt;
        return request;
    }

    private async Task AuditarAsync(AuthenticatedUser user, string evento, string descricao, string produtoId)
    {
        if (descricao.Length > 500) descricao = descricao[..497] + "...";
        try
        {
            // AuditLog.UserId tem 40 caracteres: "mcp:itk-<32 hex>" cabe (o "integracao:itk-..." não).
            var userId = user.Id.Replace("integracao:", "mcp:", StringComparison.Ordinal);
            if (userId.Length > 40) userId = userId[..40];
            await auditLogAB.RegistrarAsync(user.CompanyId, userId, NomeAgente(user), evento, descricao, "Produto", produtoId,
                httpContextAccessor.HttpContext?.Connection.RemoteIpAddress?.ToString());
        }
        catch
        {
            // a alteração já foi gravada; falha no log não pode virar erro para o agente
        }
    }

    private static string NomeAgente(AuthenticatedUser user)
    {
        var nome = $"MCP: {user.Name}";
        return nome.Length > 100 ? nome[..100] : nome;
    }

    private static object Resumo(ProdutoModel p) => new
    {
        id = p.Id,
        codigo = p.ProductCode,
        nome = p.ProductName,
        gtin = p.Gtin,
        unidade = p.UnidadeComercial,
        precoVenda = Reais(Numero(p.ProductSalePrice)),
        custo = Reais(Numero(p.ProductUnitPrice)),
        estoque = Qtd(Numero(p.ProductQnt)),
        estoqueMinimo = Qtd(Numero(p.EstoqueMinimo)),
        estoqueMaximo = Qtd(Numero(p.EstoqueMaximo)),
        categoria = p.CategoriaNome,
        fornecedor = p.ProductSupplier,
        marca = p.Marca,
        localizacao = p.LocalizacaoEstoque,
        controlaValidade = p.ControlaValidade
    };

    private static decimal Numero(string? valor) => HorusMoneyFormat.ParseDecimal(valor);
    private static string Reais(decimal valor) => "R$ " + valor.ToString("#,##0.00##", PtBr);
    private static string Qtd(decimal valor) => HorusMoneyFormat.FormatQuantity(valor);
    private static string Mostrar(string? valor) => string.IsNullOrWhiteSpace(valor) ? "(vazio)" : valor.Trim();
    private static string Serialize(object value) => JsonSerializer.Serialize(value, Json);
}
