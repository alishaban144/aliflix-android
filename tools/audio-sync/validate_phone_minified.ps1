param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaPath = 'C:/Program Files/Java/jdk-25',
    [string]$DebugKeystore = "$env:USERPROFILE/.android/debug.keystore",
    [string]$AdbPath = '',
    [switch]$FramerateMismatch,
    [switch]$RealFilm,
    [switch]$Terminator,
    [switch]$AutomaticTerminator,
    [switch]$BuildDriverOnly,
    [switch]$PlaybackLifecycle,
    [ValidateRange(0,2)][int]$SceneIndex = 0
)
# Development only. The installed target must use this local debug certificate.
# Build :app:assembleMobileBenchmark first; never uninstall or clear target data.
# Generate/push the public phone_fixture.py WAV and JSON to Aliflix external files.
# Supply ALIFLIX_DEBUG_STORE_PASSWORD locally; signing credentials are not embedded.
$ErrorActionPreference = 'Stop'
if (!$env:ALIFLIX_DEBUG_STORE_PASSWORD) { throw 'Set ALIFLIX_DEBUG_STORE_PASSWORD for the local validation keystore.' }
$env:JAVA_HOME = $JavaPath
$repository = (Resolve-Path "$PSScriptRoot/../..").Path
$output = Join-Path $repository '.validation-independent-phone-driver'
New-Item -ItemType Directory -Force "$output/classes", "$output/dex" | Out-Null
$androidJar = Join-Path $SdkPath 'platforms/android-37.0/android.jar'
$buildTools = Join-Path $SdkPath 'build-tools/36.0.0'
function Confirm-Command { if ($LASTEXITCODE -ne 0) { throw "Android validation command failed: $LASTEXITCODE" } }
& "$JavaPath/bin/javac.exe" -source 17 -target 17 -classpath $androidJar -d "$output/classes" "$PSScriptRoot/phone-driver/MinifiedDriver.java"
Confirm-Command
& "$buildTools/d8.bat" --lib $androidJar --min-api 29 --output "$output/dex" "$output/classes/com/aliflix/validation/MinifiedDriver.class"
Confirm-Command
& "$buildTools/aapt2.exe" link -o "$output/driver-unsigned.apk" --manifest "$PSScriptRoot/phone-driver/AndroidManifest.xml" -I $androidJar --min-sdk-version 29 --target-sdk-version 37
Confirm-Command
$archive = [IO.Compression.ZipFile]::Open("$output/driver-unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$output/dex/classes.dex", 'classes.dex') | Out-Null }
finally { $archive.Dispose() }
& "$buildTools/apksigner.bat" sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass env:ALIFLIX_DEBUG_STORE_PASSWORD --key-pass env:ALIFLIX_DEBUG_STORE_PASSWORD --out "$output/driver.apk" "$output/driver-unsigned.apk"
Confirm-Command
$adb = if ($AdbPath) { $AdbPath } else { Join-Path $SdkPath 'platform-tools/adb.exe' }
& $adb -s $Serial install -r "$output/driver.apk"
Confirm-Command
& $adb -s $Serial shell pm grant com.aliflix.app android.permission.ACCESS_LOCAL_NETWORK
if ($BuildDriverOnly) { return }
if ($AutomaticTerminator) {
    & $adb -s $Serial shell am instrument -w -e automaticTerminator true com.aliflix.validation/com.aliflix.validation.MinifiedDriver
} elseif ($PlaybackLifecycle) {
    & $adb -s $Serial shell am instrument -w -e lifecycle true com.aliflix.validation/com.aliflix.validation.MinifiedDriver
} elseif ($Terminator) {
    & $adb -s $Serial shell am instrument -w -e terminator true -e sceneIndex $SceneIndex com.aliflix.validation/com.aliflix.validation.MinifiedDriver
} elseif ($RealFilm) {
    & $adb -s $Serial shell am instrument -w -e real true com.aliflix.validation/com.aliflix.validation.MinifiedDriver
} elseif ($FramerateMismatch) {
    & $adb -s $Serial shell am instrument -w -e fps true com.aliflix.validation/com.aliflix.validation.MinifiedDriver
} else {
    & $adb -s $Serial shell am instrument -w com.aliflix.validation/com.aliflix.validation.MinifiedDriver
}
Confirm-Command
# Inspect result=PASS, not only the adb exit code; Android reports test failures
# through instrumentation result fields. Save minified-sync-device.txt separately.
