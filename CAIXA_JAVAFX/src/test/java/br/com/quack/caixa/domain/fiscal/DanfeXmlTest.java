package br.com.quack.caixa.domain.fiscal;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.service.ReceiptPrinter;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DanfeXmlTest {

    static String sample() throws Exception {
        try (InputStream in = DanfeXmlTest.class.getResourceAsStream("/br/com/quack/caixa/fiscal/nfce-autorizada.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void leNfceAutorizada() throws Exception {
        DanfeSnapshot d = DanfeXml.parse(sample());
        assertEquals("33261011222333000181650020000000081000000089", d.chave());
        assertTrue(d.autorizado());
        assertFalse(d.contingencia());
        assertEquals("333260000123456", d.protocolo());
        assertEquals("2", d.ambiente());
        assertEquals("Mercado Teste Quack", d.emitente().fantasia());
        assertEquals("Rua das Flores, 100, Centro, Rio de Janeiro - RJ, 20000000", d.emitente().endereco());
        assertEquals(2, d.itens().size());
        assertEquals(0, new BigDecimal("28.90").compareTo(d.pagar()));
        assertEquals(0, new BigDecimal("5.00").compareTo(d.desconto()));
        assertEquals(0, new BigDecimal("21.10").compareTo(d.troco()));
        assertEquals("Dinheiro", d.pagamentos().get(0).forma());
        assertEquals("CPF", d.consumidor().tipo());
        assertEquals(0, new BigDecimal("4.12").compareTo(d.tributos()));
        assertTrue(d.qrCode().startsWith("https://consultadfe.fazenda.rj.gov.br/"));
        assertEquals(1, d.mensagens().size());
    }

    @Test
    void recusaDoctype() {
        DomainException e = assertThrows(DomainException.class,
                () -> DanfeXml.parse("<!DOCTYPE x [<!ENTITY a \"b\">]><nfeProc/>"));
        assertTrue(e.getMessage().startsWith("Não foi possível preparar"));
    }

    @Test
    void semProtocoloNemContingenciaAindaPreparando() throws Exception {
        String xml = sample().replaceAll("(?s)<protNFe.*</protNFe>", "");
        DomainException e = assertThrows(DomainException.class, () -> DanfeXml.parse(xml));
        assertEquals("A impressão está sendo preparada. Aguarde e use Imprimir última venda.", e.getMessage());
    }

    @Test
    void protocoloQueNaoAutorizaERecusado() throws Exception {
        String xml = sample().replace("<cStat>100</cStat>", "<cStat>302</cStat>");
        DomainException e = assertThrows(DomainException.class, () -> DanfeXml.parse(xml));
        assertTrue(e.getMessage().startsWith("O protocolo recebido não autoriza"));
    }

    @Test
    void contingenciaSemAssinaturaERecusada() throws Exception {
        String xml = sample().replaceAll("(?s)<protNFe.*</protNFe>", "").replace("<tpEmis>1</tpEmis>",
                "<tpEmis>9</tpEmis>");
        DomainException e = assertThrows(DomainException.class, () -> DanfeXml.parse(xml));
        assertTrue(e.getMessage().startsWith("O documento de contingência está incompleto"));
    }

    @Test
    void formatacoesIguaisAWeb() {
        assertEquals("3326 1011 2223 3300 0181 6500 2000 0000 0810 0000 0089",
                ReceiptPrinter.formatChave("33261011222333000181650020000000081000000089"));
        assertEquals("000.000.008", ReceiptPrinter.formatNumeroNf("8"));
        assertEquals("529.982.247-25", DanfeXml.formatDocument("52998224725", "CPF"));
        assertEquals("31/10/2026 02:05:12", ReceiptPrinter.formatDate("2026-10-31T02:05:12-03:00"));
    }
}
