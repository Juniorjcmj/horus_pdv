/*
 * Arquivo: Logging/FileLoggerProvider.cs
 * Objetivo: logger de arquivo simples, thread-safe e com rotação diária, para que o Gateway rodando
 *           como Serviço do Windows (sem console) ainda registre logs em disco. Grava em
 *           <baseDir>/logs/gateway-YYYY-MM-DD.log. Mantém os N arquivos mais recentes.
 */
using System.Collections.Concurrent;
using System.Text;
using Microsoft.Extensions.Logging;

namespace HorusGateway.Logging;

public sealed class FileLoggerOptions
{
    public string Directory { get; set; } = "logs";
    public LogLevel MinLevel { get; set; } = LogLevel.Information;
    public int RetainDays { get; set; } = 14;
}

public sealed class FileLoggerProvider : ILoggerProvider
{
    private readonly FileLoggerOptions _options;
    private readonly string _directory;
    private readonly object _gate = new();
    private readonly ConcurrentDictionary<string, FileLogger> _loggers = new();
    private string _currentDate = string.Empty;

    public FileLoggerProvider(FileLoggerOptions options, string baseDirectory)
    {
        _options = options;
        _directory = Path.IsPathRooted(options.Directory)
            ? options.Directory
            : Path.Combine(baseDirectory, options.Directory);
        System.IO.Directory.CreateDirectory(_directory);
    }

    public ILogger CreateLogger(string categoryName)
        => _loggers.GetOrAdd(categoryName, name => new FileLogger(this, name));

    internal bool IsEnabled(LogLevel level) => level >= _options.MinLevel && level != LogLevel.None;

    internal void Write(string line)
    {
        lock (_gate)
        {
            try
            {
                var today = DateTime.UtcNow.ToString("yyyy-MM-dd");
                if (today != _currentDate)
                {
                    _currentDate = today;
                    CleanupOldFiles();
                }
                var file = Path.Combine(_directory, $"gateway-{today}.log");
                File.AppendAllText(file, line, Encoding.UTF8);
            }
            catch
            {
                // Nunca deixar o logging derrubar a aplicação.
            }
        }
    }

    private void CleanupOldFiles()
    {
        try
        {
            var files = System.IO.Directory.GetFiles(_directory, "gateway-*.log")
                .OrderByDescending(f => f)
                .Skip(Math.Max(1, _options.RetainDays));
            foreach (var old in files)
            {
                try { File.Delete(old); } catch { /* ignore */ }
            }
        }
        catch { /* ignore */ }
    }

    public void Dispose() => _loggers.Clear();

    private sealed class FileLogger(FileLoggerProvider provider, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => provider.IsEnabled(logLevel);

        public void Log<TState>(
            LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var message = formatter(state, exception);
            if (string.IsNullOrEmpty(message) && exception is null) return;

            var sb = new StringBuilder();
            sb.Append(DateTime.UtcNow.ToString("o"))
              .Append(" [").Append(logLevel.ToString()).Append("] ")
              .Append(category).Append(" - ").Append(message);
            if (exception is not null)
            {
                sb.Append(Environment.NewLine).Append(exception);
            }
            sb.Append(Environment.NewLine);
            provider.Write(sb.ToString());
        }
    }
}
