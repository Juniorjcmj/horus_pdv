# Quack PDV — Desktop (Electron)

Aplicativo desktop do PDV. Abre o PDV em `https://pdv.quacksistemas.com.br` numa janela própria,
em tela cheia, sem barra de endereço nem abas. Desde a versão **1.3.0**, o instalador inclui as
telas compiladas do código atual. Ao abrir, usa uma versão mais nova publicada no servidor quando
`build-info.json` indicar uma compilação posterior; sem conexão ou com o servidor antigo, usa a
cópia incluída no programa. Assim, as melhorias do instalador chegam mesmo antes do deploy do frontend.

O endereço e o perfil `%APPDATA%\\Quack PDV` continuam os mesmos, preservando login, configurações e
IndexedDB das versões anteriores. O instalador contém o frontend e o Gateway; alterações da API
central ainda precisam ser publicadas no servidor. Abrir as telas sem internet não substitui o
login offline: o operador precisa ter suas credenciais salvas neste computador.

Desde a versão **1.3.6**, entradas por NF-e/NFC-e armazenam o XML original (quando enviado ou
obtido da SEFAZ) e os itens confirmados. Consulte **Produtos → Importar / Cargas → Notas de entrada**
ou **Fiscal → Notas de entrada**. Cupons digitados conservam chave e itens, identificados como sem XML.
A API deve estar atualizada para aplicar a migração `42_notas_entrada_arquivo.sql`. O arquivo fica no
banco central e faz parte do backup completo. Entradas anteriores não possuem XML recuperável
automaticamente; não reimporte notas antigas para arquivá-las, pois isso somaria estoque.

## Por que usar no caixa (em vez do navegador)

- O IndexedDB (vendas offline, fiado, catálogo) fica no perfil do programa, em `%APPDATA%\Quack PDV`,
  e **não é apagado** ao limpar o histórico ou os dados de navegação do Chrome/Edge.
- Ninguém fecha a aba do caixa sem querer: fechar a janela pede confirmação.
- Abre direto em tela cheia, com atalho na área de trabalho.
- Na frente de caixa, leituras rápidas do leitor USB encerradas por Enter ou Tab buscam o produto
  mesmo depois de clicar em outro campo ou botão. Uma leitura durante o pagamento ou outra janela
  fica guardada até a janela fechar, preservando os valores preenchidos e sem confirmar a venda.
- Depois da primeira abertura com internet, o PDV abre também sem conexão (service worker).
- Desde a versão **1.3.2**, o painel de caixa atualiza os totais após a venda, ao abrir e antes de fechar.
  A contagem compara com os valores atuais do servidor; sem conexão, lê o resumo local com as vendas
  pendentes de sincronização. Falha ao atualizar oferece nova tentativa antes do fechamento.
- Desde a versão **1.3.3**, a entrada de mercadorias reconhece NF-e (55) e NFC-e (65).
  NFC-e pode entrar pelo XML do fornecedor ou por itens digitados do cupom, com fornecedor e
  produtos existentes reaproveitados e revisão de quantidades/custos antes da gravação. A chave
  identifica o documento; o download no Ambiente Nacional continua exclusivo de NF-e (55).
- Desde a versão **1.3.4**, a janela "Vendas do Caixa Atual" apresenta somente os dados de venda
  e pagamento, sem a coluna Status Fiscal nem o resumo Notas Autorizadas. A emissão automática,
  a impressão dos documentos e o acompanhamento no painel fiscal continuam funcionando.
- Desde a versão **1.3.5**, o Administrador Geral pode gerar e baixar um backup completo do banco
  central no Gerenciamento Geral de Empresas. Requer atualizar também a API e os volumes do stack
  conforme `DEPLOY.md`; o backup das pendências locais continua disponível nas Configurações.

## Uso

```bash
npm install
node node_modules/electron/install.js   # baixa o binário do Electron (o npm 11 bloqueia o script de instalação)
npm start                                # roda em modo desenvolvimento
npm run prepare-frontend                 # compila as telas para o instalador
npm run dist                             # gera release/quack-pdv-setup-<versão>.exe (com o Gateway)
```

