package br.com.quack.pdv.fiscal.fila.repo;

import br.com.quack.pdv.fiscal.fila.domain.DocumentoFiscalDetalhe;
import br.com.quack.pdv.fiscal.fila.domain.DocumentoFiscalDetalhe.Alerta;
import br.com.quack.pdv.shared.Dinheiro;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Leitura do documento fiscal de uma venda (DocumentoFiscalAB.ObterDetalhePorCodigoAsync). So leitura. */
@Repository
public class DocumentoFiscalConsulta {

    private static final String NFE = "http://www.portalfiscal.inf.br/nfe";
    private static final Pattern QR_CODE = Pattern.compile("<qrCode[^>]*>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</qrCode>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final String SQL = """
            SELECT TOP 1 d.Id, v.SaleNumber, d.Serie, d.NumeroNf, d.Status, d.ChaveAcesso, d.Protocolo,
                   d.MotivoStatus, d.DhAutorizacao, d.CriadoEm, d.Tentativas, d.XmlProtocolado, d.XmlAssinado,
                   v.TotalAmount, v.CustomerName, v.CustomerCpf, v.PaymentType, d.Modelo, d.CodigoStatus,
                   CASE WHEN d.XmlProtocolado IS NOT NULL OR d.XmlAssinado IS NOT NULL THEN 1 ELSE 0 END AS HasXml,
                   CASE WHEN d.XmlCancelamento IS NOT NULL THEN 1 ELSE 0 END AS HasCancelXml
            FROM DocumentosFiscais d
            INNER JOIN Vendas v ON v.Id = d.VendaId
            WHERE d.CompanyId = ?
              AND (
                  v.SaleNumber = ?
                  OR d.ChaveAcesso = ?
                  OR (? = 1 AND d.NumeroNf = ?)
                  OR d.Id = ?
              )
            ORDER BY d.CriadoEm DESC;
            """;

    private final JdbcTemplate jdbc;

    public DocumentoFiscalConsulta(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DocumentoFiscalDetalhe> obterDetalhePorCodigo(String companyId, String codigo) {
        if (codigo == null || codigo.isBlank()) return Optional.empty();
        String trim = codigo.trim();
        String limpo = trim.replace(" ", "").replace("-", "");
        Integer numero = null;
        try {
            numero = Integer.parseInt(trim);
        } catch (NumberFormatException e) {
            // nao e numero de nota
        }
        int isNumero = numero != null ? 1 : 0;
        int nf = numero != null ? numero : 0;
        return jdbc.query(SQL, (rs, n) -> {
            String xmlProtocolado = rs.getString("XmlProtocolado");
            String xmlAssinado = rs.getString("XmlAssinado");
            BigDecimal total = rs.getBigDecimal("TotalAmount");
            return new DocumentoFiscalDetalhe(
                    rs.getString("Id"), nz(rs.getString("SaleNumber")), rs.getInt("Serie"), rs.getInt("NumeroNf"),
                    rs.getInt("Status"), rs.getString("ChaveAcesso"), rs.getString("Protocolo"),
                    rs.getString("MotivoStatus"), (Integer) (rs.getObject("CodigoStatus") == null ? null
                            : rs.getInt("CodigoStatus")),
                    rs.getObject("DhAutorizacao", OffsetDateTime.class),
                    rs.getObject("CriadoEm", OffsetDateTime.class), rs.getInt("Tentativas"),
                    total == null ? "0,00" : Dinheiro.formatarSemSimbolo(total), rs.getString("CustomerName"),
                    rs.getString("CustomerCpf"), rs.getString("PaymentType"), rs.getInt("HasXml") == 1,
                    rs.getInt("HasCancelXml") == 1, rs.getShort("Modelo"), null, null, false, null,
                    qrCode(xmlProtocolado) != null ? qrCode(xmlProtocolado) : qrCode(xmlAssinado),
                    completo(xmlAssinado, xmlProtocolado), alertas(xmlProtocolado));
        }, companyId, trim, limpo, isNumero, nf, trim).stream().findFirst();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** ExtrairQrCode: link do QR Code embutido no XML. */
    static String qrCode(String xml) {
        if (xml == null || xml.isBlank()) return null;
        Matcher m = QR_CODE.matcher(xml);
        if (!m.find()) return null;
        String conteudo = m.group(1).trim();
        if (conteudo.isEmpty()) return null;
        return conteudo.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    /** FiscalXmlArchive.Alerts: pares cMsg/xMsg de infProt. */
    static List<Alerta> alertas(String resposta) {
        Document doc = parse(resposta);
        if (doc == null) return List.of();
        NodeList protocolos = doc.getElementsByTagNameNS(NFE, "infProt");
        if (protocolos.getLength() == 0) return List.of();
        List<Alerta> lista = new ArrayList<>();
        String codigo = null;
        for (Node filho = protocolos.item(0).getFirstChild(); filho != null; filho = filho.getNextSibling()) {
            if (!(filho instanceof Element e) || !NFE.equals(e.getNamespaceURI())) continue;
            if (e.getLocalName().equals("cMsg")) {
                codigo = e.getTextContent();
            } else if (e.getLocalName().equals("xMsg")) {
                lista.add(new Alerta(codigo, e.getTextContent()));
                codigo = null;
            }
        }
        return lista;
    }

    /**
     * FiscalXmlArchive.Complete (simplificado ate a Fase 7): resposta nfeProc completa vale como esta; sem ela vale o
     * XML assinado; ambos ausentes dao nulo.
     */
    static String completo(String assinado, String resposta) {
        Document retorno = parse(resposta);
        if (retorno != null && retorno.getDocumentElement() != null
                && "nfeProc".equals(retorno.getDocumentElement().getLocalName())
                && retorno.getElementsByTagNameNS(NFE, "NFe").getLength() > 0) {
            return resposta;
        }
        Document nota = parse(assinado);
        return nota == null ? null : assinado;
    }

    private static Document parse(String xml) {
        if (xml == null || xml.isBlank()) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("XML fiscal invalido.", e);
        }
    }
}
