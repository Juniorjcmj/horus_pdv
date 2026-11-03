/*
 * Arquivo: JAVA/api/contrato/vetores-dotnet/Program.cs
 * Objetivo: gerar (sem argumentos / "gerar") e verificar ("verificar <arquivo>") vetores de compatibilidade
 *           entre a API .NET e a API Java: PBKDF2 de senhas, segredos enc:v1:, JWT e hash do token de redefinicao.
 * Usa as classes reais da API .NET (HorusJwtService, HorusSecretProtector, PasswordHasher via reflexao).
 */
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using Microsoft.AspNetCore.Hosting;

const string JwtSecret = "segredo-jwt-de-vetores-0123456789-abcdefghijklmnop";
const string EncryptionKey = "chave-de-cifra-de-vetores-0123456789-abcdefghijklm";
const string Issuer = "horus-pdv-api";
const string Audience = "horus-pdv-web";

var comando = args.Length > 0 ? args[0] : "gerar";
return comando switch
{
    "gerar" => Gerar(args.Length > 1 ? args[1] : "vetores.json"),
    "verificar" => Verificar(args.Length > 1 ? args[1] : "vetores-java.json"),
    _ => Uso()
};

static int Uso()
{
    Console.Error.WriteLine("Uso: dotnet run -- gerar [vetores.json] | verificar <vetores-java.json>");
    return 2;
}

static HorusSecurityOptions Opcoes(string jwtSecret, string encryptionKey)
{
    var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
    {
        ["Auth:JwtSecret"] = jwtSecret,
        ["Auth:Issuer"] = Issuer,
        ["Auth:Audience"] = Audience,
        ["Auth:SessionHours"] = "8",
        ["Security:EncryptionKey"] = encryptionKey
    }).Build();
    return new HorusSecurityOptions(configuration, new AmbienteFalso());
}

static Type TipoPasswordHasher() =>
    typeof(HorusSecurityOptions).Assembly.GetType("HORUSPDV_API.Repositories.DatabaseAccess.PasswordHasher")
    ?? throw new InvalidOperationException("PasswordHasher nao encontrado.");

static string Hash(string senha) =>
    (string)TipoPasswordHasher().GetMethod("Hash", BindingFlags.Public | BindingFlags.Static)!.Invoke(null, [senha])!;

static bool Verify(string senha, string hash) =>
    (bool)TipoPasswordHasher().GetMethod("Verify", BindingFlags.Public | BindingFlags.Static)!.Invoke(null, [senha, hash])!;

// Mesma conta de HorusSecurityStore.HashPasswordResetToken (metodo privado da classe).
static string HashTokenRedefinicao(string token, string jwtSecret)
{
    var payload = Encoding.UTF8.GetBytes($"{token.Trim()}:{jwtSecret}");
    return Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();
}

static int Gerar(string caminho)
{
    var senhas = new[] { "Senha#Quack2026", "Çenha-ção#é9ü", "12345678", "senha com espaços  " };
    var opcoes = Opcoes(JwtSecret, EncryptionKey);
    var semChave = Opcoes(JwtSecret, "");

    var protetor = new HorusSecretProtector(opcoes);
    var protetorFallback = new HorusSecretProtector(semChave);
    var textos = new[] { "smtp-senha-123", "Açaí & café #2026 ✓", "x" };

    var usuario = new SecurityUserDto
    {
        Id = "usr-1700000000000", CompanyId = "empresa-principal", Name = "João da Conceição",
        Email = "joao@exemplo.com.br", Role = "gerente"
    };
    var sessao = new SecuritySession { Id = "sess-0123456789abcdef0123456789abcdef", UserId = usuario.Id };
    var token = new HorusJwtService(opcoes).CreateToken(usuario, sessao, "200.1.1.1");
    var payload = JsonDocument.Parse(Base64UrlDecode(token.Split('.')[1])).RootElement;

    using var rsa = RSA.Create(2048);
    var requisicao = new CertificateRequest("CN=Quack Contrato Teste", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
    using var certificado = requisicao.CreateSelfSigned(new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero), new DateTimeOffset(2036, 1, 1, 0, 0, 0, TimeSpan.Zero));
    const string senhaPfx = "Certificado#Teste2026";
    var pfx = certificado.Export(X509ContentType.Pfx, senhaPfx);
    using var certificadoImportado = new X509Certificate2(pfx, senhaPfx, X509KeyStorageFlags.EphemeralKeySet);

    var vetores = new Dictionary<string, object?>
    {
        ["certificado"] = new { base64 = Convert.ToBase64String(pfx), senha = senhaPfx, thumbprint = certificadoImportado.Thumbprint, notAfter = new DateTimeOffset(certificadoImportado.NotAfter.ToUniversalTime(), TimeSpan.Zero).ToString("o") },
        ["jwtSecret"] = JwtSecret,
        ["encryptionKey"] = EncryptionKey,
        ["issuer"] = Issuer,
        ["audience"] = Audience,
        ["senhas"] = senhas.Select(s => new { senha = s, hash = Hash(s) }).ToArray(),
        ["segredos"] = textos.Select(t => new { texto = t, cifrado = protetor.Protect(t), usaChaveDeCifra = true })
            .Concat(textos.Select(t => new { texto = t, cifrado = protetorFallback.Protect(t), usaChaveDeCifra = false }))
            .ToArray(),
        ["jwt"] = new
        {
            token,
            iat = payload.GetProperty("iat").GetInt64(),
            exp = payload.GetProperty("exp").GetInt64(),
            userId = usuario.Id, companyId = usuario.CompanyId, name = usuario.Name, email = usuario.Email,
            role = usuario.Role, sessionId = sessao.Id, ip = "200.1.1.1", sessionTtl = "8h"
        },
        ["tokenRedefinicao"] = new
        {
            token = "tok-Redefinicao_abc-123",
            hash = HashTokenRedefinicao("tok-Redefinicao_abc-123", JwtSecret)
        }
    };

    File.WriteAllText(caminho, JsonSerializer.Serialize(vetores, new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }), new UTF8Encoding(false));
    Console.WriteLine($"Vetores gerados em {Path.GetFullPath(caminho)}");
    return 0;
}

