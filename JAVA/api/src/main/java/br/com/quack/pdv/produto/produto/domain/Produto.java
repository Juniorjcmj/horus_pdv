package br.com.quack.pdv.produto.produto.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Tabela Produtos (V1). ProductQnt e TotalPriceOnProduct tem updatable=false (D2):
 * o JPA nunca muda o saldo nem o total em estoque — somente o RegistradorEstoque.
 */
@Entity
@Table(name = "Produtos")
public class Produto {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "ProductImageUrl", length = 500, nullable = false)
    public String productImageUrl = "";

    @Column(name = "ProductImageName", length = 180, nullable = false)
    public String productImageName = "";

    @Column(name = "ProductName", length = 180, nullable = false)
    public String productName = "";

    @Column(name = "ProductCode", length = 80, nullable = false)
    public String productCode = "";

    @Column(name = "ProductSupplier", length = 180, nullable = false)
    public String productSupplier = "";

    @Column(name = "SupplierId", length = 40)
    public String supplierId;

    @Column(name = "ProductDescription", length = 500, nullable = false)
    public String productDescription = "";

    @Column(name = "MargemDesejadaPercentual", precision = 9, scale = 4)
    public BigDecimal margemDesejadaPercentual;

    @Column(name = "EstoqueMinimo", precision = 15, scale = 4, nullable = false)
    public BigDecimal estoqueMinimo = BigDecimal.ZERO;

    @Column(name = "ProductQnt", precision = 15, scale = 4, nullable = false, insertable = true, updatable = false)
    public BigDecimal productQnt = BigDecimal.ZERO;

    @Column(name = "ProductUnitPrice", precision = 15, scale = 4, nullable = false)
    public BigDecimal productUnitPrice = BigDecimal.ZERO;

    @Column(name = "ProductSalePrice", precision = 15, scale = 4, nullable = false)
    public BigDecimal productSalePrice = BigDecimal.ZERO;

    @Column(name = "TotalPriceOnProduct", precision = 18, scale = 2, nullable = false, insertable = true, updatable = false)
    public BigDecimal totalPriceOnProduct = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "Ncm", length = 8, nullable = false)
    public String ncm = "00000000";

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "Cest", length = 7)
    public String cest;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "Cfop", length = 4, nullable = false)
    public String cfop = "5102";

    @Column(name = "OrigemMercadoria", nullable = false)
    public byte origemMercadoria;

    @Column(name = "UnidadeComercial", length = 6, nullable = false)
    public String unidadeComercial = "UN";

    @Column(name = "UnidadeTributavel", length = 6, nullable = false)
    public String unidadeTributavel = "UN";

    @Column(name = "Gtin", length = 14, nullable = false)
    public String gtin = "SEM GTIN";

    @Column(name = "CsosnIcms", length = 4)
    public String csosnIcms;

    @Column(name = "CstIcms", length = 3)
    public String cstIcms;

    @Column(name = "AliquotaIcms", precision = 7, scale = 4, nullable = false)
    public BigDecimal aliquotaIcms = BigDecimal.ZERO;

    @Column(name = "CstPis", length = 3, nullable = false)
    public String cstPis = "07";

    @Column(name = "CstCofins", length = 3, nullable = false)
    public String cstCofins = "07";

    @Column(name = "CstIbsCbs", length = 3)
    public String cstIbsCbs;

    @Column(name = "CClassTrib", length = 6)
    public String cClassTrib;

    @Column(name = "CategoriaId", length = 40)
    public String categoriaId;

    @Column(name = "DataValidade")
    public LocalDate dataValidade;

    @Column(name = "ControlaValidade", nullable = false)
    public boolean controlaValidade;

    @Column(name = "DiasAlertaValidade", nullable = false)
    public int diasAlertaValidade = 15;

    @Column(name = "UnidadeCompra", length = 6, nullable = false)
    public String unidadeCompra = "UN";

    @Column(name = "FatorConversao", precision = 15, scale = 4, nullable = false)
    public BigDecimal fatorConversao = BigDecimal.ONE;

    @Column(name = "QtdEmbalagem", precision = 15, scale = 4, nullable = false)
    public BigDecimal qtdEmbalagem = BigDecimal.ONE;

    @Column(name = "Marca", length = 120)
    public String marca;

    @Column(name = "Fabricante", length = 120)
    public String fabricante;

    @Column(name = "ReferenciaFabricante", length = 80)
    public String referenciaFabricante;

    @Column(name = "PesoLiquidoKg", precision = 15, scale = 4, nullable = false)
    public BigDecimal pesoLiquidoKg = BigDecimal.ZERO;

    @Column(name = "PesoBrutoKg", precision = 15, scale = 4, nullable = false)
    public BigDecimal pesoBrutoKg = BigDecimal.ZERO;

    @Column(name = "LarguraCm", precision = 10, scale = 2, nullable = false)
    public BigDecimal larguraCm = BigDecimal.ZERO;

    @Column(name = "AlturaCm", precision = 10, scale = 2, nullable = false)
    public BigDecimal alturaCm = BigDecimal.ZERO;

    @Column(name = "ComprimentoCm", precision = 10, scale = 2, nullable = false)
    public BigDecimal comprimentoCm = BigDecimal.ZERO;

    @Column(name = "EstoqueMaximo", precision = 15, scale = 4, nullable = false)
    public BigDecimal estoqueMaximo = BigDecimal.ZERO;

    @Column(name = "LocalizacaoEstoque", length = 100)
    public String localizacaoEstoque;

    @Column(name = "CustoMedio", precision = 15, scale = 4, nullable = false)
    public BigDecimal custoMedio = BigDecimal.ZERO;

    @Column(name = "CustoComImposto", precision = 15, scale = 4, nullable = false)
    public BigDecimal custoComImposto = BigDecimal.ZERO;

    @Column(name = "CustoSemImposto", precision = 15, scale = 4, nullable = false)
    public BigDecimal custoSemImposto = BigDecimal.ZERO;

    @Column(name = "DescontoMaximoPercentual", precision = 9, scale = 4, nullable = false)
    public BigDecimal descontoMaximoPercentual = BigDecimal.ZERO;

    @Column(name = "ComissaoPercentual", precision = 9, scale = 4, nullable = false)
    public BigDecimal comissaoPercentual = BigDecimal.ZERO;

    @Column(name = "MarkupCadastrado", precision = 9, scale = 4, nullable = false)
    public BigDecimal markupCadastrado = BigDecimal.ZERO;

    @Column(name = "MarkupPraticado", precision = 9, scale = 4, nullable = false)
    public BigDecimal markupPraticado = BigDecimal.ZERO;

    @Column(name = "Lucro", precision = 18, scale = 2, nullable = false)
    public BigDecimal lucro = BigDecimal.ZERO;

    @Column(name = "LeadTimeDias")
    public Integer leadTimeDias;
}

