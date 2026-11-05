package br.com.quack.pdv.produto.lote.app;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rotina periodica que tenta de novo a baixa/estorno de lotes que falhou depois do commit da venda (D13). Pega as
 * pendencias vencidas ({@link LotePendencias#reservarVencidas}), repete a operacao (idempotente: o consumo ignora
 * venda e produto ja baixados e o estorno marca cada consumo como estornado) e, ao passar do limite de tentativas,
 * deixa a pendencia em {@code revisao} com log de erro. Desligavel com {@code quack.lote.retentativa.ativa=false}.
 */
@Component
public class RetentativaBaixaLotes {

    private static final Logger LOG = LoggerFactory.getLogger(RetentativaBaixaLotes.class);
    private static final int LOTE_POR_RODADA = 50;

    private final LotePendencias pendencias;
    private final LoteOperacoes lotes;
    private final boolean ativa;
    private volatile br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao recuperacao;

    /** Opcional (so no perfil loja): durante a recuperacao pos-restauracao a rotina espera. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void definirRecuperacao(br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao recuperacao) {
        this.recuperacao = recuperacao;
    }

    public RetentativaBaixaLotes(
            LotePendencias pendencias,
            LoteOperacoes lotes,
            @Value("${quack.lote.retentativa.ativa:true}") boolean ativa) {
        this.pendencias = pendencias;
        this.lotes = lotes;
        this.ativa = ativa;
    }

    @Scheduled(
            initialDelayString = "${quack.lote.retentativa.intervalo-ms:30000}",
            fixedDelayString = "${quack.lote.retentativa.intervalo-ms:30000}")
    public void executarAgendado() {
        if (!ativa || (recuperacao != null && recuperacao.emAndamento())) return;
        try {
            processarPendentes();
        } catch (Exception ex) {
            LOG.error("Falha na rotina de nova tentativa da baixa de lotes.", ex);
        }
    }

    /** Uma rodada: devolve quantas pendencias foram concluidas. */
    public int processarPendentes() {
        List<LotePendencias.Pendencia> vencidas = pendencias.reservarVencidas(LOTE_POR_RODADA);
        int concluidas = 0;
        for (LotePendencias.Pendencia p : vencidas) {
            try {
                if (LotePendencias.TIPO_ESTORNO.equals(p.tipo())) {
                    lotes.estornarVenda(p.companyId(), p.vendaId());
                } else {
                    lotes.consumirVenda(p.companyId(), p.vendaId(), pendencias.itensDeJson(p.itensJson()));
                }
                pendencias.concluir(p.id());
                concluidas++;
            } catch (Exception ex) {
                String estado = pendencias.falhar(p, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                if (LotePendencias.ESTADO_REVISAO.equals(estado)) {
                    LOG.error("Baixa de lotes da venda {} ({}) esgotou {} tentativas e foi marcada para revisao.",
                            p.vendaId(), p.tipo(), pendencias.maxTentativas(), ex);
                } else {
                    LOG.warn("Nova tentativa da baixa de lotes da venda {} ({}) falhou; sera repetida.",
                            p.vendaId(), p.tipo(), ex);
                }
            }
        }
        return concluidas;
    }
}
