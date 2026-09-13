/*
 * Arquivo: API/NETCORE/DataBase/Migrations/09_categorias_produto.sql
 * Objetivo: cria tabela Categorias (hierarquia de 2 níveis Departamento -> Subcategoria),
 *           vincula CategoriaId em Produtos com integridade referencial e insere seed
 *           idempotente de 14 departamentos padrão de mercadinho + subcategorias essenciais.
 *
 * Idempotente: pode ser executado no boot repetidamente sem erro.
 */

USE HorusPdv;
GO

SET NOCOUNT ON;
GO

/* ------------------------------------------------------------------------- */
/* 1. Tabela Categorias                                                      */
/* ------------------------------------------------------------------------- */
IF OBJECT_ID(N'Categorias', N'U') IS NULL
BEGIN
    CREATE TABLE Categorias (
        Id              NVARCHAR(40)    NOT NULL,
        CompanyId       NVARCHAR(40)    NOT NULL DEFAULT 'empresa-principal',
        Nome            NVARCHAR(80)    NOT NULL,
        CategoriaPaiId  NVARCHAR(40)    NULL,
        Ordem           INT             NOT NULL DEFAULT 0,
        Ativa           BIT             NOT NULL DEFAULT 1,
        CONSTRAINT PK_Categorias PRIMARY KEY (Id),
        CONSTRAINT FK_Categorias_Pai FOREIGN KEY (CategoriaPaiId) REFERENCES Categorias(Id)
    );
END;
GO

/* Índices e Unicidade por Empresa */
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Categorias_Nome_Raiz')
BEGIN
    CREATE UNIQUE INDEX UQ_Categorias_Nome_Raiz
        ON Categorias (CompanyId, Nome)
        WHERE CategoriaPaiId IS NULL;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UQ_Categorias_Nome_Filho')
BEGIN
    CREATE UNIQUE INDEX UQ_Categorias_Nome_Filho
        ON Categorias (CompanyId, Nome, CategoriaPaiId)
        WHERE CategoriaPaiId IS NOT NULL;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Categorias_Company')
BEGIN
    CREATE INDEX IX_Categorias_Company
        ON Categorias (CompanyId, Ativa, Ordem);
END;
GO

/* ------------------------------------------------------------------------- */
/* 2. Campo CategoriaId na tabela Produtos                                   */
/* ------------------------------------------------------------------------- */
IF COL_LENGTH(N'Produtos', N'CategoriaId') IS NULL
BEGIN
    ALTER TABLE Produtos ADD CategoriaId NVARCHAR(40) NULL;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.foreign_keys WHERE name = N'FK_Produtos_Categoria')
BEGIN
    ALTER TABLE Produtos ADD CONSTRAINT FK_Produtos_Categoria
        FOREIGN KEY (CategoriaId) REFERENCES Categorias(Id) ON DELETE SET NULL;
END;
GO

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Produtos_Company_Categoria')
BEGIN
    CREATE INDEX IX_Produtos_Company_Categoria
        ON Produtos (CompanyId, CategoriaId);
END;
GO

/* ------------------------------------------------------------------------- */
/* 3. Seed Idempotente de Departamentos e Subcategorias (Mercadinho)         */
/* ------------------------------------------------------------------------- */

-- Helper macro: insere departamento se não existir
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
GO

-- Subcategorias de Mercearia
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

-- Subcategorias de Bebidas
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-refrigerantes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-refrigerantes', N'empresa-principal', N'Refrigerantes', N'cat-bebidas', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-cervejas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-cervejas', N'empresa-principal', N'Cervejas', N'cat-bebidas', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-sucos-aguas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-sucos-aguas', N'empresa-principal', N'Sucos e Águas', N'cat-bebidas', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bebidas-destilados')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bebidas-destilados', N'empresa-principal', N'Destilados e Vinhos', N'cat-bebidas', 4, 1);

-- Subcategorias de Laticínios
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-leite')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-leite', N'empresa-principal', N'Leites e Derivados', N'cat-laticinios', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-iogurtes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-iogurtes', N'empresa-principal', N'Iogurtes e Sobremesas', N'cat-laticinios', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-laticinios-manteigas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-laticinios-manteigas', N'empresa-principal', N'Manteigas e Requeijão', N'cat-laticinios', 3, 1);

