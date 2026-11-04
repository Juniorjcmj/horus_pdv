# Spec Delta

## Purpose

Modo loja da API Java: um servidor dedicado na loja é a única autoridade dos dados de uma empresa e opera sem
internet, enquanto um receptor na nuvem mantém a réplica. Cobre os perfis, o registro do servidor, a carga inicial e a
ativação.

## ADDED Requirements

### Requirement: Perfis da API
A API Java SHALL ter três modos: padrão (como hoje), `loja` (servidor da loja) e `nuvem` (receptor ao lado da API .NET).
No modo `nuvem` a API SHALL expor só as rotas de sincronização e de saúde. Nesse modo ela MUST NOT alterar tabelas da
API .NET fora da aplicação da sincronização e MUST NOT rodar migrações nessas tabelas.

#### Scenario: Receptor não atende o Caixa
- **WHEN** um cliente chama `POST /api/HistoricoVendas` na API em modo `nuvem`
- **THEN** a resposta é 404

#### Scenario: Servidor da loja atende o Caixa
- **WHEN** um caixa da loja chama as rotas de caixa e venda no servidor em modo `loja`
- **THEN** elas respondem como na API Java padrão

### Requirement: Uma autoridade por empresa
Com o modo loja ativo, o servidor da loja SHALL ser a única fonte da verdade dos dados da empresa. A nuvem SHALL ser
réplica: nada que a nuvem grave para essa empresa MUST prevalecer sobre o estado da loja, salvo pelos comandos aceitos
pela loja.

#### Scenario: Venda só na loja
- **WHEN** a loja registra uma venda sem internet
- **THEN** a venda existe só na loja até a próxima sincronização, e depois existe igual nas duas pontas

### Requirement: Registro do servidor da loja
O servidor da loja SHALL se registrar na nuvem com e-mail e senha de um administrador da empresa e receber um token
próprio do servidor, mostrado uma só vez e guardado na nuvem só como hash. Cada empresa SHALL ter no máximo um
servidor ativo. Credencial errada ou perfil sem permissão SHALL ser recusado.

#### Scenario: Gerente tenta registrar
- **WHEN** um gerente tenta registrar o servidor da loja
- **THEN** o registro é recusado e nenhum token é criado

#### Scenario: Segundo servidor
- **WHEN** a empresa já tem servidor ativo e outro tenta se registrar
- **THEN** o registro é recusado com mensagem que cita o servidor ativo

### Requirement: Token do servidor
Toda chamada de sincronização SHALL exigir o token do servidor. Esse token SHALL dar acesso só aos dados da empresa
dele e MUST NOT valer como login de usuário. O token SHALL poder ser revogado na nuvem; depois disso a loja continua
operando localmente, mas não sincroniza.

#### Scenario: Token de outra empresa
- **WHEN** o servidor envia um lote com o token de outra empresa
- **THEN** o lote é recusado com 403 e nada é aplicado

### Requirement: Carga inicial e ativação
O servidor registrado SHALL baixar todos os dados da empresa num retrato consistente e aplicar as mudanças feitas
depois dele até a ativação. Só então o modo loja fica ativo. A carga SHALL poder ser retomada se cair e SHALL terminar
com contagens iguais nas duas pontas.

#### Scenario: Mudança durante a carga
- **WHEN** um produto é alterado na nuvem depois do retrato e antes da ativação
- **THEN** a alteração chega à loja antes de o modo loja ficar ativo

#### Scenario: Carga interrompida
- **WHEN** a internet cai no meio da carga
- **THEN** a carga continua do ponto em que parou quando a internet volta, sem duplicar linhas

### Requirement: Segredos da loja
O servidor da loja SHALL ter segredo de sessão (JWT) e chave de cifra próprios, diferentes dos da nuvem. Os segredos
cifrados da empresa (certificado, CSC, SMTP) SHALL chegar à loja recifrados com a chave da loja. Login feito na nuvem
MUST NOT valer na loja, e vice-versa.

#### Scenario: Token da nuvem na loja
- **WHEN** um cliente usa na loja um token de sessão emitido pela nuvem
- **THEN** a loja responde 401

#### Scenario: Senha do certificado na loja
- **WHEN** a carga inicial traz a empresa com senha de certificado cifrada
- **THEN** a loja consegue decifrá-la com a própria chave e o valor guardado é diferente do da nuvem

### Requirement: Usuários na loja
Os usuários da empresa SHALL ser copiados para a loja com as senhas como estão (hash). Assim cada um entra nos caixas
da loja com a mesma senha da nuvem, inclusive sem internet. As sessões SHALL ser locais de cada ponta.

#### Scenario: Login sem internet
- **WHEN** um operador entra no caixa com a loja sem internet
- **THEN** o login funciona com a senha que ele usa na nuvem
