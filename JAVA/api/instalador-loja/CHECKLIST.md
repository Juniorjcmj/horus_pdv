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

> **Atenção ao que a restauração faz com a nuvem.** O backup é sempre mais antigo que a nuvem. A conferência diária
> trata a nuvem como reflexo da loja nas tabelas da loja (vendas, caixa etc.) e **apagaria da nuvem** as vendas feitas
> depois do backup. Por isso `restaurar.ps1` **desliga a conferência** (`quack.sync.conferencia-ativa=false`). Só religue
> depois de decidir o caminho abaixo.
>
> - **Banco corrompido, backup de hoje ou de ontem**: restaure (passos abaixo). A loja volta a enviar a partir da versão
>   do backup; as vendas feitas depois dele e já confirmadas na nuvem não existem mais na loja. Decida com o dono se isso
>   é aceitável; se não, use o caminho seguinte.
> - **Disco perdido ou perda que importa**: instale do zero (`desinstalar.ps1 -RemoverBanco`, depois `instalar.ps1`): a
>   carga inicial traz da nuvem tudo o que já foi confirmado. Só o que estava na loja e ainda não subiu se perde (o
>   backup recente ajuda a conferir o que faltou).

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 7.1 | Existem backups novos todo dia e no máximo 7 | `dir <destino>\backups` mostra 7 arquivos com datas consecutivas; o mais velho é apagado a cada novo | |
| 7.2 | Backup manual: `schtasks /run /tn QuackLoja-Backup` | `backup-estado.json` com `sucesso:true` e horário novo | |
| 7.3 | **Falha de backup vira alerta**: encher/negar a pasta de backups de teste (ou `icacls` negando escrita) e rodar o backup | `backup-estado.json` com `sucesso:false`; em até 1 minuto a situação mostra a falha e chega e-mail ao administrador (se SMTP) | |
| 7.4 | Restaurar: `<destino>\scripts\restaurar.ps1` (pede confirmação) | Termina com "Banco restaurado"; serviço volta a Running; os dados voltam ao ponto do backup | |
| 7.5 | Conferência ficou **desligada** | `findstr conferencia <destino>\config\loja.env` mostra `false` | |
| 7.6 | Depois de decidir o caminho: `restaurar.ps1 -ReativarConferencia` e `Restart-Service QuackLojaApi` | Conferência manual `ok` | |

## 8. Liberação

| # | Item | Verificação | Resultado |
|---|------|-------------|-----------|
| 8.1 | Todos os itens acima com resultado registrado | Assinatura do responsável e do dono | |
| 8.2 | Anotados em local seguro: IP fixo, endereço dos caixas, e-mail do administrador, onde está a cópia do `loja.env` | | |
| 8.3 | Telefone de suporte e instrução "se o servidor cair: os caixas vendem offline; ligue para..." fixados no balcão | | |

## Limites conhecidos

- SQL Server Express limita o banco a **10 GB** por banco. Acompanhe o tamanho (`<destino>\backups` dá a ideia); o
  servidor da loja guarda só a loja, mas planeje a revisão anual.
- Recuperação do banco é `SIMPLE` (backup completo diário): perda máxima de até 24 h em disco perdido. A nuvem guarda o
  que já subiu.
- O Windows Update pode reiniciar a máquina: defina o horário de uso fora do expediente.
- HTTP na LAN (sem TLS): senha e token trafegam em claro na rede da loja. HTTPS é melhoria futura.
