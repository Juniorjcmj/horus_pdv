SET NOCOUNT ON;
-- Snapshot do esquema (colunas, indices e chaves estrangeiras) em JSON. Roda igual no banco criado pela API .NET
-- (gera contrato/esquema/esquema-dotnet.json) e no banco criado pelo Flyway (EsquemaEquivalenteIT compara os dois).
SELECT
    (SELECT t.name AS tabela, c.name AS coluna, ty.name AS tipo, c.max_length AS tamanho, c.precision AS precisao,
            c.scale AS escala, c.is_nullable AS aceitaNulo, c.is_identity AS identidade,
            OBJECT_DEFINITION(c.default_object_id) AS padrao
       FROM sys.tables t
       JOIN sys.columns c ON c.object_id = t.object_id
       JOIN sys.types ty ON ty.user_type_id = c.user_type_id
      WHERE t.name NOT IN (N'__MigrationHistory', N'flyway_schema_history')
      ORDER BY t.name, c.name
        FOR JSON PATH) AS colunas,
    (SELECT t.name AS tabela,
            -- PK/UNIQUE sem nome ganham sufixo aleatorio (8 hex finais) a cada criacao: normaliza.
            CASE WHEN i.name LIKE N'%[_][_][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]'
                 THEN LEFT(i.name, LEN(i.name) - 8) + N'********' ELSE i.name END AS indice,
            i.type_desc AS tipo, i.is_unique AS unico,
            i.is_primary_key AS chavePrimaria, i.is_unique_constraint AS restricaoUnica,
            i.filter_definition AS filtro,
            STUFF((SELECT N',' + c.name
                          + CASE WHEN ic.is_descending_key = 1 THEN N' DESC' ELSE N'' END
                          + CASE WHEN ic.is_included_column = 1 THEN N' (incluida)' ELSE N'' END
                     FROM sys.index_columns ic
                     JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                    WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
                    ORDER BY ic.is_included_column, ic.key_ordinal, c.name
                      FOR XML PATH(N''), TYPE).value(N'.', N'nvarchar(max)'), 1, 1, N'') AS colunas
       FROM sys.indexes i
       JOIN sys.tables t ON t.object_id = i.object_id
      WHERE i.type > 0 AND t.name NOT IN (N'__MigrationHistory', N'flyway_schema_history')
      ORDER BY t.name, 2
        FOR JSON PATH) AS indices,
    (SELECT fk.name AS chave, pt.name AS tabela, rt.name AS tabelaReferenciada,
            fk.delete_referential_action_desc AS aoApagar, fk.update_referential_action_desc AS aoAtualizar,
            fk.is_not_trusted AS naoConfiavel
       FROM sys.foreign_keys fk
       JOIN sys.tables pt ON pt.object_id = fk.parent_object_id
       JOIN sys.tables rt ON rt.object_id = fk.referenced_object_id
      WHERE pt.name NOT IN (N'__MigrationHistory', N'flyway_schema_history')
      ORDER BY fk.name
        FOR JSON PATH) AS chaves
FOR JSON PATH, WITHOUT_ARRAY_WRAPPER;
