package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import static br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.CIFRADA;
import static br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.LOCAL;
import static br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.LOJA;
import static br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.NUVEM;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada.RegraLinha;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada.TransicaoDaNuvem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalogo de sincronizacao (D2 da change api-java-modo-loja): define, em codigo e testado, como cada tabela do
 * esquema participa da sincronizacao loja/nuvem. As guardas ({@link GuardaCatalogo}) falham o build se o esquema e o
 * catalogo divergirem.
 *
 * <p>Conferido contra os gravadores da Java (JAVA/api) e da .NET (API/NETCORE) na tarefa 1.1. Desvios do D2:
 * {@code ProdutoClassificacao} e {@code EstoqueSnapshotDiario} sao FORA; {@code RegrasEmissaoNfce.UpdatedAt} e
 * {@code Empresas.RequireApprovalForNewCompanies} ganharam classe propria.
 */
public final class CatalogoSincronizacao {

    private static final Map<String, TabelaSincronizada> TABELAS = new LinkedHashMap<>();

    /**
     * Ordem de aplicacao: pais antes de filhos (conferida contra sys.foreign_keys pelo CatalogoOrdemIT).
     * Exclusoes usam a ordem inversa.
     */
    private static final List<String> ORDEM = List.of(
            "Empresas", "Categorias", "Fornecedores", "Usuarios", "Clientes", "EmpresaTema",
            "CustomerCreditPolicies", "EstoqueConfigAnalise", "LoteConfig", "RegrasEmissaoNfce",
            "Produtos", "Promocoes", "PromocaoProdutos",
            "CaixaSessoes", "Vendas", "VendaItens", "VendaPagamentos", "VendaCancelamentos",
            "Pedidos", "PedidoItens", "DocumentosFiscais", "FiscalSequencias",
            "AutorizacoesPreco", "CaixaMovimentos", "EstoqueMovimentacoes", "FiadoMovimentos",
            "Inventarios", "InventarioContagens", "InventarioEventos", "InventarioItens",
            "LoteConsumos", "ProdutoLotes", "MapeamentoProdutoFornecedor", "NotasEntradaArquivo",
            "OrdemCompra", "OrdemCompraItens", "ModuloMercadoRegistros", "AuditLog");

    /** Tabelas de sincronizacao e de controle do Flyway nunca sincronizam (prefixos reservados). */
    public static final List<String> PREFIXOS_FORA = List.of("Sync", "flyway_");

