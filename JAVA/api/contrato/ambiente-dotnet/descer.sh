#!/usr/bin/env bash
# Derruba o ambiente .NET de gravacao do contrato (conteineres e rede). A imagem quack-api-dotnet:contrato fica.
set -u
docker rm -f contrato-api-dotnet contrato-sql >/dev/null 2>&1 || true
docker network rm contrato-net >/dev/null 2>&1 || true
echo "Ambiente do contrato removido."
