#!/usr/bin/env python3
"""Compara duas pastas de gravacoes do contrato (tarefa 7.3): status HTTP e corpo, mascarando o que muda a cada rodada
(GUIDs, datas/horas ISO, tokens longos, numeros de venda gerados). Uso:
    python comparar-gravacoes.py <pasta-A> <pasta-B>
Sai com 0 se nao houver diferenca de status nem de corpo normalizado."""
import json
import os
import re
import sys

PADROES = [
    (re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<guid>"),
    (re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?"), "<data-hora>"),
    (re.compile(r"\d{4}-\d{2}-\d{2}"), "<data>"),
    (re.compile(r"[A-Za-z0-9_\-]{40,}"), "<token>"),
    (re.compile(r"\d{10,}"), "<numero-longo>"),
]


def normalizar(valor):
    if isinstance(valor, dict):
        return {k: normalizar(v) for k, v in valor.items() if k not in ("date", "set-cookie", "x-request-id")}
    if isinstance(valor, list):
        return [normalizar(v) for v in valor]
    if isinstance(valor, str):
        for rx, sub in PADROES:
            valor = rx.sub(sub, valor)
    return valor


def carregar(pasta):
    arquivos = {}
    for raiz, _, nomes in os.walk(pasta):
        for nome in nomes:
            if nome.endswith(".json"):
                caminho = os.path.join(raiz, nome)
                with open(caminho, encoding="utf-8") as f:
                    arquivos[os.path.relpath(caminho, pasta).replace(os.sep, "/")] = json.load(f)
    return arquivos


def main(a, b):
    ga, gb = carregar(a), carregar(b)
    so_a, so_b = sorted(set(ga) - set(gb)), sorted(set(gb) - set(ga))
    comuns = sorted(set(ga) & set(gb))
    dif_status = [c for c in comuns if ga[c].get("status") != gb[c].get("status")]
    dif_corpo = [c for c in comuns if normalizar(ga[c].get("corpo")) != normalizar(gb[c].get("corpo"))]
    print(f"A={len(ga)} arquivos, B={len(gb)}; comuns={len(comuns)}; so em A={len(so_a)}; so em B={len(so_b)}")
    print(f"status diferente: {len(dif_status)}; corpo normalizado diferente: {len(dif_corpo)}")
    for c in dif_status[:15]:
        print(f"  STATUS {c}: {ga[c].get('status')} x {gb[c].get('status')}")
    for c in dif_corpo[:15]:
        print(f"  CORPO  {c}")
    return 0 if not (so_a or so_b or dif_status or dif_corpo) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
