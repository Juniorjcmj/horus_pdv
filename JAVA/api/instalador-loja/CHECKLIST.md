# Checklist de implantação do servidor da loja

Roteiro da spec `instalacao-servidor-loja`. **Cada item precisa de uma verificação registrada (coluna "Resultado")
antes de liberar a loja para vender.** Se um item falhar, pare e resolva; não pule.

Siga na ordem. Convenções: `<destino>` = pasta de instalação (padrão `C:\QuackLoja`); `<IP>` = IP fixo do servidor.

## 0. Antes de começar (na loja)

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 0.1 | Máquina **dedicada** (não é caixa), Windows 64 bits, mínimo 8 GB de RAM e 50 GB livres | `systeminfo`; espaço no disco do destino | |
| 0.2 | **Nobreak** ligado ao servidor e ao roteador/switch | Desligar da tomada: a máquina segue ligada | |
| 0.3 | **IP fixo** no servidor (reserva no roteador ou IP estático) | `ipconfig /all` mostra "DHCP habilitado: Não" (ou reserva confirmada no roteador) | |
| 0.4 | Rede da loja **sem Wi-Fi de clientes**: HTTP na LAN expõe senha e token | Confirmar com o dono | |
| 0.5 | Banco da loja na nuvem **ativo e com dados**; administrador da empresa sabe e-mail e senha | Login no painel da nuvem | |
| 0.6 | Os caixas **ainda na nuvem** não têm venda pendente na fila offline (a carga captura a nuvem neste momento) | Fila offline de cada caixa vazia | |

## 1. Instalação

Copie `quack-servidor-loja.zip` para a máquina, extraia e abra o PowerShell **como Administrador** na pasta.

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 1.1 | `.\instalar.ps1 -SomenteValidar` | Todas as linhas `[ OK ]` (avisos conferidos); "nada foi alterado" | |
| 1.2 | `.\instalar.ps1 -EmailAdmin <email>` (pede a senha). Se faltar internet: `-MidiaSql <SQLEXPR_x64_ENU.exe>` | Termina em "Instalação concluída" e mostra o endereço `http://<IP>:8080` | |
| 1.3 | Serviço criado com início automático | `Get-Service QuackLojaApi` = Running; `(Get-CimInstance Win32_Service -Filter "Name='QuackLojaApi'").StartMode` = Auto | |
| 1.4 | Tarefa de backup criada | `Get-ScheduledTask QuackLoja-Backup` = Ready, gatilho diário 02:00 | |
| 1.5 | Segredos próprios gerados e protegidos | `icacls <destino>\config\loja.env` lista só Administradores e SYSTEM. **Guarde cópia do `loja.env` fora da máquina, em cofre**: sem a chave de cifra o backup não decifra credenciais | |

## 2. Registro e carga inicial

O instalador faz as duas etapas; confira o resultado.

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 2.1 | Servidor registrado e ativado | Painel/consulta na nuvem: `SyncServidores.status = ativo` para a empresa | |
| 2.2 | Carga completa | No servidor, `GET http://localhost:8080/api/loja-sync/situacao` (admin): `atrasoSegundos` 0, `pausada` false | |
| 2.3 | Conferência sem diferença | `POST /api/loja-sync/conferencia` (admin) e depois a situação: `ultimaConferencia.resultado = ok` | |
| 2.4 | Primeiro backup | Existe `<destino>\backups\quack-quackloja-*.bak` e `<destino>\estado\backup-estado.json` com `"sucesso":true`; a situação mostra `ultimoBackup` | |

## 3. Rede

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 3.1 | Firewall só para a sub-rede da loja | `Get-NetFirewallRule -DisplayName 'Quack Loja*' \| Get-NetFirewallAddressFilter`: API liberada só para a sub-rede; regra de bloqueio da porta 1433 | |
| 3.2 | De um caixa: `http://<IP>:8080/actuator/health` responde `UP` | Abrir no navegador do caixa | |
| 3.3 | **Fora da sub-rede, recusa**: de um celular em rede móvel ou outra sub-rede, `http://<IP>:8080` não conecta | Tentativa registrada | |
| 3.4 | **Banco inacessível de fora da máquina**: de outro computador, `Test-NetConnection <IP> -Port 1433` = `False`; no servidor, `netstat -ano \| findstr 1433` mostra só `127.0.0.1:1433` | Saída registrada | |

