# Backup do schema "financeiro" (§99) no Windows: mesmo comportamento de scripts/backup.sh.
#
#   $env:PGHOST="localhost"; $env:PGUSER="own_financeiro"; $env:PGPASSWORD="..."; $env:PGDATABASE="erp"
#   .\scripts\backup.ps1 -RetencaoDias 30 -TestarRestauracao
#
# Precisa de pg_dump, pg_restore, psql, createdb e dropdb no PATH (pasta bin do PostgreSQL).
param(
  [string]$Pasta = ".\backups",
  [int]$RetencaoDias = 30,
  [switch]$TestarRestauracao
)
$ErrorActionPreference = "Stop"

function Executar([string]$programa, [string[]]$argumentos) {
  & $programa @argumentos
  if ($LASTEXITCODE -ne 0) { throw "$programa terminou com código $LASTEXITCODE" }
}

if (-not $env:PGDATABASE) { throw "Defina PGDATABASE" }
New-Item -ItemType Directory -Force -Path $Pasta | Out-Null
$arquivo = Join-Path $Pasta ("financeiro-{0}.dump" -f (Get-Date -Format "yyyyMMdd-HHmmss"))

Write-Host "Gerando $arquivo"
Executar "pg_dump" @("--format=custom", "--compress=9", "--schema=financeiro", "--no-owner", "--no-privileges", "--file=$arquivo", $env:PGDATABASE)
(Get-FileHash -Algorithm SHA256 $arquivo).Hash.ToLower() + "  " + (Split-Path $arquivo -Leaf) | Set-Content "$arquivo.sha256"
Executar "pg_restore" @("--list", $arquivo) | Out-Null

if ($TestarRestauracao) {
  $temp = "financeiro_restore_teste_$PID"
  Write-Host "Testando a restauração em $temp"
  Executar "createdb" @($temp)
  try {
    Executar "pg_restore" @("--no-owner", "--no-privileges", "--dbname=$temp", $arquivo)
    $tabelas = [int](& psql -At -d $temp -c "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'financeiro'")
    $migracoes = [int](& psql -At -d $temp -c "SELECT count(*) FROM financeiro.flyway_schema_history WHERE success")
    Write-Host "Restauração ok: $tabelas tabela(s), $migracoes migration(s)"
    if ($tabelas -le 0) { throw "Restauração sem tabelas" }
  } finally {
    & dropdb --if-exists $temp
  }
}

Write-Host "Apagando dumps com mais de $RetencaoDias dia(s)"
Get-ChildItem $Pasta -Filter "financeiro-*.dump*" -File |
  Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$RetencaoDias) } |
  ForEach-Object { Write-Host "  $($_.Name)"; Remove-Item $_.FullName }
Write-Host "Backup concluído"
