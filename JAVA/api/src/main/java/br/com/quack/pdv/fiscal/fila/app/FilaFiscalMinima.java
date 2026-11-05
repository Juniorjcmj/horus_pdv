package br.com.quack.pdv.fiscal.fila.app;

import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository;
import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository.CancelamentoDaVenda;
import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository.Pendente;
import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository.VendaSemDocumento;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fila fiscal minima da Fase 4 (D6): o que o {@code NfceOutboxWorker} da .NET faz SEM falar com a SEFAZ, ate o
 * worker completo chegar na Fase 7. A cada ciclo:
 * <ol>
 *   <li>grava a pendencia das vendas que decidiram emitir e ficaram sem nota;</li>
 *   <li>tira da fila, como nao emitida, a nota nao assinada de venda cancelada (aviso para inutilizar o numero);</li>
 *   <li>marca erro na nota de empresa sem emitente configurado;</li>
 *   <li>qualquer outra pendente fica parada, sem transmissao.</li>
 * </ol>
 * Nao assina, nao transmite e nao inutiliza nada: nenhuma classe desta fila usa provedor fiscal ou rede. Desligavel
 * com {@code quack.fiscal.fila-minima.ativa=false} (a .NET e a Java nao devem processar a mesma fila).
 * <p>
 * Modo da API ({@code quack.modo}, D8 do modo loja): em {@code nuvem} a rotina nunca roda; em {@code loja} roda so o
 * passo 1 (retomada das vendas sem nota, com o proximo numero da sequencia fiscal da loja), porque os demais ramos
 * ficam com a .NET da nuvem (dois donos das colunas de resultado, nao). Nota de venda cancelada na loja nao muda.
 */
@Component
public class FilaFiscalMinima {

    private static final Logger LOG = LoggerFactory.getLogger(FilaFiscalMinima.class);

    static final int LOTE_MAXIMO = 20;
    static final String ERRO_SEM_EMITENTE = "Empresa sem certificado ou CSC configurado.";
    static final String EVENTO_NAO_EMITIDA = "NfceNaoEmitida";
    private static final int STATUS_REJEITADO = 4;
    private static final long ATRASO_ERRO_INESPERADO_SEGUNDOS = 30;

    private final FilaFiscalRepository repo;
    private final PendenciaFiscal pendencia;
    private final EmitenteConfigurado emitente;
    private final Clock clock;
    private final boolean ativa;
    private final String modo;
    private volatile br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao recuperacao;

    /** Opcional (so no perfil loja): durante a recuperacao pos-restauracao a fila nao escreve. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void definirRecuperacao(br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao recuperacao) {
        this.recuperacao = recuperacao;
    }

    public FilaFiscalMinima(FilaFiscalRepository repo, PendenciaFiscal pendencia, EmitenteConfigurado emitente,
                            Clock clock, @Value("${quack.fiscal.fila-minima.ativa:true}") boolean ativa,
                            @Value("${quack.modo:padrao}") String modo) {
        this.repo = repo;
        this.pendencia = pendencia;
        this.emitente = emitente;
        this.clock = clock;
        this.ativa = ativa;
        this.modo = modo;
    }

    /** No modo nuvem a fila nunca roda, qualquer que seja a configuracao. */
    private boolean desligada() {
        return !ativa || "nuvem".equals(modo) || (recuperacao != null && recuperacao.emAndamento());
    }

    @Scheduled(initialDelayString = "${quack.fiscal.fila-minima.intervalo-ms:1000}",
            fixedDelayString = "${quack.fiscal.fila-minima.intervalo-ms:1000}")
    public void executarAgendado() {
        if (desligada()) return;
        try {
            processarLote();
        } catch (RuntimeException ex) {
            // Nunca deixar a excecao derrubar a rotina.
            LOG.error("Erro no ciclo da fila fiscal minima.", ex);
        }
    }

