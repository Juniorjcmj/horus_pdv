#!/usr/bin/env node
// Garante o modelo de cada etapa do OpenSpec no frontmatter dos comandos/skills.
// Idempotente: roda no hook SessionStart (.claude/settings.json) para reaplicar
// depois de `openspec init` / `openspec update`, que regeneram esses arquivos.
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

const PROPOSE = { model: 'opus' };
const APPLY = { model: 'sonnet', context: 'fork', background: 'false' };

const targets = [
  ['.claude/commands/opsx/propose.md', PROPOSE],
  ['.claude/commands/openspec/proposal.md', PROPOSE],
  ['.claude/skills/openspec-propose/SKILL.md', PROPOSE],
  ['.claude/commands/opsx/apply.md', APPLY],
  ['.claude/commands/openspec/apply.md', APPLY],
  ['.claude/skills/openspec-apply-change/SKILL.md', APPLY],
];

function applyKeys(text, keys) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  const bom = text.startsWith('﻿') ? '﻿' : '';
  const lines = text.slice(bom.length).split(/\r?\n/);
  const wanted = Object.entries(keys).map(([k, v]) => `${k}: ${v}`);

  // Sem frontmatter (ou bloco não fechado): cria o bloco no topo.
  const end = lines[0].trim() === '---' ? lines.findIndex((l, i) => i > 0 && l.trim() === '---') : -1;
  if (end === -1) {
    return bom + ['---', ...wanted, '---', '', ...lines].join(eol);
  }

  // Só chaves de nível superior (sem indentação) — não mexe em `metadata:` etc.
  const body = lines.slice(1, end);
  for (const [key, value] of Object.entries(keys)) {
    const line = `${key}: ${value}`;
    const isKey = (l) => l.startsWith(`${key}:`);
    const first = body.findIndex(isKey);
    if (first === -1) {
      body.push(line);
      continue;
    }
    body[first] = line;
    for (let i = body.length - 1; i > first; i--) {
      if (isKey(body[i])) body.splice(i, 1);
    }
  }
  return bom + ['---', ...body, ...lines.slice(end)].join(eol);
}

for (const [rel, keys] of targets) {
  const file = join(root, rel);
  if (!existsSync(file)) continue;
  const before = readFileSync(file, 'utf8');
  const after = applyKeys(before, keys);
  if (after === before) {
    console.log(`já ok: ${rel}`);
  } else {
    writeFileSync(file, after);
    console.log(`alterado: ${rel}`);
  }
}
