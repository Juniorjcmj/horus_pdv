package br.com.quack.caixa.domain.fiscal;

import br.com.quack.caixa.domain.DomainException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Lê o XML da NFC-e (NFe ou nfeProc) com as mesmas regras e mensagens de danfeXml.ts. */
public final class DanfeXml {
    private static final String NS_NFE = "http://www.portalfiscal.inf.br/nfe";
    private static final String NS_DSIG = "http://www.w3.org/2000/09/xmldsig#";
    private static final Pattern AMOUNT = Pattern.compile("^\\d+(?:\\.\\d+)?$");
    private static final Pattern DATE = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})$");

    static final Map<String, String> PAGAMENTOS = Map.ofEntries(
            Map.entry("01", "Dinheiro"), Map.entry("02", "Cheque"), Map.entry("03", "Cartão de crédito"),
            Map.entry("04", "Cartão de débito"), Map.entry("05", "Crédito loja"), Map.entry("10", "Vale alimentação"),
            Map.entry("11", "Vale refeição"), Map.entry("12", "Vale presente"), Map.entry("13", "Vale combustível"),
            Map.entry("15", "Boleto bancário"), Map.entry("16", "Depósito bancário"), Map.entry("17", "PIX"),
            Map.entry("18", "Transferência bancária"), Map.entry("19", "Programa de fidelidade"),
            Map.entry("20", "PIX estático"), Map.entry("21", "Crédito em loja"),
            Map.entry("22", "Pagamento eletrônico não informado"), Map.entry("90", "Sem pagamento"),
            Map.entry("99", "Outros"));

    private DanfeXml() {
    }

    public static DanfeSnapshot parse(String xml) {
        if (xml == null || xml.isEmpty() || xml.length() > 5 * 1024 * 1024
                || Pattern.compile("<!DOCTYPE|<!ENTITY", Pattern.CASE_INSENSITIVE).matcher(xml).find()) {
            throw new DomainException(
                    "Não foi possível preparar o documento de impressão. Consulte a administração e tente novamente.");
        }
        Document doc = read(xml);
        Element root = doc.getDocumentElement();
        Element nfe = "NFe".equals(root.getLocalName()) ? root
                : "nfeProc".equals(root.getLocalName()) ? child(root, "NFe") : null;
        Element info = child(nfe, "infNFe");
        if (info == null || !NS_NFE.equals(info.getNamespaceURI()) || !"4.00".equals(info.getAttribute("versao"))) {
            throw new DomainException("O documento recebido não é uma NFC-e válida para impressão.");
        }
        Element ide = child(info, "ide");
        Element emit = child(info, "emit");
        Element total = child(child(info, "total"), "ICMSTot");
        String chave = info.getAttribute("Id").replaceFirst("^NFe", "");
        if (!"65".equals(text(ide, "mod")) || !chave.matches("^\\d{44}$")) {
            throw new DomainException("Este documento exige outro modelo de impressão. Consulte a administração.");
        }
        boolean contingencia = "9".equals(text(ide, "tpEmis"));
        Element infProt = child(child(root, "protNFe"), "infProt");
        boolean autorizado = infProt != null && List.of("100", "120", "150").contains(text(infProt, "cStat"))
                && chave.equals(text(infProt, "chNFe"));
        if (infProt != null && !autorizado) {
            throw new DomainException("O protocolo recebido não autoriza esta NFC-e. Consulte a administração.");
        }
        if (!autorizado && !contingencia) {
            throw new DomainException("A impressão está sendo preparada. Aguarde e use Imprimir última venda.");
        }
        if (contingencia && nfe.getElementsByTagNameNS(NS_DSIG, "Signature").getLength() == 0) {
            throw new DomainException("O documento de contingência está incompleto. Consulte a administração.");
        }

        List<DanfeSnapshot.Item> itens = new ArrayList<>();
        for (Element det : children(info, "det")) {
            Element p = child(det, "prod");
            String unidade = text(p, "uCom");
            if (text(p, "cProd").isEmpty() || text(p, "xProd").isEmpty() || unidade.isEmpty()) {
                throw new DomainException("O documento contém um item incompleto. Consulte a administração.");
            }
            itens.add(new DanfeSnapshot.Item(text(p, "cProd"), text(p, "xProd"), amount(p, "qCom", false), unidade,
                    amount(p, "vUnCom", false), amount(p, "vProd", false)));
        }
        if (itens.isEmpty()) {
            throw new DomainException("O documento recebido não contém itens para impressão.");
        }

        Element dest = child(info, "dest");
        Element entrega = child(info, "entrega");
        String tipo = !text(dest, "CPF").isEmpty() ? "CPF" : !text(dest, "CNPJ").isEmpty() ? "CNPJ" : "Id. Estrangeiro";
        String documento = firstNonEmpty(text(dest, "CPF"), text(dest, "CNPJ"), text(dest, "idEstrangeiro"));
        Element suplementar = child(nfe, "infNFeSupl");
        Element pag = child(info, "pag");
        Element adicional = child(info, "infAdic");

        List<String> mensagens = new ArrayList<>();
        addIfPresent(mensagens, text(adicional, "infAdFisco"));
        if (infProt != null) {
            NodeList alertas = infProt.getElementsByTagNameNS(NS_NFE, "xMsg");
            for (int i = 0; i < alertas.getLength(); i++) {
                addIfPresent(mensagens, alertas.item(i).getTextContent().trim());
            }
        }
        addIfPresent(mensagens, text(adicional, "infCpl"));

        BigDecimal tributos = text(total, "vTotTrib").isEmpty() ? null : amount(total, "vTotTrib", false);
        String protocolo = autorizado ? text(infProt, "nProt") : null;
        if (autorizado && protocolo.isEmpty()) {
            throw new DomainException("O protocolo de impressão está incompleto. Tente novamente.");
        }

        List<DanfeSnapshot.Pagamento> pagamentos = new ArrayList<>();
        for (Element p : children(pag, "detPag")) {
            String forma = firstNonEmpty(text(p, "xPag"), PAGAMENTOS.get(text(p, "tPag")), "Pagamento " + text(p, "tPag"));
            pagamentos.add(new DanfeSnapshot.Pagamento(forma, amount(p, "vPag", false)));
        }

        return new DanfeSnapshot(chave, text(ide, "nNF"), text(ide, "serie"), text(ide, "tpAmb"),
                date(text(ide, "dhEmi")), contingencia, autorizado, protocolo,
                autorizado ? date(text(infProt, "dhRecbto")) : null,
                new DanfeSnapshot.Emitente(text(emit, "xNome"), text(emit, "xFant"), text(emit, "CNPJ"),
                        address(child(emit, "enderEmit"))),
                documento.isEmpty() ? null : new DanfeSnapshot.Consumidor(tipo, documento, text(dest, "xNome"),
                        address(entrega != null ? entrega : child(dest, "enderDest"))),
                itens, amount(total, "vProd", false), amount(total, "vDesc", true), amount(total, "vFrete", true),
                amount(total, "vSeg", true), amount(total, "vOutro", true), amount(total, "vNF", false), pagamentos,
                amount(pag, "vTroco", true), tributos, consultation(text(suplementar, "qrCode")),
                consultation(text(suplementar, "urlChave")), mensagens);
    }

    /** CNPJ 00.000.000/0000-00 e CPF 000.000.000-00 (formatFiscalDocument da web). */
    public static String formatDocument(String value, String tipo) {
        if ("CNPJ".equals(tipo) && value.matches("^\\d{14}$")) {
            return value.replaceFirst("^(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})$", "$1.$2.$3/$4-$5");
        }
        if ("CPF".equals(tipo) && value.matches("^\\d{11}$")) {
            return value.replaceFirst("^(\\d{3})(\\d{3})(\\d{3})(\\d{2})$", "$1.$2.$3-$4");
        }
        return value;
    }

    // ---------- leitura segura ----------

    private static Document read(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new DomainException("O documento recebido está incompleto. Tente imprimir novamente.");
        }
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        Element e = child(parent, tag);
        return e == null ? "" : e.getTextContent().trim();
    }

    private static BigDecimal amount(Element parent, String tag, boolean optional) {
        String v = text(parent, tag);
        if (v.isEmpty() && optional) {
            return BigDecimal.ZERO;
        }
        if (!AMOUNT.matcher(v).matches()) {
            throw new DomainException("O documento recebido contém " + tag + " inválido. Consulte a administração.");
        }
        return new BigDecimal(v);
    }

    private static String address(Element parent) {
        if (parent == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String t : new String[] {"xLgr", "nro", "xCpl", "xBairro"}) {
            addIfPresent(parts, text(parent, t));
        }
        String mun = text(parent, "xMun");
        String uf = text(parent, "UF");
        addIfPresent(parts, mun.isEmpty() ? uf : uf.isEmpty() ? mun : mun + " - " + uf);
        addIfPresent(parts, text(parent, "CEP"));
        return String.join(", ", parts);
    }

    private static String consultation(String value) {
        try {
            // O QR Code da NFC-e traz "|" nos parâmetros: o navegador aceita, o URI do Java não. Valida só
            // protocolo/host/usuário (o que a web checa) na parte antes do "?".
            if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException();
            }
            int q = value.indexOf('?');
            URI u = URI.create(q < 0 ? value : value.substring(0, q));
            if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || u.getUserInfo() != null
                    || u.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException e) {
            throw new DomainException(
                    "O documento recebido não contém o endereço fiscal de consulta válido. Consulte a administração.");
        }
    }

    private static String date(String value) {
        if (!DATE.matcher(value).matches()) {
            throw new DomainException("O documento recebido não contém a data fiscal válida. Consulte a administração.");
        }
        try {
            OffsetDateTime.parse(value);
        } catch (RuntimeException e) {
            throw new DomainException("O documento recebido não contém a data fiscal válida. Consulte a administração.");
        }
        return value;
    }

    private static void addIfPresent(List<String> list, String v) {
        if (v != null && !v.isEmpty()) {
            list.add(v);
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
