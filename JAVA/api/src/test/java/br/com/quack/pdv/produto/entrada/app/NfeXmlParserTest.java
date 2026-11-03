package br.com.quack.pdv.produto.entrada.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.shared.NegocioException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NfeXmlParserTest {

    private static byte[] lerFixture(String nome) throws Exception {
        try (InputStream in = NfeXmlParserTest.class.getResourceAsStream("/contrato/fixtures/" + nome)) {
            assertThat(in).as("fixture %s", nome).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void leNfeExemploENfeModelo55ENfceExemplo() throws Exception {
        var docExemplo = NfeXmlParser.parse(lerFixture("nfe-exemplo.xml"));
        assertThat(docExemplo.modelo()).isEqualTo(65);
        assertThat(docExemplo.numeroNota()).isEqualTo("8");
        assertThat(docExemplo.serie()).isEqualTo("2");
        assertThat(docExemplo.chaveAcesso()).isEqualTo("33261011222333000181650020000000081000000089");
        assertThat(docExemplo.emitente().cnpj()).isEqualTo("11222333000181");
        assertThat(docExemplo.emitente().razaoSocial()).isEqualTo("Operador Teste");
        assertThat(docExemplo.emitente().nomeFantasia()).isEqualTo("Mercado Teste Quack");
        assertThat(docExemplo.itens()).hasSize(2);
        assertThat(docExemplo.itens().get(0).codigoFornecedor()).isEqualTo("2002");
        assertThat(docExemplo.itens().get(0).gtin()).isNull();
        assertThat(docExemplo.itens().get(0).quantidade()).isEqualByComparingTo(new BigDecimal("3.0000"));
        assertThat(docExemplo.itens().get(0).valorUnitario()).isEqualByComparingTo(new BigDecimal("5.0000000000"));
        assertThat(docExemplo.valorNota()).isEqualByComparingTo(new BigDecimal("28.90"));

        var doc55 = NfeXmlParser.parse(lerFixture("nfe-modelo55.xml"));
        assertThat(doc55.modelo()).isEqualTo(55);
        assertThat(doc55.numeroNota()).isEqualTo("101");
        assertThat(doc55.serie()).isEqualTo("1");
        assertThat(doc55.chaveAcesso()).isEqualTo("33260111222333000181550010000001011100001010");
        assertThat(doc55.emitente().telefone()).isEqualTo("2133334444");
        assertThat(doc55.itens()).hasSize(2);
        assertThat(doc55.itens().get(0).gtin()).isEqualTo("7891000100103");
        assertThat(doc55.itens().get(1).gtin()).isNull();
        assertThat(doc55.itens().get(1).cest()).isEqualTo("1705300");

        var docNfce = NfeXmlParser.parse(lerFixture("nfce-exemplo.xml"));
        assertThat(docNfce.modelo()).isEqualTo(65);
        assertThat(docNfce.numeroNota()).isEqualTo("202");
        assertThat(docNfce.chaveAcesso()).isEqualTo("33260111222333000181650010000002021100002020");
    }

    @Test
    void validaChaveDeAcessoModulo11ModeloECnpj() throws Exception {
        String chave55 = "33260111222333000181550010000001011100001010";
        assertThat(NfeAccessKey.validateInvoiceKey(chave55)).isEqualTo(chave55);
        assertThat(NfeAccessKey.validateDistributionKey(chave55)).isEqualTo(chave55);
        assertThat(NfeAccessKey.extrairCnpjEmitente(chave55)).isEqualTo("11222333000181");
        assertThat(NfeAccessKey.extrairModelo(chave55)).isEqualTo(55);
        assertThat(NfeAccessKey.extrairSerie(chave55)).isEqualTo(1);
        assertThat(NfeAccessKey.extrairNumeroNota(chave55)).isEqualTo(101L);

        // Chave adulterada (DV incorreto na fixture nfe-chave-adulterada.xml)
        var docAdulterada = NfeXmlParser.parse(lerFixture("nfe-chave-adulterada.xml"));
        assertThatThrownBy(() -> NfeAccessKey.validateInvoiceKey(docAdulterada.chaveAcesso()))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Chave de acesso inválida. Confira os números e o dígito verificador.");

        // Menos de 44 digitos
        assertThatThrownBy(() -> NfeAccessKey.validateInvoiceKey("33260111222333000181"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Chave de acesso inválida. Informe os 44 dígitos da nota.");

        // Modelo 57 na chave (DV valido: 5)
        assertThatThrownBy(() -> NfeAccessKey.validateInvoiceKey("33260111222333000181570010000005011100005015"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("A entrada aceita somente notas dos modelos 55 e 65.");

        // Modelo 65 valido para entrada, mas recusado para distribuicao DFe (buscar-sefaz)
        String chave65 = "33260111222333000181650010000002021100002020";
        assertThat(NfeAccessKey.validateInvoiceKey(chave65)).isEqualTo(chave65);
        assertThatThrownBy(() -> NfeAccessKey.validateDistributionKey(chave65))
                .isInstanceOf(NegocioException.class)
                .hasMessage(
                        "Esta chave é de uma NFC-e (modelo 65). Para dar entrada no estoque, envie o XML do cupom ou use 'Digitar itens do cupom'. A consulta de NF-e no Ambiente Nacional não baixa este modelo.");
    }

    @Test
    void recusaSemDetSemEmitChaveDivergenteModeloInvalidoDoctypeBase64CorrompidoEArcoGrande() throws Exception {
        assertThatThrownBy(() -> NfeXmlParser.parse(lerFixture("nfe-sem-det.xml")))
                .isInstanceOf(NegocioException.class)
                .hasMessage("XML não tem nenhum item (<det>) para importar.");

        assertThatThrownBy(() -> NfeXmlParser.parse(lerFixture("nfe-chave-divergente.xml")))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Chaves divergentes no XML e no protocolo da nota.");

        assertThatThrownBy(() -> NfeXmlParser.parse(lerFixture("nfe-modelo-invalido.xml")))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Envie o XML de uma NF-e (modelo 55) ou NFC-e (modelo 65).");

        String semEmit = """
                <?xml version="1.0" encoding="UTF-8"?>
                <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                  <infNFe Id="NFe33260111222333000181550010000001011100001010">
                    <ide><mod>55</mod><serie>1</serie><nNF>101</nNF></ide>
                  </infNFe>
                </NFe>
                """;
        assertThatThrownBy(() -> NfeXmlParser.parse(semEmit.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NegocioException.class)
                .hasMessage("XML sem o bloco <emit> — nota fiscal inválida.");

        String comDoctypeXxe = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <NFe xmlns="http://www.portalfiscal.inf.br/nfe">
                  <infNFe><ide><mod>55</mod></ide></infNFe>
                </NFe>
                """;
        assertThatThrownBy(() -> NfeXmlParser.parse(comDoctypeXxe.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Arquivo enviado não é um XML válido.");

        assertThatThrownBy(() ->
                        NfeXmlParser.decodificarBase64("%%%nao-e-base64%%%", "Arquivo XML inválido (base64 corrompido)."))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Arquivo XML inválido (base64 corrompido).");

        byte[] maiorQue10Mb = new byte[NfeXmlParser.MAX_XML_BYTES + 1];
        assertThatThrownBy(() -> NfeXmlParser.parse(maiorQue10Mb))
                .isInstanceOf(NegocioException.class)
                .hasMessage("O XML deve ter no máximo 10 MB.");
    }
}
