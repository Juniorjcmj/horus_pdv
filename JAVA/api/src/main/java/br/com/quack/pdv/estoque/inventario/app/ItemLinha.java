package br.com.quack.pdv.estoque.inventario.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/** Linha de divergencia/contagem de um produto do inventario (mesmos campos e ordem do ItemLinha do .NET). */
public record ItemLinha(
        String produtoId,
        String codigo,
        String nome,
        String unidade,
        BigDecimal custo,
        BigDecimal saldoNa1aContagem,
        BigDecimal contado,
        BigDecimal diferenca,
        BigDecimal valorDiferenca,
        boolean recontar,
        OffsetDateTime primeiraContagemEm,
        OffsetDateTime ultimaContagemEm,
        BigDecimal saldoAntes,
        BigDecimal delta,
        BigDecimal saldoDepois,
        BigDecimal valorDelta,
        BigDecimal pisoAplicado) {

    /** Monta a linha calculando diferenca (contado - saldo da 1a contagem) e seu valor (arredondamento bancario). */
    public static ItemLinha calcular(
            String produtoId, String codigo, String nome, String unidade, BigDecimal custo,
            BigDecimal saldo, BigDecimal contado, boolean recontar,
            OffsetDateTime primeira, OffsetDateTime ultima,
            BigDecimal saldoAntes, BigDecimal delta, BigDecimal saldoDepois, BigDecimal valorDelta, BigDecimal piso) {
        BigDecimal dif = contado == null ? null : contado.subtract(saldo == null ? BigDecimal.ZERO : saldo);
        BigDecimal valor = dif == null ? null : dif.multiply(custo).setScale(2, RoundingMode.HALF_EVEN);
        return new ItemLinha(produtoId, codigo, nome, unidade, custo, saldo, contado, dif, valor, recontar,
                primeira, ultima, saldoAntes, delta, saldoDepois, valorDelta, piso);
    }

    /** Contagem cega: sem custo e sem valores. */
    public ItemLinha semValores() {
        return new ItemLinha(produtoId, codigo, nome, unidade, BigDecimal.ZERO, saldoNa1aContagem, contado, diferenca,
                null, recontar, primeiraContagemEm, ultimaContagemEm, saldoAntes, delta, saldoDepois, null,
                pisoAplicado);
    }

    boolean temContagem() {
        return contado != null;
    }
}
