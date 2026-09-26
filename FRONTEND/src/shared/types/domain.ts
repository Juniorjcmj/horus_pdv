/**
 * Arquivo: src/shared/types/domain.ts
 * Objetivo: interfaces base para entidades do domínio offline-first.
 */

export type Entity = {
  id: string;
  createdAt: string;
  updatedAt: string;
};

export type TenantEntity = Entity & {
  tenantId: string;
};

export type DeviceEntity = TenantEntity & {
  deviceId: string;
};
