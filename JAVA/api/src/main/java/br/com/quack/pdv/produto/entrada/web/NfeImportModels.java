package br.com.quack.pdv.produto.entrada.web;

import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.DocumentoInput;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;

/** Modelos de resposta da importacao de NF-e e das notas de entrada (portas de NfeImportModels.cs e records do servico). */
public final class NfeImportModels {

    private NfeImportModels() {}

    public record FornecedorPreview(
            boolean jaExiste,
            String cnpj,
            String companyName,
            String fantasyName,
            String cep,
            String city,
            String state,
            String address,
            String neighborhood,
            String number,
            String telephone) {}

    public record ItemPreview(
            int numeroItem,
            String produtoExistenteId,
            String produtoExistenteNome,
            String codigoFornecedor,
            String productCode,
            String productName,
            String gtin,
            String ncm,
            String cest,
            String unidadeComercial,
            String quantidade,
            String precoCusto,
            String precoVendaSugerido) {}

    public record PreviewModel(
            DocumentoInput documento,
            int modelo,
            String numeroNota,
            String serie,
            FornecedorPreview fornecedor,
            List<ItemPreview> itens) {}

    /** NfeImportResultModel: o recibo guardado na entrada precisa de notaEntradaId mutavel (preenchido ao salvar). */
    public static class Resultado {
        public String notaEntradaId = "";
        public boolean fornecedorCriado;
        public int produtosCriados;
        public int produtosAtualizados;
    }

    public record NotaResumo(
            String id,
            String chaveAcesso,
            int modelo,
            String numeroNota,
            String serie,
            String fornecedorNome,
            String fornecedorCnpj,
            String dataEmissao,
            BigDecimal valorNota,
            BigDecimal valorEntrada,
            int quantidadeItens,
            String origem,
            boolean temXml,
            String criadaEm,
            String usuarioNome,
            boolean temComprovante,
            String comprovanteNome,
            boolean estornada,
            String estornadaEm,
            String estornadaPorUsuarioNome,
            String estornoJustificativa) {}

    public record NotaPagina(List<NotaResumo> notas, int total, int pagina, int tamanhoPagina) {}

    public record NotaDetalhe(NotaResumo nota, JsonNode entrada) {}
}
