# Dot-source this file from the worktree before starting the application.
$demoRoot = Split-Path -Parent $PSScriptRoot
$demoEnvFile = Join-Path $demoRoot '.env.demo.local'
if (-not (Test-Path -LiteralPath $demoEnvFile)) {
    throw 'Missing .env.demo.local. Run scripts/init-demo.ps1; see README.md.'
}
$demoConfig = @{}
Get-Content -LiteralPath $demoEnvFile | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.*)$') { $demoConfig[$matches[1]] = $matches[2].Trim() }
}
$demoPassword = $demoConfig['DEMO_MYSQL_PASSWORD']
if ([string]::IsNullOrWhiteSpace($demoPassword) -or $demoPassword -eq 'CHANGE_ME') { throw 'Set a database password; run scripts/init-demo.ps1.' }
foreach ($demoPortKey in @('DEMO_MYSQL_PORT','DEMO_REDIS_PORT','DEMO_TEST_REDIS_PORT')) {
    $demoPort = 0
    if (-not [int]::TryParse($demoConfig[$demoPortKey], [ref]$demoPort) -or $demoPort -lt 1 -or $demoPort -gt 65535) { throw "Invalid port: $demoPortKey" }
    [Environment]::SetEnvironmentVariable($demoPortKey, [string]$demoPort, 'Process')
}
$env:MYSQL_URL = "jdbc:mysql://127.0.0.1:$env:DEMO_MYSQL_PORT/ai_job_analyzer?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC"
$env:MYSQL_USERNAME = 'ai_job_demo'
$env:MYSQL_PASSWORD = $demoPassword
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = $env:DEMO_REDIS_PORT
$env:REDIS_PASSWORD = ''
$env:LUCENE_ROOT = Join-Path $demoRoot 'lucene-index'
