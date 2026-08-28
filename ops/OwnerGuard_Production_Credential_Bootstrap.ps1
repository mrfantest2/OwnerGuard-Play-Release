# OwnerGuard 1.0.44 Production Credential Bootstrap
# No secret values are printed or written to GitHub source.
# Run this on a trusted Windows PC under the GitHub account that owns mrfantest2 repositories.

param(
    [string]$ServiceAccountJson = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Repo = "mrfantest2/OwnerGuard-Play-Release"
$PrivateRepo = "mrfantest2/OwnerGuard"
$EnvName = "google-play-production"
$Branch = "release/1.0.44-play-pro-drive"
$Workflow = "ownerguard-1.0.44-play-pro-drive.yml"
$ExpectedCert = "BC2E0B8928F9D9F981DB1AF5595D0168D54F73FC9A7AB52D8CED6AD0E5979C9E"

function Info([string]$Text) {
    Write-Host "[OwnerGuard] $Text"
}

function Fail([string]$Text) {
    throw "[OwnerGuard] $Text"
}

function Refresh-Path {
    $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
    $user = [Environment]::GetEnvironmentVariable("Path", "User")
    $env:Path = "$machine;$user"
}

function Ensure-Gh {
    if (Get-Command gh -ErrorAction SilentlyContinue) { return }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail "GitHub CLI (gh) is missing and winget is unavailable. Install GitHub CLI, then run this file again."
    }
    Info "Installing GitHub CLI..."
    winget install --id GitHub.cli --exact --accept-package-agreements --accept-source-agreements
    Refresh-Path
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        Fail "GitHub CLI installation completed but gh is not on PATH yet. Re-open the terminal and run this file again."
    }
}

function Ensure-Keytool {
    if (Get-Command keytool -ErrorAction SilentlyContinue) { return }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail "Java keytool is missing and winget is unavailable. Install a Java 17 JDK, then run this file again."
    }
    Info "Installing Temurin JDK 17 for certificate verification..."
    winget install --id EclipseAdoptium.Temurin.17.JDK --exact --accept-package-agreements --accept-source-agreements
    Refresh-Path
    if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
        $candidate = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Filter keytool.exe -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($candidate) {
            $env:Path = "$($candidate.DirectoryName);$env:Path"
        }
    }
    if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
        Fail "Java keytool is still unavailable. Re-open the terminal and run this file again."
    }
}

function Ensure-GhAuth {
    gh auth status --hostname github.com *> $null
    if ($LASTEXITCODE -eq 0) { return }
    Info "GitHub authentication is required. A browser sign-in will open."
    gh auth login --hostname github.com --git-protocol https --web
    gh auth status --hostname github.com *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "GitHub authentication did not complete."
    }
}

function Decode-GhContent([string]$RepoName, [string]$Path, [string]$Destination) {
    $json = gh api "repos/$RepoName/contents/$Path"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
        Fail "Could not read private repository path: $Path"
    }
    $obj = $json | ConvertFrom-Json
    if (-not $obj.content) {
        Fail "GitHub did not return file content for: $Path"
    }
    $bytes = [Convert]::FromBase64String(($obj.content -replace "\s",""))
    [IO.File]::WriteAllBytes($Destination, $bytes)
}

function Read-Properties([string]$Path) {
    $p = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)\s*$') {
            $p[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    return $p
}

function Invoke-GhSecretSetExact([string]$Name, [string]$Value) {
    if ([string]::IsNullOrEmpty($Value)) {
        Fail "Refusing to install an empty secret: $Name"
    }

    $ghExe = (Get-Command gh -ErrorAction Stop).Source
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $ghExe
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    foreach ($arg in @("secret","set",$Name,"--env",$EnvName,"--repo",$Repo)) {
        [void]$psi.ArgumentList.Add($arg)
    }

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $proc.StandardInput.Write($Value)
    $proc.StandardInput.Close()
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()

    if ($proc.ExitCode -ne 0) {
        Fail "Failed to set protected environment secret: $Name. GitHub CLI error: $stderr"
    }
}

function Set-EnvSecretFromString([string]$Name, [string]$Value) {
    Invoke-GhSecretSetExact $Name $Value
}

function Set-EnvSecretFromFile([string]$Name, [string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "Secret source file does not exist: $Path"
    }
    $raw = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path))
    Invoke-GhSecretSetExact $Name $raw
}

Ensure-Gh
Ensure-Keytool
Ensure-GhAuth

gh repo view $Repo --json nameWithOwner *> $null
if ($LASTEXITCODE -ne 0) { Fail "Cannot access public release repository: $Repo" }
gh repo view $PrivateRepo --json nameWithOwner *> $null
if ($LASTEXITCODE -ne 0) { Fail "Cannot access private historical OwnerGuard repository: $PrivateRepo" }

