/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/ClienteAB.cs
 * Objetivo: concentra comandos SQL e persistência de cadastro e manutenção de clientes.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class ClienteAB(Connection connection)
{
    private const string Columns = """
        Id, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood,
        StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email,
        IndIeDest, InscricaoEstadual, CodigoMunicipioIbge
        """;

    public async Task<List<ClienteAD>> ListarAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Clientes
            WHERE CompanyId = @CompanyId
            ORDER BY CustomerName;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<ClienteAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    /// <summary>Usada pelo módulo fiscal (DocumentoFiscalAB) para resolver o destinatário por CPF/CNPJ.</summary>
    public async Task<ClienteAD?> ObterPorDocumentoAsync(string companyId, string document)
    {
        var digits = new string(document.Where(char.IsDigit).ToArray());
        var sql = $"""
            SELECT {Columns}
            FROM Clientes
            WHERE CompanyId = @CompanyId
              AND REPLACE(REPLACE(REPLACE(Document, '.', ''), '-', ''), '/', '') = @Document;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Document", digits);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    public async Task<ClienteAD?> ObterAsync(string companyId, string id)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Clientes
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    public async Task<ClienteAD> SalvarAsync(string companyId, ClienteAD customer)
    {
        const string sql = """
            IF EXISTS (SELECT 1 FROM Clientes WHERE Id = @Id AND CompanyId = @CompanyId)
            BEGIN
                UPDATE Clientes
                   SET CustomerName = @CustomerName,
                       Document = @Document,
                       BirthDate = @BirthDate,
                       Age = @Age,
                       Cep = @Cep,
                       City = @City,
                       State = @State,
                       Address = @Address,
                       Neighborhood = @Neighborhood,
                       StreetComplement = @StreetComplement,
                       Number = @Number,
                       ReferencePoint = @ReferencePoint,
                       Telephone = @Telephone,
                       Cellphone = @Cellphone,
                       Email = @Email,
                       IndIeDest = @IndIeDest,
                       InscricaoEstadual = @InscricaoEstadual,
                       CodigoMunicipioIbge = @CodigoMunicipioIbge
                 WHERE Id = @Id AND CompanyId = @CompanyId;
            END
            ELSE
            BEGIN
                INSERT INTO Clientes
                    (Id, CompanyId, CustomerName, Document, BirthDate, Age, Cep, City, State, Address, Neighborhood,
                     StreetComplement, Number, ReferencePoint, Telephone, Cellphone, Email,
                     IndIeDest, InscricaoEstadual, CodigoMunicipioIbge)
                VALUES
                    (@Id, @CompanyId, @CustomerName, @Document, @BirthDate, @Age, @Cep, @City, @State, @Address, @Neighborhood,
                     @StreetComplement, @Number, @ReferencePoint, @Telephone, @Cellphone, @Email,
                     @IndIeDest, @InscricaoEstadual, @CodigoMunicipioIbge);
            END;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        AddParameters(command, customer);
        await command.ExecuteNonQueryAsync();
        return customer;
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand("DELETE FROM Clientes WHERE Id = @Id AND CompanyId = @CompanyId;", db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    private static void AddParameters(SqlCommand command, ClienteAD customer)
    {
        command.Parameters.AddWithValue("@Id", customer.Id);
        command.Parameters.AddWithValue("@CustomerName", customer.CustomerName);
        command.Parameters.AddWithValue("@Document", customer.Document);
        command.Parameters.AddWithValue("@BirthDate", customer.BirthDate);
        command.Parameters.AddWithValue("@Age", customer.Age);
        command.Parameters.AddWithValue("@Cep", customer.Cep);
        command.Parameters.AddWithValue("@City", customer.City);
        command.Parameters.AddWithValue("@State", customer.State);
        command.Parameters.AddWithValue("@Address", customer.Address);
        command.Parameters.AddWithValue("@Neighborhood", customer.Neighborhood);
        command.Parameters.AddWithValue("@StreetComplement", customer.StreetComplement);
        command.Parameters.AddWithValue("@Number", customer.Number);
        command.Parameters.AddWithValue("@ReferencePoint", customer.ReferencePoint);
        command.Parameters.AddWithValue("@Telephone", customer.Telephone);
        command.Parameters.AddWithValue("@Cellphone", customer.Cellphone);
        command.Parameters.AddWithValue("@Email", customer.Email);
        command.Parameters.AddWithValue("@IndIeDest", customer.IndIeDest);
        command.Parameters.AddWithValue("@InscricaoEstadual", (object?)customer.InscricaoEstadual ?? DBNull.Value);
        command.Parameters.AddWithValue("@CodigoMunicipioIbge", (object?)customer.CodigoMunicipioIbge ?? DBNull.Value);
    }

    private static ClienteAD Map(SqlDataReader source) => new()
    {
        Id = ReadString(source, "Id"),
        CustomerName = ReadString(source, "CustomerName"),
        Document = ReadString(source, "Document"),
        BirthDate = ReadString(source, "BirthDate"),
        Age = ReadString(source, "Age"),
        Cep = ReadString(source, "Cep"),
        City = ReadString(source, "City"),
        State = ReadString(source, "State"),
        Address = ReadString(source, "Address"),
        Neighborhood = ReadString(source, "Neighborhood"),
        StreetComplement = ReadString(source, "StreetComplement"),
        Number = ReadString(source, "Number"),
        ReferencePoint = ReadString(source, "ReferencePoint"),
        Telephone = ReadString(source, "Telephone"),
        Cellphone = ReadString(source, "Cellphone"),
        Email = ReadString(source, "Email"),
        IndIeDest = (byte)ReadInt(source, "IndIeDest"),
        InscricaoEstadual = ReadNullableString(source, "InscricaoEstadual"),
        CodigoMunicipioIbge = ReadNullableString(source, "CodigoMunicipioIbge")
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

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }
}