Para apontar para outro ambiente (ex.: frontend local): `QUACK_PDV_URL=http://localhost:5173 npm start`.
Para testar a cópia incluída: `QUACK_PDV_FRONTEND_SOURCE=bundled npm start` (depois de preparar o frontend).
O `config.json` aceita `"frontendSource": "auto"` (padrão), `"bundled"` ou `"remote"` para suporte.

Para verificar o instalador gerado: `npm test` valida impressão, seleção da versão e resolução dos arquivos;
`node tests/installer-smoke.js` abre o executável empacotado em um perfil isolado e invisível, com API
simulada, conferindo leitor, pagamento, impressão automática (saída física simulada), falha e reimpressão,
cadastros mínimos e preservação dos dados ao reabrir offline. Os backups ficam dentro do perfil isolado.

## Configuração na máquina do cliente (opcional)

Arquivo `%APPDATA%\Quack PDV\config.json`:

```json
{
  "url": "https://pdv.quacksistemas.com.br",
  "fullscreen": true,
  "confirmClose": true,
  "autoZoom": true,
  "zoomAdjust": 1
}
```

## Backup automático das pendências

Tudo o que ainda **não chegou ao servidor** (vendas offline, abertura/fechamento de caixa, sangria,
reforço — com todos os dados) é gravado automaticamente em **`Documentos\Quack PDV\Backups`**, fora
da pasta do programa: se o perfil `%APPDATA%\Quack PDV` for apagado ou corromper, o backup continua.

- `pendencias-atual.json` — sempre o estado mais recente (`"count": 0` quando está tudo enviado).
- `pendencias-AAAA-MM-DD.json` — último estado com pendências de cada dia; guardados por 30 dias.
- Atualiza ~2s depois de cada venda/movimento offline, a cada envio da fila e a cada 5 minutos.
- Outra pasta: `"backupDir": "D:\\Backups\\Quack"` no `config.json`.

Só funciona no programa (no navegador comum o PDV não grava arquivos). Para recuperar dados a partir
de um backup, fale com o suporte: o arquivo traz cada evento com o mesmo identificador do envio
original, então reenviar não duplica o que já tiver chegado ao servidor.

## Gateway embutido (loja de um caixa) — versão 1.1.0+

O instalador já traz o **Quack Gateway** (`resources\gateway\HorusGateway.exe`). Ativado, ele roda
**junto com o programa** em `http://127.0.0.1:5080`: sem Administrador, sem Serviço do Windows e sem
liberar firewall (só a própria máquina o acessa). Sem internet, a fila do PDV (vendas, abertura,
fechamento, sangria/reforço) vai para o banco do Gateway e segue para a nuvem quando a internet volta.

**Ativar:** no PDV, um administrador/gerente abre **Configurações → Gateway deste computador → Ativar
neste computador** (com internet). O PDV gera o token da loja, entrega ao programa, o Gateway sobe e o
caixa se registra nele — nada para digitar. "Gerar token novo e reiniciar" troca o token (o anterior é
revogado); "Desativar" para o Gateway e revoga o token.

- Token guardado no `config.json` cifrado pelo Windows (`"gateway": { "tokenEnc": ... }`).
- Banco: `%APPDATA%\Quack PDV\gateway\horus-gateway.db`. Logs: `resources\gateway\logs`.
- Fecha junto com o programa; se o Gateway cair, o programa o reinicia (2s, 5s, 10s, 30s).
- Se já houver um Gateway na porta 5080 (Serviço do Windows numa loja com vários caixas), o programa
  usa aquele e não sobe outro.
- Loja com **vários caixas/terminais na rede**: continue usando o kit do Gateway como Serviço do
  Windows (Configurações → Local Gateway), que atende a LAN.

O `npm run dist` copia o Gateway do kit `FRONTEND/public/gateway/quack-gateway-completo.zip`
(`scripts/prepare-gateway.js` → `gateway-bin/`). Mudou `GATEWAY/src`? Rode `GATEWAY/build-installer.sh`
antes, senão o programa leva o Gateway antigo. Para testar com `npm start`: `npm run prepare-gateway`.

## Impressora (impressão direta) — versão 1.2.0+