    static {
        // ---- EDITAVEL: o dono edita pela nuvem (painel .NET e MCP) e a loja aplica ----
        editavel("Produtos",
                // Saldo e valor de estoque: so a loja mexe, pelo registrador unico (RegistradorEstoque na Java,
                // EstoqueMovimentacaoAB na .NET). TotalPriceOnProduct = custo x saldo, derivado do saldo.
                // CustoMedio fica EDITAVEL: so o cadastro (ProdutoService/ProdutoAB) o grava, e na criacao pela entrada.
                Map.of("ProductQnt", LOJA, "TotalPriceOnProduct", LOJA));
        editavel("Clientes",
                // SaldoDevedor: gravado so pelo fiado (FiadoDebitos, FiadoEstornos, FiadoRepository; FiadoAB na .NET).
                Map.of("SaldoDevedor", LOJA));
        editavel("Fornecedores", Map.of());
        // De-para de codigo do fornecedor: cadastro. A .NET faz MERGE a cada nota (O5 da change
        // api-java-operacoes-estoque-nuvem), entao as duas pontas editam; a conferencia de base resolve a colisao.
        editavel("MapeamentoProdutoFornecedor", Map.of());
        editavel("Categorias", Map.of());
        editavel("Promocoes", Map.of());
        filha("PromocaoProdutos", ClasseTabela.EDITAVEL, "Promocoes", "PromocaoId");
        editavel("Usuarios", Map.of(
                // Contadores de login: cada ponta autentica os seus usuarios (UsuarioRepository, HorusSecurityStore).
                "LastLoginAt", LOCAL, "FailedLoginAttempts", LOCAL, "FirstFailedLoginAt", LOCAL, "LockoutEnd", LOCAL));
        put(new TabelaSincronizada("Empresas", ClasseTabela.EDITAVEL, "Id", null, null, Map.of(
                // Segredos: a nuvem guarda com a chave dela; a loja recebe recifrado com a chave da loja.
                "EmailSmtpPassword", CIFRADA, "CscCifrado", CIFRADA, "CertificadoPfxCifrado", CIFRADA,
                "CertificadoSenhaCifrada", CIFRADA,
                // Aprovacao de cadastro e decisao de plataforma: so o admin da nuvem (GerenciamentoEmpresasService).
                "Status", NUVEM, "ReviewedAt", NUVEM, "ReviewedBy", NUVEM, "RejectionReason", NUVEM,
                "RequireApprovalForNewCompanies", NUVEM), List.of()));
        editavel("EmpresaTema", Map.of());
        editavel("CustomerCreditPolicies", Map.of());
        editavel("EstoqueConfigAnalise", Map.of());
        editavel("LoteConfig", Map.of());
        editavel("RegrasEmissaoNfce", Map.of(
                // ContadorVendas sobe a cada venda (DecisaoEmissaoNfce, RegrasEmissaoNfceAB) e UpdatedAt vai junto.
                "ContadorVendas", LOJA, "UpdatedAt", LOJA));

        // ---- LOJA: so a loja grava ----
        loja("AutorizacoesPreco");
        loja("CaixaMovimentos");
        loja("CaixaSessoes");
        // Operacoes de estoque da nuvem (change api-java-operacoes-estoque-nuvem, O4): a nuvem pode INSERIR movimentacoes e
        // lotes (descem como passos de uma operacao; a loja recalcula o saldo) e fazer as transicoes da nota de entrada.
        // Qualquer outra alteracao da nuvem nessas tabelas continua indo para a quarentena.
        put(comOperacoes("EstoqueMovimentacoes", List.of()));
        loja("FiadoMovimentos");
        // Modelo 65 e da loja; Modelo 55 (NF-e de devolucao do painel) e autoria da nuvem.
        put(new TabelaSincronizada("FiscalSequencias", ClasseTabela.LOJA, "CompanyId", null, null, Map.of(),
                List.of(new RegraLinha("Modelo", 55, NUVEM))));
        put(new TabelaSincronizada("DocumentosFiscais", ClasseTabela.LOJA, "CompanyId", null, null, Map.ofEntries(
                // Resultado da emissao: quem emite na 4b e o worker .NET da nuvem (D8); a loja so recebe.
                Map.entry("Status", NUVEM), Map.entry("ChaveAcesso", NUVEM), Map.entry("TpEmis", NUVEM),
                Map.entry("DhContingencia", NUVEM), Map.entry("JustContingencia", NUVEM),
                Map.entry("Protocolo", NUVEM), Map.entry("DhAutorizacao", NUVEM), Map.entry("CodigoStatus", NUVEM),
                Map.entry("MotivoStatus", NUVEM), Map.entry("XmlAssinado", NUVEM), Map.entry("XmlProtocolado", NUVEM),
                Map.entry("XmlCancelamento", NUVEM), Map.entry("Tentativas", NUVEM),
                Map.entry("ProximaTentativaEm", NUVEM), Map.entry("UltimoErro", NUVEM), Map.entry("AtualizadoEm", NUVEM),
                Map.entry("QrCodeUrl", NUVEM),
                Map.entry("CanceladoJustificativa", NUVEM), Map.entry("CanceladoPorOperador", NUVEM),
                Map.entry("CanceladoPorSupervisorId", NUVEM), Map.entry("CanceladoPorSupervisorNome", NUVEM),
                Map.entry("DevolvidoJustificativa", NUVEM), Map.entry("DevolvidoPorOperador", NUVEM),
                Map.entry("DevolvidoPorSupervisorId", NUVEM), Map.entry("DevolvidoPorSupervisorNome", NUVEM)),
                List.of(new RegraLinha("Modelo", 55, NUVEM))));
        loja("InventarioContagens");
        loja("InventarioEventos");
        loja("InventarioItens");
        loja("Inventarios");
        loja("LoteConsumos");
        put(comOperacoes("ProdutoLotes", List.of()));
        put(comOperacoes("NotasEntradaArquivo", List.of(
                new TransicaoDaNuvem("estorno", "Estornada", "1", "Estornada", "0",
                        List.of("Estornada", "EstornadaEm", "EstornadaPorUsuarioId", "EstornadaPorUsuarioNome",
                                "EstornoJustificativa")),
                new TransicaoDaNuvem("xml", "Origem", "xml", "Origem", "sem-documento",
                        List.of("Identidade", "ChaveAcesso", "Modelo", "NumeroNota", "Serie", "DataEmissao", "ValorNota",
                                "Origem", "XmlOriginal")))));
        loja("OrdemCompra");
        filha("OrdemCompraItens", ClasseTabela.LOJA, "OrdemCompra", "OrdemCompraId");
        loja("Pedidos");
        filha("PedidoItens", ClasseTabela.LOJA, "Pedidos", "PedidoId");
        // O worker fiscal da .NET (NfceOutboxWorker/DocumentoFiscalAB) grava Status e Cancelado* ao cancelar ou devolver a
        // nota na nuvem: transicao aceita, desce por forcar em vez de ir para a quarentena (grupo 4).
        put(new TabelaSincronizada("Vendas", ClasseTabela.LOJA, "CompanyId", null, null, Map.of(), List.of(),
                List.of(new TabelaSincronizada.AceiteDaNuvem("Status", java.util.Set.of("cancelada", "estornada"),
                        List.of("Status", "CanceladoEm", "CanceladoPorOperadorId", "CanceladoPorOperadorNome",
                                "CanceladoPorSupervisorId", "CanceladoPorSupervisorNome", "CanceladoJustificativa")))));
        filha("VendaItens", ClasseTabela.LOJA, "Vendas", "VendaId");
        loja("VendaPagamentos");
        loja("VendaCancelamentos");
        loja("ModuloMercadoRegistros");

        // ---- ACRESCIMO ----
        put(new TabelaSincronizada("AuditLog", ClasseTabela.ACRESCIMO, "CompanyId", null, null, Map.of(), List.of()));

        // ---- FORA ----
        // Sessao e tokens: por ponta.
        fora("Sessoes", "PasswordResetTokens", "ProcessedEvents");
        // Gateway de credito e integracoes: recurso da nuvem.
        fora("GatewayTokens", "GatewayCreditBudgets", "GatewayCreditConsumptions", "GatewayCreditGrants",
                "GatewayCreditOperatorPermits", "GatewayCreditStores", "GatewayCreditTerminals", "UserCreditStores",
                "LojaGatewayConfig", "IntegracaoTokens");
        fora("FiscalIaAnalises", "FiscalIaConfig", "FiscalIaSolicitacoes");
        fora("TreinamentoSecoes", "TreinamentoVideos", "ModulosMercado");
        fora("LoteBaixaPendencias");
        // Controle local da importacao de produtos por planilha (idempotencia do saldo inicial): cada ponta tem o seu.
        fora("ImportacoesProduto");
        // Derivadas e recalculadas por cada ponta (desvio do D2, que as tinha como EDITAVEL e LOJA): o worker .NET da
        // nuvem (EstoqueSnapshotWorker) grava ProdutoClassificacao (DELETE+INSERT completo) e EstoqueSnapshotDiario
        // (MERGE) todo dia para todas as empresas. Sincronizadas, cada gravacao da nuvem viraria quarentena e alerta
        // diarios. Ambas se reconstroem de Vendas e EstoqueMovimentacoes; a Fase 6 as calcula na loja.
        fora("ProdutoClassificacao", "EstoqueSnapshotDiario");
    }

