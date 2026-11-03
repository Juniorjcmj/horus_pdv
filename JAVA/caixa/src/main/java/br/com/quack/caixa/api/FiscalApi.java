package br.com.quack.caixa.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Documentos fiscais (mesmas rotas do caixa web):
 * <ul>
 * <li>GET /api/Nfce/{saleNumber}: documento da venda (XML autorizado e QR Code para o DANFE);</li>
 * <li>GET /api/Nfce/buscar/{codigo}: busca por nº da venda, nº da nota ou chave de acesso;</li>
 * <li>POST /api/Nfce|Nfe/{id}/cancelar-com-supervisor: cancelamento com senha de gerente;</li>
 * <li>POST /api/Nfe/devolver/{id}: NF-e de devolução (modelo 55) quando o prazo de cancelamento passou.</li>
 * </ul>
 */
public class FiscalApi {
    /** Status do documento (FISCAL_STATUS da web). */
    public static final int RASCUNHO = 0;
    public static final int ASSINADO = 1;
    public static final int TRANSMITINDO = 2;
    public static final int AUTORIZADO = 3;
    public static final int REJEITADO = 4;
    public static final int DENEGADO = 5;
    public static final int CANCELADO = 6;
    public static final int INUTILIZADO = 7;
    public static final int CONTINGENCIA_PENDENTE = 8;
    public static final int DEVOLVIDO = 9;

    /** Cancelamento e devolução falam com a SEFAZ: prazo maior que o padrão. */
    private static final Duration SEFAZ_TIMEOUT = Duration.ofSeconds(60);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FiscalDoc(String id, String saleNumber, int status, String chaveAcesso, String protocolo,
            String motivoStatus, String qrCodeUrl, String xmlDocumento, Integer modelo, Integer serie,
            Integer numeroNf, String dhAutorizacao, String criadoEm, String totalAmount, String customerName,
            String customerCpf) {
        public boolean hasXml() {
            return xmlDocumento != null && !xmlDocumento.isBlank();
        }

        public boolean modelo55() {
            return modelo != null && modelo == 55;
        }

        public boolean autorizado() {
            return status == AUTORIZADO;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelResult(String documentoId, String protocoloCancelamento, String motivoStatus,
            String supervisorNome) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DevolucaoResult(String documentoId, Integer numeroNfe, Integer serieNfe, String chaveAcesso,
            String protocolo, String dhAutorizacao, String supervisorNome, String destinatarioNome) {
    }

    private final ApiClient api;

    public FiscalApi(ApiClient api) {
        this.api = api;
    }

    /** null quando não há documento (ou a API está fora): igual ao fiscalService.getBySaleNumber da web. */
    public FiscalDoc bySaleNumber(String saleNumber) {
        try {
            return api.send("GET", "/api/Nfce/" + enc(saleNumber), null, ApiClient.READ_TIMEOUT, FiscalDoc.class);
        } catch (ApiException e) {
            return null;
        }
    }

    /**
     * Busca por nº da venda, nº da nota ou chave de 44 dígitos. null = não encontrado.
     * Falha de conexão sobe como ApiException (a tela avisa que precisa de internet).
     */
    public FiscalDoc search(String codigo) {
        try {
            return api.send("GET", "/api/Nfce/buscar/" + enc(codigo.trim()), null, ApiClient.READ_TIMEOUT,
                    FiscalDoc.class);
        } catch (ApiException e) {
            if (e.isTransient()) {
                throw e;
            }
            return null;
        }
    }

    public CancelResult cancelWithSupervisor(FiscalDoc doc, String supervisorId, String supervisorPassword,
            String justificativa, String terminal) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supervisorId", supervisorId);
        b.put("supervisorPassword", supervisorPassword);
        b.put("justificativa", justificativa);
        b.put("terminal", terminal);
        String base = doc.modelo55() ? "/api/Nfe/" : "/api/Nfce/";
        return api.send("POST", base + enc(doc.id()) + "/cancelar-com-supervisor", b, SEFAZ_TIMEOUT,
                CancelResult.class);
    }

    /** destinatário opcional: sem CPF/nome a NF-e sai como "Entrada Própria" com os dados da loja. */
    public DevolucaoResult devolver(FiscalDoc doc, String supervisorId, String supervisorPassword,
            String justificativa, String cpfCnpj, String nome) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supervisorId", supervisorId);
        b.put("supervisorPassword", supervisorPassword);
        b.put("justificativa", justificativa);
        boolean temCliente = (cpfCnpj != null && !cpfCnpj.isBlank()) || (nome != null && !nome.isBlank());
        if (temCliente) {
            Map<String, Object> dest = new LinkedHashMap<>();
            dest.put("cpfCnpj", cpfCnpj == null ? "" : cpfCnpj.trim());
            dest.put("nome", nome == null || nome.isBlank() ? "Consumidor Final" : nome.trim());
            dest.put("indIeDest", 9);
            b.put("destinatario", dest);
        }
        return api.send("POST", "/api/Nfe/devolver/" + enc(doc.id()), b, SEFAZ_TIMEOUT, DevolucaoResult.class);
    }

    /** Rótulo do status (fiscalStatusLabel da web). */
    public static String statusLabel(int status, Integer modelo) {
        return switch (status) {
            case DEVOLVIDO -> "Devolvida";
            case CANCELADO -> "Cancelada";
            case AUTORIZADO -> modelo != null && modelo == 55 ? "Devolução (NF-e)" : "Autorizada";
            case RASCUNHO -> "Rascunho";
            case ASSINADO, TRANSMITINDO -> "Transmitindo";
            case REJEITADO -> "Rejeitada";
            case DENEGADO -> "Denegada";
            case INUTILIZADO -> "Inutilizada";
            case CONTINGENCIA_PENDENTE -> "Contingência";
            default -> "—";
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
