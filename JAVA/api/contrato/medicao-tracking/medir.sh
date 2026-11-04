#!/usr/bin/env bash
# Mede o custo do Change Tracking + ALLOW_SNAPSHOT_ISOLATION (tarefa 7.3 do change api-java-modo-loja) num SQL Server 2022
# em CONTAINER descartavel (nunca no banco real). Dois bancos com o MESMO esquema da API (db/migration V1..V4):
#   SemTracking  - como esta hoje a producao;
#   ComTracking  - com o db/nuvem/V1 aplicado (tabelas Sync*, Change Tracking de 3 dias e snapshot).
# Carga: N vendas (padrao 10000), cada uma uma transacao com 11 linhas gravadas (vendas.sql). Alterna os bancos em R
# rodadas (padrao 3) e informa a mediana. Depois mede o version store com um leitor SNAPSHOT aberto e o espaco.
#
# Uso: bash JAVA/api/contrato/medicao-tracking/medir.sh [N] [R]      (Docker, Git Bash)
set -euo pipefail
export MSYS_NO_PATHCONV=1
AQUI="$(cd "$(dirname "$0")" && pwd)"
RES="$(cd "$AQUI/../../src/main/resources/db" && pwd)"
N="${1:-10000}"
R="${2:-3}"
C="quack-medicao"
PWD_SA='Medicao#Quack2026'
SQLCMD=(docker exec "$C" /opt/mssql-tools18/bin/sqlcmd -C -b -S localhost -U sa -P "$PWD_SA")

limpar() { docker rm -f "$C" >/dev/null 2>&1 || true; }
trap limpar EXIT
limpar
echo ">> Subindo SQL Server 2022 ($C)"
docker run -d --name "$C" -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD="$PWD_SA" -e MSSQL_PID=Express mcr.microsoft.com/mssql/server:2022-latest >/dev/null
for _ in $(seq 1 60); do "${SQLCMD[@]}" -Q "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done
"${SQLCMD[@]}" -Q "SELECT @@VERSION" -h -1 | head -1

hp() { cygpath -w "$1" 2>/dev/null || echo "$1"; }   # docker.exe quer caminho do Windows
docker cp "$(hp "$AQUI/preparar.sql")" "$C:/tmp/preparar.sql"
docker cp "$(hp "$AQUI/vendas.sql")" "$C:/tmp/vendas.sql"
for f in migration/V1__esquema_inicial.sql migration/V2__dados_referencia.sql migration/V3__cadastros_dados_referencia.sql \
         migration/V4__lote_baixa_pendencias.sql nuvem/V1__sync_nuvem.sql; do
  docker cp "$(hp "$RES/$f")" "$C:/tmp/$(basename "$f")"
done

for BD in SemTracking ComTracking; do
  "${SQLCMD[@]}" -Q "CREATE DATABASE [$BD]"
  for f in V1__esquema_inicial V2__dados_referencia V3__cadastros_dados_referencia V4__lote_baixa_pendencias; do
    "${SQLCMD[@]}" -d "$BD" -I -i "/tmp/$f.sql" >/dev/null
  done
  "${SQLCMD[@]}" -d "$BD" -I -i /tmp/preparar.sql -h -1 -W | tail -3
done
echo ">> Aplicando db/nuvem/V1 (Sync*, Change Tracking, snapshot) so no ComTracking"
"${SQLCMD[@]}" -d ComTracking -I -i /tmp/V1__sync_nuvem.sql >/dev/null
"${SQLCMD[@]}" -d ComTracking -h -1 -W -Q "SET NOCOUNT ON; SELECT 'tabelas com tracking: ' + CAST(COUNT(*) AS varchar(10)) FROM sys.change_tracking_tables; SELECT 'snapshot: ' + snapshot_isolation_state_desc FROM sys.databases WHERE name = DB_NAME()"

# Estado inicial de espaco (antes das vendas)
espaco() { # banco
  "${SQLCMD[@]}" -d "$1" -h -1 -W -s "|" -Q "SET NOCOUNT ON;
   SELECT 'dados_MB|' + CAST(CAST(SUM(CASE WHEN type = 0 THEN size END) * 8 / 1024.0 AS DECIMAL(12,1)) AS varchar(20)) FROM sys.database_files
   UNION ALL SELECT 'log_MB|' + CAST(CAST(SUM(CASE WHEN type = 1 THEN size END) * 8 / 1024.0 AS DECIMAL(12,1)) AS varchar(20)) FROM sys.database_files
   UNION ALL SELECT 'usado_dados_MB|' + CAST(CAST(SUM(a.used_pages) * 8 / 1024.0 AS DECIMAL(12,2)) AS varchar(20)) FROM sys.allocation_units a
   UNION ALL SELECT 'change_tracking_internas_KB|' + CAST(COALESCE(SUM(ps.used_page_count) * 8, 0) AS varchar(20))
     FROM sys.internal_tables it JOIN sys.dm_db_partition_stats ps ON ps.object_id = it.object_id WHERE it.internal_type_desc = 'CHANGE_TRACKING'"
}
echo ">> Espaco ANTES (so cadastro): "; for BD in SemTracking ComTracking; do echo "[$BD]"; espaco "$BD"; done

