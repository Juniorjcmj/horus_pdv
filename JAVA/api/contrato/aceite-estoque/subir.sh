#!/usr/bin/env bash
# Copia o banco E2E sem modificar seus dados; API Java e SQL ficam isolados.
# Requer Docker, Maven, curl e Python 3; funciona no Git Bash com JDK 21.
set -euo pipefail
export MSYS_NO_PATHCONV=1
AQUI="$(cd "$(dirname "$0")" && pwd)"
JAVA="$(cd "$AQUI/../.." && pwd)"
ESTADO="$AQUI/.estado"
ORIGEM="${QUACK_E2E_SQL_ORIGEM:-pdv-teste-sql}"
SA_PWD="${QUACK_E2E_SQL_SENHA:-Teste#Caixa2026}"
PORTA_SQL="${QUACK_ACEITE_SQL_PORTA:-14335}"
PORTA_API="${QUACK_ACEITE_API_PORTA:-5261}"
APENAS_API="${QUACK_ACEITE_APENAS_API:-0}"
API_EXISTENTE=0
ROTULO="quack.aceite-estoque"
mkdir -p "$ESTADO"

verificar_dono() {
  if docker container inspect "$1" >/dev/null 2>&1; then
    [ "$(docker inspect -f '{{index .Config.Labels "quack.aceite-estoque"}}' "$1")" = true ] || {
      echo "Container $1 não pertence a este aceite; remova o conflito de nome." >&2; exit 1;
    }
    if [ "$1" = pdv-java-sql ] && [ "$APENAS_API" = 1 ]; then return; fi
    if [ "$1" = pdv-java-api ] && [ "$APENAS_API" = 1 ]; then API_EXISTENTE=1; return; fi
    echo "Container $1 já existe; execute descer.sh antes de recriar." >&2; exit 1
  fi
}
verificar_dono pdv-java-sql
verificar_dono pdv-java-api
if docker network inspect pdv-java-net >/dev/null 2>&1; then
  [ "$(docker network inspect -f '{{index .Labels "quack.aceite-estoque"}}' pdv-java-net)" = true ] || {
    echo 'Rede pdv-java-net não pertence ao aceite; escolha outro ambiente.' >&2; exit 1;
  }
fi
ESTADO_HOST="$(cd "$ESTADO" && (pwd -W 2>/dev/null || pwd))"
sql() {
  local container="$1"; shift
  docker exec "$container" /opt/mssql-tools18/bin/sqlcmd -C -I -S localhost -U sa -P "$SA_PWD" -b "$@"
}
esperar_sql() {
  local pronto=0
  for tentativa in $(seq 1 90); do
    if sql "$1" -Q 'SELECT 1' >/dev/null 2>&1; then pronto=1; break; fi
    sleep 2
  done
  [ "$pronto" = 1 ] || { echo "SQL $1 não iniciou." >&2; exit 1; }
}
esperar_banco() {
  local pronto=0
  for tentativa in $(seq 1 90); do
    if sql "$1" -d HorusPdv -Q 'SELECT 1' >/dev/null 2>&1; then pronto=1; break; fi
    sleep 2
  done
  [ "$pronto" = 1 ] || { echo "HorusPdv em $1 não está acessível." >&2; exit 1; }
}
if [ "$APENAS_API" != 1 ]; then
docker inspect "$ORIGEM" >/dev/null
ORIGEM_INICIADA=0
restaurar_origem() {
  if [ "$ORIGEM_INICIADA" = 1 ]; then docker stop -t 60 "$ORIGEM" >/dev/null; ORIGEM_INICIADA=0; fi
}
trap restaurar_origem EXIT
if [ "$(docker inspect -f '{{.State.Running}}' "$ORIGEM")" != true ]; then
  docker start "$ORIGEM" >/dev/null
  ORIGEM_INICIADA=1
fi
esperar_sql "$ORIGEM"
esperar_banco "$ORIGEM"
echo '>> Backup COPY_ONLY do HorusPdv de origem'
sql "$ORIGEM" -Q "BACKUP DATABASE [HorusPdv] TO DISK=N'/var/opt/mssql/aceite-estoque.bak' WITH COPY_ONLY, INIT, CHECKSUM;" >/dev/null
docker cp "$ORIGEM:/var/opt/mssql/aceite-estoque.bak" "$ESTADO_HOST/HorusPdv.bak" >/dev/null
restaurar_origem

echo '>> SQL isolado pdv-java-sql'
docker network inspect pdv-java-net >/dev/null 2>&1 || docker network create --label "$ROTULO=true" pdv-java-net >/dev/null
docker run -d --name pdv-java-sql --label "$ROTULO=true" --network pdv-java-net \
  --memory=2300m -p "$PORTA_SQL:1433" -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD="$SA_PWD" \
  -e MSSQL_MEMORY_LIMIT_MB=1536 mcr.microsoft.com/mssql/server:2022-latest >/dev/null
