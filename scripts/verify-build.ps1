$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$logFile = Join-Path $projectRoot 'build-verify.log'
$errorLog = Join-Path $projectRoot 'build-verify.err.log'

Set-Location -LiteralPath $projectRoot
"Aliflix build verification started at $(Get-Date -Format o)" |
    Set-Content -LiteralPath $logFile -Encoding utf8
Set-Content -LiteralPath $errorLog -Value '' -Encoding utf8

& (Join-Path $projectRoot 'gradlew.bat') `
    testDebugUnitTest `
    assembleDebug `
    lintDebug `
    --stacktrace `
    --console=plain `
    1>> $logFile `
    2>> $errorLog

if ($LASTEXITCODE -ne 0) {
    "BUILD_EXIT_CODE=$LASTEXITCODE" | Add-Content -LiteralPath $logFile -Encoding utf8
    exit $LASTEXITCODE
}

'BUILD_EXIT_CODE=0' | Add-Content -LiteralPath $logFile -Encoding utf8