declare -A TEMPOS
echo ">> Tempo: $R rodadas alternadas de $N vendas por banco"
for r in $(seq 1 "$R"); do
  for BD in SemTracking ComTracking; do
    completa="$("${SQLCMD[@]}" -d "$BD" -I -h -1 -W -s "|" -v N="$N" PREFIXO="r$r" -i /tmp/vendas.sql 2>&1)" || { echo "$completa"; exit 1; }
    saida="$(echo "$completa" | tail -1)"
    ms="$(echo "$saida" | cut -d'|' -f3)"
    echo "  rodada $r  $BD  ${ms} ms  ($(( N * 1000 / (ms>0?ms:1) )) vendas/s)"
    TEMPOS[$BD]+="$ms "
  done
done
mediana() { echo "$@" | tr ' ' '\n' | grep -v '^$' | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'; }
MS_SEM="$(mediana ${TEMPOS[SemTracking]})"; MS_COM="$(mediana ${TEMPOS[ComTracking]})"
echo "MEDIANA  SemTracking=${MS_SEM} ms  ComTracking=${MS_COM} ms  custo=$(awk -v a="$MS_SEM" -v b="$MS_COM" 'BEGIN{printf "%.1f%%", (b-a)*100/a}')"

echo ">> Espaco DEPOIS de $((N * R)) vendas por banco:"; for BD in SemTracking ComTracking; do echo "[$BD]"; espaco "$BD"; done
"${SQLCMD[@]}" -d ComTracking -h -1 -W -s "|" -Q "SET NOCOUNT ON; SELECT 'linhas_Vendas|' + CAST(COUNT(*) AS varchar(20)) FROM dbo.Vendas"

echo ">> Version store: baseline do ComTracking sem leitor snapshot (apos a carga acima)"
"${SQLCMD[@]}" -h -1 -W -Q "SET NOCOUNT ON; SELECT 'version_store_MB agora: ' + CAST(SUM(version_store_reserved_page_count) * 8 / 1024.0 AS varchar(20)) FROM tempdb.sys.dm_db_file_space_usage"

echo ">> Version store com um leitor SNAPSHOT aberto durante a carga (ComTracking, $N vendas)"
docker exec -d "$C" /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P "$PWD_SA" -d ComTracking -Q \
  "SET TRANSACTION ISOLATION LEVEL SNAPSHOT; BEGIN TRAN; SELECT COUNT(*) FROM dbo.Produtos; WAITFOR DELAY '00:02:30'; COMMIT;"
sleep 3
"${SQLCMD[@]}" -d ComTracking -I -h -1 -W -s "|" -v N="$N" PREFIXO="leitor" -i /tmp/vendas.sql | tail -1 | awk -F'|' '{printf "  com leitor aberto: %s ms; pico version store amostrado (a cada 1000 vendas): %.1f MB\n", $3, $4}'
"${SQLCMD[@]}" -h -1 -W -Q "SET NOCOUNT ON; SELECT '  version store logo apos a carga (leitor ainda aberto): ' + CAST(SUM(version_store_reserved_page_count) * 8 / 1024.0 AS varchar(20)) + ' MB' FROM tempdb.sys.dm_db_file_space_usage"
echo "   (aguardando o leitor terminar e a limpeza do version store...)"
sleep 170
"${SQLCMD[@]}" -h -1 -W -Q "SET NOCOUNT ON; SELECT '  version store depois que o leitor fechou: ' + CAST(SUM(version_store_reserved_page_count) * 8 / 1024.0 AS varchar(20)) + ' MB' FROM tempdb.sys.dm_db_file_space_usage"
"${SQLCMD[@]}" -h -1 -W -Q "SET NOCOUNT ON; SELECT '  tempdb arquivos MB: ' + CAST(SUM(size) * 8 / 1024.0 AS varchar(20)) FROM tempdb.sys.database_files"
echo ">> Fim."
