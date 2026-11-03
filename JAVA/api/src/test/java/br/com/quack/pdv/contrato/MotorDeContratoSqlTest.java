package br.com.quack.pdv.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class MotorDeContratoSqlTest {
    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode resposta(String mensagem) {
        var r = json.createObjectNode();
        r.put("status", 400);
        r.set("cabecalhos", json.createObjectNode());
        r.set("corpo", json.createObjectNode().put("message", mensagem));
        return r;
    }

    private ObjectNode cenario() {
        var c = json.createObjectNode();
        c.set("volateis", json.createObjectNode().put("message", "banco-sql"));
        return c;
    }

    private final String dotnet =
            "The INSERT statement conflicted with the FOREIGN KEY constraint \"FK_OC_Supplier\"."
                + " The conflict occurred in database \"HorusPdv\", table \"dbo.Fornecedores\","
                + " column 'Id'.";

    @Test
    void somenteONomeDoBancoPodeVariar() {
        assertThat(
                        MotorDeContrato.comparar(
                                resposta(dotnet),
                                resposta(dotnet.replace("HorusPdv", "quack")),
                                cenario()))
                .isEmpty();
    }

    @Test
    void constraintTabelaSufixoETipoDeErroContinuamLiterais() {
        for (String diferente :
                new String[] {
                    dotnet.replace("FK_OC_Supplier", "FK_OUTRA"),
                    dotnet.replace("dbo.Fornecedores", "dbo.Produtos"),
                    dotnet + " detalhe extra",
                    dotnet.replace("INSERT", "DELETE")
                }) {
            assertThat(MotorDeContrato.comparar(resposta(dotnet), resposta(diferente), cenario()))
                    .isNotEmpty();
        }
        assertThat(
                        MotorDeContrato.comparar(
                                resposta(dotnet), resposta("outra mensagem"), cenario()))
                .isNotEmpty();
    }
}
