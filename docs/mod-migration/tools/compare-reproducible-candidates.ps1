param(
    [Parameter(Mandatory = $true)][string]$FirstManifest,
    [Parameter(Mandatory = $true)][string]$SecondManifest,
    [Parameter(Mandatory = $true)][string]$FirstSbom,
    [Parameter(Mandatory = $true)][string]$SecondSbom,
    [Parameter(Mandatory = $true)][string]$OutputFile
)

$ErrorActionPreference = 'Stop'

function Read-Json([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Candidate evidence file not found: $resolved"
    }
    Get-Content -LiteralPath $resolved -Raw | ConvertFrom-Json
}

function Index-Hashes($Rows, [string]$NameProperty, [string]$HashProperty) {
    $index = [ordered]@{}
    foreach ($row in @($Rows)) {
        $name = [string]$row.PSObject.Properties.Item($NameProperty).Value
        $hash = ([string]$row.PSObject.Properties.Item($HashProperty).Value).ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($name) -or $hash -notmatch '^[0-9a-f]{64}$') {
            throw "Invalid reproducibility row: name=$name hash=$hash"
        }
        if ($index.Contains($name)) { throw "Duplicate reproducibility row: $name" }
        $index[$name] = $hash
    }
    if ($index.Count -eq 0) { throw 'Reproducibility hash set must not be empty' }
    $index
}

function Require-EqualIndex($First, $Second, [string]$Label) {
    $firstJson = $First | ConvertTo-Json -Compress
    $secondJson = $Second | ConvertTo-Json -Compress
    if ($firstJson -ne $secondJson) {
        throw "$Label differs between clean candidate builds"
    }
}

$firstData = Read-Json $FirstManifest
$secondData = Read-Json $SecondManifest
if ($firstData.sourceCommit -ne $secondData.sourceCommit -or
    $firstData.frontend.sourceCommit -ne $secondData.frontend.sourceCommit) {
    throw 'Candidate source commits differ'
}

$firstArtifacts = Index-Hashes -Rows @($firstData.artifacts) `
    -NameProperty 'file' -HashProperty 'sha256'
$secondArtifacts = Index-Hashes -Rows @($secondData.artifacts) `
    -NameProperty 'file' -HashProperty 'sha256'
$firstAssets = Index-Hashes -Rows @($firstData.frontend.assets) `
    -NameProperty 'path' -HashProperty 'sha256'
$secondAssets = Index-Hashes -Rows @($secondData.frontend.assets) `
    -NameProperty 'path' -HashProperty 'sha256'
Require-EqualIndex $firstArtifacts $secondArtifacts 'JAR artifact hashes'
Require-EqualIndex $firstAssets $secondAssets 'Frontend asset hashes'
if ($firstData.frontend.assetTreeSha256 -ne $secondData.frontend.assetTreeSha256) {
    throw 'Frontend asset tree hash differs between clean candidate builds'
}

$firstSbomHash = (Get-FileHash -LiteralPath $FirstSbom -Algorithm SHA256).Hash.ToLowerInvariant()
$secondSbomHash = (Get-FileHash -LiteralPath $SecondSbom -Algorithm SHA256).Hash.ToLowerInvariant()
if ($firstSbomHash -ne $secondSbomHash) {
    throw 'SPDX SBOM differs between clean candidate builds'
}

$destination = [IO.Path]::GetFullPath($OutputFile)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
[ordered]@{
    status = 'passed'
    sourceCommit = $firstData.sourceCommit
    frontendCommit = $firstData.frontend.sourceCommit
    artifactCount = $firstArtifacts.Count
    frontendAssetCount = $firstAssets.Count
    artifactHashes = $firstArtifacts
    frontendAssetTreeSha256 = $firstData.frontend.assetTreeSha256
    sbomSha256 = $firstSbomHash
    comparedAtUtc = [DateTime]::UtcNow.ToString('o')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $destination -Encoding utf8
