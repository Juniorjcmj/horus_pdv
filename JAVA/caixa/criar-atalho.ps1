# Cria o atalho "Quack Caixa" na área de trabalho, com o ícone do patinho.
# Usa javaw (sem janela de console). Rodar: powershell -ExecutionPolicy Bypass -File criar-atalho.ps1
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $dir "target\quack-caixa.jar"
if (-not (Test-Path $jar)) { Write-Error "Gere o jar antes: mvn -f `"$dir\pom.xml`" package"; exit 1 }
$javaw = (Get-Command javaw -ErrorAction SilentlyContinue).Source
if (-not $javaw) { Write-Error "javaw não encontrado no PATH (instale o Java 21)."; exit 1 }

$atalho = Join-Path ([Environment]::GetFolderPath("Desktop")) "Quack Caixa.lnk"
$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut($atalho)
$lnk.TargetPath = $javaw
$lnk.Arguments = "-jar `"$jar`""
$lnk.WorkingDirectory = $dir
$lnk.IconLocation = (Join-Path $dir "quack-caixa.ico") + ",0"
$lnk.Description = "Quack Caixa - frente de caixa"
$lnk.Save()
Write-Output "Atalho criado: $atalho"
