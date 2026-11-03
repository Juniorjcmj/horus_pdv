package br.com.quack.pdv.fiscal.fila.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;

/** DocumentoFiscalDetalhe da API .NET (status numerico), so leitura nesta fase. */
public record DocumentoFiscalDetalhe(
        @JsonProperty("id") String id,
        @JsonProperty("saleNumber") String saleNumber,
        @JsonProperty("serie") int serie,
        @JsonProperty("numeroNf") int numeroNf,
        @JsonProperty("status") int status,
        @JsonProperty("chaveAcesso") String chaveAcesso,
        @JsonProperty("protocolo") String protocolo,
        @JsonProperty("motivoStatus") String motivoStatus,
        @JsonProperty("codigoStatus") Integer codigoStatus,
        @JsonProperty("dhAutorizacao") OffsetDateTime dhAutorizacao,
        @JsonProperty("criadoEm") OffsetDateTime criadoEm,
        @JsonProperty("tentativas") int tentativas,
        @JsonProperty("totalAmount") String totalAmount,
        @JsonProperty("customerName") String customerName,
        @JsonProperty("customerCpf") String customerCpf,
        @JsonProperty("paymentType") String paymentType,
        @JsonProperty("hasXml") boolean hasXml,
        @JsonProperty("hasCancelXml") boolean hasCancelXml,
        @JsonProperty("modelo") short modelo,
        @JsonProperty("documentoOrigemId") String documentoOrigemId,
        @JsonProperty("chaveReferenciada") String chaveReferenciada,
        @JsonProperty("devolvida") boolean devolvida,
        @JsonProperty("numeroNfeDevolucao") Integer numeroNfeDevolucao,
        @JsonProperty("qrCodeUrl") String qrCodeUrl,
        @JsonProperty("xmlDocumento") String xmlDocumento,
        @JsonProperty("alertasAutorizacao") List<Alerta> alertasAutorizacao) {

    /** FiscalAuthorizationAlert. */
    public record Alerta(@JsonProperty("codigo") String codigo, @JsonProperty("mensagem") String mensagem) {}
}
