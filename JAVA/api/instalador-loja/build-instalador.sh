#!/usr/bin/env bash
# Arquivo: JAVA/api/instalador-loja/build-instalador.sh
# Objetivo: gerar o pacote do servidor da loja (D12): JAVA/api/target/quack-servidor-loja.zip, com
#   app/quack-pdv-api.jar, jre/ (jlink, Windows x64), winsw/WinSW-x64.exe (v2.12.0, SHA-256 conferido), os scripts .ps1,
#   o modelo do servico, o CHECKLIST.md e um LEIA-ME.txt.
# O zip NAO e versionado: e artefato de release (diferente do Gateway, ninguem baixa pelo painel nesta fase).
#
# Uso:   bash JAVA/api/instalador-loja/build-instalador.sh [--sem-maven]
# Requisitos:
#   - JDK 21 com a pasta jmods. O jlink gera o JRE do SISTEMA do JDK usado: para o pacote de Windows, rode num Windows
#     (Git Bash) ou aponte JDK_WIN_HOME para um JDK 21 de Windows descompactado (com jmods\).
#   - curl (baixa o WinSW uma vez para JAVA/api/target/instalador-cache) e python (ou zip) para compactar.
#   - Maven (a menos que use --sem-maven e ja exista target/quack-pdv-api-*.jar).
set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API="$(cd "$AQUI/.." && pwd)"
ALVO="$API/target"
CACHE="$ALVO/instalador-cache"
STAGE="$ALVO/instalador-stage/quack-servidor-loja"
ZIP="$ALVO/quack-servidor-loja.zip"

WINSW_VERSAO="v2.12.0"
WINSW_URL="https://github.com/winsw/winsw/releases/download/${WINSW_VERSAO}/WinSW-x64.exe"
WINSW_SHA256="05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"

# Modulos do JRE: java.se cobre o que Spring Boot/Hibernate/JDBC pedem; os demais sao os que o jlink nao deduz
# (TLS com curvas elipticas, locale pt-BR, charsets, FS de zip, JMX).
MODULOS="java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.localedata,jdk.charsets,jdk.httpserver"

SEM_MAVEN=0
[ "${1:-}" = "--sem-maven" ] && SEM_MAVEN=1

# -------- 1) Jar da API --------
if [ "$SEM_MAVEN" -eq 0 ]; then
    echo "[build-instalador] Empacotando o jar (mvn -DskipTests package)..."
    (cd "$API" && mvn -B -q -DskipTests package)
fi
JAR="$(ls -1 "$ALVO"/quack-pdv-api-*.jar 2>/dev/null | grep -v -- '-sources' | head -n 1 || true)"
[ -n "$JAR" ] || { echo "[build-instalador] ERRO: jar nao encontrado em $ALVO (rode sem --sem-maven)." >&2; exit 1; }
echo "[build-instalador] Jar: $JAR"

# -------- 2) WinSW (cache + SHA-256) --------
mkdir -p "$CACHE"
WINSW="$CACHE/WinSW-${WINSW_VERSAO}-x64.exe"
if [ ! -f "$WINSW" ]; then
    echo "[build-instalador] Baixando $WINSW_URL ..."
    curl -fsSL -o "$WINSW" "$WINSW_URL"
fi
SOMA="$(sha256sum "$WINSW" | cut -d' ' -f1)"
if [ "$SOMA" != "$WINSW_SHA256" ]; then
    rm -f "$WINSW"
    echo "[build-instalador] ERRO: SHA-256 do WinSW difere do esperado ($SOMA)." >&2
    exit 1
fi