    private CatalogoSincronizacao() {}

    private static void put(TabelaSincronizada t) {
        if (TABELAS.put(t.nome(), t) != null) throw new IllegalStateException("Tabela duplicada no catalogo: " + t.nome());
    }

    private static void editavel(String nome, Map<String, ClasseColuna> excecoes) {
        put(new TabelaSincronizada(nome, ClasseTabela.EDITAVEL, "CompanyId", null, null, excecoes, List.of()));
    }

    private static void loja(String nome) {
        put(new TabelaSincronizada(nome, ClasseTabela.LOJA, "CompanyId", null, null, Map.of(), List.of()));
    }

    private static TabelaSincronizada comOperacoes(String nome, List<TransicaoDaNuvem> transicoes) {
        return new TabelaSincronizada(nome, ClasseTabela.LOJA, "CompanyId", null, null, Map.of(), List.of())
                .comOperacoesDaNuvem(transicoes);
    }

    private static void filha(String nome, ClasseTabela classe, String mae, String ligacao) {
        put(new TabelaSincronizada(nome, classe, null, mae, ligacao, Map.of(), List.of()));
    }

    private static void fora(String... nomes) {
        for (String n : nomes) {
            put(new TabelaSincronizada(n, ClasseTabela.FORA, null, null, null, Map.of(), List.of()));
        }
    }

    public static Map<String, TabelaSincronizada> tabelas() { return Collections.unmodifiableMap(TABELAS); }

    public static Optional<TabelaSincronizada> tabela(String nome) { return Optional.ofNullable(TABELAS.get(nome)); }

    /** Tabelas que sincronizam (nao FORA), na ordem de aplicacao (pais antes de filhos). */
    public static List<TabelaSincronizada> ordemDeAplicacao() {
        List<TabelaSincronizada> lista = new ArrayList<>();
        for (String n : ORDEM) lista.add(TABELAS.get(n));
        return List.copyOf(lista);
    }

    /** Ordem inversa, usada nas exclusoes (filhos antes de pais). */
    public static List<TabelaSincronizada> ordemDeExclusao() {
        List<TabelaSincronizada> lista = new ArrayList<>(ordemDeAplicacao());
        Collections.reverse(lista);
        return List.copyOf(lista);
    }

    public static boolean ehReservadaFora(String nomeTabela) {
        return PREFIXOS_FORA.stream().anyMatch(nomeTabela::startsWith);
    }

    public static List<String> ordemDeclarada() { return ORDEM; }
}
