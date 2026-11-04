package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import java.util.List;

/**
 * Uma parte de um lote. {@code de}/{@code ate}: versoes do Change Tracking da loja. {@code completo}: retrato inteiro
 * (ressincronizacao, D5). {@code parte} comeca em 1; a ultima traz {@code ultima = true}.
 */
public record ParteLote(String loteId, String companyId, long de, long ate, boolean completo, int parte, boolean ultima,
                        List<BlocoTabela> blocos) {

    public ParteLote comNumero(int numero, boolean ehUltima) {
        return new ParteLote(loteId, companyId, de, ate, completo, numero, ehUltima, blocos);
    }

    public ParteLote comEmpresa(String empresa) {
        return new ParteLote(loteId, empresa, de, ate, completo, parte, ultima, blocos);
    }
}
