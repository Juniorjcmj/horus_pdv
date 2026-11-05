#!/usr/bin/env bash
# Aceite da Fase 4b (tarefa 8.2 / D13): sobe o servidor da loja REAL (API Java em perfil loja, SQL proprio) ligado a um
# receptor (API Java em perfil nuvem) sobre uma copia do banco E2E, e faz o registro + carga inicial. Os E2E do Caixa e
# do Quack Estoque rodam contra a loja (http://localhost:5261); o receptor (http://localhost:5262) pode ser parado e
# religado a vontade (a "internet" da loja). Depois: conferencia.sh.
#
# Requer o backup do banco E2E gerado por contrato/aceite-estoque/subir.sh (.estado/HorusPdv.bak), Docker, Maven, JDK 21,
# curl e Git Bash. Containers e rede rotulados "quack.aceite-loja"; descer.sh remove so eles.
# Variaveis: QUACK_ACEITE_ADMIN_EMAIL / QUACK_ACEITE_ADMIN_SENHA (administrador da empresa do banco E2E, para o registro).
set -euo pipefail
export MSYS_NO_PATHCONV=1
AQUI="$(cd "$(dirname "$0")" && pwd)"
JAVA="$(cd "$AQUI/../.." && pwd)"
ESTADO="$AQUI/.estado"
BAK_ORIGEM="$AQUI/../aceite-estoque/.estado/HorusPdv.bak"
SA_PWD='Aceite#Loja2026'
ROTULO="quack.aceite-loja"
REDE="pdv-loja-net"
ADMIN_EMAIL="${QUACK_ACEITE_ADMIN_EMAIL:-caixa@teste.com}"
ADMIN_SENHA="${QUACK_ACEITE_ADMIN_SENHA:-Teste@12345}"
mkdir -p "$ESTADO"
[ -f "$BAK_ORIGEM" ] || { echo "Falta $BAK_ORIGEM: rode contrato/aceite-estoque/subir.sh uma vez." >&2; exit 1; }
hp() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

for c in pdv-loja-nuvem-sql pdv-loja-sql pdv-loja-nuvem-api pdv-loja-api; do
  if docker container inspect "$c" >/dev/null 2>&1; then echo "Container $c ja existe; rode descer.sh." >&2; exit 1; fi
done
docker network inspect "$REDE" >/dev/null 2>&1 || docker network create --label "$ROTULO=true" "$REDE" >/dev/null

sql() { local c="$1"; shift; docker exec "$c" /opt/mssql-tools18/bin/sqlcmd -C -I -S localhost -U sa -P "$SA_PWD" -b "$@"; }
esperar_sql() { for _ in $(seq 1 90); do sql "$1" -Q 'SELECT 1' >/dev/null 2>&1 && return 0; sleep 2; done; echo "SQL $1 nao iniciou." >&2; exit 1; }
# O banco da loja tambem se chama HorusPdv: os E2E consultam o SQL com -d HorusPdv.
subir_sql() {  # nome porta
  docker run -d --name "$1" --label "$ROTULO=true" --network "$REDE" --memory=1800m -p "$2:1433" -e ACCEPT_EULA=Y \
    -e MSSQL_SA_PASSWORD="$SA_PWD" -e MSSQL_MEMORY_LIMIT_MB=1280 mcr.microsoft.com/mssql/server:2022-latest >/dev/null
}

echo '>> SQL da nuvem (copia do banco E2E) e SQL da loja'
subir_sql pdv-loja-nuvem-sql 14336
subir_sql pdv-loja-sql 14337
esperar_sql pdv-loja-nuvem-sql
esperar_sql pdv-loja-sql
docker cp "$(hp "$BAK_ORIGEM")" pdv-loja-nuvem-sql:/var/opt/mssql/origem.bak >/dev/null
sql pdv-loja-nuvem-sql -W -h -1 -s '|' -Q "RESTORE FILELISTONLY FROM DISK=N'/var/opt/mssql/origem.bak';" > "$ESTADO/arquivos.txt"
MOVES=''; INDICE=0
while IFS='|' read -r logico fisico tipo restante; do
  logico="$(printf '%s' "$logico" | tr -d '\r')"
  [ "$tipo" = D ] || [ "$tipo" = L ] || continue
  INDICE=$((INDICE+1)); [ "$tipo" = L ] && ext=ldf || ext=mdf
  logico="${logico//\'/\'\'}"
  MOVES="$MOVES, MOVE N'$logico' TO N'/var/opt/mssql/data/nuvem-$INDICE.$ext'"
done < "$ESTADO/arquivos.txt"
[ "$INDICE" -ge 2 ] || { echo 'FILELISTONLY nao retornou os arquivos esperados.' >&2; exit 1; }
sql pdv-loja-nuvem-sql -Q "RESTORE DATABASE [HorusPdv] FROM DISK=N'/var/opt/mssql/origem.bak' WITH CHECKSUM, RECOVERY $MOVES;" >/dev/null
sql pdv-loja-sql -Q "CREATE DATABASE [HorusPdv]; ALTER DATABASE [HorusPdv] SET RECOVERY SIMPLE;" >/dev/null

