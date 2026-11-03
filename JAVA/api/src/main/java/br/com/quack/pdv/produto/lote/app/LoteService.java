package br.com.quack.pdv.produto.lote.app;

import br.com.quack.pdv.produto.lote.web.CategoriaValidadeModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.CategoriaValidadeRequest;
import br.com.quack.pdv.produto.lote.web.LoteDtos.FefoDivergenciaModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.FefoStatusModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteAlertaModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteAlertasResumoModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteConsultaFiltro;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteConsultaModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteModel;
import br.com.quack.pdv.produto.lote.web.LoteDtos.RegistrarLoteRequest;
import br.com.quack.pdv.shared.FormatoO;
import br.com.quack.pdv.shared.NegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Porta de LoteService: alertas por lote (saldo estimado FEFO, janela por categoria, valor em risco), consulta com
 * filtros, registro manual, situacao FEFO e prazos por categoria. "Hoje" vem do relogio de Brasilia.
 */
@Service
public class LoteService {

    static final int JANELA_ALERTA_PADRAO_DIAS = 15;
    private static final List<String> FAIXAS_DE_ALERTA = List.of("vencido", "critico", "atencao");

    private final LoteOperacoes operacoes;

    public LoteService(LoteOperacoes operacoes) {
        this.operacoes = operacoes;
    }

    /** Faixa do lote: esgotado (sem saldo), vencido, critico, atencao (dentro da janela) ou ok. */
    static String faixa(BigDecimal saldo, long dias, int janela) {
        if (saldo.compareTo(BigDecimal.ZERO) <= 0) return "esgotado";
        if (dias < 0) return "vencido";
        if (dias > janela) return "ok";
        return dias <= Math.max(2, janela / 3) ? "critico" : "atencao";
    }

    private record Montagem(List<LoteAlertaModel> itens, String modo) {}

    private Montagem montarTodos(String companyId) {
        List<LoteLinha> linhas = operacoes.listarLinhas(companyId, null);
        String modo = operacoes.obterModo(companyId);
        LocalDate hoje = operacoes.hojeBrasilia();
        boolean ativo = LoteOperacoes.MODO_ATIVO.equals(modo);
        List<LoteAlertaModel> itens = new ArrayList<>();

        for (List<LoteLinha> doProduto : agruparPorProduto(linhas).values()) {
            Map<String, BigDecimal> saldos = LoteEstimador.saldos(doProduto, ativo);
            for (LoteLinha linha : doProduto) {
                BigDecimal saldo = saldos.getOrDefault(linha.id(), BigDecimal.ZERO);
                long dias = ChronoUnit.DAYS.between(hoje, linha.dataValidade());
                int janela = linha.diasAlertaCategoria() != null
                        ? linha.diasAlertaCategoria()
                        : (linha.diasAlertaProduto() > 0 ? linha.diasAlertaProduto() : JANELA_ALERTA_PADRAO_DIAS);
                itens.add(new LoteAlertaModel(
                        linha.id(),
                        linha.produtoId(),
                        linha.numeroLote(),
                        linha.dataValidade().toString(),
                        (int) dias,
                        linha.qtdInicial(),
                        saldo,
                        linha.origem(),
                        linha.validadePadrao(),
                        iso(linha),
                        linha.productCode(),
                        linha.productName(),
                        linha.categoriaNome(),
                        linha.categoriaId(),
                        linha.categoriaPaiId(),
                        janela,
                        faixa(saldo, dias, janela),
                        linha.custoUnitario(),
                        linha.precoVenda(),
                        saldo.multiply(linha.custoUnitario()).setScale(2, RoundingMode.HALF_EVEN)));
            }
        }
        return new Montagem(itens, modo);
    }

    private static Map<String, List<LoteLinha>> agruparPorProduto(List<LoteLinha> linhas) {
        Map<String, List<LoteLinha>> grupos = new LinkedHashMap<>();
        for (LoteLinha l : linhas) {
            grupos.computeIfAbsent(l.produtoId(), k -> new ArrayList<>()).add(l);
        }
        return grupos;
    }

    private static String iso(LoteLinha linha) {
        return FormatoO.comOffset(linha.criadoEm().atZoneSameInstant(LoteOperacoes.FUSO_BRASILIA).toOffsetDateTime());
    }

