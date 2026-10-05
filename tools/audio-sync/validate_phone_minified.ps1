param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$SdkPath = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaPath = 'C:/Program Files/Java/jdk-25',
    [string]$DebugKeystore = "$env:USERPROFILE/.android/debug.keystore"
)
# Development only. The installed target must use this local debug certificate.
# Build :app:assembleMobileBenchmark first; never uninstall or clear target data.
# Generate/push the public phone_fixture.py WAV and JSON to Aliflix external files.
$ErrorActionPreference = 'Stop'
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
& "$buildTools/apksigner.bat" sign --ks $DebugKeystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$output/driver.apk" "$output/driver-unsigned.apk"
Confirm-Command
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
& $adb -s $Serial install -r "$output/driver.apk"
Confirm-Command
& $adb -s $Serial shell pm grant com.aliflix.app android.permission.ACCESS_LOCAL_NETWORK
& $adb -s $Serial shell am instrument -w com.aliflix.validation/com.aliflix.validation.MinifiedDriver
Confirm-Command
# Inspect result=PASS, not only the adb exit code; Android reports test failures
# through instrumentation result fields. Save minified-sync-device.txt separately.