Desde a versão **1.3.1**, finalizar uma venda envia o cupom ou DANFE automaticamente à **impressora padrão
do Windows**, sem diálogo e sem abrir uma janela visível. Funciona também em vendas offline e ao reimprimir
pela prévia. A prévia opcional não interfere nesse envio. Falhas são avisadas e a venda continua salva;
use **Imprimir última venda** após conferir a impressora. O número de cópias configurado é respeitado.

Para os demais documentos, no PDV, **Configurações → Impressão no PDV → Impressora deste computador**: escolha a impressora
(ex.: a térmica do cupom), o número de cópias e ligue **Imprimir direto**. A partir daí cupom, DANFE
NFC-e, fechamento de caixa, sangria/reforço, fiado e relatórios saem direto nela, **sem abrir a janela
de impressão do Windows**. "Imprimir página de teste" confere impressora, acentos e largura (80mm).

- Como funciona: as telas chamam `window.print()`; o `preload.js` (que roda também nas janelas abertas
  pelo PDV) troca essa chamada por um pedido ao programa, que imprime com `webContents.print({ silent })`.
- Se a impressora falhar (desligada, sem papel, removida), abre a janela de impressão normal para o
  operador escolher outra — o cupom não se perde.
- Desligado (padrão) = comportamento de sempre (janela de impressão).
- Gravado no `config.json`: `"printer": { "mode": "direct", "deviceName": "", "copies": 1 }`
  (`deviceName` vazio = impressora padrão do Windows).
- O tamanho do papel vem do `@page` de cada documento (80mm) e do driver da impressora: na térmica,
  deixe o papel do driver como bobina 80mm (ou 58mm, conforme o modelo).

## Tamanho da tela (zoom automático)

O PDV foi desenhado para **1366×768**. O programa aplica um zoom proporcional ao tamanho da janela,
para a tela inteira (letras, ícones, campos) ficar igual em qualquer monitor: ~0,8 em 1024×768,
~1,4 em 1920×1080, ~1,9 em 2560×1440. Já considera a escala de exibição do Windows (125%, 150%…).

Se o operador quiser maior ou menor, usa **Ctrl +** / **Ctrl −** (ou Ctrl + roda do mouse); o ajuste
fica salvo em `zoomAdjust`. **Ctrl+0** volta ao automático. `"autoZoom": false` desliga o automático.

## Atalhos

| Tecla | Ação |
|---|---|
| F11 | Liga/desliga tela cheia |
| F5 / Ctrl+R | Recarrega |
| Ctrl+Shift+R | Recarrega ignorando o cache |
| Ctrl + / Ctrl − / Ctrl+0 | Aumenta / diminui / volta ao tamanho automático |
| Ctrl+Shift+I | Ferramentas de desenvolvedor (suporte) |

O F12 não é capturado pelo programa: é o atalho de **Pagamento** do PDV.

## Observações

- O instalador **não é assinado digitalmente**: o Windows (SmartScreen) mostra "O Windows protegeu o
  computador" na primeira execução → "Mais informações" → "Executar assim mesmo".
- O instalador tem ~150 MB (com o Gateway) e **não é versionado** (`DESKTOP/release/` está no `.gitignore`).
- Os dados do navegador **não são migrados**: no programa, o caixa começa com o IndexedDB vazio e
  faz login e sincronização de novo. Envie as vendas pendentes do navegador antes de trocar.


## Versão 1.3.7 — conferência fiscal

No cadastro de produtos, abra as ações e escolha **Verificar situação fiscal**. O painel confere os códigos salvos, mostra a descrição oficial do NCM, vigência e compatibilidade da classificação IBS/CBS e orienta a revisão contábil. Não altera dados automaticamente nem muda o cadastro mínimo. **Editar dados fiscais** abre o formulário, incluindo CST IBS/CBS e cClassTrib.

Requer publicar a API atualizada: o instalador sozinho não muda a emissão do servidor. A API também corrige exportações antigas que ofereciam apenas o protocolo de autorização. Depois da publicação, exporte novamente o mês para obter nota e protocolo juntos. A recuperação exige o XML assinado original salvo no banco.

A análise e seus limites estão em `docs/AUDITORIA_FISCAL_2026-10-10.md`. A conferência não equivale a homologação pela SEFAZ e não confirma enquadramento legal só pelo nome ou pelo NCM.