esperar_sql pdv-java-sql
docker cp "$ESTADO_HOST/HorusPdv.bak" pdv-java-sql:/var/opt/mssql/aceite-estoque.bak >/dev/null
sql pdv-java-sql -W -h -1 -s '|' -Q "RESTORE FILELISTONLY FROM DISK=N'/var/opt/mssql/aceite-estoque.bak';" > "$ESTADO/arquivos.txt"
MOVES=''
INDICE=0
while IFS='|' read -r logico fisico tipo restante; do
  logico="$(printf '%s' "$logico" | tr -d '\r')"
  [ "$tipo" = D ] || [ "$tipo" = L ] || continue
  INDICE=$((INDICE+1))
  [ "$tipo" = L ] && extensao=ldf || extensao=mdf
  logico="${logico//\'/\'\'}"
  MOVES="$MOVES, MOVE N'$logico' TO N'/var/opt/mssql/data/aceite-$INDICE.$extensao'"
done < "$ESTADO/arquivos.txt"
[ "$INDICE" -ge 2 ] || { echo 'FILELISTONLY não retornou os arquivos esperados.' >&2; exit 1; }
sql pdv-java-sql -Q "RESTORE DATABASE [HorusPdv] FROM DISK=N'/var/opt/mssql/aceite-estoque.bak' WITH CHECKSUM, RECOVERY $MOVES;" >/dev/null
else
  [ -f "$ESTADO/HorusPdv.bak" ] || { echo 'Backup local do aceite ausente.' >&2; exit 1; }
  docker inspect pdv-java-sql >/dev/null
  if [ "$(docker inspect -f '{{.State.Running}}' pdv-java-sql)" != true ]; then docker start pdv-java-sql >/dev/null; fi
  esperar_sql pdv-java-sql
  esperar_banco pdv-java-sql
  echo '>> Retomando API sobre o SQL restaurado e rotulado deste aceite'
fi

if [ "$API_EXISTENTE" = 0 ]; then
echo '>> Empacotando API Java'
env -u MSYS_NO_PATHCONV mvn -B -f "$JAVA/pom.xml" -DskipTests package > "$ESTADO/build.log" 2>&1
cp "$JAVA/target/quack-pdv-api-0.1.0-SNAPSHOT.jar" "$ESTADO/app.jar"
echo '>> API isolada pdv-java-api'
docker run -d --name pdv-java-api --label "$ROTULO=true" --network pdv-java-net --memory=600m \
  -p "$PORTA_API:8080" --mount "type=bind,source=$ESTADO_HOST/app.jar,target=/app/app.jar,readonly" \
  -e TZ=America/Sao_Paulo -e PORT=8080 \
  -e "SPRING_DATASOURCE_URL=jdbc:sqlserver://pdv-java-sql:1433;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true" \
  -e SPRING_DATASOURCE_USERNAME=sa -e SPRING_DATASOURCE_PASSWORD="$SA_PWD" \
  -e QUACK_AUTH_JWT_SECRET=segredo-jwt-aceite-estoque-0123456789-abcdefghijkl \
  -e QUACK_SECURITY_ENCRYPTION_KEY=chave-cifra-aceite-estoque-0123456789-abcdefgh \
  eclipse-temurin:21-jre java -Xmx384m -jar /app/app.jar \
  --quack.security.rate-limit.max-requests=5000 --quack.email.enabled=false >/dev/null
else
  echo '>> API já existente e rotulada; validando saúde e login sem alterar o banco'
  if [ "$(docker inspect -f '{{.State.Running}}' pdv-java-api)" != true ]; then docker start pdv-java-api >/dev/null; fi
fi
PRONTA=0
for tentativa in $(seq 1 90); do
  if curl -fsS "http://localhost:$PORTA_API/actuator/health" >/dev/null 2>&1; then PRONTA=1; break; fi
  sleep 2
done
[ "$PRONTA" = 1 ] || { docker logs --tail 60 pdv-java-api; exit 1; }
curl -fsS "http://localhost:$PORTA_API/api/Auth/login" -H 'Content-Type: application/json' \
  --data '{"email":"caixa@teste.com","password":"Teste@12345"}' > "$ESTADO/login.json"
python -c 'import json,sys; r=json.load(open(sys.argv[1],encoding="utf-8")); assert r.get("success"), "Login do caixa recusado"' "$ESTADO_HOST/login.json"
rm -f "$ESTADO/login.json"
echo ">> Pronto: API http://localhost:$PORTA_API, SQL localhost:$PORTA_SQL. Login caixa validado."
