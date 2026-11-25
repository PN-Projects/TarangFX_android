# Builds a signed release APK.
#   .\tools\release\build-release.ps1                    # phone (arm64-v8a)
#   .\tools\release\build-release.ps1 -Abi x86_64        # emulator
#
# SECURITY
# * The signing key lives OUTSIDE the repository and outside OneDrive
#   (%USERPROFILE%\.tarangfx-signing), so it is never committed or cloud-synced by accident.
# * Its password is random (256-bit), generated on first run and stored only encrypted with
#   Windows DPAPI (ConvertFrom-SecureString): it can be decrypted solely by this Windows user on
#   this machine. It reaches keytool/Gradle via process environment variables, never the command
#   line (visible in process listings) and never a file in the repo.
# * In CI, do not use this script: inject TFX_RELEASE_* from the CI secrets manager instead
#   (e.g. AWS Secrets Manager -> masked environment variables).
# * BACK UP the keystore folder (e.g. to an encrypted password manager). Android only accepts
#   updates signed with the same key; losing it means users must uninstall to upgrade.
param(
    [ValidateSet("arm64-v8a", "x86_64")] [string]$Abi = "arm64-v8a",
    # MAJOR.MINOR.PATCH; defaults to "<major>.0.0-local" from version.properties. Official
    # versions are assigned by the GitHub release pipeline, not by local builds.
    [string]$Version = ""
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
# AGP 8.7 needs JDK 17+. A machine-wide JAVA_HOME may point at an older JDK, so verify it.
function Get-JavaMajor([string]$home_) {
    $rel = Join-Path $home_ "release"
    if (-not (Test-Path $rel)) { return 0 }
    $v = (Select-String -Path $rel -Pattern 'JAVA_VERSION="(\d+)(\.(\d+))?').Matches[0].Groups
    if ($v[1].Value -eq "1") { return [int]$v[3].Value } else { return [int]$v[1].Value }
}
if (-not $env:JAVA_HOME -or (Get-JavaMajor $env:JAVA_HOME) -lt 17) {
    $jdk = Get-ChildItem "C:\Program Files\Microsoft", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java" -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Where-Object { (Get-JavaMajor $_.FullName) -ge 17 } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw "JDK 17+ not found; set JAVA_HOME to a JDK 17 install." }
    $env:JAVA_HOME = $jdk.FullName
}
$keytool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$apksigner = Join-Path $sdk "build-tools\35.0.0\apksigner.bat"

$keyDir = Join-Path $env:USERPROFILE ".tarangfx-signing"
$keystore = Join-Path $keyDir "tarangfx-release.p12"
$passFile = Join-Path $keyDir "store-password.dpapi"
$alias = "tarangfx"

if (-not (Test-Path $keystore)) {
    New-Item -ItemType Directory -Force $keyDir | Out-Null
    # Restrict the folder to the current user only (no inherited ACLs).
    icacls $keyDir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $plain = [Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $plain -AsPlainText -Force | ConvertFrom-SecureString | Set-Content $passFile
    $env:TFX_KEYGEN_PASS = $plain
    # RSA-4096, PKCS#12 (store and key share one password), ~27-year validity as Play requires >= 2033.
    & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 10000 -dname "CN=TarangFX Release" -storepass:env TFX_KEYGEN_PASS -keypass:env TFX_KEYGEN_PASS
    Remove-Item Env:\TFX_KEYGEN_PASS
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
    Write-Host "Created signing key at $keystore -- back this folder up securely."
}

$secure = Get-Content $passFile | ConvertTo-SecureString
$plainPass = [System.Net.NetworkCredential]::new("", $secure).Password
try {
    $env:TFX_RELEASE_KEYSTORE = $keystore
    $env:TFX_RELEASE_STORE_PASSWORD = $plainPass
    $env:TFX_RELEASE_KEY_PASSWORD = $plainPass
    $env:TFX_RELEASE_KEY_ALIAS = $alias
    Push-Location $root
    $gradleArgs = @(":app:assembleRelease", "-Pabi=$Abi", "--console=plain")
    if ($Version) { $gradleArgs += "-PtfxVersionName=$Version" }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed" }
} finally {
    Pop-Location
    Remove-Item Env:\TFX_RELEASE_STORE_PASSWORD, Env:\TFX_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
    $plainPass = $null
}

$major = (Select-String -Path (Join-Path $root "version.properties") -Pattern '^major=(\d+)').Matches[0].Groups[1].Value
$version = if ($Version) { $Version } else { "$major.0.0-local" }
$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force $dist | Out-Null
$out = Join-Path $dist "TarangFX-$version-$Abi-release.apk"
Copy-Item $apk $out -Force
& $apksigner verify --verbose --print-certs $out | Select-String "Verified using|Signer #1 certificate (DN|SHA-256)|Number of signers"
if ($LASTEXITCODE -ne 0) { throw "Signature verification failed" }
$sha = (Get-FileHash $out -Algorithm SHA256).Hash
Write-Host "Release APK: $out  ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB, SHA-256 $sha)"
