param(
    [Parameter(Mandatory = $true)][string]$Id,
    [Parameter(Mandatory = $true)][string]$SourceCommit,
    [Parameter(Mandatory = $true)][string]$Environment,
    [Parameter(Mandatory = $true)][string[]]$EvidencePath,
    [Parameter(Mandatory = $true)][string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$files = foreach ($candidate in $EvidencePath) {
    Get-ChildItem -Path $candidate -File -Recurse -ErrorAction Stop
}
$files = @($files | Sort-Object FullName -Unique)
if ($files.Count -eq 0) { throw "No evidence files resolved for $Id" }
$lines = foreach ($file in $files) {
    $hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($file.Name)"
}
$bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
$digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
$destination = [IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
[ordered]@{
    schemaVersion = 1
    id = $Id
    status = 'passed'
    sourceCommit = $SourceCommit
    environment = $Environment
    evidenceSha256 = $digest
    files = @($lines)
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $destination -Encoding utf8
