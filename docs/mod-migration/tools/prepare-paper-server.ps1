param(
    [Parameter(Mandatory = $true)][ValidateSet('paper', 'folia')][string]$Project,
    [Parameter(Mandatory = $true)][string]$Minecraft,
    [Parameter(Mandatory = $true)][int]$Build,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$Sha256,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory
)

$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath($WorkingDirectory)
[IO.Directory]::CreateDirectory($work) | Out-Null
$headers = @{ 'User-Agent' = 'WebShopX-CI/3.0.0 (https://github.com/Cc-Cece/WebShopX)' }
$buildsUrl = "https://fill.papermc.io/v3/projects/$Project/versions/$Minecraft/builds"
$metadata = @(Invoke-RestMethod -Headers $headers -Uri $buildsUrl) |
    Where-Object { [int]$_.id -eq $Build } | Select-Object -First 1
if (-not $metadata) { throw "PaperMC build is absent: $Project/$Minecraft/$Build" }
$download = $metadata.downloads.'server:default'
if (-not $download -or -not $download.url) { throw 'PaperMC build has no server:default download' }
$expected = $Sha256.ToLowerInvariant()
if ([string]$download.checksums.sha256 -ne $expected) {
    throw "Pinned PaperMC checksum differs from service metadata for $Project/$Minecraft/$Build"
}
$destination = Join-Path $work ([string]$download.name)
if (-not (Test-Path -LiteralPath $destination -PathType Leaf)) {
    Invoke-WebRequest -Headers $headers -Uri ([string]$download.url) -OutFile $destination
}
$actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "PaperMC server checksum mismatch: $actual" }
[ordered]@{
    project = $Project
    minecraft = $Minecraft
    build = $Build
    channel = [string]$metadata.channel
    serverJar = $destination
    sha256 = $actual
} | ConvertTo-Json
