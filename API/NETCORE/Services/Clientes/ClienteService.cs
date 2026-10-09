/**
 * Arquivo: API/NETCORE/Services/Clientes/ClienteService.cs
 * Objetivo: centraliza regras de negócio de cadastro e manutenção de clientes antes do acesso ao banco ou resposta HTTP.
 * Entradas esperadas: recebe requisições já validadas pelos controladores e aplica consistência operacional do domínio.
 */
using HORUSPDV_API.Models.Clientes;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Clientes;

public class ClienteService(ClienteAB clientesAB) : IClienteService
{
    public async Task<List<ClienteModel>> ListarAsync(string companyId)
        => (await clientesAB.ListarAsync(companyId)).Select(ToModel).ToList();

    public async Task<ClienteModel> CriarAsync(string companyId, ClienteRequest request)
    {
        Validate(request);
        await ValidateDuplicatesAsync(companyId, request, null);
        var customer = MapRequest($"cl-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}", request);
        return ToModel(await clientesAB.SalvarAsync(companyId, customer));
    }

    public async Task<ClienteModel?> AtualizarAsync(string companyId, string id, ClienteRequest request, bool podeAlterarFiado = true)
    {
        Validate(request);
        var current = await clientesAB.ObterAsync(companyId, id);
        if (current is null)
        {
            return null;
        }

        // Limite de crédito é parâmetro do fiado/crediário: quem só consulta o fiado (gerente) não altera.
        if (!podeAlterarFiado && request.LimiteCredito != current.LimiteCredito)
        {
            throw new InvalidOperationException(
                "Seu perfil só consulta fiado/crediário: o limite de crédito do cliente não pode ser alterado.");
        }

        await ValidateDuplicatesAsync(companyId, request, id);
        return ToModel(await clientesAB.SalvarAsync(companyId, MapRequest(id, request)));
    }

    public async Task<bool> ExcluirAsync(string companyId, string id, bool podeAlterarFiado = true)
    {
        if (!podeAlterarFiado)
        {
            var current = await clientesAB.ObterAsync(companyId, id);
            if (current is not null && current.SaldoDevedor > 0)
            {
                throw new InvalidOperationException(
                    "Seu perfil só consulta fiado/crediário: cliente com saldo devedor não pode ser excluído.");
            }
        }

        return await clientesAB.ExcluirAsync(companyId, id);
    }

    private static void Validate(ClienteRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.CustomerName) || request.CustomerName.Trim().Length < 3)
        {
            throw new InvalidOperationException("Nome do cliente deve ter no minimo 3 caracteres.");
        }

        var documentDigits = OnlyDigits(request.Document);
        if (!string.IsNullOrWhiteSpace(request.Document) && documentDigits.Length != 11 && documentDigits.Length != 14)
        {
            throw new InvalidOperationException("Documento do cliente invalido.");
        }

        if (string.IsNullOrWhiteSpace(request.Telephone) && string.IsNullOrWhiteSpace(request.Cellphone))
        {
            throw new InvalidOperationException("Informe um telefone ou celular com DDD.");
        }

        foreach (var phone in new[] { request.Telephone, request.Cellphone })
        {
            if (!string.IsNullOrWhiteSpace(phone) && OnlyDigits(phone).Length is not (10 or 11))
            {
                throw new InvalidOperationException("Informe um telefone ou celular válido com DDD (10 ou 11 dígitos).");
            }
        }

        if (!string.IsNullOrWhiteSpace(request.Email) && !request.Email.Contains('@'))
        {
            throw new InvalidOperationException("E-mail do cliente invalido.");
        }
    }

    private async Task ValidateDuplicatesAsync(string companyId, ClienteRequest request, string? currentId)
    {
        var document = OnlyDigits(request.Document);
        if (document.Length == 0) return;
        var customers = await clientesAB.ListarAsync(companyId);
        if (customers.Any(item => item.Id != currentId && OnlyDigits(item.Document) == document))
        {
            throw new InvalidOperationException("Já existe cliente com este documento.");
        }
    }

    private static ClienteAD MapRequest(string id, ClienteRequest request) => new()
    {
        Id = id,
        CustomerName = request.CustomerName.Trim(),
        Document = request.Document?.Trim() ?? string.Empty,
        BirthDate = request.BirthDate ?? string.Empty,
        Age = request.Age ?? string.Empty,
        Cep = request.Cep ?? string.Empty,
        City = request.City ?? string.Empty,
        State = request.State ?? string.Empty,
        Address = request.Address ?? string.Empty,
        Neighborhood = request.Neighborhood ?? string.Empty,
        StreetComplement = request.StreetComplement ?? string.Empty,
        Number = request.Number ?? string.Empty,
        ReferencePoint = request.ReferencePoint ?? string.Empty,
        Telephone = request.Telephone?.Trim() ?? string.Empty,
        Cellphone = request.Cellphone?.Trim() ?? string.Empty,
        Email = request.Email ?? string.Empty,
        IndIeDest = request.IndIeDest,
        InscricaoEstadual = string.IsNullOrWhiteSpace(request.InscricaoEstadual) ? null : request.InscricaoEstadual.Trim(),
        CodigoMunicipioIbge = string.IsNullOrWhiteSpace(request.CodigoMunicipioIbge) ? null : request.CodigoMunicipioIbge.Trim(),
        LimiteCredito = request.LimiteCredito
    };

    private static ClienteModel ToModel(ClienteAD source) => new()
    {
        Id = source.Id,
        CustomerName = source.CustomerName,
        Document = source.Document,
        BirthDate = source.BirthDate,
        Age = source.Age,
        Cep = source.Cep,
        City = source.City,
        State = source.State,
        Address = source.Address,
        Neighborhood = source.Neighborhood,
        StreetComplement = source.StreetComplement,
        Number = source.Number,
        ReferencePoint = source.ReferencePoint,
        Telephone = source.Telephone,
        Cellphone = source.Cellphone,
        Email = source.Email,
        IndIeDest = source.IndIeDest,
        InscricaoEstadual = source.InscricaoEstadual,
        CodigoMunicipioIbge = source.CodigoMunicipioIbge,
        LimiteCredito = source.LimiteCredito,
        SaldoDevedor = source.SaldoDevedor
    };

    private static string OnlyDigits(string? value) => new((value ?? string.Empty).Where(char.IsDigit).ToArray());
}
