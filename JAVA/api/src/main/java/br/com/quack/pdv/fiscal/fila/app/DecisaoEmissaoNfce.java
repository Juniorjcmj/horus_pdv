package br.com.quack.pdv.fiscal.fila.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decisao de emissao de NFC-e por venda (RegrasEmissaoNfceAB.AvaliarEmissaoParaVendaAsync): grava uma unica decisao
 * por venda, em transacao propria, e serializa o contador de vendas da empresa. Repeticoes reaproveitam a decisao.
 */
@Service
public class DecisaoEmissaoNfce {

    /** Regras de emissao da empresa (RegrasEmissaoNfceDto). */
    public record Regras(boolean habilitado, String formasPagamentoHabilitadas, int intervaloNotas,
                         boolean emitirSempreComCpf, int contadorVendas) {

        public static Regras padrao() {
            return new Regras(true, "dinheiro,credito,debito,pix,fiado", 1, true, 0);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DecisaoEmissaoNfce(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public boolean avaliarEmissaoParaVenda(String companyId, String vendaId, String primaryPaymentType,
                                           List<String> paymentTypes, String customerCpf) {
        Boolean resultado = tx.execute(s -> decidir(companyId, vendaId, primaryPaymentType, paymentTypes,
                customerCpf));
        return resultado != null && resultado;
    }

    private boolean decidir(String companyId, String vendaId, String primaryPaymentType, List<String> paymentTypes,
                            String customerCpf) {
        List<Object> decisoes = jdbc.query("""
                SELECT EmitirNfce
                  FROM Vendas WITH (UPDLOCK, HOLDLOCK)
                 WHERE CompanyId = ? AND Id = ?;
                """, (rs, n) -> rs.getObject(1), companyId, vendaId);
        if (decisoes.isEmpty()) {
            throw new IllegalArgumentException("Venda não encontrada para a empresa.");
        }
        if (decisoes.getFirst() instanceof Boolean persistida) {
            return persistida;
        }

        // Vendas anteriores a persistencia da decisao podem ja ter NFC-e: nao consome o intervalo de novo.
        boolean emitir = !jdbc.query("""
                SELECT TOP 1 Id
                  FROM DocumentosFiscais
                 WHERE CompanyId = ? AND VendaId = ? AND Modelo = 65;
                """, (rs, n) -> rs.getString(1), companyId, vendaId).isEmpty();

        if (!emitir) {
            Regras regras = lerRegrasComBloqueio(companyId).orElse(Regras.padrao());
            emitir = deveEmitirSempre(regras, primaryPaymentType, paymentTypes, customerCpf);
            if (!emitir) {
                emitir = regras.contadorVendas() >= regras.intervaloNotas() - 1;
                int contador = emitir ? 0 : regras.contadorVendas() + 1;
                jdbc.update("""
                        UPDATE RegrasEmissaoNfce
                           SET ContadorVendas = ?, UpdatedAt = SYSUTCDATETIME()
                         WHERE CompanyId = ?;
                        """, contador, companyId);
            }
        }
        jdbc.update("UPDATE Vendas SET EmitirNfce = ? WHERE CompanyId = ? AND Id = ?;", emitir, companyId, vendaId);
        return emitir;
    }

    private Optional<Regras> lerRegrasComBloqueio(String companyId) {
        return jdbc.query("""
                SELECT Habilitado, FormasPagamentoHabilitadas, IntervaloNotas, EmitirSempreComCpf, ContadorVendas
                  FROM RegrasEmissaoNfce WITH (UPDLOCK, HOLDLOCK)
                 WHERE CompanyId = ?;
                """, (rs, n) -> new Regras(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                rs.getInt(5)), companyId).stream().findFirst();
    }

    /** DeveEmitirSempre: regras 1 a 4 da decisao (desligado, CPF, forma "sempre emitir", intervalo 1). */
    static boolean deveEmitirSempre(Regras config, String primaryPaymentType, List<String> paymentTypes,
                                    String customerCpf) {
        if (!config.habilitado()) {
            return true;
        }
        if (config.emitirSempreComCpf() && customerCpf != null && !customerCpf.isBlank()) {
            long digitos = customerCpf.chars().filter(Character::isDigit).count();
            if (digitos == 11 || digitos == 14) {
                return true;
            }
        }
        List<String> configuradas = new ArrayList<>();
        String formas = config.formasPagamentoHabilitadas() == null ? "" : config.formasPagamentoHabilitadas();
        for (String parte : formas.split(",")) {
            String forma = parte.trim();
            if (!forma.isEmpty()) configuradas.add(normalizarForma(forma));
        }
        List<String> daVenda = new ArrayList<>();
        if (primaryPaymentType != null && !primaryPaymentType.isBlank()) {
            daVenda.add(normalizarForma(primaryPaymentType));
        }
        if (paymentTypes != null) {
            for (String p : paymentTypes) {
                if (p != null && !p.isBlank()) daVenda.add(normalizarForma(p));
            }
        }
        for (String forma : daVenda) {
            for (String c : configuradas) {
                if (c.equalsIgnoreCase(forma)) return true;
            }
        }
        return config.intervaloNotas() <= 1;
    }

    static String normalizarForma(String paymentType) {
        String raw = paymentType.trim().toLowerCase(Locale.ROOT);
        if (raw.contains("dinheiro") || raw.equals("01")) return "dinheiro";
        if (raw.contains("credito") || raw.contains("crédito") || raw.equals("03")) return "credito";
        if (raw.contains("debito") || raw.contains("débito") || raw.equals("04")) return "debito";
        if (raw.contains("pix") || raw.equals("17")) return "pix";
        if (raw.contains("fiado") || raw.contains("prazo") || raw.equals("05")) return "fiado";
        return raw;
    }
}
