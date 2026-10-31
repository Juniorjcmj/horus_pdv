# Quack Caixa (JavaFX)

Frente de caixa desktop nativa do Hórus PDV: Java 21 + JavaFX 21, falando com a mesma API .NET do caixa web
(`/api/Auth`, `/api/Caixa`, `/api/Produto`, `/api/Cliente`, `/api/Promocao/ativas`, `/api/Empresa`,
`/api/HistoricoVendas`). Nenhuma regra de negócio muda no servidor.

## Requisitos

- Java 21 (JDK para compilar, JRE para rodar) e Maven 3.9+.
- API do Hórus PDV acessível (padrão: produção `https://api-pdv.quacksistemas.com.br`; para testes locais use `http://localhost:5260` no link "Servidor" da tela de login).

## Build e execução

```
mvn -o -f CAIXA_JAVAFX/pom.xml package        # testes + fat-jar em target/quack-caixa.jar
java -jar CAIXA_JAVAFX/target/quack-caixa.jar  # ou run.bat
mvn -o -f CAIXA_JAVAFX/pom.xml test            # só os testes
```

`-Dquack.home=<pasta>` troca a pasta de dados (usado nos testes).
O aviso "Unsupported JavaFX configuration" ao iniciar o fat-jar é esperado e inofensivo.

## Executável Windows (QuackCaixa.exe)

`powershell -ExecutionPolicy Bypass -File build-exe.ps1` compila, roda os testes e gera com o `jpackage`:

- `dist\QuackCaixa\QuackCaixa.exe`: o programa, com o Java embutido (a loja **não** precisa ter Java);
- `dist\QuackCaixa-1.0.0-windows.zip` (~57 MB): para levar à loja, descompactar e abrir o `QuackCaixa.exe`.

Com o caixa aberto a partir de `dist\` o script para sem apagar nada (feche o programa ou use `-Dest <pasta>`).
Sem o WiX Toolset o resultado é a pasta do programa (app-image). Com o WiX instalado dá para trocar `--type`
por `msi` no script e gerar um instalador com atalho no menu Iniciar.

## Dados locais

Pasta `%APPDATA%\QuackCaixa\` (fallback `~/.quack-caixa`):

- `config.properties`: `apiBaseUrl`, `deviceId`, `lastEmail`, `glassTheme`, `offlineMaxDays` (opcional, 1 a 7).
- `quack.db`: banco SQLite **criptografado** (SQLCipher, AES-256 com HMAC por página) com o cache por empresa
  (produtos, clientes, promoções, empresa, status do caixa, último cupom), a fila offline (vendas e operações de
  caixa, pendentes e recusadas) e o acesso offline.
- `fila-copia.db`: cópia criptografada só da fila. Se o `quack.db` se perder ou for adulterado, a fila volta dela.
- `chave.bin`: a chave dos bancos, protegida pela DPAPI do Windows. Só abre no **mesmo usuário do Windows, na
  mesma máquina**: copiar a pasta para outro computador não dá acesso aos dados.

Gravação com WAL e `synchronous=FULL`: cada venda vai até o disco antes de o caixa seguir (queda de energia não
deixa venda pela metade). Banco ilegível (chave de outro usuário, arquivo danificado ou alterado) é guardado à parte
como `quack.db.ilegivel-<data>` e o caixa avisa na tela de login.

Na primeira abertura desta versão, os arquivos JSON das versões anteriores (`users.json`, `cache/`, `outbox/`)
entram no banco numa transação e só então são apagados. Não apague `quack.db`, `fila-copia.db` nem `chave.bin` com
vendas pendentes: eles ficam fora da pasta do programa justamente para sobreviver a atualizações.

## Atalhos

| Tecla | Ação |
|---|---|
| F1 / F3 | Reforço / Sangria |
| F2 / F4 | Foco na busca / na quantidade |
| F5 | Escolher cliente |
| F6 | Painel do caixa (abrir / fechar) |
| F7 | Cancelar NFC-e (ainda não disponível: avisa para usar o caixa web) |
| F8 | Cancelar venda |
| F9 | Vendas do caixa atual (busca, filtro por forma, detalhe da venda) |
| F10 | Sincronizar (catálogo + fila offline) |
| F11 | Tela cheia (igual ao caixa web); reimpressão pelo botão "Imprimir última venda" |
| F12 | Pagamento |
| Delete | Remove o item selecionado do carrinho |
| ↑ / ↓ | Navega nas sugestões da busca |
| Alt+1..5 | Forma de pagamento: Dinheiro, PIX, Débito, Crédito, Fiado |
| Enter | No pagamento: adiciona a parcela ou confirma a venda |
| Esc | Fecha o diálogo |
| 00 + Enter | Ajuda com os atalhos |

## Modo offline (igual ao caixa web)

- **Login offline:** a cada login online o caixa guarda só o hash da senha (PBKDF2-SHA256, 310 mil iterações,
  salt aleatório) no banco criptografado. Sem internet, entra por esse hash por até **7 dias** desde o último login
  online (`offlineMaxDays` no config só reduz esse prazo; relógio atrasado também bloqueia). Quando a API volta, o
  login é refeito sozinho (a senha fica só na memória) para sincronizar. Se o servidor recusar a senha ou o usuário
  (senha trocada, usuário desativado), o acesso offline dele é apagado neste terminal.
- **Preço na venda offline:** a API aceita a venda offline com preço abaixo do cadastro sem senha de gerente (ela já
  aconteceu), mas grava o evento `PrecoOfflineAbaixo` no Log de Atividades (Relatórios), com produto, preço cobrado
  e preço do cadastro, para o gerente revisar. Teste: `PrecoOfflineE2ETest`.
- **Caixa offline:** abrir, fechar, sangria e reforço tentam a API; se a falha for de conexão (sem rede, timeout,
  408/429, 5xx) a operação é gravada localmente e entra na fila com o mesmo `eventId`. A abertura offline cria a
  sessão `cx-offline-<ms>` e manda o horário real (`clientSessionId`/`openedAt`), então a nuvem cria o mesmo turno.
- **Uma fila só, em ordem:** vendas e operações de caixa ficam em `outbox/pending/` na ordem em que aconteceram;
  enquanto houver operação de caixa na fila, tudo segue pela fila (a abertura chega antes das vendas e do fechamento).
- **Totais offline:** dinheiro esperado na gaveta = fundo de troco + vendas em dinheiro (servidor + fila) +
  reforços − sangrias, como em `CashSessionRepository.ts`.
- Testado ponta a ponta contra a API (`OfflineE2ETest`): turno inteiro offline sincronizou com o mesmo id de sessão,
  a venda dentro dele e diferença zero no fechamento.

## Limitações desta versão

- Sem pedidos do vendedor, NF-e 55, alteração de preço com senha de gerente, cancelamento de venda/NFC-e,
  Local Gateway e crédito compartilhado (a NFC-e continua sendo emitida pelo servidor).
- Se o reCAPTCHA estiver ligado no servidor (`Recaptcha:Enabled=true`) o login do Java será recusado.
- A sessão usa o cookie `horuspdv.auth` e também reenvia o token como `Authorization: Bearer`, porque em
  produção o cookie é `Secure` e não volta se a API for acessada por `http://`.
