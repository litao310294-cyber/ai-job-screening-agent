# REAL providers and the confirmed profile. Run in a separate PowerShell process.
$ErrorActionPreference = 'Stop'
$realRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $realRoot
. (Join-Path $PSScriptRoot 'demo-env.ps1')
$env:SPRING_DATASOURCE_URL = $env:MYSQL_URL
$env:SPRING_DATASOURCE_USERNAME = $env:MYSQL_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:MYSQL_PASSWORD
foreach ($realName in @('AI_CHAT_BASE_URL','AI_CHAT_API_KEY','AI_CHAT_MODEL','AI_EMBEDDING_BASE_URL','AI_EMBEDDING_API_KEY','AI_EMBEDDING_MODEL')) {
    $realValue = [Environment]::GetEnvironmentVariable($realName, 'Process')
    if ([string]::IsNullOrWhiteSpace($realValue)) { $realValue = [Environment]::GetEnvironmentVariable($realName, 'User') }
    if ([string]::IsNullOrWhiteSpace($realValue)) { throw "Missing model configuration: $realName" }
    [Environment]::SetEnvironmentVariable($realName, $realValue, 'Process')
}
if (Get-NetTCPConnection -State Listen -LocalPort 18080 -ErrorAction SilentlyContinue) { throw 'Port 18080 is already in use.' }
& .\mvnw.cmd -B compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-Dmdep.outputFile=target/real-classpath.txt' '-DincludeScope=runtime'
if ($LASTEXITCODE -ne 0) { throw 'REAL backend compilation failed.' }
$realClasspath = (Join-Path $realRoot 'target/classes') + ';' + (Get-Content target/real-classpath.txt -Raw -Encoding UTF8).Trim()
$realOrigin = (Get-Content extension/extension-id.json -Raw | ConvertFrom-Json).origin
& java -cp $realClasspath com.aijob.analyzer.AiJobAnalyzerApplication '--server.port=18080' '--app.demo.provider-mode=REAL' "--app.demo.extension-origin=$realOrigin"
exit $LASTEXITCODE