static int Verificar(string caminho)
{
    var doc = JsonDocument.Parse(File.ReadAllText(caminho, Encoding.UTF8)).RootElement;
    var falhas = 0;
    void Checar(string nome, bool ok)
    {
        Console.WriteLine($"{(ok ? "OK   " : "FALHA")} {nome}");
        if (!ok) falhas++;
    }

    var opcoes = Opcoes(JwtSecret, EncryptionKey);
    var semChave = Opcoes(JwtSecret, "");

    foreach (var item in doc.GetProperty("senhas").EnumerateArray())
    {
        var senha = item.GetProperty("senha").GetString()!;
        var hash = item.GetProperty("hash").GetString()!;
        Checar($"PBKDF2 aceita senha certa ({senha})", Verify(senha, hash));
        Checar($"PBKDF2 recusa senha errada ({senha})", !Verify(senha + "x", hash));
    }

    foreach (var item in doc.GetProperty("segredos").EnumerateArray())
    {
        var texto = item.GetProperty("texto").GetString()!;
        var protetor = new HorusSecretProtector(item.GetProperty("usaChaveDeCifra").GetBoolean() ? opcoes : semChave);
        string? lido = null;
        try { lido = protetor.Unprotect(item.GetProperty("cifrado").GetString()!); } catch { }
        Checar($"enc:v1 decifra ({texto})", lido == texto);
    }

    var jwt = doc.GetProperty("jwt");
    var autenticado = new HorusJwtService(opcoes).ValidateToken(jwt.GetProperty("token").GetString()!);
    Checar("JWT do Java valido no .NET", autenticado is not null
        && autenticado.Id == jwt.GetProperty("userId").GetString()
        && autenticado.SessionId == jwt.GetProperty("sessionId").GetString()
        && autenticado.Role == jwt.GetProperty("role").GetString()
        && autenticado.Name == jwt.GetProperty("name").GetString());

    var red = doc.GetProperty("tokenRedefinicao");
    Checar("Hash do token de redefinicao igual",
        HashTokenRedefinicao(red.GetProperty("token").GetString()!, JwtSecret) == red.GetProperty("hash").GetString());

    Console.WriteLine(falhas == 0 ? "Todos os vetores do Java conferem." : $"{falhas} verificacao(oes) falharam.");
    return falhas == 0 ? 0 : 1;
}

static byte[] Base64UrlDecode(string value)
{
    var padded = value.Replace('-', '+').Replace('_', '/');
    padded += (padded.Length % 4) switch { 2 => "==", 3 => "=", _ => "" };
    return Convert.FromBase64String(padded);
}

internal sealed class AmbienteFalso : IWebHostEnvironment
{
    public string ApplicationName { get; set; } = "vetores";
    public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    public string WebRootPath { get; set; } = "";
    public string EnvironmentName { get; set; } = "Development";
    public string ContentRootPath { get; set; } = "";
    public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
}
