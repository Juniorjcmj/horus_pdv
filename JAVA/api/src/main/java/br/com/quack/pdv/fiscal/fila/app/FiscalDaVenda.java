package br.com.quack.pdv.fiscal.fila.app;

import br.com.quack.pdv.shared.NegocioException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Passo fiscal depois da venda gravada (trecho final do HistoricoVendasController.Registrar): decide se emite e, se
 * sim, grava a nota pendente. Cada parte roda em transacao propria. Falha na pendencia da 503 com a venda guardada.
 */
@Service
public class FiscalDaVenda {

    private static final Logger LOG = LoggerFactory.getLogger(FiscalDaVenda.class);

    static final String MENSAGEM_503 =
            "Venda guardada; a preparação da nota fiscal será retomada automaticamente.";

    /** Resultado do passo fiscal. */
    public record Resultado(boolean emitirFiscal, boolean fiscalQueued) {}

    private final DecisaoEmissaoNfce decisao;
    private final PendenciaFiscal pendencia;
    private final EmitenteConfigurado emitente;

    public FiscalDaVenda(DecisaoEmissaoNfce decisao, PendenciaFiscal pendencia, EmitenteConfigurado emitente) {
        this.decisao = decisao;
        this.pendencia = pendencia;
        this.emitente = emitente;
    }

    /**
     * Passo fiscal da venda nova gerada por uma correcao (HistoricoVendasController.CorrigirVenda): so com emitente
     * configurado, decide com forma e CPF vazios e grava a pendencia. Qualquer falha vira aviso no log e nunca derruba
     * a correcao. Devolve se a nota foi enfileirada.
     */
    public boolean aposCorrecao(String companyId, String novaVendaId) {
        try {
            if (!emitente.existe(companyId)) {
                return false;
            }
            if (!decisao.avaliarEmissaoParaVenda(companyId, novaVendaId, "", List.of(), "")) {
                return false;
            }
            pendencia.enfileirar(companyId, novaVendaId);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Falha ao enfileirar NFC-e para venda corrigida {}.", novaVendaId, e);
            return false;
        }
    }

    public Resultado aposVenda(String companyId, String vendaId, String saleNumber, String paymentType,
                               List<String> formas, String customerCpf) {
        boolean emitir;
        try {
            emitir = decisao.avaliarEmissaoParaVenda(companyId, vendaId, paymentType, formas, customerCpf);
        } catch (IllegalArgumentException e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!emitir) {
            return new Resultado(false, false);
        }
        try {
            pendencia.enfileirar(companyId, vendaId);
            return new Resultado(true, true);
        } catch (RuntimeException e) {
            LOG.error("Falha ao enfileirar NFC-e da venda {}.", vendaId, e);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("saleNumber", saleNumber);
            data.put("vendaId", vendaId);
            throw new NegocioException(HttpStatus.SERVICE_UNAVAILABLE, MENSAGEM_503, null, data);
        }
    }
}
