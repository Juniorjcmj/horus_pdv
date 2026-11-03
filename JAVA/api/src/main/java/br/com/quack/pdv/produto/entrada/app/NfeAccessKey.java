package br.com.quack.pdv.produto.entrada.app;

import br.com.quack.pdv.shared.NegocioException;
import org.springframework.http.HttpStatus;

/**
 * Porta literal de NfeAccessKey (.NET): valida chave de 44 digitos, digito verificador modulo 11,
 * modelo (posicoes 21-22) e extrai CNPJ do emitente (posicoes 7-20).
 */
public final class NfeAccessKey {

    private NfeAccessKey() {}

    public static String somentesDigitos(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String validateInvoiceKey(String value) {
        String key = somentesDigitos(value);
        if (key.length() != 44) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Chave de acesso inválida. Informe os 44 dígitos da nota.");
        }
        int sum = 0;
        for (int i = 42, weight = 2; i >= 0; i--, weight = (weight == 9 ? 2 : weight + 1)) {
            sum += (key.charAt(i) - '0') * weight;
        }
        int remainder = sum % 11;
        int expectedDv = remainder < 2 ? 0 : 11 - remainder;
        if ((key.charAt(43) - '0') != expectedDv) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Chave de acesso inválida. Confira os números e o dígito verificador.");
        }
        String modelo = key.substring(20, 22);
        if (!"55".equals(modelo) && !"65".equals(modelo)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A entrada aceita somente notas dos modelos 55 e 65.");
        }
        return key;
    }

    public static String validateDistributionKey(String value) {
        String key = validateInvoiceKey(value);
        String modelo = key.substring(20, 22);
        if ("65".equals(modelo)) {
            throw new NegocioException(
                    HttpStatus.BAD_REQUEST,
                    "Esta chave é de uma NFC-e (modelo 65). Para dar entrada no estoque, envie o XML do cupom ou use 'Digitar itens do cupom'. A consulta de NF-e no Ambiente Nacional não baixa este modelo.");
        }
        if (!"55".equals(modelo)) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "A consulta no Ambiente Nacional aceita somente chaves de NF-e (modelo 55).");
        }
        return key;
    }

    public static String extrairCnpjEmitente(String chaveValidada) {
        return chaveValidada.substring(6, 20);
    }

    public static int extrairModelo(String chaveValidada) {
        return Integer.parseInt(chaveValidada.substring(20, 22));
    }

    public static int extrairSerie(String chaveValidada) {
        return Integer.parseInt(chaveValidada.substring(22, 25));
    }

    public static long extrairNumeroNota(String chaveValidada) {
        return Long.parseLong(chaveValidada.substring(25, 34));
    }
}
