package br.com.quack.poc;

import br.com.swconsultoria.certificado.Certificado;
import br.com.swconsultoria.nfe.Nfe;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.dom.enuns.EstadosEnum;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TEnviNFe;
import br.com.swconsultoria.nfe.schema_4.enviNFe.TNFe;
import br.com.swconsultoria.nfe.util.NFCeUtil;
import br.com.swconsultoria.nfe.util.XmlNfeUtil;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

/** Monta, assina, gera o QR Code e valida nos XSD 4.00 uma NFC-e de exemplo. */
public final class NotaFiscal {

    public record Resultado(String chave, String xmlAssinado, TEnviNFe envio, ConfiguracoesNfe config, boolean contingencia) {}

    private NotaFiscal() {}

    public static ConfiguracoesNfe configuracao(EstadosEnum uf, AmbienteEnum ambiente, Certificado certificado, Path schemas)
            throws Exception {
        ConfiguracoesNfe cfg = ConfiguracoesNfe.criarConfiguracoes(uf, ambiente, certificado, schemas.toAbsolutePath().toString());
        cfg.setValidacaoDocumento(true);
        return cfg;
    }

    /** NFC-e normal (tpEmis 1) ou em contingencia offline (tpEmis 9), assinada e com QR Code, validada nos XSD. */
    public static Resultado gerar(ConfiguracoesNfe cfg, int numero, boolean contingencia, String cnpj, String idCsc, String csc,
                                  Path schemas) throws Exception {
        VendaExemplo venda = VendaExemplo.montar(cfg, numero, contingencia, cnpj);
        TNFe nfe = venda.envio.getNFe().get(0);
        String urlQr = VendaExemplo.urlQrCode(cfg);
        String urlConsulta = VendaExemplo.urlConsulta(cfg);
        String ambiente = cfg.getAmbiente().getCodigo();

        // Assina (e valida o leiaute) com a biblioteca. O QR Code fica FORA da assinatura (infNFeSupl).
        TEnviNFe assinado = Nfe.montaNfe(cfg, venda.envio, true);
        TNFe.InfNFeSupl supl = new TNFe.InfNFeSupl();
        if (contingencia) {
            String digVal = digestValue(XmlNfeUtil.objectToXml(assinado));
            String dhEmi = nfe.getInfNFe().getIde().getDhEmi(); // a biblioteca extrai o dia de dentro da data
            supl.setQrCode(NFCeUtil.getCodeQRCodeContingencia(venda.chave, ambiente, dhEmi,
                    venda.valorNota.setScale(2).toPlainString(), digVal, idCsc, csc, urlQr));
        } else {
            supl.setQrCode(NFCeUtil.getCodeQRCode(venda.chave, ambiente, idCsc, csc, urlQr));
        }
        supl.setUrlChave(urlConsulta);
        assinado.getNFe().get(0).setInfNFeSupl(supl);

        String xml = XmlNfeUtil.objectToXml(assinado);
        validarNosXsd(xml, schemas);
        return new Resultado(venda.chave, xml, assinado, cfg, contingencia);
    }

    /** Valida o lote completo (enviNFe) no XSD oficial 4.00, com infNFeSupl e IBS/CBS. */
    public static void validarNosXsd(String xml, Path schemas) throws Exception {
        SchemaFactory fabrica = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        File xsd = schemas.resolve("enviNFe_v4.00.xsd").toFile();
        fabrica.newSchema(xsd).newValidator().validate(new StreamSource(new StringReader(xml)));
    }

    static String digestValue(String xml) {
        int i = xml.indexOf("<DigestValue>");
        int f = xml.indexOf("</DigestValue>");
        if (i < 0 || f < 0) throw new IllegalStateException("DigestValue ausente: NFe nao assinada");
        return xml.substring(i + "<DigestValue>".length(), f);
    }
}
