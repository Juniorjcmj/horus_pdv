/**
 * Arquivo: src/utils/produtosBalancaData.ts
 * Objetivo: lista completa dos 133 produtos da balança etiquetadora Triunfo Quantum 30 T
 *           extraídos das tabelas do mercado (Frutas, Legumes, Açougue, Frios, Rações).
 */

export interface ProdutoBalancaItem {
  code: string;
  name: string;
  unit: "KG" | "BDJ" | "UN";
  category: "Frutas" | "Legumes" | "Açougue" | "Frios" | "Rações";
  defaultSalePrice: number;
}

export const PRODUTOS_BALANCA: ProdutoBalancaItem[] = [
  // =========================================================================
  // 1. FRUTAS (32 itens)
  // =========================================================================
  { code: "4442", name: "ABACATE KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "4008", name: "AMEIXA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2691", name: "BANANA ORGANICA KG", unit: "KG", category: "Frutas", defaultSalePrice: 8.99 },
  { code: "2325", name: "BANANA PRATA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "4954", name: "CAJU KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6163", name: "CAQUI BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "727", name: "CAQUI KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6262", name: "CARAMBOLA BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "5982", name: "CEREJA FRUTA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "5999", name: "FIGO ROXO BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "5234", name: "GOIABA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "5920", name: "KIWI BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2288", name: "LARANJA PERA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "4985", name: "LARANJA SELECTA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6033", name: "LICHIA BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2899", name: "MACA ARGENTINA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2349", name: "MACA GALA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6545", name: "MAMAO FORMOSA", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2677", name: "MAMAO PAPAYA", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2318", name: "MANGA PALMER KG", unit: "KG", category: "Frutas", defaultSalePrice: 7.5 },
  { code: "2295", name: "MARACUJA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2684", name: "MELANCIA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "4015", name: "MELAO KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "5937", name: "PERA BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "2141", name: "PERA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "3995", name: "PESSEGO KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "4961", name: "PINHA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6279", name: "PITAYA BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "925", name: "PITAYA KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "901", name: "PITAYA UNIDADE", unit: "UN", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "949", name: "TANGERINA PONKAN KG", unit: "KG", category: "Frutas", defaultSalePrice: 0.0 },
  { code: "6637", name: "UVA VERDE THOMPSON BDJ", unit: "BDJ", category: "Frutas", defaultSalePrice: 0.0 },

  // =========================================================================
  // 2. LEGUMES (24 itens)
  // =========================================================================
  { code: "4404", name: "ABOBORA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4435", name: "ABOBRINHA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4565", name: "AIPIM KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2394", name: "ALHO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2653", name: "BATATA DOCE KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2370", name: "BATATA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4657", name: "BERINJELA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4282", name: "BETERABA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2387", name: "CEBOLA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2363", name: "CENOURA KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4299", name: "CHUCHU KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4923", name: "GENGIBRE KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "697", name: "INHAME KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "703", name: "JILO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2332", name: "LIMAO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "6118", name: "MAXIXE KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4688", name: "MILHO VERDE C/3", unit: "BDJ", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4275", name: "PEPINO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "3469", name: "PIMENTAO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "3438", name: "QUIABO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4350", name: "REPOLHO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "4367", name: "REPOLHO ROXO KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "2356", name: "TOMATE KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },
  { code: "5876", name: "VAGEM KG", unit: "KG", category: "Legumes", defaultSalePrice: 0.0 },

  // =========================================================================
  // 3. AÇOUGUE E CARNES (44 itens)
  // =========================================================================
  { code: "4039", name: "BACALHAU ZARBO KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4084", name: "CAR BOV MIOLO ALCATRA MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "499", name: "CAR BOV ACEM FRIBOI KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3544", name: "CAR BOV ALCATRA C/MAMIN FRIBOI KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3971", name: "CAR BOV BIFE ANCHO MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "5012", name: "CAR BOV BIFE ANCHO ENTRECORTE MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4992", name: "CAR BOV BIFE CHORIZO COMPO NOBRE KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3407", name: "CAR BOV CONTRA FILE KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6576", name: "CAR BOV COR ALC BABY B MONT STEAKH NA BRASA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6583", name: "CAR BOV COR ALC BABY BIFE NA BRASA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2240", name: "CAR BOV CORACAO ALCATRA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "611", name: "CAR BOV CORACAO DE ALCATRA MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2257", name: "CAR BOV FILE COSTELA MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "1618", name: "CAR BOV PALETA FRIBOI KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "5005", name: "CAR BOV PICANHA RESFRIADA MATURATTA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2752", name: "CAR BOV PICANHA A BOA CARNE KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6132", name: "CAR BOV PICANHA CONG FATIADA BLACK FRIBOI KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6064", name: "CAR BOV PICANHA GRILL PUL SELECTION KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3414", name: "CAR SUIN BISTECA FRIMESA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "482", name: "CAR SUIN CARRE KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3988", name: "CAR SUIN CONG AURORA BISTECA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4091", name: "CAR SUIN CONG BISTECA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "5722", name: "CAR SUIN PERNIL C/O SADIA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4855", name: "FGO CONG ASA ENVELOP. LAR KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6668", name: "FGO CONG COXA C/DORSO ENVE JAGUAFRANGOS", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6347", name: "FGO CONG COXA/SOBRECOXA ENVELOP. CVALE KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4800", name: "FGO CONG COXA/SOBRECOXA ENVELOP. LAR KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4831", name: "FGO CONG DRUMET LAR KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6651", name: "FGO CONG FILE DE PEITO ENVEL", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6125", name: "FGO CONG FILE DE PEITO ENVEL. BELLO KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "550", name: "FGO CONG FILE DE PEITO KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4886", name: "FGO CONG FILE DE PEITO ENVEL. LAR KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "4848", name: "FGO CONG PEITO C/OSSO ENVELOP. LAR KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "6644", name: "FGO CONG PEITO ENVELOP. COOPAVEL KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3391", name: "LING CHURRASCO PERDIGAO KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2158", name: "LING DE FRANGO AURORA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "826", name: "LING DE FRANGO SEARA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "413", name: "LING MISTA FINA DEF PERDIGAO KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2165", name: "LING SUINA AURORA CHURRASCO", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "2875", name: "LING SUINA EDER KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "833", name: "LING SUINA SEARA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3476", name: "LING T CALAB DEF SADIA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "3612", name: "LING T CALAB DEF SEARA KG", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },
  { code: "5098", name: "LINGUICA MISTA COZ DEF", unit: "KG", category: "Açougue", defaultSalePrice: 0.0 },

  // =========================================================================
  // 4. FRIOS (17 itens)
  // =========================================================================
  { code: "3483", name: "BACON KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "994", name: "MORTADELA DEFUMADA KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "987", name: "MORTADELA TRADICIONAL KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "376", name: "PEITO PERU DEFUMADO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "2424", name: "PRESUNTO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "956", name: "QJO CHEDDAR KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4428", name: "QJO COALHO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "451", name: "QJO MUSSARELA KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4893", name: "QJO PARMESAO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "352", name: "QJO PRATO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4916", name: "QJO PRATO BOLA KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4909", name: "QJO PROVOLONE KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4183", name: "QUEIJO MINAS", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "5111", name: "SALAME AGRANEL KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "4046", name: "SALSICHA AGRANEL KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "819", name: "SALSICHA PERDIGAO KG", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },
  { code: "2936", name: "BACON PREMIADO", unit: "KG", category: "Frios", defaultSalePrice: 0.0 },

  // =========================================================================
  // 5. RAÇÕES (16 itens)
  // =========================================================================
  { code: "3711", name: "RACÃO CAO FANNY AD PREMIUM R.PEQ KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3346", name: "RACAO CAO GOLDEN AD CARNE MINIBIT KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "6088", name: "RACAO CAO GOLDEN AD FGO MINIBIT KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3230", name: "RACAO CAO PEDIGREE NUTR ESSENCIAL KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "5906", name: "RACAO CAO PEDIGREE R.PQ ADULTO CARNE/VEG KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3919", name: "RACAO CAO PURUCA CARNE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "2516", name: "RACAO DE CAO FOSTER ORIGINAL KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "4077", name: "RACAO GATO FANNY CAT CASTR CARNE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "4107", name: "RACAO GATO FANNY CAT S/COR FG/PEIXE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3681", name: "RACAO GATO GOLDEN AD CASTRAD SALMAO KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3360", name: "RACAO GATO GOLDEN AD FRANGO KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3636", name: "RACAO GATO GOLDEN AD CARNE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3667", name: "RACAO GATO GOLDEN FILHOTE FGO KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3148", name: "RACAO GATO WHISKAS CARNE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "3186", name: "RACAO GATO WHISKAS CASTADO PEIXE KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
  { code: "6149", name: "RACAO GATO WHISKAS FRANGO KG", unit: "KG", category: "Rações", defaultSalePrice: 0.0 },
];
