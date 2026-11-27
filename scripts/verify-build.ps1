$ErrorActionPreference = 'Stop'

Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    & .\gradlew.bat :app:testMobileDebugUnitTest :app:lintMobileDebug :app:assembleMobileDebug --console=plain
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