- Comprovantes iguais aos da web, na impressora padrão do Windows (papel de 80 mm, `ReceiptPrinter.RECEIPT_WIDTH_PX`):
  - **DANFE NFC-e** montado só do XML autorizado (`GET /api/Nfce/{venda}`), com chave de acesso, protocolo,
    QR Code oficial, avisos de homologação/contingência, consumidor e tributos (porta de `danfePrint.ts`/`danfeXml.ts`);
  - **cupom não fiscal** com QR Code dos dados da venda e bloco de fiado com novo saldo e assinatura
    (porta de `buildReceiptPrintHtml`).
  - Após a venda com NFC-e, o caixa espera a nota ficar pronta (até ~30 s) e imprime o DANFE; "Imprimir última venda"
    abre a pré-visualização e consulta a nota de novo a cada 1,5 s enquanto ela não chega.
- Só tema claro.
- Fontes embutidas (Nunito e Roboto Mono): o JavaFX desenha o negrito das fontes do sistema com as letras
  trocadas no Windows 11, então o caixa não usa nenhuma fonte do sistema.
- Busca de pedido do vendedor aparece na tela, mas ainda não está disponível neste caixa.

## Paridade com o caixa web (`SalesStartPage.tsx`)

- [x] Atalhos F1 a F12, Esc e Alt+1..5 iguais aos do caixa web (F7 só avisa: cancelamento de NFC-e fica na web).
- [x] Layout da tela, do pagamento, do painel de caixa e de "Vendas do Caixa Atual" no mesmo desenho da web.
- [x] Mensagens: "Estoque insuficiente. Disponível: X.", "Produto não encontrado.", "Faltam R$ X para liquidar a venda.",
      "Limite de crédito insuficiente para NOME! Limite total ... | Saldo devedor ... | Disponível ... | Parcela fiado ...".
- [x] Regras de centavos (`productMoney`, `itemNet`, `validateSale`), promoções (6 tipos, melhor desconto sem empilhar)
      e etiqueta de balança portadas de `saleMoney.ts`, `promotionEngine.ts`, `balancaBarcode.ts`.
- [x] Payload da venda e `payloadHash` canônico como em `cryptoHash.ts` / `HorusPayloadHash.cs`.
- [ ] Não portado (fora de escopo): F7 cancelar NFC-e, F9 vendas do turno, restrição de sangria a quem abriu o caixa
      (a API continua validando).
