package br.com.quack.poc;

import br.com.swconsultoria.nfe.Nfe;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.Evento;
import br.com.swconsultoria.nfe.dom.enuns.DocumentoEnum;
import br.com.swconsultoria.nfe.schema.envEventoCancNFe.TEnvEvento;
import br.com.swconsultoria.nfe.schema.envEventoCancNFe.TRetEnvEvento;
import br.com.swconsultoria.nfe.schema_4.consSitNFe.TRetConsSitNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TEnviNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TRetEnviNFe;
import br.com.swconsultoria.nfe.schema_4.inutNFe.TInutNFe;
import br.com.swconsultoria.nfe.schema_4.inutNFe.TRetInutNFe;
import br.com.swconsultoria.nfe.util.CancelamentoUtil;
import br.com.swconsultoria.nfe.util.InutilizacaoUtil;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operacoes com a SEFAZ de HOMOLOGACAO (so rodam com POC_* configurado): status, autorizar, consultar, cancelar,
 * inutilizar e a contingencia offline (gerar em tpEmis 9 e transmitir depois). Cada passo devolve cStat + motivo,
 * que vao para o relatorio como evidencia.
 */
public final class OperacoesSefaz {

    private final ConfiguracoesNfe cfg;
    private final String cnpj;
    private final String idCsc;
    private final String csc;
    private final Path schemas;

    public OperacoesSefaz(ConfiguracoesNfe cfg, String cnpj, String idCsc, String csc, Path schemas) {
        this.cfg = cfg;
        this.cnpj = cnpj;
        this.idCsc = idCsc;
        this.csc = csc;
        this.schemas = schemas;
    }

    public Map<String, String> cicloCompleto(int numeroBase) {
        Map<String, String> evidencias = new LinkedHashMap<>();
        passo(evidencias, "status-servico", () -> {
            var r = Nfe.statusServico(cfg, DocumentoEnum.NFCE);
            return r.getCStat() + " - " + r.getXMotivo();
        });

        String[] chaveAutorizada = new String[1];
        String[] protocolo = new String[1];
        passo(evidencias, "autorizar", () -> {
            NotaFiscal.Resultado nota = NotaFiscal.gerar(cfg, numeroBase, false, cnpj, idCsc, csc, schemas);
            chaveAutorizada[0] = nota.chave();
            TRetEnviNFe ret = Nfe.enviarNfe(cfg, nota.envio(), DocumentoEnum.NFCE);
            var prot = ret.getProtNFe();
            if (prot != null) protocolo[0] = prot.getInfProt().getNProt();
            return prot != null ? prot.getInfProt().getCStat() + " - " + prot.getInfProt().getXMotivo()
                    : ret.getCStat() + " - " + ret.getXMotivo();
        });

        passo(evidencias, "consultar", () -> {
            TRetConsSitNFe r = Nfe.consultaXml(cfg, chaveAutorizada[0], DocumentoEnum.NFCE);
            return r.getCStat() + " - " + r.getXMotivo();
        });

        passo(evidencias, "cancelar", () -> {
            Evento ev = new Evento();
            ev.setChave(chaveAutorizada[0]);
            ev.setCnpj(cnpj);
            ev.setProtocolo(protocolo[0]);
            ev.setMotivo("Cancelamento de teste da prova de conceito fiscal");
            ev.setDataEvento(LocalDateTime.now());
            TEnvEvento env = CancelamentoUtil.montaCancelamento(ev, cfg);
            TRetEnvEvento ret = Nfe.cancelarNfe(cfg, env, true, DocumentoEnum.NFCE);
            var inf = ret.getRetEvento().get(0).getInfEvento();
            return inf.getCStat() + " - " + inf.getXMotivo();
        });

        passo(evidencias, "inutilizar", () -> {
            int ini = numeroBase + 100;
            TInutNFe inut = InutilizacaoUtil.montaInutilizacao(DocumentoEnum.NFCE, cnpj, 1, ini, ini + 1,
                    "Inutilizacao de teste da prova de conceito fiscal", LocalDateTime.now(), cfg);
            TRetInutNFe ret = Nfe.inutilizacao(cfg, inut, DocumentoEnum.NFCE, true);
            return ret.getInfInut().getCStat() + " - " + ret.getInfInut().getXMotivo();
        });

        passo(evidencias, "contingencia-offline-gerar-e-transmitir", () -> {
            NotaFiscal.Resultado nota = NotaFiscal.gerar(cfg, numeroBase + 200, true, cnpj, idCsc, csc, schemas);
            // "Depois": a mesma nota (tpEmis 9, ja assinada e guardada) e transmitida quando a SEFAZ volta.
            TEnviNFe guardada = nota.envio();
            TRetEnviNFe ret = Nfe.enviarNfe(cfg, guardada, DocumentoEnum.NFCE);
            var prot = ret.getProtNFe();
            return "gerada tpEmis=9 chave=" + nota.chave() + "; transmissao: "
                    + (prot != null ? prot.getInfProt().getCStat() + " - " + prot.getInfProt().getXMotivo()
                    : ret.getCStat() + " - " + ret.getXMotivo());
        });
        return evidencias;
    }

    @FunctionalInterface
    private interface Passo {
        String executar() throws Exception;
    }

    private static void passo(Map<String, String> evidencias, String nome, Passo passo) {
        try {
            evidencias.put(nome, passo.executar());
        } catch (Exception e) {
            evidencias.put(nome, "FALHA: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
