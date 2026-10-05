# Quack PDV — Desktop (Electron)

Casca desktop do PDV. Abre o PDV publicado (`https://pdv.quacksistemas.com.br`) numa janela própria,
em tela cheia, sem barra de endereço nem abas. O código do PDV continua vindo do servidor, então o
programa **não precisa ser reinstalado a cada deploy do frontend**.

## Por que usar no caixa (em vez do navegador)

- O IndexedDB (vendas offline, fiado, catálogo) fica no perfil do programa, em `%APPDATA%\Quack PDV`,
  e **não é apagado** ao limpar o histórico ou os dados de navegação do Chrome/Edge.
- Ninguém fecha a aba do caixa sem querer: fechar a janela pede confirmação.
- Abre direto em tela cheia, com atalho na área de trabalho.
- Depois da primeira abertura com internet, o PDV abre também sem conexão (service worker).

## Uso

```bash
npm install
node node_modules/electron/install.js   # baixa o binário do Electron (o npm 11 bloqueia o script de instalação)
npm start                                # roda em modo desenvolvimento
npm run dist                             # gera release/quack-pdv-setup-<versão>.exe
```

Para apontar para outro ambiente (ex.: frontend local): `QUACK_PDV_URL=http://localhost:5173 npm start`.

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
- O instalador tem ~100 MB e **não é versionado** (`DESKTOP/release/` está no `.gitignore`).
- Os dados do navegador **não são migrados**: no programa, o caixa começa com o IndexedDB vazio e
  faz login e sincronização de novo. Envie as vendas pendentes do navegador antes de trocar.
