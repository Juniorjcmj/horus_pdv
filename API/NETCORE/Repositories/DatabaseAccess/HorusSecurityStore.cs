/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/HorusSecurityStore.cs
 * Objetivo: concentra comandos SQL de usuários, sessões, tentativas de login e tokens de recuperação.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Services.Security;
using Microsoft.Data.SqlClient;
using System.Security.Cryptography;
using System.Text;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class HorusSecurityStore(Connection connection, HorusSecurityOptions securityOptions)
{
    private const int MaxFailedAttempts = 5;
    private static readonly TimeSpan AttemptWindow = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan LockDuration = TimeSpan.FromMinutes(10);
    private static readonly object AttemptSyncRoot = new();
    private static readonly Dictionary<string, LoginAttemptBucket> Attempts = new(StringComparer.OrdinalIgnoreCase);

    public List<SecurityUserDto> ListUsers(string companyId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            SELECT Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
            FROM Usuarios
            WHERE CompanyId = @CompanyId
            ORDER BY Name;
            """,
            db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        using var reader = command.ExecuteReader();
        var rows = new List<SecurityUserDto>();
        while (reader.Read())
        {
            rows.Add(ToDto(ReadUser(reader)));
        }

        return rows;
    }

    public SecurityUserDto CreateUser(UsuarioRequest request, string companyId)
    {
        var user = MapRequest($"usr-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}", request, true);
        user.CompanyId = companyId;
        ValidateDuplicates(user, null, companyId);
        InsertUser(user);
        return ToDto(user);
    }

    public SecurityUserDto RegisterPublicUser(AuthRegisterRequest request)
    {
        if (!IsValidCnpj(request.Cnpj))
        {
            throw new InvalidOperationException("CNPJ inválido.");
        }

        if (!request.Password.Equals(request.ConfirmPassword, StringComparison.Ordinal))
        {
            throw new InvalidOperationException("A confirmação de senha não confere.");
        }

        var normalizedEmail = request.Email.Trim().ToLowerInvariant();
        var normalizedCnpj = request.Cnpj.Trim();

        using (var db = connection.OpenConnection())
        {
            using (var cmdCheckEmail = new SqlCommand("SELECT COUNT(1) FROM Usuarios WHERE LOWER(LTRIM(RTRIM(Email))) = @Email;", db))
            {
                cmdCheckEmail.Parameters.AddWithValue("@Email", normalizedEmail);
                if (Convert.ToInt32(cmdCheckEmail.ExecuteScalar()) > 0)
                {
                    throw new InvalidOperationException("Este e-mail já está cadastrado no sistema. Faça login para acessar.");
                }
            }

            using (var cmdCheckCnpj = new SqlCommand("SELECT COUNT(1) FROM Empresas WHERE Cnpj = @Cnpj AND Id <> 'empresa-principal';", db))
            {
                cmdCheckCnpj.Parameters.AddWithValue("@Cnpj", normalizedCnpj);
                if (Convert.ToInt32(cmdCheckCnpj.ExecuteScalar()) > 0)
                {
                    throw new InvalidOperationException("Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta.");
                }
            }
        }

        var companyId = $"emp-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
        var user = MapRequest($"usr-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}", new UsuarioRequest
        {
            Cpf = request.Cnpj,
            Name = request.Name,
            Email = request.Email,
            Phone = request.Phone,
            Role = "administrador",
            Status = "ativo",
            Password = request.Password
        }, true);
        user.CompanyId = companyId;
        user.MustChangePassword = false;
        ValidateDuplicates(user, null, companyId);
        EnsureCompanyForPublicRegistration(companyId, request);
        InsertUser(user);
        return ToDto(user);
    }

    public SecurityUserDto? UpdateUser(string id, UsuarioRequest request, string companyId)
    {
        var current = FindUserById(id, companyId);
        if (current is null) return null;

        var updated = MapRequest(id, request, false);
        updated.CompanyId = companyId;
        ValidateDuplicates(updated, id, companyId);
        updated.PasswordHash = string.IsNullOrWhiteSpace(request.Password)
            ? current.PasswordHash
            : PasswordHasher.Hash(request.Password);
        updated.MustChangePassword = !string.IsNullOrWhiteSpace(request.Password) || current.MustChangePassword;
        UpdateUserRecord(updated);

        if (updated.Status == "inativo")
        {
            DeleteSessionsByUser(updated.Id);
        }

        return ToDto(updated);
    }

    public SecurityUserDto? UpdateStatus(string id, string status, string companyId)
    {
        var user = FindUserById(id, companyId);
        if (user is null) return null;

        user.Status = status == "inativo" ? "inativo" : "ativo";
        using var db = connection.OpenConnection();
        using var command = new SqlCommand("UPDATE Usuarios SET Status = @Status WHERE Id = @Id AND CompanyId = @CompanyId;", db);
        command.Parameters.AddWithValue("@Status", user.Status);
        command.Parameters.AddWithValue("@Id", user.Id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.ExecuteNonQuery();

        if (user.Status == "inativo")
        {
            DeleteSessionsByUser(user.Id);
        }

        return ToDto(user);
    }

    public SecurityUserDto? UpdateOwnProfile(string userId, string name, string email, string phone)
    {
        var user = FindUserById(userId);
        if (user is null || user.Status != "ativo") return null;

        var request = new UsuarioRequest
        {
            CompanyId = user.CompanyId,
            Cpf = user.Cpf,
            Name = name,
            Email = email,
            Phone = phone,
            Role = user.Role,
            Status = user.Status,
            Password = string.Empty
        };
        var updated = MapRequest(user.Id, request, false);
        updated.CompanyId = user.CompanyId;
        updated.PasswordHash = user.PasswordHash;
        updated.CreatedAt = user.CreatedAt;
        updated.LastLoginAt = user.LastLoginAt;
        updated.MustChangePassword = user.MustChangePassword;
        ValidateDuplicates(updated, user.Id, user.CompanyId);
        UpdateUserRecord(updated);
        return ToDto(updated);
    }

    public LoginResult Authenticate(string email, string password, string ip, string userAgent)
    {
        var normalizedEmail = email.Trim().ToLowerInvariant();
        var now = DateTimeOffset.UtcNow;
        var attemptKey = $"{ip}|{normalizedEmail}";

        lock (AttemptSyncRoot)
        {
            var bucket = GetAttemptBucket(attemptKey, now);
            if (bucket.LockedUntil is not null && bucket.LockedUntil > now)
            {
                return LoginResult.Fail("Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente.", bucket.LockedUntil);
            }

            var user = FindUserByEmail(normalizedEmail);
            if (user is null || user.Status != "ativo" || !PasswordHasher.Verify(password, user.PasswordHash))
            {
                RegisterFailedAttempt(bucket, now);
                return LoginResult.Fail("E-mail ou senha inválidos.", bucket.LockedUntil);
            }

            if (!string.Equals(user.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase))
            {
                var companyInfo = GetCompanyStatusInfo(user.CompanyId);
                if (companyInfo != null)
                {
                    if (string.Equals(companyInfo.Value.Status, "pendente", StringComparison.OrdinalIgnoreCase))
                    {
                        return LoginResult.Fail("O cadastro da sua empresa está em análise e aguarda aprovação pelo administrador do sistema.");
                    }
                    if (string.Equals(companyInfo.Value.Status, "rejeitada", StringComparison.OrdinalIgnoreCase))
                    {
                        var motivo = !string.IsNullOrWhiteSpace(companyInfo.Value.RejectionReason) ? $" Motivo: {companyInfo.Value.RejectionReason}" : "";
                        return LoginResult.Fail($"O cadastro da sua empresa foi recusado.{motivo}");
                    }
                    if (string.Equals(companyInfo.Value.Status, "bloqueada", StringComparison.OrdinalIgnoreCase))
                    {
                        var motivo = !string.IsNullOrWhiteSpace(companyInfo.Value.RejectionReason) ? $" Motivo: {companyInfo.Value.RejectionReason}" : "";
                        return LoginResult.Fail($"O acesso da sua empresa está suspenso/bloqueado.{motivo}");
                    }
                }
            }

            Attempts.Remove(attemptKey);
            user.LastLoginAt = now.UtcDateTime.ToString("o");
            var session = CreateSession(user, ip, userAgent, now);

            using var db = connection.OpenConnection();
            using var transaction = db.BeginTransaction();
            try
            {
                using var updateUser = new SqlCommand(
                    "UPDATE Usuarios SET LastLoginAt = @LastLoginAt WHERE Id = @Id;",
                    db,
                    transaction);
                updateUser.Parameters.AddWithValue("@LastLoginAt", user.LastLoginAt);
                updateUser.Parameters.AddWithValue("@Id", user.Id);
                updateUser.ExecuteNonQuery();

                InsertSession(db, transaction, session);
                transaction.Commit();
            }
            catch
            {
                transaction.Rollback();
                throw;
            }

            return LoginResult.Ok(ToDto(user), session);
        }
    }

    public SecurityUserDto? GetActiveUser(string id)
    {
        var user = FindUserById(id);
        return user is null || user.Status != "ativo" ? null : ToDto(user);
    }

    public List<SecuritySessionDto> ListSessions(string userId, string currentSessionId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            SELECT Id, UserId, Device, Location, Ip, LastActive, Platform, CreatedAt
            FROM Sessoes
            WHERE UserId = @UserId
            ORDER BY CreatedAt DESC;
            """,
            db);
        command.Parameters.AddWithValue("@UserId", userId);
        using var reader = command.ExecuteReader();
        var rows = new List<SecuritySessionDto>();
        while (reader.Read())
        {
            rows.Add(ToSessionDto(ReadSession(reader), ReadString(reader, "Id") == currentSessionId));
        }

        return rows;
    }

    public bool TerminateSession(string userId, string id, string currentSessionId)
    {
        if (id == currentSessionId) return false;

        using var db = connection.OpenConnection();
        using var command = new SqlCommand("DELETE FROM Sessoes WHERE Id = @Id AND UserId = @UserId;", db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@UserId", userId);
        return command.ExecuteNonQuery() > 0;
    }

    public void TerminateOtherSessions(string userId, string currentSessionId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand("DELETE FROM Sessoes WHERE UserId = @UserId AND Id <> @Id;", db);
        command.Parameters.AddWithValue("@UserId", userId);
        command.Parameters.AddWithValue("@Id", currentSessionId);
        command.ExecuteNonQuery();
    }

    public void TerminateCurrentSession(string currentSessionId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand("DELETE FROM Sessoes WHERE Id = @Id;", db);
        command.Parameters.AddWithValue("@Id", currentSessionId);
        command.ExecuteNonQuery();
    }

    public bool ChangePassword(string userId, string currentPassword, string nextPassword)
    {
        if (nextPassword.Length < 8) throw new InvalidOperationException("A nova senha deve ter no minimo 8 caracteres.");

        var user = FindUserById(userId);
        if (user is null || user.Status != "ativo") return false;
        if (!PasswordHasher.Verify(currentPassword, user.PasswordHash)) return false;

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            using var command = new SqlCommand(
                """
                UPDATE Usuarios
                   SET PasswordHash = @PasswordHash,
                       MustChangePassword = 0
                 WHERE Id = @Id;
                DELETE FROM Sessoes WHERE UserId = @Id;
                """,
                db,
                transaction);
            command.Parameters.AddWithValue("@PasswordHash", PasswordHasher.Hash(nextPassword));
            command.Parameters.AddWithValue("@Id", user.Id);
            command.ExecuteNonQuery();
            transaction.Commit();
            return true;
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public bool IsSessionActive(string sessionId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand("SELECT COUNT(1) FROM Sessoes WHERE Id = @Id;", db);
        command.Parameters.AddWithValue("@Id", sessionId);
        return Convert.ToInt32(command.ExecuteScalar()) > 0;
    }

    public PasswordResetRequestResult CreatePasswordResetToken(string cnpj, string email, string ip, string userAgent)
    {
        var normalizedCnpj = OnlyDigits(cnpj);
        var normalizedEmail = email.Trim().ToLowerInvariant();
        var now = DateTimeOffset.UtcNow;
        var expiresAt = now.AddMinutes(securityOptions.PasswordResetTokenMinutes);
        var requestedDevice = BuildDeviceLabel(userAgent);

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            using (var cleanup = new SqlCommand(
                       "DELETE FROM PasswordResetTokens WHERE ExpiresAt <= @RetentionCutoff;",
                       db,
                       transaction))
            {
                cleanup.Parameters.AddWithValue("@RetentionCutoff", now.AddDays(-7));
                cleanup.ExecuteNonQuery();
            }

            var user = FindUserForPasswordReset(db, transaction, normalizedCnpj, normalizedEmail);
            if (user is null)
            {
                transaction.Commit();
                return PasswordResetRequestResult.Create();
            }

            using (var deleteTokens = new SqlCommand(
                       """
                       UPDATE PasswordResetTokens
                          SET ConsumedAt = @Now,
                              UpdatedAt = @Now
                        WHERE UserId = @UserId
                          AND ConsumedAt IS NULL;
                       """,
                       db,
                       transaction))
            {
                deleteTokens.Parameters.AddWithValue("@UserId", user.Id);
                deleteTokens.Parameters.AddWithValue("@Now", now);
                deleteTokens.ExecuteNonQuery();
            }

            var token = GenerateSecureToken();
            var tokenHash = HashPasswordResetToken(token);
            using (var insert = new SqlCommand(
                       """
                       INSERT INTO PasswordResetTokens
                           (Id, UserId, Email, Cnpj, TokenHash, CreatedAt, RequestedAt, ExpiresAt, ConsumedAt,
                            RequestedIp, RequestedUserAgent, RequestedDevice, ResetIp, ResetUserAgent, ResetDevice, UpdatedAt)
                       VALUES
                           (@Id, @UserId, @Email, @Cnpj, @TokenHash, @CreatedAt, @RequestedAt, @ExpiresAt, NULL,
                            @RequestedIp, @RequestedUserAgent, @RequestedDevice, NULL, NULL, NULL, @UpdatedAt);
                       """,
                       db,
                       transaction))
            {
                insert.Parameters.AddWithValue("@Id", $"prt-{Guid.NewGuid():N}");
                insert.Parameters.AddWithValue("@UserId", user.Id);
                insert.Parameters.AddWithValue("@Email", user.Email);
                insert.Parameters.AddWithValue("@Cnpj", normalizedCnpj);
                insert.Parameters.AddWithValue("@TokenHash", tokenHash);
                insert.Parameters.AddWithValue("@CreatedAt", now);
                insert.Parameters.AddWithValue("@RequestedAt", now);
                insert.Parameters.AddWithValue("@ExpiresAt", expiresAt);
                insert.Parameters.AddWithValue("@RequestedIp", NullIfEmpty(ip));
                insert.Parameters.AddWithValue("@RequestedUserAgent", NullIfEmpty(userAgent));
                insert.Parameters.AddWithValue("@RequestedDevice", NullIfEmpty(requestedDevice));
                insert.Parameters.AddWithValue("@UpdatedAt", now);
                insert.ExecuteNonQuery();
            }

            transaction.Commit();
            return PasswordResetRequestResult.Create(MaskEmail(user.Email), token, expiresAt);
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public AdminPasswordResetResult? CreateAdminPasswordResetToken(
        string userId,
        string companyId,
        string ip,
        string userAgent)
    {
        var now = DateTimeOffset.UtcNow;
        var expiresAt = now.AddMinutes(securityOptions.PasswordResetTokenMinutes);
        var requestedDevice = BuildDeviceLabel(userAgent);

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            var user = FindUserById(db, transaction, userId, companyId);
            if (user is null) return null;

            var cnpj = FindCompanyCnpj(db, transaction, companyId);

            using (var cleanup = new SqlCommand(
                       "DELETE FROM PasswordResetTokens WHERE ExpiresAt <= @RetentionCutoff;",
                       db,
                       transaction))
            {
                cleanup.Parameters.AddWithValue("@RetentionCutoff", now.AddDays(-7));
                cleanup.ExecuteNonQuery();
            }

            using (var deleteTokens = new SqlCommand(
                       """
                       UPDATE PasswordResetTokens
                          SET ConsumedAt = @Now,
                              UpdatedAt = @Now
                        WHERE UserId = @UserId
                          AND ConsumedAt IS NULL;
                       """,
                       db,
                       transaction))
            {
                deleteTokens.Parameters.AddWithValue("@UserId", user.Id);
                deleteTokens.Parameters.AddWithValue("@Now", now);
                deleteTokens.ExecuteNonQuery();
            }

            var token = GenerateSecureToken();
            var tokenHash = HashPasswordResetToken(token);
            using (var insert = new SqlCommand(
                       """
                       INSERT INTO PasswordResetTokens
                           (Id, UserId, Email, Cnpj, TokenHash, CreatedAt, RequestedAt, ExpiresAt, ConsumedAt,
                            RequestedIp, RequestedUserAgent, RequestedDevice, ResetIp, ResetUserAgent, ResetDevice, UpdatedAt)
                       VALUES
                           (@Id, @UserId, @Email, @Cnpj, @TokenHash, @CreatedAt, @RequestedAt, @ExpiresAt, NULL,
                            @RequestedIp, @RequestedUserAgent, @RequestedDevice, NULL, NULL, NULL, @UpdatedAt);
                       """,
                       db,
                       transaction))
            {
                insert.Parameters.AddWithValue("@Id", $"prt-{Guid.NewGuid():N}");
                insert.Parameters.AddWithValue("@UserId", user.Id);
                insert.Parameters.AddWithValue("@Email", user.Email);
                insert.Parameters.AddWithValue("@Cnpj", cnpj);
                insert.Parameters.AddWithValue("@TokenHash", tokenHash);
                insert.Parameters.AddWithValue("@CreatedAt", now);
                insert.Parameters.AddWithValue("@RequestedAt", now);
                insert.Parameters.AddWithValue("@ExpiresAt", expiresAt);
                insert.Parameters.AddWithValue("@RequestedIp", NullIfEmpty(ip));
                insert.Parameters.AddWithValue("@RequestedUserAgent", NullIfEmpty(userAgent));
                insert.Parameters.AddWithValue("@RequestedDevice", NullIfEmpty(requestedDevice));
                insert.Parameters.AddWithValue("@UpdatedAt", now);
                insert.ExecuteNonQuery();
            }

            using (var updateUser = new SqlCommand(
                       "UPDATE Usuarios SET MustChangePassword = 1 WHERE Id = @UserId AND CompanyId = @CompanyId;",
                       db,
                       transaction))
            {
                updateUser.Parameters.AddWithValue("@UserId", user.Id);
                updateUser.Parameters.AddWithValue("@CompanyId", companyId);
                updateUser.ExecuteNonQuery();
            }

            using (var deleteSessions = new SqlCommand("DELETE FROM Sessoes WHERE UserId = @UserId;", db, transaction))
            {
                deleteSessions.Parameters.AddWithValue("@UserId", user.Id);
                deleteSessions.ExecuteNonQuery();
            }

            transaction.Commit();
            user.MustChangePassword = true;
            return AdminPasswordResetResult.Create(ToDto(user), MaskEmail(user.Email), token, expiresAt);
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public void ConsumePasswordResetToken(string token, string ip, string userAgent)
    {
        if (string.IsNullOrWhiteSpace(token)) return;

        var now = DateTimeOffset.UtcNow;
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            UPDATE PasswordResetTokens
               SET ConsumedAt = COALESCE(ConsumedAt, @Now),
                   ResetIp = @ResetIp,
                   ResetUserAgent = @ResetUserAgent,
                   ResetDevice = @ResetDevice,
                   UpdatedAt = @Now
             WHERE TokenHash = @TokenHash;
            """,
            db);
        command.Parameters.AddWithValue("@Now", now);
        command.Parameters.AddWithValue("@ResetIp", NullIfEmpty(ip));
        command.Parameters.AddWithValue("@ResetUserAgent", NullIfEmpty(userAgent));
        command.Parameters.AddWithValue("@ResetDevice", NullIfEmpty(BuildDeviceLabel(userAgent)));
        command.Parameters.AddWithValue("@TokenHash", HashPasswordResetToken(token.Trim()));
        command.ExecuteNonQuery();
    }

    public SecurityUserDto ResetPasswordWithToken(
        string token,
        string nextPassword,
        string confirmPassword,
        string ip = "",
        string userAgent = "")
    {
        if (string.IsNullOrWhiteSpace(token)) throw new InvalidOperationException("Token de redefinição inválido.");
        if (!nextPassword.Equals(confirmPassword, StringComparison.Ordinal)) throw new InvalidOperationException("A confirmação de senha não confere.");
        if (nextPassword.Length < 8) throw new InvalidOperationException("A nova senha deve ter no minimo 8 caracteres.");

        var now = DateTimeOffset.UtcNow;
        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            var resetToken = FindResetToken(db, transaction, token.Trim());
            if (resetToken is null || resetToken.ExpiresAt <= now)
            {
                throw new InvalidOperationException("Token de redefinição inválido ou expirado.");
            }

            var user = FindUserById(db, transaction, resetToken.UserId);
            if (user is null || user.Status != "ativo")
            {
                throw new InvalidOperationException("Usuário inativo ou inexistente.");
            }

            using var command = new SqlCommand(
                """
                UPDATE Usuarios
                   SET PasswordHash = @PasswordHash,
                       MustChangePassword = 0
                 WHERE Id = @UserId;
                UPDATE PasswordResetTokens
                   SET ConsumedAt = COALESCE(ConsumedAt, @Now),
                       ResetIp = @ResetIp,
                       ResetUserAgent = @ResetUserAgent,
                       ResetDevice = @ResetDevice,
                       UpdatedAt = @Now
                 WHERE UserId = @UserId;
                DELETE FROM Sessoes WHERE UserId = @UserId;
                """,
                db,
                transaction);
            command.Parameters.AddWithValue("@PasswordHash", PasswordHasher.Hash(nextPassword));
            command.Parameters.AddWithValue("@UserId", user.Id);
            command.Parameters.AddWithValue("@Now", now);
            command.Parameters.AddWithValue("@ResetIp", NullIfEmpty(ip));
            command.Parameters.AddWithValue("@ResetUserAgent", NullIfEmpty(userAgent));
            command.Parameters.AddWithValue("@ResetDevice", NullIfEmpty(BuildDeviceLabel(userAgent)));
            command.ExecuteNonQuery();
            transaction.Commit();
            user.MustChangePassword = false;
            return ToDto(user);
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    private LoginAttemptBucket GetAttemptBucket(string key, DateTimeOffset now)
    {
        if (!Attempts.TryGetValue(key, out var bucket) || now - bucket.FirstAttemptAt > AttemptWindow)
        {
            bucket = new LoginAttemptBucket { FirstAttemptAt = now };
            Attempts[key] = bucket;
        }

        return bucket;
    }

    private static void RegisterFailedAttempt(LoginAttemptBucket bucket, DateTimeOffset now)
    {
        bucket.Count += 1;
        bucket.LastAttemptAt = now;
        if (bucket.Count >= MaxFailedAttempts)
        {
            bucket.LockedUntil = now.Add(LockDuration);
        }
    }

    private static SecuritySession CreateSession(SecurityUserRecord user, string ip, string userAgent, DateTimeOffset now)
    {
        var platform = userAgent.Contains("Mobile", StringComparison.OrdinalIgnoreCase) ? "mobile" : "desktop";
        return new SecuritySession
        {
            Id = $"sess-{Guid.NewGuid():N}",
            UserId = user.Id,
            Device = BuildDeviceLabel(userAgent),
            Location = "Localização indisponível",
            Ip = ip,
            LastActive = "Agora mesmo",
            Platform = platform,
            CreatedAt = now
        };
    }

    private static string BuildDeviceLabel(string userAgent)
    {
        if (userAgent.Contains("Firefox", StringComparison.OrdinalIgnoreCase)) return "Navegador - Firefox";
        if (userAgent.Contains("Edg", StringComparison.OrdinalIgnoreCase)) return "Navegador - Edge";
        if (userAgent.Contains("Chrome", StringComparison.OrdinalIgnoreCase)) return "Navegador - Chrome";
        if (userAgent.Contains("Safari", StringComparison.OrdinalIgnoreCase)) return "Navegador - Safari";
        return "Dispositivo web";
    }

    private static SecurityUserRecord MapRequest(string id, UsuarioRequest request, bool isCreate)
    {
        var documentDigits = OnlyDigits(request.Cpf);
        if (documentDigits.Length != 11 && documentDigits.Length != 14)
        {
            throw new InvalidOperationException("CPF/CNPJ invalido.");
        }

        if (string.IsNullOrWhiteSpace(request.Name)) throw new InvalidOperationException("Nome e obrigatorio.");
        if (string.IsNullOrWhiteSpace(request.Email) || !request.Email.Contains('@')) throw new InvalidOperationException("E-mail invalido.");
        if (isCreate && request.Password.Length < 8) throw new InvalidOperationException("Senha deve ter no minimo 8 caracteres.");

        return new SecurityUserRecord
        {
            Id = id,
            CompanyId = request.CompanyId,
            Cpf = request.Cpf.Trim(),
            Name = request.Name.Trim(),
            Email = request.Email.Trim().ToLowerInvariant(),
            Phone = request.Phone.Trim(),
            Role = string.IsNullOrWhiteSpace(request.Role) ? "atendente" : request.Role.Trim(),
            Status = request.Status == "inativo" ? "inativo" : "ativo",
            CreatedAt = DateTime.UtcNow.ToString("yyyy-MM-dd"),
            LastLoginAt = "-",
            PasswordHash = string.IsNullOrWhiteSpace(request.Password) ? string.Empty : PasswordHasher.Hash(request.Password),
            MustChangePassword = isCreate || !string.IsNullOrWhiteSpace(request.Password)
        };
    }

    private void ValidateDuplicates(SecurityUserRecord user, string? currentId, string companyId)
    {
        using var db = connection.OpenConnection();

        // 1. E-mail é único globalmente na base de usuários da plataforma
        using (var cmdEmail = new SqlCommand(
            """
            SELECT COUNT(1)
            FROM Usuarios
            WHERE Id <> @CurrentId
              AND LOWER(LTRIM(RTRIM(Email))) = LOWER(LTRIM(RTRIM(@Email)));
            """,
            db))
        {
            cmdEmail.Parameters.AddWithValue("@CurrentId", currentId ?? string.Empty);
            cmdEmail.Parameters.AddWithValue("@Email", user.Email);
            if (Convert.ToInt32(cmdEmail.ExecuteScalar()) > 0)
            {
                throw new InvalidOperationException("Este e-mail já está cadastrado no sistema.");
            }
        }

        // 2. CPF é único por empresa (multi-tenant)
        using (var cmdCpf = new SqlCommand(
            """
            SELECT COUNT(1)
            FROM Usuarios
            WHERE Id <> @CurrentId
              AND CompanyId = @CompanyId
              AND Cpf = @Cpf;
            """,
            db))
        {
            cmdCpf.Parameters.AddWithValue("@CurrentId", currentId ?? string.Empty);
            cmdCpf.Parameters.AddWithValue("@CompanyId", companyId);
            cmdCpf.Parameters.AddWithValue("@Cpf", user.Cpf);
            if (Convert.ToInt32(cmdCpf.ExecuteScalar()) > 0)
            {
                throw new InvalidOperationException("Já existe um usuário com este CPF nesta empresa.");
            }
        }
    }

    private void InsertUser(SecurityUserRecord user)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            INSERT INTO Usuarios (Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword)
            VALUES (@Id, @CompanyId, @Cpf, @Name, @Email, @Phone, @Role, @Status, @CreatedAt, @LastLoginAt, @PasswordHash, @MustChangePassword);
            """,
            db);
        AddUserParameters(command, user);
        command.ExecuteNonQuery();
    }

    private void EnsureCompanyForPublicRegistration(string companyId, AuthRegisterRequest request)
    {
        var requireApproval = IsApprovalRequiredForNewCompanies();
        var initialStatus = requireApproval ? "pendente" : "aprovada";

        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            IF NOT EXISTS (SELECT 1 FROM Empresas WHERE Id = @Id)
            BEGIN
                INSERT INTO Empresas
                    (Id, FantasyName, CorporateName, Cnpj, StateRegistration, Website, Email, SacPhone, Phone, Mobile,
                     Cep, Address, Number, Neighborhood, City, Uf, Complement, EmailSmtpEnabled, EmailSmtpHost,
                     EmailSmtpPort, EmailSmtpEnableSsl, EmailSmtpUser, EmailSmtpPassword, EmailSmtpFromEmail,
                     EmailSmtpFromName, EmailSmtpReplyTo, Status, CreatedAt)
                VALUES
                    (@Id, @FantasyName, @CorporateName, @Cnpj, N'', N'', @Email, N'', @Phone, @Phone,
                     N'', N'', N'', N'', N'', N'', N'', 0, N'smtp-mail.outlook.com',
                     587, 1, N'', N'', N'', @FantasyName, N'', @Status, SYSDATETIMEOFFSET());
            END;
            """,
            db);
        command.Parameters.AddWithValue("@Id", companyId);
        command.Parameters.AddWithValue("@FantasyName", request.Name.Trim());
        command.Parameters.AddWithValue("@CorporateName", request.Name.Trim());
        command.Parameters.AddWithValue("@Cnpj", request.Cnpj.Trim());
        command.Parameters.AddWithValue("@Email", request.Email.Trim().ToLowerInvariant());
        command.Parameters.AddWithValue("@Phone", request.Phone.Trim());
        command.Parameters.AddWithValue("@Status", initialStatus);
        command.ExecuteNonQuery();
    }

    private void UpdateUserRecord(SecurityUserRecord user)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            UPDATE Usuarios
               SET Cpf = @Cpf,
                   CompanyId = @CompanyId,
                   Name = @Name,
                   Email = @Email,
                   Phone = @Phone,
                   Role = @Role,
                   Status = @Status,
                   LastLoginAt = @LastLoginAt,
                   PasswordHash = @PasswordHash,
                   MustChangePassword = @MustChangePassword
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """,
            db);
        AddUserParameters(command, user);
        command.ExecuteNonQuery();
    }

    private SecurityUserRecord? FindUserById(string id)
    {
        using var db = connection.OpenConnection();
        return FindUserById(db, null, id);
    }

    private SecurityUserRecord? FindUserById(string id, string companyId)
    {
        using var db = connection.OpenConnection();
        return FindUserById(db, null, id, companyId);
    }

    private SecurityUserRecord? FindUserByEmail(string email)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand(
            """
            SELECT Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
            FROM Usuarios
            WHERE Email = @Email;
            """,
            db);
        command.Parameters.AddWithValue("@Email", email);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadUser(reader) : null;
    }

    private static SecurityUserRecord? FindUserById(SqlConnection db, SqlTransaction? transaction, string id)
    {
        using var command = new SqlCommand(
            """
            SELECT Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
            FROM Usuarios
            WHERE Id = @Id;
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@Id", id);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadUser(reader) : null;
    }

    private static SecurityUserRecord? FindUserById(
        SqlConnection db,
        SqlTransaction? transaction,
        string id,
        string companyId)
    {
        using var command = new SqlCommand(
            """
            SELECT Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
            FROM Usuarios
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadUser(reader) : null;
    }

    private static string FindCompanyCnpj(SqlConnection db, SqlTransaction transaction, string companyId)
    {
        using var command = new SqlCommand("SELECT Cnpj FROM Empresas WHERE Id = @CompanyId;", db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        var result = command.ExecuteScalar()?.ToString() ?? "";
        return OnlyDigits(result);
    }

    private static SecurityUserRecord? FindUserForPasswordReset(
        SqlConnection db,
        SqlTransaction transaction,
        string cnpj,
        string email)
    {
        using var command = new SqlCommand(
            """
            SELECT Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
            FROM Usuarios
            WHERE REPLACE(REPLACE(REPLACE(REPLACE(Cpf, '.', ''), '/', ''), '-', ''), ' ', '') = @Cnpj
              AND Email = @Email
              AND Status = N'ativo';
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@Cnpj", cnpj);
        command.Parameters.AddWithValue("@Email", email);
        using var reader = command.ExecuteReader();
        return reader.Read() ? ReadUser(reader) : null;
    }

    private PasswordResetTokenRecord? FindResetToken(SqlConnection db, SqlTransaction transaction, string token)
    {
        var tokenHash = HashPasswordResetToken(token);
        using var command = new SqlCommand(
            """
            SELECT Id, UserId, Email, Cnpj, TokenHash, CreatedAt, RequestedAt, ExpiresAt, ConsumedAt
            FROM PasswordResetTokens
            WHERE TokenHash = @TokenHash AND ConsumedAt IS NULL;
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@TokenHash", tokenHash);
        using var reader = command.ExecuteReader();
        return reader.Read()
            ? new PasswordResetTokenRecord(
                ReadString(reader, "Id"),
                ReadString(reader, "UserId"),
                ReadString(reader, "Email"),
                ReadString(reader, "Cnpj"),
                ReadString(reader, "TokenHash"),
                reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
                reader.GetDateTimeOffset(reader.GetOrdinal("RequestedAt")),
                reader.GetDateTimeOffset(reader.GetOrdinal("ExpiresAt")))
            : null;
    }

    private static void InsertSession(SqlConnection db, SqlTransaction transaction, SecuritySession session)
    {
        using var command = new SqlCommand(
            """
            INSERT INTO Sessoes (Id, UserId, Device, Location, Ip, LastActive, Platform, CreatedAt)
            VALUES (@Id, @UserId, @Device, @Location, @Ip, @LastActive, @Platform, @CreatedAt);
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@Id", session.Id);
        command.Parameters.AddWithValue("@UserId", session.UserId);
        command.Parameters.AddWithValue("@Device", session.Device);
        command.Parameters.AddWithValue("@Location", session.Location);
        command.Parameters.AddWithValue("@Ip", session.Ip);
        command.Parameters.AddWithValue("@LastActive", session.LastActive);
        command.Parameters.AddWithValue("@Platform", session.Platform);
        command.Parameters.AddWithValue("@CreatedAt", session.CreatedAt);
        command.ExecuteNonQuery();
    }

    private void DeleteSessionsByUser(string userId)
    {
        using var db = connection.OpenConnection();
        using var command = new SqlCommand("DELETE FROM Sessoes WHERE UserId = @UserId;", db);
        command.Parameters.AddWithValue("@UserId", userId);
        command.ExecuteNonQuery();
    }

    private static void AddUserParameters(SqlCommand command, SecurityUserRecord user)
    {
        command.Parameters.AddWithValue("@Id", user.Id);
        command.Parameters.AddWithValue("@CompanyId", user.CompanyId);
        command.Parameters.AddWithValue("@Cpf", user.Cpf);
        command.Parameters.AddWithValue("@Name", user.Name);
        command.Parameters.AddWithValue("@Email", user.Email);
        command.Parameters.AddWithValue("@Phone", user.Phone);
        command.Parameters.AddWithValue("@Role", user.Role);
        command.Parameters.AddWithValue("@Status", user.Status);
        command.Parameters.AddWithValue("@CreatedAt", user.CreatedAt);
        command.Parameters.AddWithValue("@LastLoginAt", user.LastLoginAt);
        command.Parameters.AddWithValue("@PasswordHash", user.PasswordHash);
        command.Parameters.AddWithValue("@MustChangePassword", user.MustChangePassword);
    }

    private static string OnlyDigits(string value) => new(value.Where(char.IsDigit).ToArray());

    public static bool IsValidCnpj(string rawCnpj)
    {
        var cnpj = OnlyDigits(rawCnpj);
        if (cnpj.Length != 14 || cnpj.All(digit => digit == cnpj[0])) return false;

        static int CalcDigit(string baseValue, int[] factors)
        {
            var sum = 0;
            for (var index = 0; index < factors.Length; index += 1)
            {
                sum += (baseValue[index] - '0') * factors[index];
            }

            var remainder = sum % 11;
            return remainder < 2 ? 0 : 11 - remainder;
        }

        var base12 = cnpj[..12];
        var firstDigit = CalcDigit(base12, [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
        var secondDigit = CalcDigit($"{base12}{firstDigit}", [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]);
        return cnpj.EndsWith($"{firstDigit}{secondDigit}", StringComparison.Ordinal);
    }

    private static string GenerateSecureToken()
    {
        var bytes = RandomNumberGenerator.GetBytes(32);
        return Convert.ToBase64String(bytes)
            .Replace("+", "-", StringComparison.Ordinal)
            .Replace("/", "_", StringComparison.Ordinal)
            .TrimEnd('=');
    }

    private string HashPasswordResetToken(string token)
    {
        var normalized = token.Trim();
        var payload = Encoding.UTF8.GetBytes($"{normalized}:{securityOptions.JwtSecret}");
        var hash = SHA256.HashData(payload);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static object NullIfEmpty(string value)
        => string.IsNullOrWhiteSpace(value) ? DBNull.Value : value.Trim();

    private static string MaskEmail(string email)
    {
        var parts = email.Split('@', 2);
        if (parts.Length != 2 || parts[0].Length == 0) return email;
        var prefix = parts[0].Length == 1 ? parts[0] : $"{parts[0][0]}***{parts[0][^1]}";
        return $"{prefix}@{parts[1]}";
    }

    private static SecurityUserRecord ReadUser(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CompanyId = ReadString(reader, "CompanyId"),
        Cpf = ReadString(reader, "Cpf"),
        Name = ReadString(reader, "Name"),
        Email = ReadString(reader, "Email"),
        Phone = ReadString(reader, "Phone"),
        Role = ReadString(reader, "Role"),
        Status = ReadString(reader, "Status"),
        CreatedAt = ReadString(reader, "CreatedAt"),
        LastLoginAt = ReadString(reader, "LastLoginAt"),
        PasswordHash = ReadString(reader, "PasswordHash"),
        MustChangePassword = reader.GetBoolean(reader.GetOrdinal("MustChangePassword"))
    };

    private static SecuritySession ReadSession(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        UserId = ReadString(reader, "UserId"),
        Device = ReadString(reader, "Device"),
        Location = ReadString(reader, "Location"),
        Ip = ReadString(reader, "Ip"),
        LastActive = ReadString(reader, "LastActive"),
        Platform = ReadString(reader, "Platform"),
        CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt"))
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);
    }

    private static DateTimeOffset? ReadNullableDateTimeOffset(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDateTimeOffset(ordinal);
    }

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    public bool IsApprovalRequiredForNewCompanies()
    {
        try
        {
            using var db = connection.OpenConnection();
            using var cmd = new SqlCommand(
                "SELECT TOP 1 ISNULL(RequireApprovalForNewCompanies, 1) FROM Empresas WHERE Id = 'empresa-principal';",
                db);
            var val = cmd.ExecuteScalar();
            return val != null && Convert.ToBoolean(val);
        }
        catch
        {
            return true;
        }
    }

    public void SetApprovalRequiredForNewCompanies(bool required)
    {
        using var db = connection.OpenConnection();
        using var cmd = new SqlCommand(
            "UPDATE Empresas SET RequireApprovalForNewCompanies = @Required WHERE Id = 'empresa-principal';",
            db);
        cmd.Parameters.AddWithValue("@Required", required);
        cmd.ExecuteNonQuery();
    }

    public string GetCompanyStatus(string companyId)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return "aprovada";
        }

        try
        {
            using var db = connection.OpenConnection();
            using var cmd = new SqlCommand("SELECT TOP 1 Status FROM Empresas WHERE Id = @Id;", db);
            cmd.Parameters.AddWithValue("@Id", companyId);
            var val = cmd.ExecuteScalar();
            return val != null ? Convert.ToString(val) ?? "aprovada" : "aprovada";
        }
        catch
        {
            return "aprovada";
        }
    }

    public (string Status, string? RejectionReason)? GetCompanyStatusInfo(string companyId)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return ("aprovada", null);
        }

        try
        {
            using var db = connection.OpenConnection();
            using var cmd = new SqlCommand("SELECT TOP 1 Status, RejectionReason FROM Empresas WHERE Id = @Id;", db);
            cmd.Parameters.AddWithValue("@Id", companyId);
            using var reader = cmd.ExecuteReader();
            if (reader.Read())
            {
                var status = ReadString(reader, "Status");
                var reason = ReadNullableString(reader, "RejectionReason");
                return (string.IsNullOrWhiteSpace(status) ? "pendente" : status, reason);
            }
            return null;
        }
        catch
        {
            return null;
        }
    }

    public EmpresasMetricasDto GetCompaniesMetrics()
    {
        var metricas = new EmpresasMetricasDto
        {
            RequireApprovalForNewCompanies = IsApprovalRequiredForNewCompanies()
        };

        using var db = connection.OpenConnection();
        using var cmd = new SqlCommand(
            """
            SELECT
                COUNT(1) AS Total,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'pendente' THEN 1 ELSE 0 END) AS Pendentes,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'aprovada' THEN 1 ELSE 0 END) AS Aprovadas,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'rejeitada' THEN 1 ELSE 0 END) AS Rejeitadas,
                SUM(CASE WHEN ISNULL(Status, 'aprovada') = 'bloqueada' THEN 1 ELSE 0 END) AS Bloqueadas
            FROM Empresas;
            """,
            db);

        using var reader = cmd.ExecuteReader();
        if (reader.Read())
        {
            metricas.Total = ReadInt(reader, "Total");
            metricas.Pendentes = ReadInt(reader, "Pendentes");
            metricas.Aprovadas = ReadInt(reader, "Aprovadas");
            metricas.Rejeitadas = ReadInt(reader, "Rejeitadas");
            metricas.Bloqueadas = ReadInt(reader, "Bloqueadas");
        }

        return metricas;
    }

    public EmpresasAdminListResult ListCompaniesAdmin(string? search, string? status, int page, int pageSize)
    {
        page = Math.Max(1, page);
        pageSize = Math.Clamp(pageSize, 1, 100);
        var offset = (page - 1) * pageSize;
        var searchPattern = string.IsNullOrWhiteSpace(search) ? null : $"%{search.Trim()}%";
        var normalizedStatus = string.IsNullOrWhiteSpace(status) || status.Equals("todas", StringComparison.OrdinalIgnoreCase)
            ? null
            : status.Trim().ToLowerInvariant();

        using var db = connection.OpenConnection();

        var countSql = """
            SELECT COUNT(1)
            FROM Empresas e
            WHERE (@Status IS NULL OR ISNULL(e.Status, 'aprovada') = @Status)
              AND (@SearchPattern IS NULL OR
                   e.FantasyName LIKE @SearchPattern OR
                   e.CorporateName LIKE @SearchPattern OR
                   e.Cnpj LIKE @SearchPattern OR
                   e.Email LIKE @SearchPattern);
            """;

        using var countCmd = new SqlCommand(countSql, db);
        countCmd.Parameters.AddWithValue("@Status", (object?)normalizedStatus ?? DBNull.Value);
        countCmd.Parameters.AddWithValue("@SearchPattern", (object?)searchPattern ?? DBNull.Value);
        var totalCount = Convert.ToInt32(countCmd.ExecuteScalar());

        var itemsSql = """
            SELECT
                e.Id,
                e.FantasyName,
                e.CorporateName,
                e.Cnpj,
                e.Email,
                e.Phone,
                e.Mobile,
                e.City,
                e.Uf,
                ISNULL(e.Status, 'aprovada') AS Status,
                ISNULL(e.CreatedAt, SYSDATETIMEOFFSET()) AS CreatedAt,
                e.ReviewedAt,
                e.ReviewedBy,
                e.RejectionReason,
                (SELECT COUNT(1) FROM Usuarios u WHERE u.CompanyId = e.Id) AS TotalUsers,
                (SELECT COUNT(1) FROM Produtos p WHERE p.CompanyId = e.Id) AS TotalProducts,
                (SELECT COUNT(1) FROM Vendas v WHERE v.CompanyId = e.Id) AS TotalSales,
                (SELECT TOP 1 u.Name FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserName,
                (SELECT TOP 1 u.Email FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserEmail,
                (SELECT TOP 1 u.Phone FROM Usuarios u WHERE u.CompanyId = e.Id ORDER BY u.CreatedAt ASC) AS AdminUserPhone
            FROM Empresas e
            WHERE (@Status IS NULL OR ISNULL(e.Status, 'aprovada') = @Status)
              AND (@SearchPattern IS NULL OR
                   e.FantasyName LIKE @SearchPattern OR
                   e.CorporateName LIKE @SearchPattern OR
                   e.Cnpj LIKE @SearchPattern OR
                   e.Email LIKE @SearchPattern)
            ORDER BY
                CASE WHEN e.Id = 'empresa-principal' THEN 0 ELSE 1 END ASC,
                CASE WHEN ISNULL(e.Status, 'aprovada') = 'pendente' THEN 0 ELSE 1 END ASC,
                e.CreatedAt DESC
            OFFSET @Offset ROWS FETCH NEXT @PageSize ROWS ONLY;
            """;

        using var itemsCmd = new SqlCommand(itemsSql, db);
        itemsCmd.Parameters.AddWithValue("@Status", (object?)normalizedStatus ?? DBNull.Value);
        itemsCmd.Parameters.AddWithValue("@SearchPattern", (object?)searchPattern ?? DBNull.Value);
        itemsCmd.Parameters.AddWithValue("@Offset", offset);
        itemsCmd.Parameters.AddWithValue("@PageSize", pageSize);

        var list = new List<EmpresaAdminItemDto>();
        using var reader = itemsCmd.ExecuteReader();
        while (reader.Read())
        {
            list.Add(new EmpresaAdminItemDto
            {
                Id = ReadString(reader, "Id"),
                FantasyName = ReadString(reader, "FantasyName"),
                CorporateName = ReadString(reader, "CorporateName"),
                Cnpj = ReadString(reader, "Cnpj"),
                Email = ReadString(reader, "Email"),
                Phone = ReadString(reader, "Phone"),
                Mobile = ReadString(reader, "Mobile"),
                City = ReadString(reader, "City"),
                Uf = ReadString(reader, "Uf"),
                Status = ReadString(reader, "Status"),
                CreatedAt = reader.IsDBNull(reader.GetOrdinal("CreatedAt")) ? DateTimeOffset.UtcNow : reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
                ReviewedAt = ReadNullableDateTimeOffset(reader, "ReviewedAt"),
                ReviewedBy = ReadNullableString(reader, "ReviewedBy"),
                RejectionReason = ReadNullableString(reader, "RejectionReason"),
                TotalUsers = ReadInt(reader, "TotalUsers"),
                TotalProducts = ReadInt(reader, "TotalProducts"),
                TotalSales = ReadInt(reader, "TotalSales"),
                AdminUserName = ReadNullableString(reader, "AdminUserName"),
                AdminUserEmail = ReadNullableString(reader, "AdminUserEmail"),
                AdminUserPhone = ReadNullableString(reader, "AdminUserPhone")
            });
        }

        return new EmpresasAdminListResult
        {
            Items = list,
            TotalCount = totalCount,
            Page = page,
            PageSize = pageSize
        };
    }

    public bool ApproveCompany(string companyId, string reviewerName)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)) return false;

        using var db = connection.OpenConnection();
        using var cmd = new SqlCommand(
            """
            UPDATE Empresas
               SET Status = 'aprovada',
                   ReviewedAt = SYSDATETIMEOFFSET(),
                   ReviewedBy = @Reviewer,
                   RejectionReason = NULL
             WHERE Id = @CompanyId AND Id <> 'empresa-principal';
            """,
            db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Reviewer", reviewerName);
        return cmd.ExecuteNonQuery() > 0;
    }

    public bool RejectCompany(string companyId, string reason, string reviewerName)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)) return false;

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            using var cmd = new SqlCommand(
                """
                UPDATE Empresas
                   SET Status = 'rejeitada',
                       ReviewedAt = SYSDATETIMEOFFSET(),
                       ReviewedBy = @Reviewer,
                       RejectionReason = @Reason
                 WHERE Id = @CompanyId AND Id <> 'empresa-principal';

                DELETE s
                FROM Sessoes s
                INNER JOIN Usuarios u ON s.UserId = u.Id
                WHERE u.CompanyId = @CompanyId;
                """,
                db,
                transaction);
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@Reviewer", reviewerName);
            cmd.Parameters.AddWithValue("@Reason", reason.Trim());
            var affected = cmd.ExecuteNonQuery();
            transaction.Commit();
            return affected > 0;
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public bool BlockCompany(string companyId, string reason, string reviewerName)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)) return false;

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            using var cmd = new SqlCommand(
                """
                UPDATE Empresas
                   SET Status = 'bloqueada',
                       ReviewedAt = SYSDATETIMEOFFSET(),
                       ReviewedBy = @Reviewer,
                       RejectionReason = @Reason
                 WHERE Id = @CompanyId AND Id <> 'empresa-principal';

                DELETE s
                FROM Sessoes s
                INNER JOIN Usuarios u ON s.UserId = u.Id
                WHERE u.CompanyId = @CompanyId;
                """,
                db,
                transaction);
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@Reviewer", reviewerName);
            cmd.Parameters.AddWithValue("@Reason", reason.Trim());
            var affected = cmd.ExecuteNonQuery();
            transaction.Commit();
            return affected > 0;
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public bool ReactivateCompany(string companyId, string reviewerName)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)) return false;

        using var db = connection.OpenConnection();
        using var cmd = new SqlCommand(
            """
            UPDATE Empresas
               SET Status = 'aprovada',
                   ReviewedAt = SYSDATETIMEOFFSET(),
                   ReviewedBy = @Reviewer,
                   RejectionReason = NULL
             WHERE Id = @CompanyId AND Id <> 'empresa-principal';
            """,
            db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Reviewer", reviewerName);
        return cmd.ExecuteNonQuery() > 0;
    }

    public (bool Success, string Message) UpdateCompanyAdminCredentials(string companyId, string? newEmail, string? newPassword)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase))
            return (false, "Não é possível alterar credenciais da empresa-principal por aqui.");

        var hasEmail = !string.IsNullOrWhiteSpace(newEmail);
        var hasPassword = !string.IsNullOrWhiteSpace(newPassword);
        if (!hasEmail && !hasPassword)
            return (false, "Informe o novo e-mail ou a nova senha.");

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            // Busca o admin (role=administrador) da empresa
            using var findCmd = new SqlCommand(
                """
                SELECT TOP 1 Id, CompanyId, Cpf, Name, Email, Phone, Role, Status, CreatedAt, LastLoginAt, PasswordHash, MustChangePassword
                FROM Usuarios
                WHERE CompanyId = @CompanyId AND Role = N'administrador' AND Status = N'ativo'
                ORDER BY CreatedAt;
                """,
                db, transaction);
            findCmd.Parameters.AddWithValue("@CompanyId", companyId);
            SecurityUserRecord? admin;
            using (var reader = findCmd.ExecuteReader())
            {
                admin = reader.Read() ? ReadUser(reader) : null;
            }

            if (admin is null)
                return (false, "Nenhum usuário administrador ativo encontrado nesta empresa.");

            if (hasEmail)
            {
                var email = newEmail!.Trim().ToLowerInvariant();
                if (!email.Contains('@'))
                    return (false, "E-mail inválido.");

                // Verifica duplicidade
                using var dupCmd = new SqlCommand(
                    "SELECT COUNT(1) FROM Usuarios WHERE Email = @Email AND Id <> @UserId;",
                    db, transaction);
                dupCmd.Parameters.AddWithValue("@Email", email);
                dupCmd.Parameters.AddWithValue("@UserId", admin.Id);
                if (Convert.ToInt32(dupCmd.ExecuteScalar()) > 0)
                    return (false, "Este e-mail já está em uso por outro usuário.");
            }

            var setClauses = new List<string>();
            var parameters = new List<(string Name, object Value)>();

            if (hasEmail)
            {
                setClauses.Add("Email = @NewEmail");
                parameters.Add(("@NewEmail", newEmail!.Trim().ToLowerInvariant()));
            }

            if (hasPassword)
            {
                setClauses.Add("PasswordHash = @NewPasswordHash");
                setClauses.Add("MustChangePassword = 0");
                parameters.Add(("@NewPasswordHash", PasswordHasher.Hash(newPassword!)));
            }

            var sql = $"UPDATE Usuarios SET {string.Join(", ", setClauses)} WHERE Id = @UserId;";
            using var updateCmd = new SqlCommand(sql, db, transaction);
            updateCmd.Parameters.AddWithValue("@UserId", admin.Id);
            foreach (var (name, value) in parameters)
                updateCmd.Parameters.AddWithValue(name, value);

            updateCmd.ExecuteNonQuery();

            // Invalida sessões ativas do usuário se a senha foi trocada
            if (hasPassword)
            {
                using var killSessions = new SqlCommand(
                    "DELETE FROM Sessoes WHERE UserId = @UserId;", db, transaction);
                killSessions.Parameters.AddWithValue("@UserId", admin.Id);
                killSessions.ExecuteNonQuery();
            }

            transaction.Commit();

            var changes = new List<string>();
            if (hasEmail) changes.Add("e-mail");
            if (hasPassword) changes.Add("senha");
            return (true, $"Credenciais atualizadas ({string.Join(" e ", changes)}) para o administrador {admin.Name}.");
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    public bool DeleteCompany(string companyId)
    {
        if (string.Equals(companyId, "empresa-principal", StringComparison.OrdinalIgnoreCase)) return false;

        using var db = connection.OpenConnection();
        using var transaction = db.BeginTransaction();
        try
        {
            var tables = new[]
            {
                "FiadoMovimentos", "PromocaoProdutos", "Promocoes", "Categorias",
                "DocumentosFiscais", "FiscalSequencias",
                "PedidoItens", "Pedidos",
                "VendaPagamentos", "VendaItens", "Vendas",
                "CaixaMovimentos", "CaixaSessoes",
                "ModuloMercadoRegistros",
                "AuditLog", "PasswordResetTokens", "Sessoes",
                "Produtos", "Clientes", "Fornecedores", "Usuarios"
            };

            foreach (var table in tables)
            {
                using var cmd = new SqlCommand($"DELETE FROM [{table}] WHERE CompanyId = @CompanyId;", db, transaction);
                cmd.Parameters.AddWithValue("@CompanyId", companyId);
                cmd.ExecuteNonQuery();
            }

            using var deleteEmpresa = new SqlCommand(
                "DELETE FROM Empresas WHERE Id = @CompanyId AND Id <> 'empresa-principal';", db, transaction);
            deleteEmpresa.Parameters.AddWithValue("@CompanyId", companyId);
            var deleted = deleteEmpresa.ExecuteNonQuery() > 0;

            transaction.Commit();
            return deleted;
        }
        catch
        {
            transaction.Rollback();
            throw;
        }
    }

    private static SecurityUserDto ToDto(SecurityUserRecord source) => new()
    {
        Id = source.Id,
        CompanyId = source.CompanyId,
        Cpf = source.Cpf,
        Name = source.Name,
        Email = source.Email,
        Phone = source.Phone,
        Role = source.Role,
        Status = source.Status,
        CreatedAt = source.CreatedAt,
        LastLoginAt = source.LastLoginAt,
        MustChangePassword = source.MustChangePassword
    };

    private static SecuritySessionDto ToSessionDto(SecuritySession source, bool current) => new()
    {
        Id = source.Id,
        Device = source.Device,
        Location = source.Location,
        Ip = source.Ip,
        LastActive = current ? "Agora mesmo" : source.LastActive,
        Current = current,
        Platform = source.Platform
    };
}

public class SecurityUserDto
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string Cpf { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Role { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string CreatedAt { get; set; } = string.Empty;
    public string LastLoginAt { get; set; } = string.Empty;
    public bool MustChangePassword { get; set; }
}

public class SecuritySessionDto
{
    public string Id { get; set; } = string.Empty;
    public string Device { get; set; } = string.Empty;
    public string Location { get; set; } = string.Empty;
    public string Ip { get; set; } = string.Empty;
    public string LastActive { get; set; } = string.Empty;
    public bool Current { get; set; }
    public string Platform { get; set; } = "desktop";
}

public record PasswordResetRequestResult(bool Accepted, string? MaskedEmail, string? ResetToken, DateTimeOffset? ExpiresAt)
{
    public static PasswordResetRequestResult Create(string? maskedEmail = null, string? resetToken = null, DateTimeOffset? expiresAt = null)
        => new(true, maskedEmail, resetToken, expiresAt);
}

public record AdminPasswordResetResult(
    SecurityUserDto User,
    bool Accepted,
    string? MaskedEmail,
    string? ResetToken,
    DateTimeOffset? ExpiresAt)
{
    public static AdminPasswordResetResult Create(
        SecurityUserDto user,
        string? maskedEmail,
        string? resetToken,
        DateTimeOffset? expiresAt)
        => new(user, true, maskedEmail, resetToken, expiresAt);
}

public record LoginResult(bool Success, string Message, SecurityUserDto? User, SecuritySession? Session, DateTimeOffset? LockedUntil)
{
    public static LoginResult Ok(SecurityUserDto user, SecuritySession session) => new(true, "Login realizado com sucesso.", user, session, null);
    public static LoginResult Fail(string message, DateTimeOffset? lockedUntil = null) => new(false, message, null, null, lockedUntil);
}

public class SecuritySession
{
    public string Id { get; set; } = string.Empty;
    public string UserId { get; set; } = string.Empty;
    public string Device { get; set; } = string.Empty;
    public string Location { get; set; } = string.Empty;
    public string Ip { get; set; } = string.Empty;
    public string LastActive { get; set; } = string.Empty;
    public string Platform { get; set; } = "desktop";
    public DateTimeOffset CreatedAt { get; set; }
}

internal class LoginAttemptBucket
{
    public int Count { get; set; }
    public DateTimeOffset FirstAttemptAt { get; set; }
    public DateTimeOffset LastAttemptAt { get; set; }
    public DateTimeOffset? LockedUntil { get; set; }
}

internal sealed class SecurityUserRecord
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = "empresa-principal";
    public string Cpf { get; set; } = string.Empty;
    public string Name { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Role { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string CreatedAt { get; set; } = string.Empty;
    public string LastLoginAt { get; set; } = string.Empty;
    public string PasswordHash { get; set; } = string.Empty;
    public bool MustChangePassword { get; set; }
}

internal sealed record PasswordResetTokenRecord(
    string Id,
    string UserId,
    string Email,
    string Cnpj,
    string TokenHash,
    DateTimeOffset CreatedAt,
    DateTimeOffset RequestedAt,
    DateTimeOffset ExpiresAt);

internal static class PasswordHasher
{
    private const int Iterations = 100_000;
    private const int SaltSize = 16;
    private const int KeySize = 32;

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(SaltSize);
        var key = Rfc2898DeriveBytes.Pbkdf2(password, salt, Iterations, HashAlgorithmName.SHA256, KeySize);
        return $"{Iterations}.{Convert.ToBase64String(salt)}.{Convert.ToBase64String(key)}";
    }

    public static bool Verify(string password, string hash)
    {
        var parts = hash.Split('.');
        if (parts.Length != 3) return false;
        if (!int.TryParse(parts[0], out var iterations)) return false;

        var salt = Convert.FromBase64String(parts[1]);
        var expectedKey = Convert.FromBase64String(parts[2]);
        var actualKey = Rfc2898DeriveBytes.Pbkdf2(password, salt, iterations, HashAlgorithmName.SHA256, expectedKey.Length);
        return CryptographicOperations.FixedTimeEquals(actualKey, expectedKey);
    }
}
