package br.com.quack.pdv.produto.lote.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "LoteConfig")
public class LoteConfig {

    @Id
    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "Modo", length = 12, nullable = false)
    public String modo = "sombra";

    @Column(name = "AtualizadoEm", nullable = false)
    public OffsetDateTime atualizadoEm;
}

