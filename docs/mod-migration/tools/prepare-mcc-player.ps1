param(
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [ValidateSet('auto', 'windows-x64', 'linux-x64')][string]$Platform = 'auto'
)

$ErrorActionPreference = 'Stop'
$release = '20260704-478'
$targets = @{
    'windows-x64' = @{
        Name = "MinecraftClient-$release-win-x64.exe"
        Sha256 = '32c0ef4cd8a7cffabcc8267479cb03f244960451da3c2677f375bc4f2bfb9604'
    }
    'linux-x64' = @{
        Name = "MinecraftClient-$release-linux-x64"
        Sha256 = '8736c0d7979fe6cd1bacfa669a2a0d301978171afb4c18d5a10112435dc01578'
    }
}

if ($Platform -eq 'auto') {
    $Platform = if ($env:OS -eq 'Windows_NT') { 'windows-x64' } else { 'linux-x64' }
}
$target = $targets[$Platform]
if (-not $target) { throw "Unsupported MCC platform: $Platform" }
$work = [IO.Path]::GetFullPath($WorkingDirectory)
New-Item -ItemType Directory -Force -Path $work | Out-Null
$destination = Join-Path $work $target.Name
$url = "https://github.com/MCCTeam/Minecraft-Console-Client/releases/download/$release/$($target.Name)"

if (Test-Path -LiteralPath $destination -PathType Leaf) {
    $existing = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existing -ne $target.Sha256) {
        throw "Existing MCC binary hash mismatch: $destination"
    }
} else {
    $temporary = "$destination.download"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temporary
    $downloaded = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloaded -ne $target.Sha256) {
        Remove-Item -LiteralPath $temporary -Force
        throw "Downloaded MCC binary hash mismatch: expected $($target.Sha256), got $downloaded"
    }
    Move-Item -LiteralPath $temporary -Destination $destination
}
if ($Platform -eq 'linux-x64') { & chmod 755 $destination }

[ordered]@{
    executable = $destination
    release = $release
    sha256 = $target.Sha256
    source = $url
    license = 'CDDL-1.0'
    platform = $Platform
} | ConvertTo-Json
