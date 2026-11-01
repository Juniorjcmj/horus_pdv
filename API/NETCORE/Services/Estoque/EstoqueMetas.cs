/**
 * Arquivo: API/NETCORE/Services/Estoque/EstoqueMetas.cs
 * Objetivo: metas do semáforo dos KPIs (verde/amarelo) e cache de 5 minutos dos blocos da análise de estoque.
 *           Metas: padrões conservadores, sobrescritos por EstoqueConfigAnalise.MetasJson
 *           ({"giroAnualizado":{"verde":6,"amarelo":3}, ...}).
 */
using System.Collections.Concurrent;
using System.Text.Json;
using Microsoft.Extensions.Caching.Memory;

namespace HORUSPDV_API.Services.Estoque;

public sealed record Meta(decimal Verde, decimal Amarelo, bool MaiorMelhor);

public static class EstoqueMetas
{
    public static readonly IReadOnlyDictionary<string, Meta> Padrao = new Dictionary<string, Meta>
    {
        ["giroAnualizado"] = new(6m, 3m, true),
        ["coberturaMediaDias"] = new(45m, 90m, false),
        ["emRupturaAgoraPercentual"] = new(2m, 5m, false),
        ["taxaRupturaPercentual"] = new(2m, 5m, false),
        ["acuracidade"] = new(98m, 95m, true),
    };

    public static IReadOnlyDictionary<string, Meta> Efetivas(string? metasJson)
    {
        var mapa = new Dictionary<string, Meta>(Padrao);
        if (string.IsNullOrWhiteSpace(metasJson)) return mapa;
        try
        {
            using var doc = JsonDocument.Parse(metasJson);
            foreach (var prop in doc.RootElement.EnumerateObject())
            {
                if (!Padrao.TryGetValue(prop.Name, out var padrao)) continue;
                var verde = prop.Value.TryGetProperty("verde", out var v) && v.TryGetDecimal(out var dv) ? dv : padrao.Verde;
                var amarelo = prop.Value.TryGetProperty("amarelo", out var a) && a.TryGetDecimal(out var da) ? da : padrao.Amarelo;
                mapa[prop.Name] = padrao with { Verde = verde, Amarelo = amarelo };
            }
        }
        catch (JsonException) { /* configuração inválida: mantém os padrões */ }
        return mapa;
    }

    /// <summary>"verde", "amarelo" ou "vermelho"; null quando não há valor ou meta para o KPI.</summary>
    public static string? Semaforo(IReadOnlyDictionary<string, Meta> metas, string chave, decimal? valor)
    {
        if (valor is not { } v || !metas.TryGetValue(chave, out var m)) return null;
        return m.MaiorMelhor
            ? v >= m.Verde ? "verde" : v >= m.Amarelo ? "amarelo" : "vermelho"
            : v <= m.Verde ? "verde" : v <= m.Amarelo ? "amarelo" : "vermelho";
    }
}

/// <summary>Cache de 5 minutos por (empresa, rota, filtros, visão de valores), invalidado por empresa ao salvar a configuração.</summary>
public sealed class EstoqueAnaliseCache(IMemoryCache cache)
{
    private static readonly TimeSpan Validade = TimeSpan.FromMinutes(5);
    private static readonly ConcurrentDictionary<string, int> Versoes = new(StringComparer.Ordinal);

    public async Task<(T Valor, DateTime CalculadoEmUtc, bool DoCache)> ObterAsync<T>(string companyId, string chave, Func<Task<T>> calcular)
    {
        var versao = Versoes.GetValueOrDefault(companyId);
        var k = $"estoque-analise|{companyId}|{versao}|{chave}";
        if (cache.TryGetValue(k, out (T, DateTime) existente)) return (existente.Item1, existente.Item2, true);
        var valor = await calcular();
        var agora = DateTime.UtcNow;
        cache.Set(k, (valor, agora), Validade);
        return (valor, agora, false);
    }

    public void Invalidar(string companyId) => Versoes.AddOrUpdate(companyId, 1, (_, v) => v + 1);
}