## 4. Troca do endereço em cada caixa

Faça um caixa por vez. **Só troque o endereço com a fila offline do caixa vazia** (sem vendas pendentes).

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 4.1 | Caixa 1 sem vendas pendentes na fila offline | Tela da fila vazia | |
| 4.2 | Trocar o endereço da API para `http://<IP>:8080` na configuração do caixa (sem nova versão do programa) | Login e uma venda de teste passam | |
| 4.3 | Quack Estoque (se usado) idem | Consulta de produto funciona | |
| 4.4 | Repetir para cada caixa | Todos vendem; a venda de cada um aparece na nuvem em até 1 minuto (`versaoConfirmada` sobe) | |
| 4.5 | Cancelar/estornar as vendas de teste | Estoque e caixa conferem | |

## 5. Teste de queda de internet

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 5.1 | Desligar a internet **do servidor** (cabo do roteador para a operadora). Os caixas seguem na LAN | Vender em 2 caixas por ~10 min: tudo funciona | |
| 5.2 | A situação mostra atraso crescendo e `nuvemAcessivel = false`; **sem** pausa | `GET /api/loja-sync/situacao` | |
| 5.3 | Religar a internet | Em poucos minutos `atrasoSegundos` volta a 0 | |
| 5.4 | As vendas feitas durante a queda estão na nuvem, sem duplicar | Contagem de vendas do período na loja = na nuvem; conferência `ok` | |
| 5.5 | NFC-e ficam pendentes sem internet e são emitidas ao voltar (a emissão só entra em produção com a Fase 7) | Registrar o comportamento observado | |

## 6. Teste de queda do servidor

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 6.1 | Com os caixas vendendo, desligar o servidor (ou `Stop-Service QuackLojaApi`) | Os caixas continuam vendendo **offline**, na fila de cada um, **sem pular para a nuvem** | |
| 6.2 | Religar o servidor | O serviço sobe sozinho (reinício da máquina: sem ação do operador) | |
| 6.3 | Os caixas reconectam e reenviam a fila | Todas as vendas offline aparecem no servidor, **sem duplicar** | |
| 6.4 | Reiniciar a máquina do servidor com o caixa aberto | `Get-Service QuackLojaApi` volta a Running sem login de ninguém | |

## 7. Restauração do backup

> **O que a restauração faz com a nuvem, e por quê há uma recuperação.** O backup é sempre mais antigo que a nuvem: as
> vendas, notas, movimentos e cadastros feitos depois dele só existem lá. Como a loja é a autoridade, a conferência
> diária **apagaria da nuvem** esses dados. Por isso, depois de restaurar, o servidor **se recupera sozinho** antes de
> voltar ao normal:
>
> 1. `restaurar.ps1` restaura o banco e deixa o marcador `<destino>\config\recuperar.pendente`; a API o consome ao subir.
> 2. **O servidor não aceita vendas** (resposta 503 com `Retry-After`; os caixas ficam na fila offline deles e reenviam
>    depois, sem duplicar). Envio, conferência, comandos da nuvem e fila fiscal ficam parados.
> 3. Ele puxa da nuvem tudo o que falta (vendas, notas, movimentos, cadastros, auditoria), realinha as versões da
>    sincronização com a nuvem (nada de 409 nem de lote pulado), avança as sequências de numeração e só então libera
>    as vendas, religa o envio e a conferência. **Não é preciso decidir nada nem religar a conferência na mão.**
> 4. Sem internet, a recuperação **fica pendente** (a loja não vende e a conferência não roda) e repete a cada 15 s;
>    termina sozinha quando a internet voltar. Acompanhe por `GET /api/loja-sync/situacao` (`recuperando: true`).
>
> Alternativas: `POST /api/loja-sync/recuperar` (administrador) repete/dispara a recuperação; pela linha de comando,
> `--quack.sync.recuperacao.executar=true` (código de saída 0 = concluída). Exceção: `restaurar.ps1 -SemRecuperacao`
> só quando se sabe que a nuvem não tem nada além do backup. Se alguém restaurar o banco **sem** o script, a loja
> percebe em até 5 minutos que a nuvem está à frente da versão local e se recupera igual (mas, nesses minutos, vende:
> use sempre o script).
>
> Limites conhecidos: linhas **excluídas** depois do backup voltam (a conferência as devolve à nuvem; exclua de novo);
> uma linha da loja ainda não enviada quando o backup foi feito e alterada na nuvem depois fica com o valor da nuvem;
> `ProcessedEvents` não é sincronizado, mas vendas e movimentos de caixa reenviados depois da restauração são reconhecidos
> pelo dado que voltou da nuvem (`ClientSaleId` da venda, `Id` do movimento) e não duplicam. Só o reenvio de *fechar* caixa
> (ou de movimento de turno já fechado) é recusado com 400: **confira os caixas com fila offline** ao final.
> Disco perdido ou instalação nova: `desinstalar.ps1 -RemoverBanco` e `instalar.ps1` (carga inicial) também funciona.


| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 7.1 | Existem backups novos todo dia e no máximo 7 | `dir <destino>\backups` mostra 7 arquivos com datas consecutivas; o mais velho é apagado a cada novo | |
| 7.2 | Backup manual: `schtasks /run /tn QuackLoja-Backup` | `backup-estado.json` com `sucesso:true` e horário novo | |
| 7.3 | **Falha de backup vira alerta**: encher/negar a pasta de backups de teste (ou `icacls` negando escrita) e rodar o backup | `backup-estado.json` com `sucesso:false`; em até 1 minuto a situação mostra a falha e chega e-mail ao administrador (se SMTP) | |
| 7.4 | Restaurar: `<destino>\scripts\restaurar.ps1` (pede confirmação) | Termina com "Banco restaurado. RECUPERACAO pedida"; serviço volta a Running; existe (ou já foi consumido) `<destino>\config\recuperar.pendente` | |
| 7.5 | **Recuperando**: logo após subir, `GET /api/loja-sync/situacao` (admin) | `recuperando: true`, `recuperacao.estado` `executando` ou `pendente`, `fase` e `linhasPuxadas` andando; um caixa que tente vender recebe 503 | |
| 7.6 | **Termina sozinha**: aguarde (depende do tamanho do banco) | `recuperando` some; `recuperacao.estado = concluida`; o marcador foi apagado; as vendas feitas depois do backup aparecem na loja (contagem de vendas do dia igual à da nuvem) | |
| 7.7 | **Numeração**: faça uma venda de teste | O número da venda é o seguinte ao maior que já existia; a próxima NFC-e pendente usa número acima do último da nuvem (sem repetir) | |
| 7.8 | **Conferência religada sozinha**: `POST /api/loja-sync/conferencia` (admin) | `ok`; a contagem de vendas da nuvem **não diminuiu**; `findstr conferencia <destino>\config\loja.env` não mostra `false` | |
| 7.9 | **Sem internet**: restaure com o cabo da operadora desligado (ou rode o item 7.4 com a internet fora) | A situação mostra `recuperacao.estado = pendente` com o erro; a loja não vende; a conferência continua parada; ao religar a internet a recuperação termina sozinha | |
| 7.10 | Caixas com fila offline: confira se alguma venda aparece duplicada depois que eles reenviam | Nenhuma duplicata (ou anotada para o dono) | |


## 8. Liberação

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 8.1 | Todos os itens acima com resultado registrado | Assinatura do responsável e do dono | |
| 8.2 | Anotados em local seguro: IP fixo, endereço dos caixas, e-mail do administrador, onde está a cópia do `loja.env` | | |
| 8.3 | Telefone de suporte e instrução "se o servidor cair: os caixas vendem offline; ligue para..." fixados no balcão | | |

