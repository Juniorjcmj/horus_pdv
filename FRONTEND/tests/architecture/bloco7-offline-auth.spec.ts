import { test, expect } from "@playwright/test";

test.describe("BLOCO 7 — Autenticação Offline e Resiliência de Credenciais", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.waitForFunction(() => !!(window as any).__horus_test__);
    await page.evaluate(async () => {
      await (window as any).__horus_test__.resetDatabase();
    });
  });

  test("Teste 24 — Sessão offline restaurada: saveUserForOfflineAuth persiste credenciais locais criptografadas", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { UserRepository, db } = (window as any).__horus_test__;
      const repo = UserRepository.userRepository;

      const mockUser = {
        id: "usr-001",
        companyId: "comp-123",
        cpf: "12345678900",
        name: "Operador Caixa 1",
        email: "operador1@horuspdv.com",
        phone: "11999999999",
        role: "operador",
        status: "ativo",
        createdAt: new Date().toISOString(),
        lastLoginAt: new Date().toISOString(),
        mustChangePassword: false,
      };

      await repo.saveUserForOfflineAuth(mockUser, "SenhaForte@2026", 7);

      const savedInDb = await db.users.get("usr-001");
      const byEmail = await repo.getByEmail("operador1@horuspdv.com");

      return {
        savedInDb,
        byEmailMatchesId: byEmail?.id === "usr-001",
      };
    });

    expect(result.savedInDb).toBeDefined();
    expect(result.savedInDb.email).toBe("operador1@horuspdv.com");
    expect(result.savedInDb.name).toBe("Operador Caixa 1");
    expect(result.savedInDb.role).toBe("operador");
    expect(result.savedInDb.tenantId).toBe("comp-123");
    expect(result.savedInDb.passwordHash).toBeDefined();
    expect(result.savedInDb.passwordHash.length).toBe(64); // SHA-256 hex string (256 bits = 64 hex chars)
    expect(result.savedInDb.maxOfflineDays).toBe(7);
    expect(result.byEmailMatchesId).toBe(true);
  });

  test("Teste 25 — Autenticação offline por credencial: validação por PBKDF2 com sucesso e rejeição por senha incorreta", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { UserRepository } = (window as any).__horus_test__;
      const repo = UserRepository.userRepository;

      const mockUser = {
        id: "usr-002",
        companyId: "comp-123",
        cpf: "12345678900",
        name: "Operador Autorizado",
        email: "operador2@horuspdv.com",
        phone: "11999999999",
        role: "operador",
        status: "ativo",
        createdAt: new Date().toISOString(),
        lastLoginAt: new Date().toISOString(),
        mustChangePassword: false,
      };

      await repo.saveUserForOfflineAuth(mockUser, "SenhaCorreta#123", 7);

      // 1. Sucesso com senha correta
      const authSuccess = await repo.authenticateOffline(
        "Operador2@horuspdv.com ", // testa case e trim de email
        "SenhaCorreta#123",
      );

      // 2. Falha com senha errada
      let wrongPasswordError = "";
      try {
        await repo.authenticateOffline("operador2@horuspdv.com", "SenhaInvalida");
      } catch (err: any) {
        wrongPasswordError = err.message;
      }

      // 3. Falha com usuário inexistente
      let notFoundError = "";
      try {
        await repo.authenticateOffline("inexistente@horuspdv.com", "qualquer");
      } catch (err: any) {
        notFoundError = err.message;
      }

      return {
        authSuccess,
        wrongPasswordError,
        notFoundError,
      };
    });

    expect(result.authSuccess.id).toBe("usr-002");
    expect(result.authSuccess.email).toBe("operador2@horuspdv.com");
    expect(result.authSuccess.name).toBe("Operador Autorizado");
    expect(result.wrongPasswordError).toBe("Senha incorreta.");
    expect(result.notFoundError).toContain("Usuário não cadastrado para acesso offline");
  });

  test("Teste 26 — Expiração do período offline: bloqueia login se lastOnlineLoginAt ultrapassar maxOfflineDays", async ({
    page,
  }) => {
    const result = await page.evaluate(async () => {
      const { UserRepository, db } = (window as any).__horus_test__;
      const repo = UserRepository.userRepository;

      const mockUser = {
        id: "usr-expired",
        companyId: "comp-123",
        cpf: "12345678900",
        name: "Operador Expirado",
        email: "expirado@horuspdv.com",
        phone: "11999999999",
        role: "operador",
        status: "ativo",
        createdAt: new Date().toISOString(),
        lastLoginAt: new Date().toISOString(),
        mustChangePassword: false,
      };

      await repo.saveUserForOfflineAuth(mockUser, "MinhaSenha@123", 7);

      // Forçar data de último login online para 8 dias atrás no IndexedDB
      const eightDaysAgo = new Date(Date.now() - 8 * 24 * 60 * 60 * 1000).toISOString();
      await db.users.update("usr-expired", {
        lastOnlineLoginAt: eightDaysAgo,
      });

      let expiredError = "";
      try {
        await repo.authenticateOffline("expirado@horuspdv.com", "MinhaSenha@123");
      } catch (err: any) {
        expiredError = err.message;
      }

      return {
        expiredError,
      };
    });

    expect(result.expiredError).toContain("Período máximo de operação offline excedido");
    expect(result.expiredError).toContain("8/7 dias");
  });
});
