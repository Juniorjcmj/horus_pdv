package br.com.quack.pdv.produto.entrada.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "NotasEntradaArquivo")
public class NotaEntrada {

    @Id
    @Column(name = "Id", length = 64, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 100, nullable = false)
    public String companyId = "";

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "Identidade", length = 64, nullable = false)
    public String identidade = "";

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ChaveAcesso", length = 44)
    public String chaveAcesso;

    @Column(name = "Modelo", nullable = false)
    public int modelo;

    @Column(name = "NumeroNota", length = 30, nullable = false)
    public String numeroNota = "";

    @Column(name = "Serie", length = 10, nullable = false)
    public String serie = "";

    @Column(name = "FornecedorNome", length = 250, nullable = false)
    public String fornecedorNome = "";

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "FornecedorCnpj", length = 14, nullable = false)
    public String fornecedorCnpj = "";

    @Column(name = "DataEmissao", length = 40)
    public String dataEmissao;

    @Column(name = "ValorNota", precision = 18, scale = 2)
    public BigDecimal valorNota;

    @Column(name = "ValorEntrada", precision = 18, scale = 2, nullable = false)
    public BigDecimal valorEntrada = BigDecimal.ZERO;

    @Column(name = "QuantidadeItens", nullable = false)
    public int quantidadeItens;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "Origem", length = 30, nullable = false)
    public String origem = "";

    @Column(name = "XmlOriginal", columnDefinition = "varbinary(max)")
    public byte[] xmlOriginal;

    @Column(name = "EntradaJson", columnDefinition = "nvarchar(max)", nullable = false)
    public String entradaJson = "";

    @Column(name = "UsuarioId", length = 100, nullable = false)
    public String usuarioId = "";

    @Column(name = "UsuarioNome", length = 250, nullable = false)
    public String usuarioNome = "";

    @Column(name = "CriadaEm", nullable = false)
    public LocalDateTime criadaEm;

    @Column(name = "ComprovanteNome", length = 180)
    public String comprovanteNome;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ComprovanteTipo", length = 80)
    public String comprovanteTipo;

    @Column(name = "ComprovanteOriginal", columnDefinition = "varbinary(max)")
    public byte[] comprovanteOriginal;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "OperacaoManualId", length = 32)
    public String operacaoManualId;

    @Column(name = "Estornada", nullable = false)
    public boolean estornada;

    @Column(name = "EstornadaEm")
    public LocalDateTime estornadaEm;

    @Column(name = "EstornadaPorUsuarioId", length = 100)
    public String estornadaPorUsuarioId;

    @Column(name = "EstornadaPorUsuarioNome", length = 250)
    public String estornadaPorUsuarioNome;

    @Column(name = "EstornoJustificativa", length = 500)
    public String estornoJustificativa;
}
