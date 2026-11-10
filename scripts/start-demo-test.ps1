# Public alias for the existing isolated integration launcher.
& (Join-Path $PSScriptRoot 'start-phase5-test.ps1')
exit $LASTEXITCODE
