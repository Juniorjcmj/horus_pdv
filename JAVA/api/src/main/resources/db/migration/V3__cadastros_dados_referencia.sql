-- Dados de referência: categorias e treinamento padrão.

IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia', N'empresa-principal', N'Mercearia', NULL, 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas', N'empresa-principal', N'Bebidas', NULL, 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios', N'empresa-principal', N'Laticínios', NULL, 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria', N'empresa-principal', N'Padaria', NULL, 4, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue', N'empresa-principal', N'Açougue', NULL, 5, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti', N'empresa-principal', N'Hortifruti', NULL, 6, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-frios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-frios', N'empresa-principal', N'Frios', NULL, 7, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza', N'empresa-principal', N'Limpeza', NULL, 8, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene', N'empresa-principal', N'Higiene', NULL, 9, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados', N'empresa-principal', N'Congelados', NULL, 10, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere', N'empresa-principal', N'Bomboniere', NULL, 11, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bazar')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bazar', N'empresa-principal', N'Bazar', NULL, 12, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-pet')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-pet', N'empresa-principal', N'Pet', NULL, 13, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-tabacaria')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-tabacaria', N'empresa-principal', N'Tabacaria', NULL, 14, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia-graos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia-graos', N'empresa-principal', N'Arroz e Grãos', N'cat-mercearia', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia-oleos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia-oleos', N'empresa-principal', N'Óleos e Temperos', N'cat-mercearia', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia-massas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia-massas', N'empresa-principal', N'Massas e Molhos', N'cat-mercearia', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia-enlatados')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia-enlatados', N'empresa-principal', N'Conservas e Enlatados', N'cat-mercearia', 4, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-mercearia-farinhas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-mercearia-farinhas', N'empresa-principal', N'Farinhas e Açúcares', N'cat-mercearia', 5, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-refrigerantes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-refrigerantes', N'empresa-principal', N'Refrigerantes', N'cat-bebidas', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-cervejas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-cervejas', N'empresa-principal', N'Cervejas', N'cat-bebidas', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-sucos-aguas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-sucos-aguas', N'empresa-principal', N'Sucos e Águas', N'cat-bebidas', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-destilados')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-destilados', N'empresa-principal', N'Destilados e Vinhos', N'cat-bebidas', 4, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-leite')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-leite', N'empresa-principal', N'Leites e Derivados', N'cat-laticinios', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-iogurtes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-iogurtes', N'empresa-principal', N'Iogurtes e Sobremesas', N'cat-laticinios', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-manteigas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-manteigas', N'empresa-principal', N'Manteigas e Requeijão', N'cat-laticinios', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-paes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-paes', N'empresa-principal', N'Pães', N'cat-padaria', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-bolos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-bolos', N'empresa-principal', N'Bolos e Tortas', N'cat-padaria', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-biscoitos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-biscoitos', N'empresa-principal', N'Biscoitos e Torradas', N'cat-padaria', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-bovinos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-bovinos', N'empresa-principal', N'Bovinos', N'cat-acougue', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-aves')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-aves', N'empresa-principal', N'Aves', N'cat-acougue', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-suinos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-suinos', N'empresa-principal', N'Suínos', N'cat-acougue', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-frutas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-frutas', N'empresa-principal', N'Frutas', N'cat-hortifruti', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-legumes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-legumes', N'empresa-principal', N'Legumes', N'cat-hortifruti', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-verduras')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-verduras', N'empresa-principal', N'Verduras e Temperos', N'cat-hortifruti', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-frios-queijos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-frios-queijos', N'empresa-principal', N'Queijos', N'cat-frios', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-frios-embutidos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-frios-embutidos', N'empresa-principal', N'Embutidos e Presuntos', N'cat-frios', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-lavanderia')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-lavanderia', N'empresa-principal', N'Lavanderia', N'cat-limpeza', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-desinfetantes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-desinfetantes', N'empresa-principal', N'Desinfetantes e Multiuso', N'cat-limpeza', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-utensilios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-utensilios', N'empresa-principal', N'Utensílios de Limpeza', N'cat-limpeza', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-banho')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-banho', N'empresa-principal', N'Sabonetes e Banhos', N'cat-higiene', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-cabelo')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-cabelo', N'empresa-principal', N'Cabelos', N'cat-higiene', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-bucal')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-bucal', N'empresa-principal', N'Higiene Bucal', N'cat-higiene', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-papeis')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-papeis', N'empresa-principal', N'Papéis e Descartáveis', N'cat-higiene', 4, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-pratos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-pratos', N'empresa-principal', N'Pratos Prontos e Pizzas', N'cat-congelados', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-hamburguer')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-hamburguer', N'empresa-principal', N'Hambúrgueres e Empanados', N'cat-congelados', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-sorvetes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-sorvetes', N'empresa-principal', N'Sorvetes', N'cat-congelados', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-chocolates')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-chocolates', N'empresa-principal', N'Chocolates', N'cat-bomboniere', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-balas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-balas', N'empresa-principal', N'Balas e Chicletes', N'cat-bomboniere', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-snacks')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-snacks', N'empresa-principal', N'Salgadinhos e Snacks', N'cat-bomboniere', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bazar-utilidades')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bazar-utilidades', N'empresa-principal', N'Utilidades Domésticas', N'cat-bazar', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bazar-acessorios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bazar-acessorios', N'empresa-principal', N'Pilhas e Acessórios', N'cat-bazar', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-pet-caes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-pet-caes', N'empresa-principal', N'Ração Cães', N'cat-pet', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-pet-gatos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-pet-gatos', N'empresa-principal', N'Ração Gatos', N'cat-pet', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-tabacaria-cigarros')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-tabacaria-cigarros', N'empresa-principal', N'Cigarros e Isqueiros', N'cat-tabacaria', 1, 1);

