#!/usr/bin/env bash
# Tarefa 7.3 (parte 2): roda a SUITE DE CONTRATO da .NET (GravadorDoContrato, todos os grupos) contra uma API .NET real
# com e sem o db/nuvem/V1 aplicado (Change Tracking + snapshot), e compara tempo e gravacoes.
# Ambiente proprio e descartavel (nao mexe nos conteineres contrato-* nem pdv-teste-*): rede/sql/api com prefixo "medicao-".
# As gravacoes vao para uma COPIA de JAVA/api (nao sobrescreve src/test/resources do repositorio).
#
# Uso: bash JAVA/api/contrato/medicao-tracking/suite-dotnet.sh sem|com [pasta-de-saida]
# Requer a imagem quack-api-dotnet:contrato (contrato/ambiente-dotnet/subir.sh a constroi), Docker, Maven offline e JDK 21.
set -euo pipefail
export MSYS_NO_PATHCONV=1
MODO="${1:?informe sem ou com}"
SAIDA="${2:-${TMPDIR:-/tmp}/medicao-suite-$MODO}"
AQUI="$(cd "$(dirname "$0")" && pwd)"
API="$(cd "$AQUI/../.." && pwd)"
REDE="medicao-net"; SQL="medicao-dotnet-sql"; WEB="medicao-dotnet-api"
SA='Contrato#Quack2026'; PORTA_SQL=14341; PORTA_API=5281
SQLCMD=(docker exec "$SQL" /opt/mssql-tools18/bin/sqlcmd -C -I -b -S localhost -U sa -P "$SA")
hp() { cygpath -w "$1" 2>/dev/null || echo "$1"; }
limpar() { docker rm -f "$WEB" "$SQL" >/dev/null 2>&1 || true; docker network rm "$REDE" >/dev/null 2>&1 || true; }
trap limpar EXIT
limpar
docker network create "$REDE" >/dev/null

docker run -d --name "$SQL" --network "$REDE" -p "$PORTA_SQL:1433" -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD="$SA" \
  mcr.microsoft.com/mssql/server:2022-latest >/dev/null
for _ in $(seq 1 60); do "${SQLCMD[@]}" -Q "SELECT 1" >/dev/null 2>&1 && break; sleep 2; done

subir_api() {
  docker rm -f "$WEB" >/dev/null 2>&1 || true
  docker run -d --name "$WEB" --network "$REDE" -p "$PORTA_API:8080" \
    -e ASPNETCORE_ENVIRONMENT=Production \
    -e "ConnectionStrings__HorusPdv=Server=$SQL,1433;Database=HorusPdv;User Id=sa;Password=$SA;TrustServerCertificate=True;Encrypt=True;MultipleActiveResultSets=True" \
    -e Auth__JwtSecret=segredo-jwt-do-contrato-0123456789-abcdefghijkl \
    -e Security__EncryptionKey=chave-de-cifra-do-contrato-0123456789-abcdefgh \
    -e Recaptcha__Enabled=false -e Security__RateLimit__MaxRequests=5000 -e Email__Enabled=false \
    quack-api-dotnet:contrato >/dev/null
  local codigo=""
  for _ in $(seq 1 90); do
    codigo="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORTA_API/api/Auth/me" || true)"
    [ "$codigo" = "401" ] && return 0
    sleep 2
  done
  echo "API .NET nao subiu (codigo $codigo)"; docker logs --tail 40 "$WEB"; exit 1
}

echo ">> [$MODO] subindo a API .NET (cria o esquema na primeira vez)"
subir_api
if [ "$MODO" = "com" ]; then
  echo ">> [com] aplicando db/nuvem/V1 (Sync*, Change Tracking, snapshot) e reiniciando a .NET sobre o banco com tracking"
  docker cp "$(hp "$API/src/main/resources/db/nuvem/V1__sync_nuvem.sql")" "$SQL:/tmp/V1__sync_nuvem.sql"
  "${SQLCMD[@]}" -d HorusPdv -i /tmp/V1__sync_nuvem.sql >/dev/null
  "${SQLCMD[@]}" -d HorusPdv -h -1 -W -Q "SET NOCOUNT ON; SELECT 'tabelas com tracking: ' + CAST(COUNT(*) AS varchar(10)) FROM sys.change_tracking_tables"
  subir_api
fi
docker cp "$(hp "$API/contrato/seed/seed-contrato.sql")" "$SQL:/tmp/seed-contrato.sql"
"${SQLCMD[@]}" -d HorusPdv -i /tmp/seed-contrato.sql >/dev/null

echo ">> [$MODO] copiando JAVA/api para $SAIDA (sem target)"
rm -rf "$SAIDA"; mkdir -p "$SAIDA"
(cd "$API" && tar --exclude=./target --exclude=./backups --exclude=./graphify-out --exclude=./instalador-loja -cf - .) | tar -xf - -C "$SAIDA"

echo ">> [$MODO] suite de contrato da .NET (GravadorDoContrato, todos os grupos)"
(cd "$SAIDA" && env -u MSYS_NO_PATHCONV mvn -B -o -q test-compile 2>&1 | tail -5)   # compila antes, para medir so a suite
INICIO=$(date +%s)
(cd "$SAIDA" && env -u MSYS_NO_PATHCONV mvn -B -o -q test -Dtest=GravadorDoContrato -Dgroups=gravar-contrato -Dexcluir.grupos=nenhum -Dcontrato.gravar=true \
   -Dcontrato.url="http://localhost:$PORTA_API" \
   -Dcontrato.sql="jdbc:sqlserver://localhost:$PORTA_SQL;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true" \
   -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -15)
FIM=$(date +%s)
echo "SUITE_DOTNET modo=$MODO segundos_suite=$((FIM - INICIO)) "
echo "gravacoes: $(find "$SAIDA/src/test/resources/contrato/gravacoes" -name '*.json' | wc -l) arquivos em $SAIDA/src/test/resources/contrato/gravacoes"