    /** Um ciclo da fila (ProcessarLoteAsync sem transmissao). Usado pela rotina agendada e pelos testes. */
    public void processarLote() {
        if (recuperacao != null && recuperacao.emAndamento()) return;
        retomarVendasSemNota();
        if ("loja".equals(modo)) return; // na loja so a retomada; o resto fica com a .NET da nuvem (D8)
        for (Pendente doc : repo.pendentes(LOTE_MAXIMO, OffsetDateTime.now(clock))) {
            try {
                processar(doc);
            } catch (RuntimeException ex) {
                // Erro inesperado: nao deixa um documento ruim travar o lote; backoff curto e segue.
                LOG.error("Falha inesperada ao processar o documento fiscal {}.", doc.id(), ex);
                OffsetDateTime agora = OffsetDateTime.now(clock);
                repo.marcarErro(doc.id(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                        agora.plusSeconds(ATRASO_ERRO_INESPERADO_SEGUNDOS), agora);
            }
        }
    }

    /**
     * Passo 1 do ciclo: grava a pendencia (numero da sequencia fiscal local) das vendas que decidiram emitir e ficaram
     * sem nota. Repete em lotes enquanto houver progresso. Devolve quantas vendas continuam sem nota por falha.
     */
    public int retomarVendasSemNota() {
        java.util.Set<String> falhou = new java.util.HashSet<>();
        while (true) {
            // pede a mais as que ja falharam, para sempre ver as ainda nao tentadas
            java.util.List<VendaSemDocumento> candidatas = repo.vendasSemDocumento(LOTE_MAXIMO + falhou.size()).stream()
                    .filter(v -> !falhou.contains(v.vendaId())).toList();
            if (candidatas.isEmpty()) return falhou.size();
            for (VendaSemDocumento venda : candidatas) {
                try {
                    pendencia.enfileirar(venda.companyId(), venda.vendaId());
                } catch (RuntimeException ex) {
                    falhou.add(venda.vendaId());
                    LOG.error("Falha ao retomar fila fiscal da venda {}.", venda.vendaId(), ex);
                }
            }
        }
    }

    /** Retomada exigida antes do envio da loja: so roda no modo loja com a fila ligada. Falha se sobrar venda sem nota. */
    public void retomarAntesDoEnvio() {
        if (desligada() || !"loja".equals(modo)) return;
        int falhas = retomarVendasSemNota();
        if (falhas > 0) {
            throw new IllegalStateException(falhas + " venda(s) sem nota pendente: o envio espera para nao subir sem o "
                    + "numero reservado (D8).");
        }
    }

    private void processar(Pendente doc) {
        // Venda cancelada antes de a nota ser assinada: nao emite (o numero deve ser inutilizado).
        Optional<CancelamentoDaVenda> cancelamento = repo.cancelamentoDaVenda(doc.companyId(), doc.vendaId());
        boolean semXml = doc.xmlAssinado() == null || doc.xmlAssinado().isBlank();
        if (cancelamento.isPresent() && semXml) {
            naoEmitir(doc, cancelamento.get());
            return;
        }
        if (!emitente.existe(doc.companyId())) {
            LOG.warn("{} do documento {} (venda {}) nao transmitida: empresa sem certificado digital A1 ou CSC.",
                    tipo(doc), doc.id(), doc.vendaId());
            repo.marcarErro(doc.id(), ERRO_SEM_EMITENTE, null, OffsetDateTime.now(clock));
        }
        // Com emitente: fica parada, sem transmissao, ate a Fase 7.
    }

    /** Ramo de NaoEmitirAsync sem SEFAZ: Rejeitado, com o aviso para inutilizar o numero manualmente. */
    private void naoEmitir(Pendente doc, CancelamentoDaVenda cancelamento) {
        String venda = cancelamento.saleNumber() != null ? cancelamento.saleNumber() : doc.vendaId();
        boolean semEmitente = !emitente.existe(doc.companyId());
        String motivo = tipo(doc) + " não emitida: a venda #" + venda + " foi cancelada antes da emissão. Inutilize o "
                + "número " + doc.numeroNf() + " (série " + doc.serie() + ") na SEFAZ"
                + (semEmitente ? " (empresa sem certificado configurado)." : ".");
        OffsetDateTime agora = OffsetDateTime.now(clock);
        if (!repo.encerrarSemEmissao(doc.companyId(), doc.id(), STATUS_REJEITADO, motivo, agora)) {
            return; // a nota mudou nesse meio tempo
        }
        LOG.warn("Documento {}: {}", doc.id(), motivo);
        try {
            repo.auditar(doc.companyId(), cancelamento.operadorId(),
                    cancelamento.operadorNome() != null ? cancelamento.operadorNome() : "Fila fiscal",
                    EVENTO_NAO_EMITIDA, motivo, doc.id());
        } catch (RuntimeException ex) {
            LOG.warn("Trilha da nota nao emitida {} nao gravada: {}", doc.id(), ex.toString());
        }
    }

    private static String tipo(Pendente doc) {
        return doc.modelo() == 55 ? "NF-e" : "NFC-e";
    }
}