IF NOT EXISTS (SELECT 1 FROM TreinamentoSecoes WHERE Id = N'trs-primeiros-passos')
    INSERT INTO TreinamentoSecoes (Id, Nome, Descricao, Ordem) VALUES (N'trs-primeiros-passos', N'Primeiros passos', N'Como acessar e começar a usar o Quack Sistemas.', 1);
IF NOT EXISTS (SELECT 1 FROM TreinamentoSecoes WHERE Id = N'trs-caixa')
    INSERT INTO TreinamentoSecoes (Id, Nome, Descricao, Ordem) VALUES (N'trs-caixa', N'Caixa', N'Tudo sobre a frente de caixa: abertura, vendas, ajustes e fechamento.', 2);

IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-entrar-sistema')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-entrar-sistema', N'trs-primeiros-passos', N'Como entrar no Quack Sistemas',
         N'Passo a passo para acessar o sistema com seu usuário e senha.',
         N'Acesse o endereço do sistema no navegador.' + CHAR(10) + N'Informe seu e-mail e sua senha.' + CHAR(10) + N'Clique em Entrar.' + CHAR(10) + N'Em caso de dúvida na senha, fale com o administrador da sua loja.',
         N'uEj6GUdI0BI', 1);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-abertura-caixa')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-abertura-caixa', N'trs-caixa', N'Abertura de caixa',
         N'Como abrir o caixa informando o fundo de troco.',
         N'Abra a tela Caixa.' + CHAR(10) + N'Informe o valor do fundo de troco.' + CHAR(10) + N'Confirme a abertura do caixa.' + CHAR(10) + N'Pronto: o PDV já pode registrar vendas.',
         N'SzV2ejoasmw', 1);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-venda-simples')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-venda-simples', N'trs-caixa', N'Fazer uma venda simples',
         N'Registrando uma venda do início ao fim.',
         N'Na tela de Vendas, busque o produto pelo nome ou código de barras.' + CHAR(10) + N'Informe a quantidade e adicione o item.' + CHAR(10) + N'Clique em Pagamento, escolha a forma de pagamento e informe o valor.' + CHAR(10) + N'Confirme a venda e entregue o troco, se houver.',
         N'VWoaz46u564', 2);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-venda-fiado')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-venda-fiado', N'trs-caixa', N'Venda fiado',
         N'Como vender no fiado (crediário) para um cliente cadastrado.',
         N'Adicione os produtos à venda.' + CHAR(10) + N'No pagamento, escolha Fiado.' + CHAR(10) + N'Selecione o cliente.' + CHAR(10) + N'Confirme a venda: o valor fica lançado no fiado do cliente.',
         N'uBAkCw_WxEw', 3);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-alterar-valor')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-alterar-valor', N'trs-caixa', N'Alteração do valor do produto no caixa',
         N'Mudando o preço de um item na venda com a senha de um gerente.',
         N'Clique no ícone de preço do item.' + CHAR(10) + N'Informe o novo valor.' + CHAR(10) + N'Peça ao gerente para digitar a senha de autorização.' + CHAR(10) + N'O item passa a valer o novo preço apenas nesta venda.',
         N'4ZAPZEemohI', 4);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-vendas-caixa')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-vendas-caixa', N'trs-caixa', N'Relatório de vendas do caixa',
         N'Consultando as vendas feitas no caixa atual.',
         N'Abra Vendas do Caixa na tela do PDV.' + CHAR(10) + N'Use os filtros (forma de pagamento, valor) para localizar vendas.' + CHAR(10) + N'O total no topo acompanha os filtros aplicados.',
         N'EXcpcR43h9o', 5);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-cancelar-venda')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-cancelar-venda', N'trs-caixa', N'Cancelamento de venda',
         N'Como cancelar uma venda já finalizada a partir do caixa.',
         N'Abra Vendas do Caixa e localize a venda.' + CHAR(10) + N'Escolha a opção de cancelar.' + CHAR(10) + N'Informe o motivo e confirme.' + CHAR(10) + N'O estoque e os valores do caixa são ajustados.',
         N'otCFMlPVScQ', 6);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-sangria-reforco')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-sangria-reforco', N'trs-caixa', N'Sangria e reforço',
         N'Retirando dinheiro do caixa (sangria) e colocando troco (reforço).',
         N'Abra a tela Caixa.' + CHAR(10) + N'Escolha Sangria (retirada) ou Reforço (entrada de dinheiro).' + CHAR(10) + N'Informe o valor e o motivo.' + CHAR(10) + N'Confirme: o movimento entra no relatório de fechamento.',
         N'j6MFC1eT9Zc', 7);
IF NOT EXISTS (SELECT 1 FROM TreinamentoVideos WHERE Id = N'trv-fechamento-caixa')
    INSERT INTO TreinamentoVideos (Id, SecaoId, Titulo, Descricao, Instrucoes, YoutubeId, Ordem) VALUES (N'trv-fechamento-caixa', N'trs-caixa', N'Fechamento de caixa',
         N'Conferindo valores e fechando o caixa no fim do turno.',
         N'Abra a tela Caixa e clique em Fechar caixa.' + CHAR(10) + N'Informe o dinheiro contado na gaveta.' + CHAR(10) + N'Confira o resumo por forma de pagamento, sangrias e reforços.' + CHAR(10) + N'Confirme o fechamento e imprima o relatório.',
         N'OE_mPHYK8cE', 8);
