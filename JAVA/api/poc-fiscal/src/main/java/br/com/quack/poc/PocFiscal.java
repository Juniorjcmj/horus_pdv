package br.com.quack.poc;

import br.com.swconsultoria.certificado.Certificado;
import br.com.swconsultoria.nfe.dom.ConfiguracoesNfe;
import br.com.swconsultoria.nfe.dom.enuns.AmbienteEnum;
import br.com.swconsultoria.nfe.dom.enuns.EstadosEnum;
import java.nio.file.Path;
import java.util.Map;

/**
 * Executa a PoC fiscal.
 *
 * Sempre (offline): monta a NFC-e de exemplo, assina com certificado autoassinado em memoria, gera o QR Code
 * (normal e contingencia) e valida nos XSD 4.00 -> sai com 0 se tudo certo.
 *
 * Com as variaveis POC_CERT_PFX_BASE64, POC_CERT_SENHA, POC_CSC_ID, POC_CSC_TOKEN, POC_UF e POC_CNPJ (certificado A1
 * e CSC de HOMOLOGACAO): tambem roda status, autorizar, consultar, cancelar, inutilizar e contingencia na SEFAZ.
 * POC_SCHEMAS (opcional) aponta para a pasta de XSD (padrao: API/NETCORE/DataBase/Schemas do repositorio).
 *
 *   mvn -B -q exec:java        (na pasta JAVA/api/poc-fiscal)
 */
public final class PocFiscal {

    private PocFiscal() {}

    public static void main(String[] args) throws Exception {
        Path schemas = Path.of(env("POC_SCHEMAS", "../../../API/NETCORE/DataBase/Schemas"));

        // 1) Offline
        byte[] pfx = Certificados.pfxAutoassinado("senha-teste", "11222333000181");
        Certificado cert = Certificados.doPfx(pfx, "senha-teste");
        ConfiguracoesNfe cfg = NotaFiscal.configuracao(EstadosEnum.SP, AmbienteEnum.HOMOLOGACAO, cert, schemas);
        NotaFiscal.Resultado nota = NotaFiscal.gerar(cfg, 1, false, "11222333000181", "000001", "CSC-DE-TESTE-0123456789", schemas);
        System.out.println("[OFFLINE] NFC-e assinada e valida nos XSD 4.00. Chave: " + nota.chave());

        // 2) SEFAZ de homologacao (so com POC_*)
        String pfxB64 = System.getenv("POC_CERT_PFX_BASE64");
        if (pfxB64 == null || pfxB64.isBlank()) {
            System.out.println("[SEFAZ] PENDENTE: defina POC_CERT_PFX_BASE64, POC_CERT_SENHA, POC_CSC_ID, POC_CSC_TOKEN, POC_UF e POC_CNPJ.");
            return;
        }
        String senha = exigir("POC_CERT_SENHA");
        String cnpj = exigir("POC_CNPJ");
        EstadosEnum uf = EstadosEnum.valueOf(exigir("POC_UF").toUpperCase());
        Certificado real = Certificados.doPfx(Certificados.doBase64(pfxB64), senha);
        ConfiguracoesNfe cfgReal = NotaFiscal.configuracao(uf, AmbienteEnum.HOMOLOGACAO, real, schemas);
        OperacoesSefaz ops = new OperacoesSefaz(cfgReal, cnpj, exigir("POC_CSC_ID"), exigir("POC_CSC_TOKEN"), schemas);
        int base = (int) (System.currentTimeMillis() / 1000 % 900_000) + 1000;
        Map<String, String> evidencias = ops.cicloCompleto(base);
        evidencias.forEach((passo, resultado) -> System.out.println("[SEFAZ] " + passo + ": " + resultado));
    }

    private static String env(String nome, String padrao) {
        String v = System.getenv(nome);
        return v == null || v.isBlank() ? padrao : v;
    }

    private static String exigir(String nome) {
        String v = System.getenv(nome);
        if (v == null || v.isBlank()) throw new IllegalStateException("Falta a variavel " + nome);
        return v;
    }
}
