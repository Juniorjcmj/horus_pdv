#!/usr/bin/env bash
# Gera src/main/resources/db/migration/V1__esquema_inicial.sql a partir do banco criado pela API .NET atual.
#
# Pre-requisitos: ambiente do contrato no ar (contrato/ambiente-dotnet/subir.sh) e o SqlPackage:
#   dotnet tool install --tool-path <pasta> microsoft.sqlpackage     (DOTNET_ROLL_FORWARD=Major se so houver .NET 10)
# Uso: SQLPACKAGE=<pasta>/sqlpackage.exe contrato/esquema/gerar-v1.sh
#
# Passos: extrai um .dacpac do banco HorusPdv (so esquema), gera com /Action:Script o script de criacao contra um
# banco vazio ("Vazio") e limpa o resultado: sem variaveis SQLCMD, sem USE, sem __MigrationHistory, sem PRINT e
# sem o bloco de verificacao com cursor (os WITH CHECK CHECK CONSTRAINT simples ficam, para as FKs ficarem confiaveis).
set -euo pipefail
export MSYS_NO_PATHCONV=1
export DOTNET_ROLL_FORWARD=Major

AQUI="$(cd "$(dirname "$0")" && pwd)"
SQLPACKAGE="${SQLPACKAGE:?informe SQLPACKAGE=<caminho do sqlpackage>}"
TMP="${TMPDIR:-/tmp}"
SA_PWD='Contrato#Quack2026'
DESTINO="$AQUI/../../src/main/resources/db/migration/V1__esquema_inicial.sql"
win() { local d; d="$(cd "$(dirname "$1")" && (pwd -W 2>/dev/null || pwd))"; echo "$d/$(basename "$1")"; }

docker exec contrato-sql /opt/mssql-tools18/bin/sqlcmd -C -I -S localhost -U sa -P "$SA_PWD" \
  -Q "IF DB_ID('Vazio') IS NULL CREATE DATABASE Vazio"

DACPAC="$TMP/horus-esquema.dacpac"
BRUTO="$TMP/esquema-bruto.sql"
CONEXAO="Server=localhost,14334;User Id=sa;Password=$SA_PWD;TrustServerCertificate=True;Encrypt=False"

"$SQLPACKAGE" /Action:Extract "/SourceConnectionString:$CONEXAO;Database=HorusPdv" "/TargetFile:$(win "$DACPAC")"
"$SQLPACKAGE" /Action:Script "/SourceFile:$(win "$DACPAC")" "/TargetConnectionString:$CONEXAO;Database=Vazio" \
  "/OutputPath:$(win "$BRUTO")" /p:IncludeCompositeObjects=False /p:ScriptDatabaseOptions=False

mkdir -p "$(dirname "$DESTINO")"
{
  echo "-- Esquema inicial da API Quack PDV: tabelas, colunas, defaults, PK, FK, UNIQUE, indices e a funcao"
  echo "-- fn_HorusParseDecimal, exatamente como o inicializador da API .NET os cria num banco do zero."
  echo "-- Gerado por contrato/esquema/gerar-v1.sh. NUNCA edite depois de aplicado (o Flyway valida o checksum)."
  tr -d '\r' < "$BRUTO" | sed '1s/^\xEF\xBB\xBF//' | awk '
    BEGIN { corpo = 0; pulando = 0 }
    /^USE \[\$\(DatabaseName\)\];/ { corpo++; next }          # o primeiro USE encerra o cabecalho SQLCMD
    corpo == 0 { next }
    /^CREATE TABLE \[#__checkStatus\]/ { fim = 1 }
    fim == 1 { next }
    /__MigrationHistory/ && /^CREATE TABLE/ { pulando = 1 }
    /^ALTER TABLE \[dbo\]\.\[__MigrationHistory\]/ { pulando = 2 }
    pulando == 1 { if ($0 ~ /^\);/) pulando = 0; next }
    pulando == 2 { if ($0 ~ /;[ \t]*$/) pulando = 0; next }
    /^PRINT N/ { next }
    /^EXECUTE sp_addextendedproperty/ { next }
    { print }
  ' | cat -s
  echo "GO"
} > "$DESTINO"

echo "Gerado: $DESTINO ($(wc -l < "$DESTINO") linhas, $(grep -c '^CREATE TABLE' "$DESTINO") tabelas)"
