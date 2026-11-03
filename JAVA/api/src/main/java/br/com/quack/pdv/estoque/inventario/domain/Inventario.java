package br.com.quack.pdv.estoque.inventario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "Inventarios")
public class Inventario {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "Numero", nullable = false)
    public int numero;

    @Column(name = "Descricao", length = 200, nullable = false)
    public String descricao = "";

    @Column(name = "Escopo", length = 20, nullable = false)
    public String escopo = "";

    @Column(name = "EscopoJson", columnDefinition = "nvarchar(max)")
    public String escopoJson;

    @Column(name = "Status", length = 20, nullable = false)
    public String status = "em_contagem";

    @Column(name = "AbertoPorId", length = 40, nullable = false)
    public String abertoPorId = "";

    @Column(name = "AbertoPorNome", length = 180, nullable = false)
    public String abertoPorNome = "";

    @Column(name = "AbertoEm", nullable = false)
    public OffsetDateTime abertoEm;

    @Column(name = "AplicadoPorId", length = 40)
    public String aplicadoPorId;

    @Column(name = "AplicadoPorNome", length = 180)
    public String aplicadoPorNome;

    @Column(name = "AplicadoEm")
    public OffsetDateTime aplicadoEm;

    @Column(name = "SupervisorId", length = 40)
    public String supervisorId;

    @Column(name = "SupervisorNome", length = 180)
    public String supervisorNome;

    @Column(name = "Justificativa", length = 500)
    public String justificativa;

    @Column(name = "ZerarNaoContados", nullable = false)
    public boolean zerarNaoContados;

    @Column(name = "CanceladoPorId", length = 40)
    public String canceladoPorId;

    @Column(name = "CanceladoPorNome", length = 180)
    public String canceladoPorNome;

    @Column(name = "CanceladoEm")
    public OffsetDateTime canceladoEm;

    @Column(name = "MotivoCancelamento", length = 500)
    public String motivoCancelamento;

    @Column(name = "TotalSobra", precision = 15, scale = 2)
    public BigDecimal totalSobra;

    @Column(name = "TotalFalta", precision = 15, scale = 2)
    public BigDecimal totalFalta;

    @Column(name = "TotalLiquido", precision = 15, scale = 2)
    public BigDecimal totalLiquido;
}

