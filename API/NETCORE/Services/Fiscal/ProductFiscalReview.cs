using HORUSPDV_API.Repositories.DataAccess;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record FiscalFinding(string Nivel, string Campo, string Mensagem);
public sealed record ProductFiscalReport(string ProdutoId, string ProdutoNome, DateTimeOffset VerificadoEm,
    string Uf, byte Crt, byte Ambiente, bool FontesOnline, string DataBase, string? DescricaoNcm,
    string? DescricaoClassificacao, IReadOnlyList<FiscalFinding> Apontamentos);

public static class ProductFiscalReview
{
    public static ProductFiscalReport Check(ProdutoAD p, EmpresaAD e, FiscalTableData tables, DateTimeOffset now)
    {
        var items = new List<FiscalFinding>();
        void Add(string level, string field, string message) => items.Add(new(level, field, message));
        var date = DateOnly.FromDateTime(now.DateTime);
        string? ncmDescription = null, classDescription = null;
        var ncmCode = FiscalReferenceTables.Digits(p.Ncm);
        if (ncmCode.Length != 8 || !tables.Ncms.TryGetValue(ncmCode, out var ncm))
            Add("erro", "NCM", "Código ausente ou não encontrado na tabela oficial. Confira o NCM do produto.");
        else
        {
            ncmDescription = ncm.Descricao;
            if (date < ncm.Inicio || date > ncm.Fim) Add("erro", "NCM", "Este código não está vigente na data da conferência.");
            else Add("informacao", "NCM", "Código vigente na tabela consultada. A descrição oficial abaixo deve corresponder à composição e ao uso do produto.");
            if (p.Ncm != ncmCode) Add("erro", "NCM", "O emissor espera oito dígitos sem pontuação. Ajuste o formato no cadastro.");
        }
        if (e.Crt is not (1 or 2 or 3 or 4)) Add("erro", "Empresa", "Regime tributário da empresa não reconhecido.");
        if (!string.Equals(e.Uf, "RJ", StringComparison.OrdinalIgnoreCase)) Add("erro", "Empresa", "A emissão NFC-e deste sistema está configurada para o Rio de Janeiro. Confira a UF da empresa.");
        if (e.AmbienteFiscal != 1) Add("aviso", "Empresa", "A empresa está em homologação; as notas emitidas nesse ambiente não têm valor fiscal.");
        if (p.OrigemMercadoria > 8) Add("erro", "Origem", "Origem da mercadoria deve estar entre 0 e 8.");
        if (p.Cfop.Length != 4 || !p.Cfop.All(char.IsAsciiDigit) || !p.Cfop.StartsWith('5')) Add("erro", "CFOP", "Para esta conferência de venda interna por NFC-e, informe CFOP de quatro dígitos iniciado em 5.");
        else Add("aviso", "CFOP", "Formato válido. Confirme com a contabilidade se a operação e a substituição tributária correspondem ao CFOP escolhido.");
        if (string.IsNullOrWhiteSpace(p.UnidadeComercial) || p.UnidadeComercial.Length > 6) Add("erro", "Unidade", "Informe unidade comercial de até seis caracteres.");
        if (p.UnidadeComercial != p.UnidadeTributavel) Add("aviso", "Unidade tributável", "O emissor usa a mesma unidade e quantidade comercial e tributável. Este produto requer conferência da conversão fiscal.");
        if (!string.IsNullOrWhiteSpace(p.Cest) && (p.Cest.Length != 7 || !p.Cest.All(char.IsAsciiDigit))) Add("erro", "CEST", "CEST deve ter sete dígitos sem pontuação.");
        if (e.Crt is 1 or 4)
        {
            if (p.CsosnIcms is not ("102" or "103" or "300" or "400" or "500")) Add("erro", "CSOSN", "Informe CSOSN compatível com Simples/MEI. O emissor atual suporta 102, 103, 300, 400 e 500; outros códigos precisam de dados e implementação específicos.");
        }
        else if (p.CstIcms is not ("00" or "40" or "41" or "50" or "60")) Add("erro", "CST ICMS", "Regime normal/excesso de sublimite exige CST ICMS. O emissor atual suporta 00, 40, 41, 50 e 60; os demais exigem implementação específica.");
        if (p.AliquotaIcms is < 0 or > 100) Add("erro", "ICMS", "Alíquota deve estar entre zero e 100%. Não é possível definir a alíquota só pelo NCM.");
        CheckContribution(p.CstPis, "PIS"); CheckContribution(p.CstCofins, "COFINS");
        void CheckContribution(string value, string name)
        {
            if (value is not ("04" or "05" or "06" or "07" or "08" or "09" or "49" or "99")) Add("erro", name, "Este CST exige cálculo não disponível no cadastro atual. Não use valores zerados como substituição da tributação devida.");
            else Add("aviso", name, "Confira o enquadramento com a contabilidade. O sistema não confirma isenção, alíquota zero ou tributação monofásica pela descrição do produto.");
        }
        if (p.Gtin != "SEM GTIN" && !ValidGtin(p.Gtin)) Add("erro", "GTIN", "Informe GTIN de 8, 12, 13 ou 14 dígitos com verificador válido, ou SEM GTIN se realmente não houver código GS1.");
        else if (p.Gtin != "SEM GTIN") Add("aviso", "GTIN", "Dígito verificador válido. Esta conferência não consulta o cadastro GS1 nem confirma o vínculo do código com o produto.");
        var hasCst = !string.IsNullOrWhiteSpace(p.CstIbsCbs); var hasClass = !string.IsNullOrWhiteSpace(p.CClassTrib);
        if (hasCst != hasClass) Add("erro", "IBS/CBS", "Preencha CST IBS/CBS e cClassTrib em conjunto.");
        else if (hasClass)
        {
            if (!tables.Classes.TryGetValue(p.CClassTrib!, out var classification)) Add("erro", "cClassTrib", "Classificação não encontrada na tabela oficial.");
            else
            {
                classDescription = classification.Descricao;
                if (classification.Cst != p.CstIbsCbs) Add("erro", "IBS/CBS", "O CST IBS/CBS não corresponde ao cClassTrib informado.");
                if (!classification.Nfce) Add("erro", "cClassTrib", "Esta classificação não permite NFC-e modelo 65.");
                if (date < classification.Inicio || date > classification.Fim) Add("erro", "cClassTrib", "Classificação fora da vigência na data da conferência.");
                if (classification.Especial || classification.Cst is not ("000" or "200" or "400" or "410")) Add("erro", "IBS/CBS", "Este enquadramento exige grupos específicos ainda não implementados pelo emissor. A emissão não deve substituir o código por um padrão.");
            }
        }
        else Add("aviso", "IBS/CBS", e.Crt is 1 or 4
            ? "Sem classificação IBS/CBS. Confirme as obrigações do Simples/MEI e a opção de apuração para 2027 com a contabilidade."
            : "Sem classificação IBS/CBS. O adiamento da rejeição automática pela ausência dos campos não comprova conformidade tributária; confirme as obrigações vigentes.");
        if (hasClass && e.Crt is 1 or 2 or 4 && date.Year == 2026) Add("informacao", "IBS/CBS", "Em 2026, o emissor não aplica as alíquotas do regime normal a Simples, MEI ou excesso de sublimite. Os códigos ficam no cadastro para revisão do próximo período.");
        if (date.Year != 2026) Add("aviso", "Reforma tributária", "As regras de cálculo revisadas nesta versão abrangem 2026. Revise o emissor e a opção tributária antes de emitir em outro ano.");
        if (!tables.Online) Add("aviso", "Fontes", "As fontes oficiais estão indisponíveis. A conferência usa uma referência datada e pode não incluir alterações recentes.");
        Add("aviso", "Revisão contábil", "Confirme a correspondência NCM/produto, CEST/ST, benefícios e FECP do RJ. O cadastro não dispõe de todos os dados necessários para calcular esses tratamentos; autorização da SEFAZ não valida o enquadramento.");
        return new(p.Id, p.ProductName, now, e.Uf, e.Crt, e.AmbienteFiscal, tables.Online, tables.DataBase, ncmDescription, classDescription, items);
    }

    public static bool ValidGtin(string value)
    {
        if (value.Length is not (8 or 12 or 13 or 14) || !value.All(char.IsAsciiDigit) || value.All(c => c == '0')) return false;
        var sum = 0;
        for (var i = value.Length - 2; i >= 0; i--) sum += (value[i] - '0') * ((value.Length - 2 - i) % 2 == 0 ? 3 : 1);
        return (10 - sum % 10) % 10 == value[^1] - '0';
    }
}
