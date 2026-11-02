package br.com.quack.poc;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.swconsultoria.certificado.Certificado;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.dom.enuns.EstadosEnum;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Parte offline da PoC: monta, assina com certificado autoassinado (em memoria) e valida nos XSD 4.00. */
class NfceOfflineTest {

    static final Path SCHEMAS = Path.of("..", "..", "NETCORE", "DataBase", "Schemas");
    static ConfiguracoesNfe cfg;

    @BeforeAll
    static void preparar() throws Exception {
        assertThat(Files.isDirectory(SCHEMAS)).as("pasta de XSD " + SCHEMAS.toAbsolutePath()).isTrue();
        byte[] pfx = Certificados.pfxAutoassinado("senha-teste", "11222333000181");
        Certificado cert = Certificados.doPfx(pfx, "senha-teste");
        cfg = NotaFiscal.configuracao(EstadosEnum.SP, AmbienteEnum.HOMOLOGACAO, cert, SCHEMAS);
    }

    @Test
    void nfceNormalAssinadaComQrCodeValidaNosXsd() throws Exception {
        NotaFiscal.Resultado r = NotaFiscal.gerar(cfg, 1001, false, "11222333000181", "000001", "CSC-DE-TESTE-0123456789", SCHEMAS);
        String xml = r.xmlAssinado();
        assertThat(r.chave()).hasSize(44).startsWith("35");
        assertThat(xml).contains("<mod>65</mod>").contains("<tpEmis>1</tpEmis>").contains("<infNFeSupl>")
                .contains("<qrCode>").contains("<urlChave>").contains("<Signature").contains("<SignatureValue>");
        assertThat(xml).contains("<tPag>01</tPag>").contains("<tPag>17</tPag>").contains("<vDesc>1.00</vDesc>");
        assertThat(xml).doesNotContain("<dest>"); // consumidor sem CPF
    }

    @Test
    void itemTemOGrupoIbsCbsComCstECClassTrib() throws Exception {
        String xml = NotaFiscal.gerar(cfg, 1002, false, "11222333000181", "000001", "CSC-DE-TESTE-0123456789", SCHEMAS).xmlAssinado();
        assertThat(xml).contains("<IBSCBS>").contains("<CST>000</CST>").contains("<cClassTrib>000001</cClassTrib>")
                .contains("<gIBSCBS>").contains("<IBSCBSTot>");
    }

    @Test
    void contingenciaOfflineTpEmis9GeraQrCodeComDigestValue() throws Exception {
        NotaFiscal.Resultado r = NotaFiscal.gerar(cfg, 1003, true, "11222333000181", "000001", "CSC-DE-TESTE-0123456789", SCHEMAS);
        assertThat(r.xmlAssinado()).contains("<tpEmis>9</tpEmis>").contains("<dhCont>").contains("<xJust>").contains("<qrCode>");
        assertThat(r.chave().substring(34, 35)).isEqualTo("9"); // tpEmis dentro da chave
    }

    @Test
    void certificadoNaoVaiParaDisco() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long antes;
        try (var s = Files.list(tmp)) { antes = s.filter(p -> p.getFileName().toString().startsWith("poc-cert")).count(); }
        Certificados.pfxAutoassinado("outra-senha", "11222333000181");
        long depois;
        try (var s = Files.list(tmp)) { depois = s.filter(p -> p.getFileName().toString().startsWith("poc-cert")).count(); }
        assertThat(depois).isEqualTo(antes);
    }
}
