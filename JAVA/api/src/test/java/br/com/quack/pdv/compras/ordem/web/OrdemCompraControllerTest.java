package br.com.quack.pdv.compras.ordem.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

class OrdemCompraControllerTest {
    @Test
    void integraSomenteDiagnosticoDeTerminoDaViolacaoSql() {
        var sql = new SQLException("FK_OC_Supplier", "23000", 547);
        sql.setNextException(new SQLException("The statement has been terminated.", "S0000", 3621));
        sql.setNextException(new SQLException("diagnóstico auxiliar", "S0000", 0));
        sql.setNextException(new SQLException("The statement has been terminated.", "S0000", 3621));
        assertThat(
                        OrdemCompraController.mensagemErro(
                                new DataIntegrityViolationException("jdbc", sql)))
                .isEqualTo("FK_OC_Supplier\nThe statement has been terminated.");
    }

    @Test
    void preservaOutrosErrosSemAcrescentarTermino() {
        var sql = new SQLException("Outro erro", "S0000", 50000);
        sql.setNextException(new SQLException("The statement has been terminated.", "S0000", 3621));
        assertThat(
                        OrdemCompraController.mensagemErro(
                                new DataIntegrityViolationException("jdbc", sql)))
                .isEqualTo("Outro erro");
        assertThat(OrdemCompraController.mensagemErro(new IllegalArgumentException("inválido")))
                .isEqualTo("inválido");
        var fk =
                new SQLException(
                        "The DELETE statement conflicted with the REFERENCE constraint FK_X.",
                        "23000",
                        547);
        assertThat(
                        OrdemCompraController.mensagemErro(
                                new DataIntegrityViolationException("jdbc", fk)))
                .isEqualTo(fk.getMessage());
    }

    @Test
    void reproduzDiagnosticoDescartadoPeloJdbcSomenteNoInsertFkEmIngles() {
        String mensagem =
                "The INSERT statement conflicted with the FOREIGN KEY constraint"
                    + " \"FK_OC_Supplier\". The conflict occurred in database \"quack\", table"
                    + " \"dbo.Fornecedores\", column 'Id'.";
        var sql = new SQLException(mensagem, "23000", 547);
        assertThat(
                        OrdemCompraController.mensagemErro(
                                new DataIntegrityViolationException("jdbc", sql)))
                .isEqualTo(mensagem + "\nThe statement has been terminated.");
    }
}
