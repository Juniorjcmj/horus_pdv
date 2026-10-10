# Referências fiscais oficiais

Referência obtida em 10/10/2026. Estes arquivos são dados públicos, sem informações da loja.

- `ncm.json`: 10.516 códigos completos de oito dígitos, descrições hierárquicas e vigência. Fonte: https://portalunico.siscomex.gov.br/classif/api/publico/nomenclatura/download/json
- `cclass.json`: 173 classificações em 18 grupos CST, vigência, modelos permitidos e indicadores usados pela conferência. Fonte: https://dfe-portal.svrs.rs.gov.br/CFF/ClassificacaoTributaria

A conferência tenta consultar as duas fontes públicas; mantém cache de 24 horas. Se a consulta falhar, usa o último cache ou esta referência e informa a limitação. Produtos, certificados, CSC e dados da empresa não são enviados às fontes.

A emissão usa uma referência versionada, sem downloads durante a venda. As regras implementadas cobrem vendas comuns de 2026: regime normal, CST IBS/CBS 000/200/400/410 sem grupos especiais; Simples/MEI/excesso de sublimite não recebem as alíquotas de teste do regime normal em 2026. Outros enquadramentos exigem revisão e implementação específica. Uma atualização da tabela de consulta não atualiza automaticamente o cálculo do emissor. Antes de mudar esta referência, validar indicadores, schemas, regras e período de vigência. Não reutilizar as alíquotas de 2026 em 2027.
