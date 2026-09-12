/* ------------------------------------------------------------------------- */
/* 05_produtos_balanca.sql                                                   */
/* Carga automática dos 133 produtos da balança etiquetadora                 */
/* (Triunfo Quantum 30 T) em todas as empresas da base                       */
/* Idempotente — executado no boot pelo HorusDatabaseInitializer             */
/* ------------------------------------------------------------------------- */

SET NOCOUNT ON;

-- Tabela temporária com os 133 produtos catalogados da balança
CREATE TABLE #ProdutosBalancaTemp
(
    ProductCode NVARCHAR(80) NOT NULL,
    ProductName NVARCHAR(180) NOT NULL,
    ProductSalePrice DECIMAL(15, 4) NOT NULL,
    Unidade NVARCHAR(6) NOT NULL,
    Categoria NVARCHAR(50) NOT NULL
);

INSERT INTO #ProdutosBalancaTemp (ProductCode, ProductName, ProductSalePrice, Unidade, Categoria)
VALUES
    -- =========================================================================
    -- 1. FRUTAS (32 itens)
    -- =========================================================================
    (N'4442', N'ABACATE KG', 0.00, N'KG', N'Frutas'),
    (N'4008', N'AMEIXA KG', 0.00, N'KG', N'Frutas'),
    (N'2691', N'BANANA ORGANICA KG', 8.99, N'KG', N'Frutas'),
    (N'2325', N'BANANA PRATA KG', 0.00, N'KG', N'Frutas'),
    (N'4954', N'CAJU KG', 0.00, N'KG', N'Frutas'),
    (N'6163', N'CAQUI BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'727',  N'CAQUI KG', 0.00, N'KG', N'Frutas'),
    (N'6262', N'CARAMBOLA BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'5982', N'CEREJA FRUTA KG', 0.00, N'KG', N'Frutas'),
    (N'5999', N'FIGO ROXO BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'5234', N'GOIABA KG', 0.00, N'KG', N'Frutas'),
    (N'5920', N'KIWI BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'2288', N'LARANJA PERA KG', 0.00, N'KG', N'Frutas'),
    (N'4985', N'LARANJA SELECTA KG', 0.00, N'KG', N'Frutas'),
    (N'6033', N'LICHIA BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'2899', N'MACA ARGENTINA KG', 0.00, N'KG', N'Frutas'),
    (N'2349', N'MACA GALA KG', 0.00, N'KG', N'Frutas'),
    (N'6545', N'MAMAO FORMOSA', 0.00, N'KG', N'Frutas'),
    (N'2677', N'MAMAO PAPAYA', 0.00, N'KG', N'Frutas'),
    (N'2318', N'MANGA PALMER KG', 7.50, N'KG', N'Frutas'),
    (N'2295', N'MARACUJA KG', 0.00, N'KG', N'Frutas'),
    (N'2684', N'MELANCIA KG', 0.00, N'KG', N'Frutas'),
    (N'4015', N'MELAO KG', 0.00, N'KG', N'Frutas'),
    (N'5937', N'PERA BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'2141', N'PERA KG', 0.00, N'KG', N'Frutas'),
    (N'3995', N'PESSEGO KG', 0.00, N'KG', N'Frutas'),
    (N'4961', N'PINHA KG', 0.00, N'KG', N'Frutas'),
    (N'6279', N'PITAYA BDJ', 0.00, N'BDJ', N'Frutas'),
    (N'925',  N'PITAYA KG', 0.00, N'KG', N'Frutas'),
    (N'901',  N'PITAYA UNIDADE', 0.00, N'UN', N'Frutas'),
    (N'949',  N'TANGERINA PONKAN KG', 0.00, N'KG', N'Frutas'),
    (N'6637', N'UVA VERDE THOMPSON BDJ', 0.00, N'BDJ', N'Frutas'),

    -- =========================================================================
    -- 2. LEGUMES (24 itens)
    -- =========================================================================
    (N'4404', N'ABOBORA KG', 0.00, N'KG', N'Legumes'),
    (N'4435', N'ABOBRINHA KG', 0.00, N'KG', N'Legumes'),
    (N'4565', N'AIPIM KG', 0.00, N'KG', N'Legumes'),
    (N'2394', N'ALHO KG', 0.00, N'KG', N'Legumes'),
    (N'2653', N'BATATA DOCE KG', 0.00, N'KG', N'Legumes'),
    (N'2370', N'BATATA KG', 0.00, N'KG', N'Legumes'),
    (N'4657', N'BERINJELA KG', 0.00, N'KG', N'Legumes'),
    (N'4282', N'BETERABA KG', 0.00, N'KG', N'Legumes'),
    (N'2387', N'CEBOLA KG', 0.00, N'KG', N'Legumes'),
    (N'2363', N'CENOURA KG', 0.00, N'KG', N'Legumes'),
    (N'4299', N'CHUCHU KG', 0.00, N'KG', N'Legumes'),
    (N'4923', N'GENGIBRE KG', 0.00, N'KG', N'Legumes'),
    (N'697',  N'INHAME KG', 0.00, N'KG', N'Legumes'),
    (N'703',  N'JILO KG', 0.00, N'KG', N'Legumes'),
    (N'2332', N'LIMAO KG', 0.00, N'KG', N'Legumes'),
    (N'6118', N'MAXIXE KG', 0.00, N'KG', N'Legumes'),
    (N'4688', N'MILHO VERDE C/3', 0.00, N'BDJ', N'Legumes'),
    (N'4275', N'PEPINO KG', 0.00, N'KG', N'Legumes'),
    (N'3469', N'PIMENTAO KG', 0.00, N'KG', N'Legumes'),
    (N'3438', N'QUIABO KG', 0.00, N'KG', N'Legumes'),
    (N'4350', N'REPOLHO KG', 0.00, N'KG', N'Legumes'),
    (N'4367', N'REPOLHO ROXO KG', 0.00, N'KG', N'Legumes'),
    (N'2356', N'TOMATE KG', 0.00, N'KG', N'Legumes'),
    (N'5876', N'VAGEM KG', 0.00, N'KG', N'Legumes'),

    -- =========================================================================
    -- 3. AÇOUGUE E CARNES (44 itens)
    -- =========================================================================
    (N'4039', N'BACALHAU ZARBO KG', 0.00, N'KG', N'Açougue'),
    (N'4084', N'CAR BOV MIOLO ALCATRA MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'499',  N'CAR BOV ACEM FRIBOI KG', 0.00, N'KG', N'Açougue'),
    (N'3544', N'CAR BOV ALCATRA C/MAMIN FRIBOI KG', 0.00, N'KG', N'Açougue'),
    (N'3971', N'CAR BOV BIFE ANCHO MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'5012', N'CAR BOV BIFE ANCHO ENTRECORTE MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'4992', N'CAR BOV BIFE CHORIZO COMPO NOBRE KG', 0.00, N'KG', N'Açougue'),
    (N'3407', N'CAR BOV CONTRA FILE KG', 0.00, N'KG', N'Açougue'),
    (N'6576', N'CAR BOV COR ALC BABY B MONT STEAKH NA BRASA KG', 0.00, N'KG', N'Açougue'),
    (N'6583', N'CAR BOV COR ALC BABY BIFE NA BRASA KG', 0.00, N'KG', N'Açougue'),
    (N'2240', N'CAR BOV CORACAO ALCATRA KG', 0.00, N'KG', N'Açougue'),
    (N'611',  N'CAR BOV CORACAO DE ALCATRA MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'2257', N'CAR BOV FILE COSTELA MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'1618', N'CAR BOV PALETA FRIBOI KG', 0.00, N'KG', N'Açougue'),
    (N'5005', N'CAR BOV PICANHA RESFRIADA MATURATTA KG', 0.00, N'KG', N'Açougue'),
    (N'2752', N'CAR BOV PICANHA A BOA CARNE KG', 0.00, N'KG', N'Açougue'),
    (N'6132', N'CAR BOV PICANHA CONG FATIADA BLACK FRIBOI KG', 0.00, N'KG', N'Açougue'),
    (N'6064', N'CAR BOV PICANHA GRILL PUL SELECTION KG', 0.00, N'KG', N'Açougue'),
    (N'3414', N'CAR SUIN BISTECA FRIMESA KG', 0.00, N'KG', N'Açougue'),
    (N'482',  N'CAR SUIN CARRE KG', 0.00, N'KG', N'Açougue'),
    (N'3988', N'CAR SUIN CONG AURORA BISTECA KG', 0.00, N'KG', N'Açougue'),
    (N'4091', N'CAR SUIN CONG BISTECA KG', 0.00, N'KG', N'Açougue'),
    (N'5722', N'CAR SUIN PERNIL C/O SADIA KG', 0.00, N'KG', N'Açougue'),
    (N'4855', N'FGO CONG ASA ENVELOP. LAR KG', 0.00, N'KG', N'Açougue'),
    (N'6668', N'FGO CONG COXA C/DORSO ENVE JAGUAFRANGOS', 0.00, N'KG', N'Açougue'),
    (N'6347', N'FGO CONG COXA/SOBRECOXA ENVELOP. CVALE KG', 0.00, N'KG', N'Açougue'),
    (N'4800', N'FGO CONG COXA/SOBRECOXA ENVELOP. LAR KG', 0.00, N'KG', N'Açougue'),
    (N'4831', N'FGO CONG DRUMET LAR KG', 0.00, N'KG', N'Açougue'),
    (N'6651', N'FGO CONG FILE DE PEITO ENVEL', 0.00, N'KG', N'Açougue'),
    (N'6125', N'FGO CONG FILE DE PEITO ENVEL. BELLO KG', 0.00, N'KG', N'Açougue'),
    (N'550',  N'FGO CONG FILE DE PEITO KG', 0.00, N'KG', N'Açougue'),
    (N'4886', N'FGO CONG FILE DE PEITO ENVEL. LAR KG', 0.00, N'KG', N'Açougue'),
    (N'4848', N'FGO CONG PEITO C/OSSO ENVELOP. LAR KG', 0.00, N'KG', N'Açougue'),
    (N'6644', N'FGO CONG PEITO ENVELOP. COOPAVEL KG', 0.00, N'KG', N'Açougue'),
    (N'3391', N'LING CHURRASCO PERDIGAO KG', 0.00, N'KG', N'Açougue'),
    (N'2158', N'LING DE FRANGO AURORA KG', 0.00, N'KG', N'Açougue'),
    (N'826',  N'LING DE FRANGO SEARA KG', 0.00, N'KG', N'Açougue'),
    (N'413',  N'LING MISTA FINA DEF PERDIGAO KG', 0.00, N'KG', N'Açougue'),
    (N'2165', N'LING SUINA AURORA CHURRASCO', 0.00, N'KG', N'Açougue'),
    (N'2875', N'LING SUINA EDER KG', 0.00, N'KG', N'Açougue'),
    (N'833',  N'LING SUINA SEARA KG', 0.00, N'KG', N'Açougue'),
    (N'3476', N'LING T CALAB DEF SADIA KG', 0.00, N'KG', N'Açougue'),
    (N'3612', N'LING T CALAB DEF SEARA KG', 0.00, N'KG', N'Açougue'),
    (N'5098', N'LINGUICA MISTA COZ DEF', 0.00, N'KG', N'Açougue'),

    -- =========================================================================
    -- 4. FRIOS (17 itens)
    -- =========================================================================
    (N'3483', N'BACON KG', 0.00, N'KG', N'Frios'),
    (N'994',  N'MORTADELA DEFUMADA KG', 0.00, N'KG', N'Frios'),
    (N'987',  N'MORTADELA TRADICIONAL KG', 0.00, N'KG', N'Frios'),
    (N'376',  N'PEITO PERU DEFUMADO KG', 0.00, N'KG', N'Frios'),
    (N'2424', N'PRESUNTO KG', 0.00, N'KG', N'Frios'),
    (N'956',  N'QJO CHEDDAR KG', 0.00, N'KG', N'Frios'),
    (N'4428', N'QJO COALHO KG', 0.00, N'KG', N'Frios'),
    (N'451',  N'QJO MUSSARELA KG', 0.00, N'KG', N'Frios'),
    (N'4893', N'QJO PARMESAO KG', 0.00, N'KG', N'Frios'),
    (N'352',  N'QJO PRATO KG', 0.00, N'KG', N'Frios'),
    (N'4916', N'QJO PRATO BOLA KG', 0.00, N'KG', N'Frios'),
    (N'4909', N'QJO PROVOLONE KG', 0.00, N'KG', N'Frios'),
    (N'4183', N'QUEIJO MINAS', 0.00, N'KG', N'Frios'),
    (N'5111', N'SALAME AGRANEL KG', 0.00, N'KG', N'Frios'),
    (N'4046', N'SALSICHA AGRANEL KG', 0.00, N'KG', N'Frios'),
    (N'819',  N'SALSICHA PERDIGAO KG', 0.00, N'KG', N'Frios'),
    (N'2936', N'BACON PREMIADO', 0.00, N'KG', N'Frios'),

    -- =========================================================================
    -- 5. RAÇÕES (16 itens)
    -- =========================================================================
    (N'3711', N'RACÃO CAO FANNY AD PREMIUM R.PEQ KG', 0.00, N'KG', N'Rações'),
    (N'3346', N'RACAO CAO GOLDEN AD CARNE MINIBIT KG', 0.00, N'KG', N'Rações'),
    (N'6088', N'RACAO CAO GOLDEN AD FGO MINIBIT KG', 0.00, N'KG', N'Rações'),
    (N'3230', N'RACAO CAO PEDIGREE NUTR ESSENCIAL KG', 0.00, N'KG', N'Rações'),
    (N'5906', N'RACAO CAO PEDIGREE R.PQ ADULTO CARNE/VEG KG', 0.00, N'KG', N'Rações'),
    (N'3919', N'RACAO CAO PURUCA CARNE KG', 0.00, N'KG', N'Rações'),
    (N'2516', N'RACAO DE CAO FOSTER ORIGINAL KG', 0.00, N'KG', N'Rações'),
    (N'4077', N'RACAO GATO FANNY CAT CASTR CARNE KG', 0.00, N'KG', N'Rações'),
    (N'4107', N'RACAO GATO FANNY CAT S/COR FG/PEIXE KG', 0.00, N'KG', N'Rações'),
    (N'3681', N'RACAO GATO GOLDEN AD CASTRAD SALMAO KG', 0.00, N'KG', N'Rações'),
    (N'3360', N'RACAO GATO GOLDEN AD FRANGO KG', 0.00, N'KG', N'Rações'),
    (N'3636', N'RACAO GATO GOLDEN AD CARNE KG', 0.00, N'KG', N'Rações'),
    (N'3667', N'RACAO GATO GOLDEN FILHOTE FGO KG', 0.00, N'KG', N'Rações'),
    (N'3148', N'RACAO GATO WHISKAS CARNE KG', 0.00, N'KG', N'Rações'),
    (N'3186', N'RACAO GATO WHISKAS CASTADO PEIXE KG', 0.00, N'KG', N'Rações'),
    (N'6149', N'RACAO GATO WHISKAS FRANGO KG', 0.00, N'KG', N'Rações');

-- Aplica para todas as empresas da base (ou empresa-principal se não houver empresas cadastradas)
DECLARE @EmpresasDestino TABLE (CompanyId NVARCHAR(40));

IF OBJECT_ID(N'Empresas', N'U') IS NOT NULL
BEGIN
    INSERT INTO @EmpresasDestino (CompanyId)
    SELECT DISTINCT Id FROM Empresas;
END;

IF NOT EXISTS (SELECT 1 FROM @EmpresasDestino)
BEGIN
    INSERT INTO @EmpresasDestino (CompanyId) VALUES (N'empresa-principal');
END;

-- MERGE idempotente
MERGE INTO Produtos AS target
USING (
    SELECT e.CompanyId, t.ProductCode, t.ProductName, t.ProductSalePrice, t.Unidade, t.Categoria
    FROM #ProdutosBalancaTemp t
    CROSS JOIN @EmpresasDestino e
) AS source
ON target.CompanyId = source.CompanyId AND target.ProductCode = source.ProductCode
WHEN MATCHED THEN
    UPDATE SET 
        target.ProductName = source.ProductName,
        target.UnidadeComercial = source.Unidade,
        target.UnidadeTributavel = source.Unidade,
        target.ProductSalePrice = CASE 
                                      WHEN target.ProductSalePrice = 0 AND source.ProductSalePrice > 0 
                                      THEN source.ProductSalePrice 
                                      ELSE target.ProductSalePrice 
                                  END
WHEN NOT MATCHED THEN
    INSERT
    (
        Id,
        CompanyId,
        ProductImageUrl,
        ProductImageName,
        ProductName,
        ProductCode,
        ProductSupplier,
        SupplierId,
        ProductDescription,
        ProductQnt,
        ProductUnitPrice,
        ProductSalePrice,
        TotalPriceOnProduct,
        MargemDesejadaPercentual,
        Ncm,
        Cest,
        Cfop,
        OrigemMercadoria,
        UnidadeComercial,
        UnidadeTributavel,
        Gtin,
        CsosnIcms,
        CstIcms,
        AliquotaIcms,
        CstPis,
        CstCofins,
        CstIbsCbs,
        CClassTrib
    )
    VALUES
    (
        LOWER(NEWID()),
        source.CompanyId,
        N'',
        N'',
        source.ProductName,
        source.ProductCode,
        N'Balança Etiquetadora',
        NULL,
        N'Produto pesado - ' + source.Categoria,
        9999.0000,
        0.0000,
        source.ProductSalePrice,
        0.00,
        NULL,
        N'00000000',
        NULL,
        N'5102',
        0,
        source.Unidade,
        source.Unidade,
        N'SEM GTIN',
        N'102',
        NULL,
        0.0000,
        N'07',
        N'07',
        NULL,
        NULL
    );

DROP TABLE #ProdutosBalancaTemp;
GO
