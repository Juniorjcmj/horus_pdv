using System.Security.Cryptography;
using System.Text;
using HorusGateway.Configuration;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

// The witness lives OUTSIDE the database backup. It is protected by machine DPAPI and service ACL.
// Write-ahead revision intentionally fails closed after a crash between witness write and DB commit.
public sealed class CreditInstallationGuard(IOptions<GatewayOptions> options) : IDisposable
{
    private readonly GatewayOptions config=options.Value;
    private FileStream? lease;
    public void Start()
    {
        if(!config.CreditEnabled)return;
        if(!OperatingSystem.IsWindows() || !Path.IsPathFullyQualified(config.CreditWitnessPath) || string.IsNullOrWhiteSpace(config.GatewayId) || string.IsNullOrWhiteSpace(config.CreditEpoch))throw new InvalidOperationException("CREDIT_INSTALLATION_NOT_PROVISIONED");
        Directory.CreateDirectory(Path.GetDirectoryName(config.CreditWitnessPath)!);
        lease=new FileStream(config.CreditWitnessPath+".lock",FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);
    }
    internal void Advance(SqliteConnection db,SqliteTransaction tx)
    {
        if(!config.CreditEnabled)return;
        if(lease is null || !OperatingSystem.IsWindows())throw new InvalidOperationException("CREDIT_INSTALLATION_BLOCKED");
        using var read=db.CreateCommand();read.Transaction=tx;read.CommandText="SELECT Revision FROM CreditRevision WHERE Id=1";var revision=Convert.ToInt64(read.ExecuteScalar());
        var identity=$"{config.CompanyId}|{config.StoreId}|{config.GatewayId}|{config.CreditEpoch}|";
        var expected=identity+revision;
        if(!File.Exists(config.CreditWitnessPath))throw new InvalidOperationException("CREDIT_WITNESS_MISSING_RECOVERY_REQUIRED");
        var witness=Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(config.CreditWitnessPath),null,DataProtectionScope.LocalMachine));
        if(witness!=expected)throw new InvalidOperationException("CREDIT_RESTORE_DETECTED_RECOVERY_REQUIRED");
        var next=ProtectedData.Protect(Encoding.UTF8.GetBytes(identity+checked(revision+1)),null,DataProtectionScope.LocalMachine);
        using(var file=new FileStream(config.CreditWitnessPath,FileMode.Create,FileAccess.Write,FileShare.None,4096,FileOptions.WriteThrough)){file.Write(next);file.Flush(true);}
        using var update=db.CreateCommand();update.Transaction=tx;update.CommandText="UPDATE CreditRevision SET Revision=Revision+1 WHERE Id=1";update.ExecuteNonQuery();
    }
    public void Dispose()=>lease?.Dispose();
}
