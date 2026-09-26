/**
 * Arquivo: src/infrastructure/database/repositories/UserRepository.ts
 * Objetivo: gerencia a persistência de credenciais criptografadas e a autenticação offline de operadores no IndexedDB.
 */
import { db, type LocalUserRecord } from "../dexie";
import type { AuthenticatedUser } from "@/utils/authStorage";
import { computePasswordHash } from "@/utils/cryptoHash";

const DEFAULT_MAX_OFFLINE_DAYS = 7;

export const userRepository = {
  /**
   * Salva ou atualiza um usuário para permitir autenticação offline futura.
   * O hash da senha é gerado usando PBKDF2 com salt local único.
   */
  async saveUserForOfflineAuth(
    user: AuthenticatedUser,
    password: string,
    maxOfflineDays = DEFAULT_MAX_OFFLINE_DAYS,
  ): Promise<void> {
    if (!user || !user.email || !password) return;

    const normalizedEmail = user.email.trim().toLowerCase();
    const hash = await computePasswordHash(normalizedEmail, password);

    const record: LocalUserRecord = {
      id: user.id,
      tenantId: user.companyId || "",
      email: normalizedEmail,
      name: user.name || "",
      role: user.role || "operador",
      passwordHash: hash,
      lastOnlineLoginAt: new Date().toISOString(),
      maxOfflineDays,
    };

    await db.users.put(record);
  },

  /**
   * Autentica um operador localmente quando sem conexão à internet.
   * Valida email, senha (hash PBKDF2) e período máximo permitido de operação offline.
   */
  async authenticateOffline(email: string, password: string): Promise<AuthenticatedUser> {
    const normalizedEmail = email.trim().toLowerCase();
    const localUser = await db.users.where("email").equals(normalizedEmail).first();

    if (!localUser) {
      throw new Error(
        "Usuário não cadastrado para acesso offline neste terminal. Faça o primeiro login online.",
      );
    }

    // Valida período de operação offline
    const lastLogin = new Date(localUser.lastOnlineLoginAt).getTime();
    const now = Date.now();
    const elapsedDays = (now - lastLogin) / (1000 * 60 * 60 * 24);

    if (elapsedDays > localUser.maxOfflineDays) {
      const dias = Math.floor(elapsedDays);
      throw new Error(
        `Período máximo de operação offline excedido (${dias}/${localUser.maxOfflineDays} dias). Conecte-se à internet para revalidar seu acesso.`,
      );
    }

    // Valida senha comparando o hash
    const candidateHash = await computePasswordHash(normalizedEmail, password);
    if (candidateHash !== localUser.passwordHash) {
      throw new Error("Senha incorreta.");
    }

    return {
      id: localUser.id,
      companyId: localUser.tenantId,
      cpf: "",
      name: localUser.name,
      email: localUser.email,
      phone: "",
      role: localUser.role,
      status: "ativo",
      createdAt: localUser.lastOnlineLoginAt,
      lastLoginAt: new Date().toISOString(),
      mustChangePassword: false,
    };
  },

  /** Busca usuário local por email. */
  async getByEmail(email: string): Promise<LocalUserRecord | undefined> {
    return db.users.where("email").equals(email.trim().toLowerCase()).first();
  },

  /** Busca usuário local por ID. */
  async getById(id: string): Promise<LocalUserRecord | undefined> {
    return db.users.get(id);
  },
};
