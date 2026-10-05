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
{ "url": "https://pdv.quacksistemas.com.br", "fullscreen": true, "confirmClose": true }
```

## Atalhos

| Tecla | Ação |
|---|---|
| F11 | Liga/desliga tela cheia |
| F5 / Ctrl+R | Recarrega |
| Ctrl+Shift+R | Recarrega ignorando o cache |
| F12 | Ferramentas de desenvolvedor (suporte) |

## Observações

- O instalador **não é assinado digitalmente**: o Windows (SmartScreen) mostra "O Windows protegeu o
  computador" na primeira execução → "Mais informações" → "Executar assim mesmo".
- O instalador tem ~100 MB e **não é versionado** (`DESKTOP/release/` está no `.gitignore`).
- Os dados do navegador **não são migrados**: no programa, o caixa começa com o IndexedDB vazio e
  faz login e sincronização de novo. Envie as vendas pendentes do navegador antes de trocar.
