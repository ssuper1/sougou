[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SogouApk,
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [ValidateSet('companion', 'embedded', 'both')][string]$Mode = 'both',
    [string]$LSPatchJar,
    [string]$Java,
    [string]$Javac,
    [string]$Keystore = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$BuildToolsVersion = '37.0.0'
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$outputRoot = Join-Path $repo 'build\nonroot'
$toolDir = Join-Path $outputRoot 'tools'
$null = New-Item -ItemType Directory -Path $toolDir -Force
$sourceApk = (Resolve-Path -LiteralPath $SogouApk).Path
if (!$LSPatchJar) { $LSPatchJar = Join-Path $toolDir 'lspatch-v1.2-487-release.jar' }
$toolHash = 'd238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165'
$buildTools = Join-Path $AndroidSdk ('build-tools\' + $BuildToolsVersion)
$aapt = Join-Path $buildTools 'aapt.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'

function Invoke-Checked([string]$Program, [string[]]$Arguments, [string]$LogPath) {
    if ($LogPath) { & $Program @Arguments *> $LogPath }
    else { & $Program @Arguments }
    if ($LASTEXITCODE -ne 0) {
        if ($LogPath) { Get-Content -LiteralPath $LogPath -Tail 40 }
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Read-ZipText([string]$Path, [string]$EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (!$entry) { throw "Missing APK entry: $EntryName" }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Get-ZipHash([string]$Path, [string]$EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry($EntryName)
        if (!$entry) { throw "Missing APK entry: $EntryName" }
        $stream = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
        } finally { $sha.Dispose(); $stream.Dispose() }
    } finally { $zip.Dispose() }
}

foreach ($required in @($aapt, $apksigner, $zipalign)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Android build tool not found: $required" }
}
$badging = & $aapt dump badging $sourceApk
if ($LASTEXITCODE -ne 0) { throw 'Cannot read source APK' }
$packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
if ($packageLine -notmatch "name='com\.sohu\.inputmethod\.sogou'") {
    throw 'The input must be the original Sogou APK (com.sohu.inputmethod.sogou)'
}
if ($packageLine -notmatch "versionCode='(2180|2620)'") {
    throw 'This prototype supports Sogou 12.0 (2180) and 20.17.0 (2620) only'
}
$version = if ($packageLine -match "versionName='([^']+)'") { $Matches[1] } else { 'unknown' }
$sourceHash = (Get-FileHash -LiteralPath $sourceApk -Algorithm SHA256).Hash.ToLowerInvariant()

if (!(Test-Path -LiteralPath $LSPatchJar)) {
    Write-Host 'Downloading pinned LSPatch v1.2 (487)...'
    Invoke-Checked 'curl.exe' @('-L', '--fail', '--retry', '2', '--max-time', '900', '-o', $LSPatchJar,
        'https://github.com/JingMatrix/LSPatch/releases/download/v1.2/lspatch-v1.2-487-release.jar')
}
if ((Get-FileHash -LiteralPath $LSPatchJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $toolHash) {
    throw 'LSPatch checksum mismatch. Remove the incomplete JAR and download the pinned release again.'
}
if (!$Java) {
    $cachedJava = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\jdks') -Filter java.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[-\\]21[-\\]' } | Select-Object -First 1
    if ($cachedJava) { $Java = $cachedJava.FullName }
    elseif ($env:JAVA_HOME) { $Java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $Java = (Get-Command java.exe -ErrorAction Stop).Source }
}
if (!$Javac) { $Javac = Join-Path (Split-Path $Java) 'javac.exe' }

$modes = if ($Mode -eq 'both') { @('companion', 'embedded') } else { @($Mode) }
Push-Location $repo
try {
    foreach ($stage in $modes) {
        Write-Host "Building $stage mode for Sogou $version..."
        $embedded = if ($stage -eq 'embedded') { 'true' } else { 'false' }
        Invoke-Checked (Join-Path $repo 'gradlew.bat') @(':app:assembleRelease', ':app:testDebugUnitTest',
            ':app:lintRelease', '-PnonRoot=true', "-Pembedded=$embedded")
        if (!(Test-Path -LiteralPath $Keystore)) { throw "Keystore not found: $Keystore" }
        $stageDir = Join-Path $outputRoot $stage
        $null = New-Item -ItemType Directory -Path $stageDir -Force
        $moduleApk = Join-Path $stageDir 'sogousym-module.apk'
        Copy-Item -LiteralPath (Join-Path $repo 'app\build\outputs\apk\release\app-release.apk') -Destination $moduleApk -Force
        Invoke-Checked $Java @('-jar', $LSPatchJar, $sourceApk, '-m', $moduleApk, '-o', $stageDir, '-f', '-l', '2',
            '-k', $Keystore, 'android', 'androiddebugkey', 'android') (Join-Path $stageDir 'lspatch.log')
        $patched = Join-Path $stageDir ([IO.Path]::GetFileNameWithoutExtension($sourceApk) + '-487-lspatched.apk')
        if (!(Test-Path -LiteralPath $patched)) { throw "LSPatch did not produce $patched" }
        $finalApk = Join-Path $stageDir "sogou-$version-nonroot-$stage.apk"
        $candidateApk = Join-Path $stageDir 'candidate.apk'
        if ($stage -eq 'embedded') {
            $classes = Join-Path $toolDir 'classes'
            $null = New-Item -ItemType Directory -Path $classes -Force
            Invoke-Checked $Javac @('-encoding', 'UTF-8', '-cp', $LSPatchJar, '-d', $classes,
                (Join-Path $PSScriptRoot 'PatchSettings.java'))
            Invoke-Checked $Java @('-cp', "$classes;$LSPatchJar", 'PatchSettings', $patched)
            $aligned = Join-Path $stageDir 'settings-aligned.apk'
            Invoke-Checked $zipalign @('-p', '-f', '4', $patched, $aligned) (Join-Path $stageDir 'zipalign.log')
            Invoke-Checked $apksigner @('sign', '--ks', $Keystore, '--ks-key-alias', 'androiddebugkey',
                '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                '--alignment-preserved', '--lib-page-alignment', '4096',
                '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
                '--out', $candidateApk, $aligned)
        } else {
            Copy-Item -LiteralPath $patched -Destination $candidateApk -Force
        }
        Invoke-Checked $apksigner @('verify', $candidateApk)
        Invoke-Checked $zipalign @('-c', '-p', '4', $candidateApk)
        $config = Read-ZipText $candidateApk 'assets/lspatch/config.json' | ConvertFrom-Json
        if ($config.useManager) { throw 'Unexpected manager dependency in patched APK' }
        $moduleHash = (Get-FileHash -LiteralPath $moduleApk -Algorithm SHA256).Hash.ToLowerInvariant()
        if ((Get-ZipHash $candidateApk 'assets/lspatch/modules/com.qoder.sogousym.apk') -ne $moduleHash) {
            throw 'Embedded module differs from the module just built'
        }
        if ((Get-ZipHash $candidateApk 'assets/lspatch/origin.apk') -ne $sourceHash) {
            throw 'The nested original APK was altered'
        }
        $manifest = (& $aapt dump xmltree $candidateApk AndroidManifest.xml) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect patched manifest' }
        if ($stage -eq 'embedded' -and ($manifest -notmatch 'com.qoder.sogousym.EmbeddedSettingsActivity' -or
                $manifest -notmatch 'com.sohu.inputmethod.sogou.sogousym.config')) {
            throw 'Embedded settings activity or provider is missing'
        }
        Move-Item -LiteralPath $candidateApk -Destination $finalApk -Force
        $report = [ordered]@{
            mode = $stage; sogouVersion = $version; lspatchVersion = '1.2 (487)'
            sourceSha256 = $sourceHash; toolSha256 = $toolHash; moduleSha256 = $moduleHash
            apkSha256 = (Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
            apk = $finalApk; managerRequired = $false; deviceVerified = $false
            nativeLibraryAlignmentKb = 4
        }
        $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stageDir 'build-report.json') -Encoding UTF8
        Write-Host "APK ready: $finalApk"
    }
} finally {
    Pop-Location
}
