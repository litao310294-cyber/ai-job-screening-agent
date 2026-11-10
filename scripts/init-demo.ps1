$ErrorActionPreference = 'Stop'
$demoInitRoot = Split-Path -Parent $PSScriptRoot
$demoInitTarget = Join-Path $demoInitRoot '.env.demo.local'
if (Test-Path -LiteralPath $demoInitTarget) { throw 'Configuration already exists; edit explicitly. Nothing overwritten.' }
function New-DemoPassword {
    $demoBytes = New-Object byte[] 32
    $demoRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $demoRng.GetBytes($demoBytes) } finally { $demoRng.Dispose() }
    return [System.BitConverter]::ToString($demoBytes).Replace('-', '').ToLowerInvariant()
}
$demoInitContent = Get-Content -LiteralPath (Join-Path $demoInitRoot '.env.example') -Raw
$demoInitContent = $demoInitContent.Replace('DEMO_MYSQL_ROOT_PASSWORD=CHANGE_ME', 'DEMO_MYSQL_ROOT_PASSWORD=' + (New-DemoPassword))
$demoInitContent = $demoInitContent.Replace('DEMO_MYSQL_PASSWORD=CHANGE_ME', 'DEMO_MYSQL_PASSWORD=' + (New-DemoPassword))
[System.IO.File]::WriteAllText($demoInitTarget, $demoInitContent, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created .env.demo.local with random database passwords; model settings are read from the environment.'
