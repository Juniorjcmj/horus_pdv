/**
 * Arquivo: src/utils/promotionEngine.ts
 * Objetivo: motor puro de cálculo de promoções e preços dinâmicos para o PDV.
 */
import type { Promocao, TipoPromocao } from "@/services/api/promocaoService";

export type CartItemPromo = {
  id: string;
  code: string;
  name: string;
  quantity: number;
  unitPrice: number;
  categoriaId?: string | null;
  discount?: number;
  itemTotal?: number;
  promocaoId?: string | null;
  promocaoNome?: string | null;
  [key: string]: unknown;
};

function round2(val: number): number {
  return Math.round((val + Number.EPSILON) * 100) / 100;
}

/**
 * Verifica se a promoção está atualmente em vigor.
 */
export function isPromocaoVigente(promo: Promocao, now = new Date()): boolean {
  if (!promo.ativa) return false;
  const start = new Date(promo.inicioVigencia);
  const end = new Date(promo.fimVigencia);
  return now >= start && now <= end;
}

/**
 * Verifica se a promoção se aplica ao item com base em produtoIds ou categoriaId.
 */
export function promocaoAplicaAoItem(
  promo: Promocao,
  item: CartItemPromo,
  produtoCategoriaMap?: Map<string, string | null>,
): boolean {
  // Verificação direta por ID ou código do produto
  if (promo.produtoIds && promo.produtoIds.length > 0) {
    if (promo.produtoIds.includes(item.id) || promo.produtoIds.includes(item.code)) {
      return true;
    }
  }

  // Verificação por Categoria
  if (promo.categoriaId) {
    const itemCatId = item.categoriaId ?? produtoCategoriaMap?.get(item.id) ?? produtoCategoriaMap?.get(item.code);
    if (itemCatId && itemCatId === promo.categoriaId) {
      return true;
    }
  }

  return false;
}

/**
 * Calcula o desconto em R$ que uma promoção específica geraria para o item.
 */
export function calcularDescontoPromo(promo: Promocao, item: CartItemPromo): number {
  const { quantity, unitPrice } = item;
  if (quantity <= 0 || unitPrice <= 0) return 0;

  const grossTotal = quantity * unitPrice;

  switch (promo.tipo as TipoPromocao) {
    case "desconto_percentual": {
      const pct = promo.valorDesconto ?? 0;
      if (pct <= 0) return 0;
      return round2(grossTotal * (Math.min(pct, 100) / 100));
    }

    case "desconto_valor": {
      const val = promo.valorDesconto ?? 0;
      if (val <= 0) return 0;
      return round2(Math.min(grossTotal, quantity * val));
    }

    case "preco_fixo": {
      const pf = promo.precoFixo ?? 0;
      if (pf <= 0 || pf >= unitPrice) return 0;
      return round2(quantity * (unitPrice - pf));
    }

    case "leve_x_pague_y": {
      const leva = promo.quantidadeLeva ?? 0;
      const paga = promo.quantidadePaga ?? 0;
      if (leva <= 0 || paga <= 0 || leva <= paga) return 0;
      if (quantity < leva) return 0;

      const grupos = Math.floor(quantity / leva);
      const gratis = grupos * (leva - paga);
      return round2(gratis * unitPrice);
    }

    case "combo_quantidade": {
      const minQtd = promo.quantidadeMinima ?? 0;
      if (minQtd <= 1 || quantity < minQtd) return 0;

      const grupos = Math.floor(quantity / minQtd);
      if (promo.precoFixo && promo.precoFixo > 0) {
        // Ex.: 3 por R$ 10,00 (precoFixo = 10,00 para o pacote de minQtd)
        const valorNormalItensCombo = grupos * minQtd * unitPrice;
        const valorPromocionalItensCombo = grupos * promo.precoFixo;
        return round2(Math.max(0, valorNormalItensCombo - valorPromocionalItensCombo));
      }
      if (promo.valorDesconto && promo.valorDesconto > 0) {
        return round2(grupos * promo.valorDesconto);
      }
      return 0;
    }

    case "preco_atacado": {
      const minQtd = promo.quantidadeMinima ?? 0;
      if (minQtd <= 1 || quantity < minQtd) return 0;

      if (promo.precoFixo && promo.precoFixo > 0) {
        if (promo.precoFixo < unitPrice) {
          return round2(quantity * (unitPrice - promo.precoFixo));
        }
        return 0;
      }
      if (promo.valorDesconto && promo.valorDesconto > 0) {
        return round2(Math.min(grossTotal, quantity * promo.valorDesconto));
      }
      return 0;
    }

    default:
      return 0;
  }
}

/**
 * Aplica promoções ativas aos itens do carrinho.
 * Quando múltiplas promoções se aplicam ao mesmo item, a que gerar MAIOR desconto em R$ vence (sem empilhamento).
 */
export function applyPromotions<T extends CartItemPromo>(
  cartItems: T[],
  promocoesAtivas: Promocao[],
  produtoCategoriaMap?: Map<string, string | null>,
  now = new Date(),
): T[] {
  if (cartItems.length === 0) return [];

  // Filtra apenas promoções ativas e dentro da vigência
  const vigentes = promocoesAtivas.filter((p) => isPromocaoVigente(p, now));

  return cartItems.map((item) => {
    let bestDiscount = 0;
    let bestPromo: Promocao | null = null;

    for (const promo of vigentes) {
      if (promocaoAplicaAoItem(promo, item, produtoCategoriaMap)) {
        const discount = calcularDescontoPromo(promo, item);
        if (discount > bestDiscount) {
          bestDiscount = discount;
          bestPromo = promo;
        }
      }
    }

    const gross = round2(item.quantity * item.unitPrice);
    const effectiveDiscount = Math.min(gross, bestDiscount);
    const itemTotal = round2(Math.max(0, gross - effectiveDiscount));

    return {
      ...item,
      discount: effectiveDiscount,
      itemTotal,
      promocaoId: effectiveDiscount > 0 && bestPromo ? bestPromo.id : null,
      promocaoNome: effectiveDiscount > 0 && bestPromo ? bestPromo.nome : null,
    };
  });
}
