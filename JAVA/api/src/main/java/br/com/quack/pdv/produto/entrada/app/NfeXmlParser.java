package br.com.quack.pdv.produto.entrada.app;

import br.com.quack.pdv.shared.NegocioException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Porta literal de NfeXmlParser (.NET) com DOM do JDK endurecido contra XXE e limite de 10 MB (D5).
 */
public final class NfeXmlParser {

    public static final String NS = "http://www.portalfiscal.inf.br/nfe";
    public static final int MAX_XML_BYTES = 10 * 1024 * 1024;
    public static final int MAX_BASE64_CHARS = 14_000_000;

    private NfeXmlParser() {}

    public record NfeParsedEmitente(
            String cnpj,
            String razaoSocial,
            String nomeFantasia,
            String logradouro,
            String numero,
            String bairro,
            String municipio,
            String uf,
            String cep,
            String telefone) {}

    public record NfeParsedItem(
            int numeroItem,
            String codigoFornecedor,
            String gtin,
            String descricao,
            String ncm,
            String cest,
            String unidadeComercial,
            BigDecimal quantidade,
            BigDecimal valorUnitario) {}

    public record NfeParsedDocument(
            int modelo,
            String numeroNota,
            String serie,
            NfeParsedEmitente emitente,
            List<NfeParsedItem> itens,
            String chaveAcesso,
            String dataEmissao,
            BigDecimal valorNota) {}

    private static NegocioException erro(String message) {
        return new NegocioException(HttpStatus.BAD_REQUEST, message);
    }

    public static byte[] decodificarBase64(String xmlBase64, String mensagemBase64Invalido) {
        if (xmlBase64 == null || xmlBase64.isBlank()) {
            throw erro("Nenhum arquivo XML enviado.");
        }
        if (xmlBase64.length() > MAX_BASE64_CHARS) {
            throw erro("O XML deve ter no máximo 10 MB.");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(xmlBase64.trim());
            if (bytes.length > MAX_XML_BYTES) {
                throw erro("O XML deve ter no máximo 10 MB.");
            }
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw erro(mensagemBase64Invalido);
        }
    }

    public static NfeParsedDocument parse(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw erro("Arquivo enviado não é um XML válido.");
        }
        if (xmlBytes.length > MAX_XML_BYTES) {
            throw erro("O XML deve ter no máximo 10 MB.");
        }

        Document doc = lerDomSeguro(xmlBytes);

        Element infNFe = primeiroDescendente(doc, "infNFe");
        if (infNFe == null) {
            throw erro(
                    "XML não parece ser uma NF-e (elemento infNFe não encontrado). Envie o XML autorizado da nota de compra.");
        }

        Element ide = filhoDireto(infNFe, "ide");
        if (ide == null) {
            throw erro("XML sem o bloco <ide> — nota fiscal inválida.");
        }
        Element emit = filhoDireto(infNFe, "emit");
        if (emit == null) {
            throw erro("XML sem o bloco <emit> — nota fiscal inválida.");
        }

        Integer modelo = tryParseInt(valor(ide, "mod"));
        if (modelo == null || (modelo != 55 && modelo != 65)) {
            throw erro("Envie o XML de uma NF-e (modelo 55) ou NFC-e (modelo 65).");
        }

        Element enderEmit = filhoDireto(emit, "enderEmit");
        String cnpj = valor(emit, "CNPJ");
        if (cnpj.isBlank()) {
            throw erro("Emitente da nota sem CNPJ — não é possível identificar o fornecedor.");
        }

        NfeParsedEmitente emitente = new NfeParsedEmitente(
                cnpj,
                valor(emit, "xNome"),
                emptyToNull(valor(emit, "xFant")),
                valor(enderEmit, "xLgr"),
                valor(enderEmit, "nro"),
                valor(enderEmit, "xBairro"),
                valor(enderEmit, "xMun"),
                valor(enderEmit, "UF"),
                valor(enderEmit, "CEP"),
                emptyToNull(valor(enderEmit, "fone")));

