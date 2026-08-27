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
foreach ($check in $checks) {
    if ($check.schemaVersion -ne 1) { throw "Unsupported evidence schema for $($check.id)" }
    if ($check.sourceCommit -ne $SourceCommit) { throw "Stale source commit for $($check.id)" }
    if ($check.status -ne 'passed') { throw "Required check did not pass: $($check.id)" }
    if ([string]::IsNullOrWhiteSpace($check.environment)) { throw "Missing environment for $($check.id)" }
    if ($check.evidenceSha256 -notmatch '^[0-9a-f]{64}$') { throw "Invalid evidence hash for $($check.id)" }
    $files = @($check.files)
    if ($files.Count -eq 0) { throw "Missing evidence manifest for $($check.id)" }
    if (@($files | Where-Object { $_ -notmatch '^[0-9a-f]{64}  [^\r\n]+$' }).Count -gt 0) {
        throw "Malformed evidence manifest for $($check.id)"
    }
    if (@($files | Sort-Object -Unique).Count -ne $files.Count) {
        throw "Duplicate evidence manifest rows for $($check.id)"
    }
    $manifestBytes = [Text.Encoding]::UTF8.GetBytes(($files -join "`n") + "`n")
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $calculatedDigest = ([BitConverter]::ToString($sha256.ComputeHash($manifestBytes)) -replace '-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
    if ($calculatedDigest -ne $check.evidenceSha256) {
        throw "Evidence manifest hash mismatch for $($check.id)"
    }
    $generated = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$check.generatedAtUtc, [ref]$generated)) {
        throw "Missing or invalid evidence timestamp for $($check.id)"
    }
    if ($generated -gt [DateTimeOffset]::UtcNow.AddMinutes(5) -or
        $generated -lt [DateTimeOffset]::UtcNow.AddHours(-24)) {
        throw "Evidence is future-dated or stale for $($check.id): $generated"
    }
}
$destination = [IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
[ordered]@{
    schemaVersion = 1
    sourceCommit = $SourceCommit
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    releaseManifestSha256 = (Get-FileHash -LiteralPath $ReleaseManifest -Algorithm SHA256).Hash.ToLowerInvariant()
    sbomSha256 = (Get-FileHash -LiteralPath $Sbom -Algorithm SHA256).Hash.ToLowerInvariant()
    checks = @($checks)
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $destination -Encoding utf8