-- Subcategorias de Padaria
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-paes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-paes', N'empresa-principal', N'Pães', N'cat-padaria', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-bolos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-bolos', N'empresa-principal', N'Bolos e Tortas', N'cat-padaria', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-padaria-biscoitos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-padaria-biscoitos', N'empresa-principal', N'Biscoitos e Torradas', N'cat-padaria', 3, 1);

-- Subcategorias de Açougue
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-bovinos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-bovinos', N'empresa-principal', N'Bovinos', N'cat-acougue', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-aves')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-aves', N'empresa-principal', N'Aves', N'cat-acougue', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-acougue-suinos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-acougue-suinos', N'empresa-principal', N'Suínos', N'cat-acougue', 3, 1);

-- Subcategorias de Hortifruti
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-frutas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-frutas', N'empresa-principal', N'Frutas', N'cat-hortifruti', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-legumes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-legumes', N'empresa-principal', N'Legumes', N'cat-hortifruti', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-hortifruti-verduras')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-hortifruti-verduras', N'empresa-principal', N'Verduras e Temperos', N'cat-hortifruti', 3, 1);

-- Subcategorias de Frios
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-frios-queijos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-frios-queijos', N'empresa-principal', N'Queijos', N'cat-frios', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-frios-embutidos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-frios-embutidos', N'empresa-principal', N'Embutidos e Presuntos', N'cat-frios', 2, 1);

-- Subcategorias de Limpeza
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-lavanderia')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-lavanderia', N'empresa-principal', N'Lavanderia', N'cat-limpeza', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-desinfetantes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-desinfetantes', N'empresa-principal', N'Desinfetantes e Multiuso', N'cat-limpeza', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-limpeza-utensilios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-limpeza-utensilios', N'empresa-principal', N'Utensílios de Limpeza', N'cat-limpeza', 3, 1);

-- Subcategorias de Higiene
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-banho')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-banho', N'empresa-principal', N'Sabonetes e Banhos', N'cat-higiene', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-cabelo')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-cabelo', N'empresa-principal', N'Cabelos', N'cat-higiene', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-bucal')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-bucal', N'empresa-principal', N'Higiene Bucal', N'cat-higiene', 3, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-higiene-papeis')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-higiene-papeis', N'empresa-principal', N'Papéis e Descartáveis', N'cat-higiene', 4, 1);

-- Subcategorias de Congelados
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-pratos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-pratos', N'empresa-principal', N'Pratos Prontos e Pizzas', N'cat-congelados', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-hamburguer')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-hamburguer', N'empresa-principal', N'Hambúrgueres e Empanados', N'cat-congelados', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-congelados-sorvetes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-congelados-sorvetes', N'empresa-principal', N'Sorvetes', N'cat-congelados', 3, 1);

-- Subcategorias de Bomboniere
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-chocolates')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-chocolates', N'empresa-principal', N'Chocolates', N'cat-bomboniere', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-balas')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-balas', N'empresa-principal', N'Balas e Chicletes', N'cat-bomboniere', 2, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bomboniere-snacks')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bomboniere-snacks', N'empresa-principal', N'Salgadinhos e Snacks', N'cat-bomboniere', 3, 1);

-- Subcategorias de Bazar
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bazar-utilidades')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bazar-utilidades', N'empresa-principal', N'Utilidades Domésticas', N'cat-bazar', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-bazar-acessorios')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-bazar-acessorios', N'empresa-principal', N'Pilhas e Acessórios', N'cat-bazar', 2, 1);

-- Subcategorias de Pet
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-pet-caes')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-pet-caes', N'empresa-principal', N'Ração Cães', N'cat-pet', 1, 1);
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-pet-gatos')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-pet-gatos', N'empresa-principal', N'Ração Gatos', N'cat-pet', 2, 1);

-- Subcategorias de Tabacaria
IF NOT EXISTS (SELECT 1 FROM Categorias WHERE Id = N'cat-tabacaria-cigarros')
    INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa) VALUES (N'cat-tabacaria-cigarros', N'empresa-principal', N'Cigarros e Isqueiros', N'cat-tabacaria', 1, 1);
GO