        List<NfeParsedItem> itens = new ArrayList<>();
        for (Element det : filhosDiretos(infNFe, "det")) {
            Element prod = filhoDireto(det, "prod");
            if (prod == null) {
                continue;
            }
            Integer nItem = tryParseInt(det.getAttribute("nItem"));
            int numeroItem = nItem != null ? nItem : 0;
            String gtin = valor(prod, "cEAN");

            itens.add(new NfeParsedItem(
                    numeroItem,
                    valor(prod, "cProd"),
                    isGtinValido(gtin) ? gtin : null,
                    valor(prod, "xProd"),
                    emptyToDefault(valor(prod, "NCM"), "00000000"),
                    emptyToNull(valor(prod, "CEST")),
                    emptyToDefault(valor(prod, "uCom"), "UN").toUpperCase(Locale.ROOT),
                    parseXmlDecimal(valor(prod, "qCom")),
                    parseXmlDecimal(valor(prod, "vUnCom"))));
        }

        if (itens.isEmpty()) {
            throw erro("XML não tem nenhum item (<det>) para importar.");
        }

        String idAttr = infNFe.hasAttribute("Id") ? infNFe.getAttribute("Id") : null;
        String chave = idAttr != null && idAttr.startsWith("NFe") ? idAttr.substring(3) : null;
        Element chNFeEl = primeiroDescendente(doc, "chNFe");
        String protocolo = chNFeEl != null && chNFeEl.getTextContent() != null ? chNFeEl.getTextContent().trim() : null;
        if (chave != null && !chave.isBlank() && protocolo != null && !protocolo.isBlank() && !chave.equals(protocolo)) {
            throw erro("Chaves divergentes no XML e no protocolo da nota.");
        }

        Element totalEl = filhoDireto(infNFe, "total");
        Element icmsTotEl = filhoDireto(totalEl, "ICMSTot");
        String totalStr = valor(icmsTotEl, "vNF");

        String chaveFinal = chave != null ? chave : emptyToNull(protocolo != null ? protocolo : "");
        String dhEmi = emptyToNull(valor(ide, "dhEmi"));
        String dataEmissao = dhEmi != null ? dhEmi : emptyToNull(valor(ide, "dEmi"));
        BigDecimal valorNota = parseNullableDecimal(totalStr);

        return new NfeParsedDocument(
                modelo,
                valor(ide, "nNF"),
                valor(ide, "serie"),
                emitente,
                itens,
                chaveFinal,
                dataEmissao,
                valorNota);
    }

    private static Document lerDomSeguro(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);

            DocumentBuilder builder = dbf.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {}

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(new ByteArrayInputStream(xmlBytes));
        } catch (Exception ex) {
            throw erro("Arquivo enviado não é um XML válido.");
        }
    }

    private static Element primeiroDescendente(Document doc, String localName) {
        NodeList list = doc.getElementsByTagNameNS(NS, localName);
        if (list.getLength() == 0) {
            return null;
        }
        return (Element) list.item(0);
    }

    private static Element filhoDireto(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el
                    && NS.equals(el.getNamespaceURI())
                    && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> filhosDiretos(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element el
                    && NS.equals(el.getNamespaceURI())
                    && localName.equals(el.getLocalName())) {
                result.add(el);
            }
        }
        return result;
    }

    private static String valor(Element parent, String localName) {
        Element child = filhoDireto(parent, localName);
        if (child == null || child.getTextContent() == null) {
            return "";
        }
        return child.getTextContent().trim();
    }

    private static boolean isGtinValido(String value) {
        return value != null && !value.isBlank() && !"SEM GTIN".equalsIgnoreCase(value.trim());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer tryParseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseXmlDecimal(String value) {
        BigDecimal d = parseNullableDecimal(value);
        return d != null ? d : BigDecimal.ZERO;
    }

    private static BigDecimal parseNullableDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
