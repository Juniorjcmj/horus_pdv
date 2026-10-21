using System.Security.Cryptography;
using System.Text;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;
public sealed class CreditInstallationGuardTests
{
    [Fact]
    public void WindowsDpapiWitnessRejectsRestoredLedgerAndSecondInstance()
    {
        if(!OperatingSystem.IsWindows())return;
        var folder=Path.Combine(Path.GetTempPath(),Guid.NewGuid().ToString());Directory.CreateDirectory(folder);
        var witness=Path.Combine(folder,"host.witness");var path=Path.Combine(folder,"credit.db");
        var options=Options.Create(new GatewayOptions{CompanyId="c",StoreId="s",GatewayId="g",CreditEpoch="e",CreditEnabled=true,CreditWitnessPath=witness,DatabasePath=path});
        File.WriteAllBytes(witness,ProtectedData.Protect(Encoding.UTF8.GetBytes("c|s|g|e|0"),null,DataProtectionScope.LocalMachine));
        var database=new GatewayDatabase(options,NullLogger<GatewayDatabase>.Instance);database.Initialize();
        try
        {
            using(var guard=new CreditInstallationGuard(options))
            {
                guard.Start();using var duplicate=new CreditInstallationGuard(options);Assert.Throws<IOException>(()=>duplicate.Start());
                using var db=database.OpenConnection();using var tx=db.BeginTransaction();guard.Advance(db,tx);tx.Commit();
            }
            using(var restart=new CreditInstallationGuard(options))
            {
                restart.Start();using var db=database.OpenConnection();using var tx=db.BeginTransaction();restart.Advance(db,tx);tx.Commit();
            }
            using(var db=database.OpenConnection())using(var cmd=db.CreateCommand()){cmd.CommandText="UPDATE CreditRevision SET Revision=0";cmd.ExecuteNonQuery();}
            using var restored=new CreditInstallationGuard(options);restored.Start();
            using var restoredDb=database.OpenConnection();using var restoredTx=restoredDb.BeginTransaction();
            Assert.Throws<InvalidOperationException>(()=>restored.Advance(restoredDb,restoredTx));
        }
        finally{Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();foreach(var file in Directory.GetFiles(folder))File.Delete(file);Directory.Delete(folder);}
    }
}