## Problemas já vistos e como resolver

Da primeira instalação real (loja de material de construção, 06-07/11/2026). Os itens marcados *corrigido* já estão
neste pacote; ficam aqui para reconhecer o sintoma se aparecer numa versão antiga.

| Sintoma | Causa | O que fazer |
|---|---|---|
| `-SomenteValidar` acusa `app\quack-pdv-api.jar`, `jre\bin\java.exe` e `winsw\WinSW-x64.exe` ausentes | Foi copiada a pasta `instalador-loja` do código-fonte, não o pacote | Copiar e extrair `quack-servidor-loja.zip` |
| "a execução de scripts foi desabilitada neste sistema" | Política do PowerShell; vale por janela | `Set-ExecutionPolicy -Scope Process Bypass -Force` em **cada** janela nova |
| `Could not find or load main class .encoding=UTF-8` na carga | PowerShell 5.1 quebrava `-Dfile.encoding=UTF-8` sem aspas | *Corrigido* (argumentos do java entre aspas) |
| Carga para em `FK_Produtos_Categoria` | Produtos da empresa ligados às categorias padrão da `empresa-principal`, que a carga não trazia | *Corrigido na nuvem* (a carga leva as categorias de fora usadas pela empresa). Se a carga já tinha passado das categorias, reabrir: `UPDATE SyncCarga SET Concluida = 0, UltimaChave = NULL WHERE Tabela = N'Categorias'` e rodar `instalar.ps1` de novo |
| Comando SQL manual: "O tempo limite de espera foi atingido" em `localhost,1433` | `localhost` tenta IPv6 primeiro; o SQL só escuta em `127.0.0.1` | Usar `lpc:localhost\QUACK`. *Corrigido* nos scripts (o backup diário usava o endereço errado) |
| "Acompanhar a sincronização" nunca chega a atraso 0 (versão local sempre 1 à frente) | O acompanhamento fazia login a cada 10 s e cada login grava uma mudança | *Corrigido* (um login só). Numa versão antiga: interromper e rodar `instalar.ps1 -EmailAdmin … -PularCarga` |
| Atraso crescendo sem erro, versão confirmada parada | Só havia mudança que não sobe (outra empresa); a loja não avisava a nuvem | *Corrigido* (a loja envia lote vazio e a nuvem confirma a versão) |
| Backup falha: "BACKUP DATABASE WITH COMPRESSION is not supported on Express Edition" (`backup-estado.json` com `sucesso:false`; na instalação, só o aviso "O primeiro backup falhou") | O SQL Server Express não aceita backup com compressão | *Corrigido* (sem compressão no Express). Numa instalação antiga: copiar o `backup.ps1` novo para `<destino>\scripts\` e rodar `schtasks /run /tn QuackLoja-Backup` |
| Aviso "IP fixo — dinâmico (DHCP)" | — | Fixar o IP (Windows ou reserva no roteador) **antes** de apontar os caixas |

Para rodar comandos SQL na loja sem SQL Management Studio (PowerShell como Administrador, na pasta do pacote):

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
. .\lib\comum.ps1
$con = Get-StringConexaoSql -Servidor 'lpc:localhost\QUACK' -Banco 'QuackLoja'
Invoke-SqlComando -StringConexao $con -Escalar -Sql "SELECT CONCAT('VersaoDe=', VersaoDe, ' Erro=', UltimoErro, ' Pausa=', PausadaMotivo) FROM SyncEstado WHERE Id = 1"
```

## Limites conhecidos

- SQL Server Express limita o banco a **10 GB** por banco. Acompanhe o tamanho (`<destino>\backups` dá a ideia); o
  servidor da loja guarda só a loja, mas planeje a revisão anual.
- Recuperação do banco é `SIMPLE` (backup completo diário): perda máxima de até 24 h em disco perdido. A nuvem guarda o
  que já subiu.
- O Windows Update pode reiniciar a máquina: defina o horário de uso fora do expediente.
- HTTP na LAN (sem TLS): senha e token trafegam em claro na rede da loja. HTTPS é melhoria futura.
