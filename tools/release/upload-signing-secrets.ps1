# Uploads the local TarangFX release signing key to the repository's encrypted Actions secrets,
# so the CI release pipeline can sign APKs. Run by a repository admin; also used to ROTATE keys.
#
#   .\tools\release\upload-signing-secrets.ps1 [-Repo PN-Projects/TarangFX_android]
#
# SECURITY: values are read from the DPAPI-protected local store created by build-release.ps1 and
# piped to `gh secret set` on stdin: they never appear on a command line, in shell history or in
# output. GitHub stores them encrypted (libsodium sealed boxes) and masks them in logs; they are
# not exposed to workflows triggered by pull requests from forks.
param([string]$Repo = "PN-Projects/TarangFX_android")
$ErrorActionPreference = "Stop"
$keyDir = Join-Path $env:USERPROFILE ".tarangfx-signing"
$keystore = Join-Path $keyDir "tarangfx-release.p12"
$passFile = Join-Path $keyDir "store-password.dpapi"
if (-not (Test-Path $keystore)) { throw "No key at $keystore. Run tools\release\build-release.ps1 once to create it." }
gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Log in first: gh auth login" }

$pass = [System.Net.NetworkCredential]::new("", (Get-Content $passFile | ConvertTo-SecureString)).Password
try {
    [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)) | gh secret set TFX_KEYSTORE_BASE64 -R $Repo
    $pass | gh secret set TFX_KEYSTORE_PASSWORD -R $Repo
    $pass | gh secret set TFX_KEY_PASSWORD -R $Repo
    "tarangfx" | gh secret set TFX_KEY_ALIAS -R $Repo
} finally {
    $pass = $null
}
gh secret list -R $Repo
Write-Host "Signing secrets set on $Repo."
