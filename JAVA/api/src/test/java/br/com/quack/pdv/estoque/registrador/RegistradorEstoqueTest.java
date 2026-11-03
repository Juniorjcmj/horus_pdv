package br.com.quack.pdv.estoque.registrador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RegistradorEstoqueTest {

    @Test
    void origemSistemaPreenchePadroes() {
        var o = MovimentoOrigem.sistema("inventario", "inv-1", "Ajuste");
        assertThat(o.usuarioId()).isEqualTo("sistema");
        assertThat(o.usuarioNome()).isEqualTo("Sistema");

        var branco = new MovimentoOrigem("ajuste", null, null, "   ", "");
        assertThat(branco.motivo()).isEmpty();
        assertThat(branco.usuarioId()).isEqualTo("sistema");
        assertThat(branco.usuarioNome()).isEqualTo("Sistema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deltaZeroDevolveSaldosESemInserirMovimentacao() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("10.000"));
            when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("10.000"));
            when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("4.5000"));
            return List.of(rm.mapRow(rs, 0));
        });

        var reg = new RegistradorEstoque(jdbc);
        var res = reg.aplicar("emp-1", "pr-1", new BigDecimal("0.0000"), EstoqueTipos.ENTRADA, null,
                MovimentoOrigem.sistema("cadastro", "pr-1", "Sem mudanca"));

        assertThat(res).isPresent();
        assertThat(res.get().anterior()).isEqualByComparingTo("10.000");
        assertThat(res.get().novo()).isEqualByComparingTo("10.000");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usaCustoPadraoDoProdutoETruncaCamposEGravaQuantidadeAbsoluta() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any())).thenAnswer(inv -> {
            RowMapper<Object> rm = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("10.000"));
            when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("7.500"));
            when(rs.getBigDecimal(3)).thenReturn(new BigDecimal("3.4567"));
            return List.of(rm.mapRow(rs, 0));
        });

        var reg = new RegistradorEstoque(jdbc);
        var origem = new MovimentoOrigem(
                "t".repeat(30),
                "i".repeat(100),
                "m".repeat(600),
                "u".repeat(50),
                "n".repeat(250));

        var res = reg.aplicar("emp-1", "pr-1", new BigDecimal("-2.500"), EstoqueTipos.PERDA, null, origem);
        assertThat(res).isPresent();

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();

        assertThat((String) args[0]).startsWith("mov-").hasSize(36);
        assertThat(args[3]).isEqualTo(EstoqueTipos.PERDA);
        assertThat((BigDecimal) args[4]).isEqualByComparingTo("2.500"); // abs(delta)
        assertThat((BigDecimal) args[5]).isEqualByComparingTo("10.000");
        assertThat((BigDecimal) args[6]).isEqualByComparingTo("7.500");
        assertThat(((String) args[7])).hasSize(500);
        assertThat(((String) args[8])).hasSize(36);
        assertThat(((String) args[9])).hasSize(200);
        assertThat((BigDecimal) args[10]).isEqualByComparingTo("3.4567"); // custo do produto
        assertThat(((String) args[11])).hasSize(20);
        assertThat(((String) args[12])).hasSize(80);
        assertThat(args[13]).isEqualTo("loja");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aplicarPorCodigoResolveIdEDevolvemVazioQuandoInexistente() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("emp-1"), eq("INEXISTENTE")))
                .thenReturn(List.of());

        var reg = new RegistradorEstoque(jdbc);
        assertThat(reg.aplicarPorCodigo("emp-1", "INEXISTENTE", BigDecimal.ONE, EstoqueTipos.ENTRADA, null,
                MovimentoOrigem.sistema("oc", "1", "Rec"))).isEmpty();
    }
}