# -------- 3) JRE minimo com jlink --------
JDK="${JDK_WIN_HOME:-${JAVA_HOME:-}}"
if [ -z "$JDK" ] && command -v jlink >/dev/null 2>&1; then JDK="$(dirname "$(dirname "$(command -v jlink)")")"; fi
[ -d "$JDK/jmods" ] || { echo "[build-instalador] ERRO: defina JDK_WIN_HOME (ou JAVA_HOME) para um JDK 21 com a pasta jmods." >&2; exit 1; }
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *) [ -n "${JDK_WIN_HOME:-}" ] || { echo "[build-instalador] ERRO: fora do Windows, JDK_WIN_HOME deve apontar para um JDK 21 de Windows." >&2; exit 1; } ;;
esac
JRE_TMP="$ALVO/instalador-stage/jre"
rm -rf "$JRE_TMP"
mkdir -p "$ALVO/instalador-stage"
echo "[build-instalador] jlink ($JDK)..."
"$JDK/bin/jlink" --module-path "$JDK/jmods" --add-modules "$MODULOS" --strip-debug --no-header-files --no-man-pages \
    --compress=zip-6 --output "$JRE_TMP"

# -------- 4) Montar o pacote --------
rm -rf "$STAGE"
mkdir -p "$STAGE/app" "$STAGE/winsw" "$STAGE/lib"
cp "$JAR" "$STAGE/app/quack-pdv-api.jar"
cp -r "$JRE_TMP" "$STAGE/jre"
cp "$WINSW" "$STAGE/winsw/WinSW-x64.exe"
cp "$AQUI/winsw/QuackLojaApi.xml.modelo" "$STAGE/winsw/"
cp "$AQUI/instalar.ps1" "$AQUI/backup.ps1" "$AQUI/restaurar.ps1" "$AQUI/desinstalar.ps1" "$AQUI/CHECKLIST.md" "$STAGE/"
cp "$AQUI/lib/comum.ps1" "$STAGE/lib/"
cat > "$STAGE/LEIA-ME.txt" <<'TXT'
QUACK - SERVIDOR DA LOJA (modo loja)
====================================
Na maquina dedicada da loja (Windows 10/11 ou Server, 64 bits), com PowerShell COMO ADMINISTRADOR, nesta pasta:

  1. Valide o ambiente (nao altera nada):      .\instalar.ps1 -SomenteValidar
  2. Instale:                                  .\instalar.ps1 -EmailAdmin admin@sualoja.com.br
     (pede a senha do administrador da empresa na nuvem; instala o SQL Server Express se faltar,
      cria o servico, o firewall e o backup diario, registra o servidor e faz a carga inicial)
  3. Siga o CHECKLIST.md (troca do endereco nos caixas, teste de queda de internet e do servidor, restauracao).

Se o PowerShell reclamar de politica de execucao:  powershell -ExecutionPolicy Bypass -File .\instalar.ps1 ...
Sem internet na maquina: baixe o SQL Server Express antes e use  -MidiaSql C:\caminho\SQLEXPR_x64_ENU.exe
Backups: <destino>\backups (ultimos 7), estado em <destino>\estado\backup-estado.json.
TXT

# -------- 5) Compactar --------
# (o atalho "python3" da Microsoft Store existe no PATH do Windows mas nao roda: por isso o teste com -c)
PY=""
for candidato in python python3; do
    if command -v "$candidato" >/dev/null 2>&1 && "$candidato" -c "import zipfile" >/dev/null 2>&1; then PY="$candidato"; break; fi
done
rm -f "$ZIP"
if command -v zip >/dev/null 2>&1; then
    (cd "$(dirname "$STAGE")" && zip -qr "$ZIP" "$(basename "$STAGE")")
elif [ -n "$PY" ]; then
    "$PY" - "$STAGE" "$ZIP" <<'PYEOF'
import os, sys, zipfile
stage, destino = sys.argv[1], sys.argv[2]
base = os.path.dirname(stage)
with zipfile.ZipFile(destino, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for pasta, _, arquivos in os.walk(stage):
        for nome in arquivos:
            caminho = os.path.join(pasta, nome)
            z.write(caminho, os.path.relpath(caminho, base).replace(os.sep, "/"))
PYEOF
else
    echo "[build-instalador] ERRO: precisa de zip ou python para compactar." >&2
    exit 1
fi
echo "[build-instalador] OK: $ZIP ($(du -h "$ZIP" | cut -f1))"
