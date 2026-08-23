param(
    [Parameter(Mandatory = $true)][string]$SourceCommit,
    [Parameter(Mandatory = $true)][string]$EvidenceDirectory,
    [Parameter(Mandatory = $true)][string]$ReleaseManifest,
    [Parameter(Mandatory = $true)][string]$Sbom,
    [Parameter(Mandatory = $true)][string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$checks = Get-ChildItem -LiteralPath $EvidenceDirectory -Filter '*.check.json' -File -Recurse |
    Sort-Object FullName | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json }
if (@($checks).Count -eq 0) { throw 'No required-check evidence files were found' }
$duplicates = @($checks | Group-Object id | Where-Object Count -ne 1)
if ($duplicates.Count -gt 0) { throw "Duplicate evidence IDs: $($duplicates.Name -join ', ')" }
$destination = [IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
[ordered]@{
    schemaVersion = 1
    sourceCommit = $SourceCommit
    releaseManifestSha256 = (Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    sbomSha256 = (Get-FileHash -LiteralPath $Sbom -Algorithm SHA256).Hash.ToLowerInvariant()
    checks = @($checks)
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination -Encoding utf8
