/**
 * Arquivo: src/application/products/ProductSyncAdapter.ts
 * Objetivo: puxa catálogo de produtos da API e sincroniza com IndexedDB.
 *           Estratégia: full-replace (limpa + insere tudo) — adequado para catálogos < 50k itens.
 *           Também migra cache antigo do localStorage na primeira execução.
 */
import { productService } from "@/services/api/productService";
import type { ProductDto } from "@/services/api/productService";
import { productRepository } from "@/infrastructure/database/repositories/ProductRepository";
import type { LocalProductRecord } from "@/infrastructure/database/dexie";

const LS_PRODUCTS_KEY = "horus-pdv-products-cache";

/** Converte um ProductDto da API para o formato IndexedDB. */
function mapDtoToLocal(dto: ProductDto): LocalProductRecord {
  return {
    id: dto.id,
    tenantId: "",
    barcode: dto.gtin || "",
    productCode: dto.productCode,
    productName: dto.productName,
    salePrice: parseDecimalBr(dto.productSalePrice),
    unitPrice: parseDecimalBr(dto.productUnitPrice),
    stock: parseDecimalBr(dto.productQnt),
    unit: dto.unidadeComercial || "UN",
    categoryId: dto.categoriaId ?? null,
    imageUrl: dto.productImageUrl || "",
    marca: dto.marca ?? null,
    ncm: dto.ncm || "",
    cfop: dto.cfop || "",
    active: true,
    controlaValidade: dto.controlaValidade ?? false,
    dataValidade: dto.dataValidade ?? null,
    diasAlertaValidade: dto.diasAlertaValidade ?? 0,
    updatedAt: new Date().toISOString(),
    version: 1,
  };
}

/** Parse decimal no formato "1.234,56" ou "1234.56" para number. */
function parseDecimalBr(value: string | undefined | null): number {
  if (!value) return 0;
  // Remove pontos de milhar, troca vírgula por ponto
  const cleaned = value.replace(/\./g, "").replace(",", ".");
  const num = parseFloat(cleaned);
  return isNaN(num) ? 0 : num;
}

/**
 * Puxa todos os produtos da API e salva no IndexedDB.
 * Retorna os registros locais salvos.
 */
export async function syncProductsFromApi(): Promise<LocalProductRecord[]> {
  const dtos = await productService.list();
  const records = dtos.map(mapDtoToLocal);
  await productRepository.clear();
  await productRepository.bulkUpsert(records);
  // Limpa cache antigo do localStorage (migração)
  removeLegacyCache();
  return records;
}

/**
 * Carrega produtos do IndexedDB. Se vazio e houver cache legado no localStorage,
 * migra automaticamente para IndexedDB.
 */
export async function loadProductsLocal(): Promise<LocalProductRecord[]> {
  const count = await productRepository.count();
  if (count > 0) {
    return productRepository.getAll();
  }

  // Tenta migrar cache legado do localStorage
  const migrated = await migrateLegacyCache();
  if (migrated && migrated.length > 0) return migrated;

  return [];
}

/**
 * Migra o cache de produtos do localStorage para IndexedDB.
 * Retorna os registros migrados ou null se não havia cache.
 */
async function migrateLegacyCache(): Promise<LocalProductRecord[] | null> {
  try {
    const raw = window.localStorage.getItem(LS_PRODUCTS_KEY);
    if (!raw) return null;

    const cached = JSON.parse(raw) as Array<{
      id: string;
      name: string;
      code: string;
      stock: number;
      salePrice: number;
      imageUrl?: string;
      unit: string;
      marca?: string | null;
      categoriaId?: string | null;
      dataValidade?: string | null;
      controlaValidade?: boolean;
      diasAlertaValidade?: number;
    }>;

    if (!cached || cached.length === 0) return null;

    const records: LocalProductRecord[] = cached.map((c) => ({
      id: c.id,
      tenantId: "",
      barcode: "",
      productCode: c.code,
      productName: c.name,
      salePrice: c.salePrice,
      unitPrice: 0,
      stock: c.stock,
      unit: c.unit || "UN",
      categoryId: c.categoriaId ?? null,
      imageUrl: c.imageUrl || "",
      marca: c.marca ?? null,
      ncm: "",
      cfop: "",
      active: true,
      controlaValidade: c.controlaValidade ?? false,
      dataValidade: c.dataValidade ?? null,
      diasAlertaValidade: c.diasAlertaValidade ?? 0,
      updatedAt: new Date().toISOString(),
      version: 0, // marcado como versão 0 — será sobrescrito no próximo sync
    }));

    await productRepository.bulkUpsert(records);
    removeLegacyCache();
    return records;
  } catch {
    return null;
  }
}

function removeLegacyCache(): void {
  try {
    window.localStorage.removeItem(LS_PRODUCTS_KEY);
  } catch {
    // ignora
  }
}