echo '>> Empacotando a API Java'
env -u MSYS_NO_PATHCONV mvn -B -o -f "$JAVA/pom.xml" -DskipTests package > "$ESTADO/build.log" 2>&1
cp "$JAVA/target/quack-pdv-api-0.1.0-SNAPSHOT.jar" "$ESTADO/app.jar"
JAR_HOST="$(hp "$ESTADO/app.jar")"

# Chaves: a nuvem usa as do aceite do estoque; a loja tem as proprias (D1).
NUVEM_ENV=(-e TZ=America/Sao_Paulo -e PORT=8080
  -e "SPRING_DATASOURCE_URL=jdbc:sqlserver://pdv-loja-nuvem-sql:1433;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true"
  -e SPRING_DATASOURCE_USERNAME=sa -e "SPRING_DATASOURCE_PASSWORD=$SA_PWD"
  -e QUACK_AUTH_JWT_SECRET=segredo-jwt-aceite-estoque-0123456789-abcdefghijkl
  -e QUACK_SECURITY_ENCRYPTION_KEY=chave-cifra-aceite-estoque-0123456789-abcdefgh)
LOJA_ENV=(-e TZ=America/Sao_Paulo -e PORT=8080
  -e "SPRING_DATASOURCE_URL=jdbc:sqlserver://pdv-loja-sql:1433;databaseName=HorusPdv;encrypt=true;trustServerCertificate=true"
  -e SPRING_DATASOURCE_USERNAME=sa -e "SPRING_DATASOURCE_PASSWORD=$SA_PWD"
  -e QUACK_AUTH_JWT_SECRET=segredo-jwt-da-loja-aceite-0123456789-abcdefghijkl
  -e QUACK_SECURITY_ENCRYPTION_KEY=chave-cifra-da-loja-aceite-0123456789-abcdefgh)
MONTAGEM=(--mount "type=bind,source=$JAR_HOST,target=/app/app.jar,readonly")

esperar_http() { for _ in $(seq 1 90); do curl -fsS "$1" >/dev/null 2>&1 && return 0; sleep 2; done; echo "$1 nao respondeu." >&2; docker logs --tail 60 "$2"; exit 1; }

echo '>> Receptor (perfil nuvem) em http://localhost:5262'
docker run -d --name pdv-loja-nuvem-api --label "$ROTULO=true" --network "$REDE" --memory=700m -p 5262:8080 "${MONTAGEM[@]}" \
  "${NUVEM_ENV[@]}" eclipse-temurin:21-jre java -Xmx448m -jar /app/app.jar --spring.profiles.active=nuvem \
  --quack.security.rate-limit.max-requests=5000 --quack.email.enabled=false >/dev/null
esperar_http http://localhost:5262/actuator/health pdv-loja-nuvem-api

echo ">> Registro e carga inicial (administrador $ADMIN_EMAIL)"
docker run --rm --name pdv-loja-carga --label "$ROTULO=true" --network "$REDE" --memory=700m "${MONTAGEM[@]}" "${LOJA_ENV[@]}" \
  -e "QUACK_SYNC_CARGA_SENHA=$ADMIN_SENHA" eclipse-temurin:21-jre java -Xmx448m -jar /app/app.jar --spring.profiles.active=loja \
  --quack.sync.carga.executar=true --quack.sync.carga.nuvem-url=http://pdv-loja-nuvem-api:8080 \
  --quack.sync.carga.email="$ADMIN_EMAIL" "--quack.sync.carga.nome-servidor=Servidor do aceite" \
  --quack.sync.alertas-ativo=false --quack.sync.conferencia-ativa=false --server.port=0 > "$ESTADO/carga.log" 2>&1 \
  || { tail -40 "$ESTADO/carga.log"; echo 'A carga inicial falhou.' >&2; exit 1; }

echo '>> Servidor da loja (perfil loja) em http://localhost:5261'
docker run -d --name pdv-loja-api --label "$ROTULO=true" --network "$REDE" --memory=700m -p 5261:8080 "${MONTAGEM[@]}" \
  "${LOJA_ENV[@]}" eclipse-temurin:21-jre java -Xmx448m -jar /app/app.jar --spring.profiles.active=loja \
  --quack.security.rate-limit.max-requests=5000 --quack.email.enabled=false --quack.sync.alertas-ativo=false \
  --quack.sync.conferencia-ativa=false >/dev/null
esperar_http http://localhost:5261/actuator/health pdv-loja-api
curl -fsS http://localhost:5261/api/Auth/login -H 'Content-Type: application/json' \
  --data '{"email":"caixa@teste.com","password":"Teste@12345"}' | grep -q '"success"[ ]*:[ ]*true' \
  || { echo 'Login do caixa recusado na loja.' >&2; exit 1; }
echo '>> Pronto: loja http://localhost:5261 (SQL pdv-loja-sql), receptor http://localhost:5262 (SQL pdv-loja-nuvem-sql).'