    private static BigDecimal somar(List<LoteAlertaModel> itens) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoteAlertaModel i : itens) total = total.add(i.valorEmRisco());
        return total;
    }

    public LoteAlertasResumoModel listarAlertas(String companyId) {
        Montagem m = montarTodos(companyId);
        List<LoteAlertaModel> itens = m.itens().stream()
                .filter(i -> FAIXAS_DE_ALERTA.contains(i.faixa()))
                .sorted(Comparator.comparingInt(LoteAlertaModel::diasParaVencer)
                        .thenComparing(LoteAlertaModel::productName, String::compareTo))
                .toList();
        return new LoteAlertasResumoModel(
                (int) itens.stream().filter(i -> "vencido".equals(i.faixa())).count(),
                (int) itens.stream().filter(i -> "critico".equals(i.faixa())).count(),
                (int) itens.stream().filter(i -> "atencao".equals(i.faixa())).count(),
                somar(itens),
                operacoes.contarProdutosSemLote(companyId),
                m.modo(),
                itens);
    }

    public LoteConsultaModel consultar(String companyId, LoteConsultaFiltro filtro) {
        String de = parseFiltroData(filtro.de(), "inicial");
        String ate = parseFiltroData(filtro.ate(), "final");
        if (de != null && ate != null && ate.compareTo(de) < 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Período inválido: a validade final é anterior à inicial.");
        }

        String busca = minusculo(filtro.busca());
        String categoriaId = filtro.categoriaId() == null ? null : filtro.categoriaId().trim();
        String faixa = minusculo(filtro.faixa());
        String origem = minusculo(filtro.origem());

        Montagem m = montarTodos(companyId);
        List<LoteAlertaModel> filtrados = m.itens().stream()
                .filter(i -> busca == null || busca.isEmpty()
                        || i.productName().toLowerCase(Locale.ROOT).contains(busca)
                        || i.productCode().toLowerCase(Locale.ROOT).contains(busca)
                        || i.numeroLote().toLowerCase(Locale.ROOT).contains(busca))
                .filter(i -> de == null || i.dataValidade().compareTo(de) >= 0)
                .filter(i -> ate == null || i.dataValidade().compareTo(ate) <= 0)
                .filter(i -> categoriaId == null || categoriaId.isEmpty()
                        || categoriaId.equals(i.categoriaId()) || categoriaId.equals(i.categoriaPaiId()))
                .filter(i -> faixa == null || faixa.isEmpty() || "todas".equals(faixa) || faixa.equals(i.faixa()))
                .filter(i -> !Boolean.TRUE.equals(filtro.comSaldo()) || i.qtdEstimada().compareTo(BigDecimal.ZERO) > 0)
                .filter(i -> origem == null || origem.isEmpty() || "todas".equals(origem)
                        || origem.equals(i.origem().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(LoteAlertaModel::dataValidade)
                        .thenComparing(LoteAlertaModel::productName, String::compareTo))
                .toList();

        int tamanho = Math.max(5, Math.min(200, filtro.tamanhoPagina()));
        int totalPaginas = Math.max(1, (int) Math.ceil(filtrados.size() / (double) tamanho));
        int pagina = Math.max(1, Math.min(totalPaginas, filtro.pagina()));
        int inicio = (pagina - 1) * tamanho;
        List<LoteAlertaModel> pag =
                filtrados.subList(Math.min(inicio, filtrados.size()), Math.min(inicio + tamanho, filtrados.size()));
        return new LoteConsultaModel(filtrados.size(), pagina, tamanho, somar(filtrados), m.modo(), List.copyOf(pag));
    }

    private static String minusculo(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String parseFiltroData(String valor, String nome) {
        if (valor == null || valor.isBlank()) return null;
        try {
            return LocalDate.parse(valor.trim()).toString();
        } catch (DateTimeParseException ex) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Data " + nome + " inválida no filtro de validade.");
        }
    }

    public List<LoteModel> listarPorProduto(String companyId, String produtoId) {
        List<LoteLinha> linhas = operacoes.listarLinhas(companyId, produtoId);
        String modo = operacoes.obterModo(companyId);
        Map<String, BigDecimal> saldos = LoteEstimador.saldos(linhas, LoteOperacoes.MODO_ATIVO.equals(modo));
        LocalDate hoje = operacoes.hojeBrasilia();
        return linhas.stream()
                .map(l -> new LoteModel(
                        l.id(),
                        l.produtoId(),
                        l.numeroLote(),
                        l.dataValidade().toString(),
                        (int) ChronoUnit.DAYS.between(hoje, l.dataValidade()),
                        l.qtdInicial(),
                        saldos.getOrDefault(l.id(), BigDecimal.ZERO),
                        l.origem(),
                        l.validadePadrao(),
                        iso(l)))
                .sorted(Comparator.comparing(LoteModel::dataValidade))
                .toList();
    }

    public void registrarManual(String companyId, String usuarioNome, RegistrarLoteRequest req) {
        if (req == null || req.produtoId() == null || req.produtoId().isBlank()) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Informe o produto do lote.");
        }
        LocalDate validade;
        try {
            validade = req.dataValidade() == null ? null : LocalDate.parse(req.dataValidade());
        } catch (DateTimeParseException ex) {
            validade = null;
        }
        if (validade == null) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Data de validade inválida.");
        }
        BigDecimal qtd = req.quantidade() == null ? BigDecimal.ZERO : req.quantidade();
        if (qtd.compareTo(BigDecimal.ZERO) <= 0) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A quantidade do lote deve ser maior que zero.");
        }
        operacoes.registrarEntrada(
                companyId, req.produtoId().trim(), validade, qtd, req.numeroLote(), "manual", usuarioNome);
    }

    /** Compara o saldo real por lote com o estimado (decide a passagem de "sombra" para "ativo"). */
    public FefoStatusModel obterFefoStatus(String companyId) {
        final BigDecimal tolerancia = new BigDecimal("0.001");
        List<LoteLinha> linhas = operacoes.listarLinhas(companyId, null);
        String modo = operacoes.obterModo(companyId);
        List<FefoDivergenciaModel> divergencias = new ArrayList<>();
        int lotesComSaldo = 0;
        int produtosComDivergencia = 0;
        int produtosSemCobertura = 0;

        for (List<LoteLinha> doProduto : agruparPorProduto(linhas).values()) {
            Map<String, BigDecimal> estimados = LoteEstimador.distribuir(doProduto);
            boolean divergiu = false;
            BigDecimal somaReal = BigDecimal.ZERO;
            for (LoteLinha l : doProduto) {
                BigDecimal estimado = estimados.getOrDefault(l.id(), BigDecimal.ZERO);
                BigDecimal real = l.qtdAtual().max(BigDecimal.ZERO);
                somaReal = somaReal.add(real);
                if (real.compareTo(BigDecimal.ZERO) > 0) lotesComSaldo++;
                if (real.subtract(estimado).abs().compareTo(tolerancia) <= 0) continue;
                divergiu = true;
                divergencias.add(new FefoDivergenciaModel(
                        l.produtoId(),
                        l.productCode(),
                        l.productName(),
                        l.id(),
                        l.numeroLote(),
                        l.dataValidade().toString(),
                        estimado,
                        real,
                        real.subtract(estimado)));
            }
            if (divergiu) produtosComDivergencia++;
            BigDecimal estoque = doProduto.getFirst().estoque().max(BigDecimal.ZERO);
            if (estoque.subtract(somaReal).compareTo(tolerancia) > 0) produtosSemCobertura++;
        }

        List<FefoDivergenciaModel> top = divergencias.stream()
                .sorted(Comparator.comparing((FefoDivergenciaModel d) -> d.diferenca().abs()).reversed())
                .limit(100)
                .toList();
        return new FefoStatusModel(
                modo, lotesComSaldo, divergencias.size(), produtosComDivergencia, produtosSemCobertura, top);
    }

    public void definirModo(String companyId, String modo) {
        String normalizado = modo == null ? null : modo.trim().toLowerCase(Locale.ROOT);
        if (!LoteOperacoes.modoValido(normalizado)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Modo inválido. Use 'desligado', 'sombra' ou 'ativo'.");
        }
        operacoes.definirModo(companyId, normalizado);
    }

    public List<CategoriaValidadeModel> listarCategorias(String companyId) {
        return operacoes.listarCategoriasValidade(companyId);
    }

    public boolean salvarCategoria(String companyId, String categoriaId, CategoriaValidadeRequest req) {
        Integer prazo = req == null ? null : req.prazoPadraoDias();
        Integer alerta = req == null ? null : req.diasAlerta();
        if (prazo != null && (prazo < 1 || prazo > 3650)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "O prazo padrão deve ficar entre 1 e 3650 dias.");
        }
        if (alerta != null && (alerta < 0 || alerta > 3650)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A janela de alerta deve ficar entre 0 e 3650 dias.");
        }
        return operacoes.salvarCategoriaValidade(companyId, categoriaId, prazo, alerta);
    }
}
