#!/usr/bin/env bash
# Arquivo: GATEWAY/build-installer.sh
# Objetivo: (re)gerar o INSTALADOR COMPLETO do Quack Gateway que o sistema oferece para download em
#           Configurações: publica o binario self-contained (win-x64), junta scripts + checklist e
#           empacota em FRONTEND/public/gateway/quack-gateway-completo.zip.
#
# QUANDO RODAR: sempre que algo em GATEWAY/src/ mudar (mantem o download atualizado). Depois, COMMITE
#              o zip atualizado.
#
# COMO: basta `bash GATEWAY/build-installer.sh`. O script se adapta ao ambiente:
#   - PUBLICACAO: usa `dotnet` se existir; senao usa Docker (imagem SDK 8.0) — ideal no ambiente de nuvem.
#   - COMPACTACAO: usa `zip` se existir; senao `python3`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GW="$ROOT/GATEWAY"
OUT="$ROOT/FRONTEND/public/gateway"
STAGE_PARENT="$(mktemp -d)"
STAGE="$STAGE_PARENT/QuackGateway"
mkdir -p "$STAGE" "$OUT"

# -------- 1) Publicacao self-contained win-x64 --------
if command -v dotnet >/dev/null 2>&1; then
    echo "[build-installer] Publicando com dotnet local..."
    dotnet publish "$GW/src/HorusGateway" -c Release -r win-x64 --self-contained true \
        -p:PublishSingleFile=false -o "$STAGE/publish-service"
elif command -v docker >/dev/null 2>&1; then
    echo "[build-installer] dotnet nao encontrado — publicando via container SDK 8.0..."
    CA_MOUNT=(); CA_CMD=":"
    if [ -f /root/.ccr/ca-bundle.crt ]; then
        CA_MOUNT=(-v /root/.ccr:/ca)
        CA_CMD='cp /ca/ca-bundle.crt /usr/local/share/ca-certificates/ccr.crt && update-ca-certificates >/dev/null 2>&1'
    fi
    docker run --rm --network host \
        -v "$ROOT":/work -v "$STAGE":/out "${CA_MOUNT[@]}" -v horus-nuget:/root/.nuget/packages \
        -e PROXY="${HTTPS_PROXY:-}" mcr.microsoft.com/dotnet/sdk:8.0 bash -lc \
        "$CA_CMD; export HTTPS_PROXY=\${PROXY:-} https_proxy=\${PROXY:-} NUGET_PACKAGES=/root/.nuget/packages; \
         dotnet publish /work/GATEWAY/src/HorusGateway -c Release -r win-x64 --self-contained true \
         -p:PublishSingleFile=false -o /out/publish-service"
else
    echo "[build-installer] ERRO: precisa de 'dotnet' ou 'docker' para publicar." >&2
    exit 1
fi

# -------- 2) Reunir scripts + checklist + LEIA-ME --------
echo "[build-installer] Reunindo scripts e checklist..."
cp "$GW/install-service.ps1" "$GW/uninstall-service.ps1" "$GW/setup-https.ps1" \
   "$GW/run-gateway.ps1" "$GW/run-gateway.bat" "$STAGE/"
cp "$OUT/checklist.html" "$STAGE/"
cat > "$STAGE/LEIA-ME.txt" <<'TXT'
QUACK GATEWAY - KIT COMPLETO (pronto para instalar, sem .NET no cliente)
========================================================================
Esta pasta JA contem o programa publicado (publish-service\) + os scripts.
Nao precisa rodar "dotnet publish". Na maquina do cliente (PowerShell como ADMIN):

  HTTP:   powershell -ExecutionPolicy Bypass -File .\install-service.ps1 -SkipPublish -CompanyId <EMPRESA> -StoreId loja-01 -Port 5080
  HTTPS:  veja o checklist.html (setup-https.ps1 + GATEWAY_HTTPS_CERT), porta 5443

Se baixou pelo navegador/Drive: clique direito no .zip > Propriedades > DESBLOQUEAR antes de extrair.
Remover: powershell -ExecutionPolicy Bypass -File .\uninstall-service.ps1
TXT

# -------- 3) Compactar em .zip --------
echo "[build-installer] Compactando..."
rm -f "$OUT/quack-gateway-completo.zip"
if command -v zip >/dev/null 2>&1; then
    ( cd "$STAGE_PARENT" && zip -r -q "$OUT/quack-gateway-completo.zip" QuackGateway )
elif command -v python3 >/dev/null 2>&1; then
    python3 - "$STAGE_PARENT" "$OUT/quack-gateway-completo.zip" <<'PY'
import sys, os, zipfile
base, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(base):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, base))
print("zip criado:", out)
PY
else
    echo "[build-installer] ERRO: precisa de 'zip' ou 'python3' para compactar." >&2
    exit 1
fi

rm -rf "$STAGE_PARENT"
echo "[build-installer] OK -> $OUT/quack-gateway-completo.zip"
ls -lh "$OUT/quack-gateway-completo.zip"
