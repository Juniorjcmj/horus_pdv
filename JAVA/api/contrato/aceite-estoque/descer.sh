#!/usr/bin/env bash
# Remove somente os containers descartáveis rotulados pelo script de aceite.
set -euo pipefail
export MSYS_NO_PATHCONV=1
for container in pdv-java-api pdv-java-sql; do
  if docker container inspect "$container" >/dev/null 2>&1; then
    [ "$(docker inspect -f '{{index .Config.Labels "quack.aceite-estoque"}}' "$container")" = true ] || {
      echo "Recusando remover $container: rótulo de aceite ausente." >&2; exit 1;
    }
    docker rm -f "$container" >/dev/null
  fi
done
if docker network inspect pdv-java-net >/dev/null 2>&1; then
  [ "$(docker network inspect -f '{{index .Labels "quack.aceite-estoque"}}' pdv-java-net)" = true ] || {
    echo 'Rede pdv-java-net não pertence ao aceite.' >&2; exit 1;
  }
  docker network rm pdv-java-net >/dev/null
fi
echo 'Containers do aceite removidos; backup local preservado em .estado/.'
