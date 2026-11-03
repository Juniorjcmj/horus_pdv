package br.com.quack.pdv.produto.entrada.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corpos de entrada da importacao de NF-e e da entrada manual (portas de NfeImportRequest.cs e
 * EntradaManualRequest.cs). Os campos ficam NA ORDEM do .NET (a serializacao do JSON guardado e a impressao da
 * entrada manual dependem dela) e os padroes sao os mesmos das classes C#.
 */
public final class NfeImportRequests {

    private NfeImportRequests() {}

    public static class PreviewRequest {
        public String xmlBase64 = "";
    }

    public static class ChaveRequest {
        public String chaveAcesso = "";
    }

    public static class FornecedorInput {
        public String cnpj = "";
        public String companyName = "";
        public String fantasyName = "";
        public String cep = "";
        public String city = "";
        public String state = "";
        public String address = "";
        public String neighborhood = "";
        public String number = "";
        public String telephone = "";

        public Map<String, Object> paraMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cnpj", cnpj);
            m.put("companyName", companyName);
            m.put("fantasyName", fantasyName);
            m.put("cep", cep);
            m.put("city", city);
            m.put("state", state);
            m.put("address", address);
            m.put("neighborhood", neighborhood);
            m.put("number", number);
            m.put("telephone", telephone);
            return m;
        }
    }

    public static class DocumentoInput {
        public String xmlBase64;
        public String chaveAcesso;
        public int modelo = 55;
        public String numeroNota = "";
        public String serie = "";
    }

    /** NfeImportItemInput. */
    public static class ItemInput {
        public int numeroItem;
        public String produtoExistenteId;
        public String produtoExistenteNome;
        public String codigoFornecedor;
        public String productCode = "";
        public String productName = "";
        public String gtin = "SEM GTIN";
        public String ncm = "00000000";
        public String cest;
        public String unidadeComercial = "UN";
        public String quantidade = "0";
        public String precoCusto = "0";
        public String precoVenda = "0";
        public String dataValidade;
        public String numeroLote;
        public String margemPercentual;

        /** Propriedades da classe base, na ordem do .NET. */
        protected void escreverBase(Map<String, Object> m) {
            m.put("numeroItem", numeroItem);
            m.put("produtoExistenteId", produtoExistenteId);
            m.put("produtoExistenteNome", produtoExistenteNome);
            m.put("codigoFornecedor", codigoFornecedor);
            m.put("productCode", productCode);
            m.put("productName", productName);
            m.put("gtin", gtin);
            m.put("ncm", ncm);
            m.put("cest", cest);
            m.put("unidadeComercial", unidadeComercial);
            m.put("quantidade", quantidade);
            m.put("precoCusto", precoCusto);
            m.put("precoVenda", precoVenda);
            m.put("dataValidade", dataValidade);
            m.put("numeroLote", numeroLote);
            m.put("margemPercentual", margemPercentual);
        }

        public Map<String, Object> paraMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            escreverBase(m);
            return m;
        }
    }

    public static class ConfirmRequest {
        public DocumentoInput documento;
        public FornecedorInput fornecedor = new FornecedorInput();
        public List<ItemInput> itens = new ArrayList<>();
    }

    public static class ComprovanteInput {
        public String nome = "";
        public String base64 = "";

        public Map<String, Object> paraMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nome", nome);
            m.put("base64", base64);
            return m;
        }
    }

    /** EntradaManualItem: o System.Text.Json escreve primeiro as propriedades da classe derivada. */
    public static class ManualItem extends ItemInput {
        public String quantidadeCompra = "";
        public String unidadeCompra = "UN";
        public String fatorConversao = "1";
        public String custoCompra = "";
        public boolean alterarPrecoVenda;
        public String estoqueAntes;
        public String estoqueDepois;

        @Override
        public Map<String, Object> paraMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quantidadeCompra", quantidadeCompra);
            m.put("unidadeCompra", unidadeCompra);
            m.put("fatorConversao", fatorConversao);
            m.put("custoCompra", custoCompra);
            m.put("alterarPrecoVenda", alterarPrecoVenda);
            m.put("estoqueAntes", estoqueAntes);
            m.put("estoqueDepois", estoqueDepois);
            escreverBase(m);
            return m;
        }
    }

    public static class EntradaManualRequest {
        public String operacaoId = "";
        public String dataCompra = "";
        public String fornecedorId;
        public String tipoDocumento = "cupom";
        public String numeroDocumento = "";
        public String chaveAcesso;
        public String observacao = "";
        public ComprovanteInput comprovante;
        public List<ManualItem> itens = new ArrayList<>();

        public Map<String, Object> paraMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operacaoId", operacaoId);
            m.put("dataCompra", dataCompra);
            m.put("fornecedorId", fornecedorId);
            m.put("tipoDocumento", tipoDocumento);
            m.put("numeroDocumento", numeroDocumento);
            m.put("chaveAcesso", chaveAcesso);
            m.put("observacao", observacao);
            m.put("comprovante", comprovante == null ? null : comprovante.paraMapa());
            List<Object> lista = new ArrayList<>();
            if (itens != null) {
                for (ManualItem i : itens) lista.add(i == null ? null : i.paraMapa());
            }
            m.put("itens", lista);
            return m;
        }
    }
}
