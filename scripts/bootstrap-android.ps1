$ErrorActionPreference = 'Stop'
$env:SKIP_JDK_VERSION_CHECK = 'true'

$sdkRoot = 'C:\Users\alish\AppData\Local\Android\Sdk'
$toolsArchive = 'C:\tmp\commandlinetools-win-15859902_aliflix.zip'
$gradleArchive = 'C:\tmp\gradle-9.5.0-bin-aliflix.zip'
$toolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$gradleUrl = 'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip'
$bootstrapRoot = 'C:\tmp\aliflix-android-bootstrap'
$logFile = Join-Path $PSScriptRoot 'bootstrap.log'

function Write-Step([string]$message) {
    "[$(Get-Date -Format 'HH:mm:ss')] $message" | Out-File -LiteralPath $logFile -Append -Encoding utf8
}

function Get-VerifiedDownload(
    [string]$source,
    [string]$destination,
    [string]$expectedSha256
) {
    if (Test-Path -LiteralPath $destination) {
        $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
        if ($existingHash -eq $expectedSha256) {
            Write-Step "Using verified existing download: $destination"
            return
        }
        Remove-Item -LiteralPath $destination -Force
    }

    Write-Step "Downloading $source"
    Start-BitsTransfer -Source $source -Destination $destination
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
    if ($hash -ne $expectedSha256) {
        throw "Checksum mismatch for $destination. Expected $expectedSha256, received $hash"
    }
    Write-Step "Verified $destination"
}

try {
    Set-Content -LiteralPath $logFile -Value "Aliflix Android bootstrap" -Encoding utf8
    New-Item -ItemType Directory -Force -Path $bootstrapRoot | Out-Null

    Get-VerifiedDownload `
        -source $toolsUrl `
        -destination $toolsArchive `
        -expectedSha256 '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'

    Write-Step 'Extracting Android command-line tools'
    $toolsExtract = Join-Path $bootstrapRoot 'command-line-tools'
    if (Test-Path -LiteralPath $toolsExtract) {
        Remove-Item -LiteralPath $toolsExtract -Recurse -Force
    }
    Expand-Archive -LiteralPath $toolsArchive -DestinationPath $toolsExtract -Force
    $latestTools = Join-Path $sdkRoot 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path $latestTools | Out-Null
    Copy-Item -Path (Join-Path $toolsExtract 'cmdline-tools\*') -Destination $latestTools -Recurse -Force

    $sdkManager = Join-Path $latestTools 'bin\sdkmanager.bat'
    Write-Step 'Accepting Android SDK licenses authorized for this build'
    $yes = 1..40 | ForEach-Object { 'y' }
    $yes | & $sdkManager --sdk_root=$sdkRoot --licenses | Out-File -LiteralPath $logFile -Append -Encoding utf8

    Write-Step 'Installing Android platform, build tools, and ADB'
    & $sdkManager --sdk_root=$sdkRoot `
        'platform-tools' `
        'platforms;android-37.0' `
        'build-tools;36.0.0' |
        Out-File -LiteralPath $logFile -Append -Encoding utf8

    Get-VerifiedDownload `
        -source $gradleUrl `
        -destination $gradleArchive `
        -expectedSha256 '553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'

    Write-Step 'Extracting Gradle 9.5'
    Expand-Archive -LiteralPath $gradleArchive -DestinationPath 'C:\tmp' -Force
    Write-Step 'Bootstrap complete'
} catch {
    Write-Step "FAILED: $($_.Exception.Message)"
    throw
}
