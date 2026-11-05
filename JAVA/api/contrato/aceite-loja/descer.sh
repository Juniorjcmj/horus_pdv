#!/usr/bin/env bash
# Remove somente os containers e a rede rotulados pelo aceite da loja (quack.aceite-loja).
set -euo pipefail
export MSYS_NO_PATHCONV=1
for c in pdv-loja-api pdv-loja-nuvem-api pdv-loja-carga pdv-loja-sql pdv-loja-nuvem-sql; do
  if docker container inspect "$c" >/dev/null 2>&1; then
    [ "$(docker inspect -f '{{index .Config.Labels "quack.aceite-loja"}}' "$c")" = true ] || { echo "Recusando remover $c: sem o rotulo do aceite." >&2; exit 1; }
    docker rm -f "$c" >/dev/null
  fi
done
if docker network inspect pdv-loja-net >/dev/null 2>&1; then
  [ "$(docker network inspect -f '{{index .Labels "quack.aceite-loja"}}' pdv-loja-net)" = true ] || { echo 'Rede sem o rotulo do aceite.' >&2; exit 1; }
  docker network rm pdv-loja-net >/dev/null
fi
echo 'Containers do aceite da loja removidos.'