$temp = Join-Path $env:TEMP ("OwnerGuard-Prod-Bootstrap-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temp | Out-Null

try {
    $jks = Join-Path $temp "ownerguard-release.jks"
    $propsPath = Join-Path $temp "keystore.properties"

    Info "Retrieving historical OwnerGuard signing material directly from the private repository..."
    Decode-GhContent $PrivateRepo "signing/ownerguard-release.jks" $jks
    Decode-GhContent $PrivateRepo "signing/keystore.properties" $propsPath

    $props = Read-Properties $propsPath
    foreach ($name in @("storePassword","keyAlias","keyPassword")) {
        if (-not $props.ContainsKey($name) -or [string]::IsNullOrWhiteSpace([string]$props[$name])) {
            Fail "Historical keystore.properties is missing required field: $name"
        }
    }

    $env:OWNERGUARD_BOOTSTRAP_STORE_PASSWORD = [string]$props["storePassword"]
    try {
        $keytoolOutput = & keytool -list -v -keystore $jks -storepass:env OWNERGUARD_BOOTSTRAP_STORE_PASSWORD 2>&1
        if ($LASTEXITCODE -ne 0) { Fail "Historical OwnerGuard keystore could not be opened." }
    }
    finally {
        Remove-Item Env:OWNERGUARD_BOOTSTRAP_STORE_PASSWORD -ErrorAction SilentlyContinue
    }

    $m = $keytoolOutput | Select-String -Pattern 'SHA256:\s*([0-9A-Fa-f:]{64,95})' | Select-Object -First 1
    if (-not $m) { Fail "Could not derive the OwnerGuard signing certificate SHA-256." }
    $actual = (($m.Matches[0].Groups[1].Value -replace ':','').ToUpperInvariant())
    if ($actual -ne $ExpectedCert) {
        Fail "Historical OwnerGuard signing certificate does not match the pinned production identity. No secrets were changed."
    }
    Info "Historical OwnerGuard signing certificate verified."

    gh api --method PUT "repos/$Repo/environments/$EnvName" *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "Could not ensure the GitHub production environment exists."
    }

    Info "Installing OwnerGuard signing secrets into the protected GitHub environment..."
    $jksB64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
    Set-EnvSecretFromString "OWNERGUARD_UPLOAD_KEYSTORE_B64" $jksB64
    Set-EnvSecretFromString "OWNERGUARD_KEYSTORE_PASSWORD" ([string]$props["storePassword"])
    Set-EnvSecretFromString "OWNERGUARD_KEY_ALIAS" ([string]$props["keyAlias"])
    Set-EnvSecretFromString "OWNERGUARD_KEY_PASSWORD" ([string]$props["keyPassword"])

    gh variable set OWNERGUARD_EXPECTED_UPLOAD_CERT_SHA256 --body $ExpectedCert --env $EnvName --repo $Repo
    if ($LASTEXITCODE -ne 0) { Fail "Failed to set the expected OwnerGuard certificate variable." }

    if ([string]::IsNullOrWhiteSpace($ServiceAccountJson)) {
        $ServiceAccountJson = Read-Host "Enter the LOCAL path to the Google Play service-account JSON file (do not paste JSON)"
    }
    $ServiceAccountJson = [Environment]::ExpandEnvironmentVariables($ServiceAccountJson.Trim('"'))
    if (-not (Test-Path -LiteralPath $ServiceAccountJson -PathType Leaf)) {
        Fail "Google Play service-account JSON file was not found. The four OwnerGuard signing secrets were installed successfully; rerun this file after creating the Play service-account JSON."
    }

    try {
        $sa = Get-Content -LiteralPath $ServiceAccountJson -Raw | ConvertFrom-Json
    } catch {
        Fail "The selected Google Play credential file is not valid JSON."
    }
    foreach ($field in @("type","client_email","private_key","token_uri")) {
        if (-not ($sa.PSObject.Properties.Name -contains $field) -or [string]::IsNullOrWhiteSpace([string]$sa.$field)) {
            Fail "The selected JSON is missing required service-account field: $field"
        }
    }
    if ([string]$sa.type -ne "service_account") {
        Fail "The selected JSON is not a Google service-account key."
    }

    Info "Installing Google Play publisher credential into the protected GitHub environment..."
    Set-EnvSecretFromFile "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" $ServiceAccountJson

    gh variable set OWNERGUARD_PRO_PRICE_MICROS --body "19990000" --env $EnvName --repo $Repo
    if ($LASTEXITCODE -ne 0) { Fail "Failed to set OwnerGuard Pro price variable." }
    gh variable set OWNERGUARD_PRO_CURRENCY --body "AED" --env $EnvName --repo $Repo
    if ($LASTEXITCODE -ne 0) { Fail "Failed to set OwnerGuard Pro currency variable." }

    Info "Protected environment is populated. Triggering the already-authorized production workflow..."
    gh workflow run $Workflow --repo $Repo --ref $Branch -f publish=true
    if ($LASTEXITCODE -ne 0) { Fail "Could not trigger the OwnerGuard production workflow." }

    Start-Sleep -Seconds 3
    Info "Latest OwnerGuard release runs:"
    gh run list --repo $Repo --workflow $Workflow --branch $Branch --limit 3

    Write-Host ""
    Write-Host "OwnerGuard production bootstrap completed."
    Write-Host "The workflow still enforces QA, Android 16 emulator validation, the pinned historical signer, package/version checks, and Google Play edit validation before publication."
}
finally {
    Remove-Item Env:OWNERGUARD_BOOTSTRAP_STORE_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
