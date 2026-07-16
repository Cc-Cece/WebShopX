param(
    [string]$FrontendDir = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($FrontendDir)) {
    $siblingFrontend = Join-Path $repoRoot "..\webshopx-web"
    $legacySiblingFrontend = Join-Path $repoRoot "..\..\webshopx-web"
    $ciFrontend = Join-Path $repoRoot ".frontend\webshopx-web"
    if (Test-Path (Join-Path $siblingFrontend "package.json") -PathType Leaf) {
        $FrontendDir = $siblingFrontend
    } elseif (Test-Path (Join-Path $legacySiblingFrontend "package.json") -PathType Leaf) {
        $FrontendDir = $legacySiblingFrontend
    } elseif (Test-Path (Join-Path $ciFrontend "package.json") -PathType Leaf) {
        $FrontendDir = $ciFrontend
    } else {
        throw "webshopx-web was not found beside the project or under .frontend/webshopx-web"
    }
}
$frontendRoot = (Resolve-Path $FrontendDir).Path
$generatedResourcesRoot = Join-Path $repoRoot "build\generated-resources\main"
$resourceRoot = Join-Path $generatedResourcesRoot "web"
$stageRoot = Join-Path $repoRoot "build\frontend-sync\web"
$distRoot = Join-Path $frontendRoot "dist"

if (-not (Test-Path (Join-Path $frontendRoot "package.json") -PathType Leaf)) {
    throw "Frontend package.json was not found: $frontendRoot"
}
if (-not $resourceRoot.StartsWith((Join-Path $repoRoot "build"), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace a generated resource directory outside build: $resourceRoot"
}
if (-not $stageRoot.StartsWith((Join-Path $repoRoot "build"), [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use an unsafe staging directory: $stageRoot"
}

Push-Location $frontendRoot
try {
    pnpm build
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path (Join-Path $distRoot "index.html") -PathType Leaf)) {
    throw "Frontend build did not produce dist/index.html"
}

if (Test-Path (Split-Path $stageRoot -Parent)) {
    Remove-Item -LiteralPath (Split-Path $stageRoot -Parent) -Recurse -Force
}
New-Item -ItemType Directory -Path $stageRoot | Out-Null
Copy-Item -Path (Join-Path $distRoot "*") -Destination $stageRoot -Recurse -Force

# LocaleCenterService serves these source JSON files through /api/locales.
$frontendI18n = Join-Path $frontendRoot "src\i18n"
$runtimeI18n = Join-Path $stageRoot "i18n"
New-Item -ItemType Directory -Path $runtimeI18n -Force | Out-Null
foreach ($namespace in @("app", "admin", "materials", "market-algorithms")) {
    $namespacePath = Join-Path $frontendI18n $namespace
    if (-not (Test-Path $namespacePath -PathType Container)) {
        throw "Required frontend locale namespace was not found: $namespacePath"
    }
    Copy-Item -LiteralPath $namespacePath -Destination (Join-Path $runtimeI18n $namespace) -Recurse -Force
}

$manifestPath = Join-Path $stageRoot "assets-manifest.txt"
$managedFiles = Get-ChildItem -LiteralPath $stageRoot -File -Recurse |
    Where-Object { $_.FullName -ne $manifestPath } |
    ForEach-Object { $_.FullName.Substring($stageRoot.Length + 1).Replace("\", "/") } |
    Sort-Object
[System.IO.File]::WriteAllLines($manifestPath, $managedFiles, [System.Text.UTF8Encoding]::new($false))

if (Test-Path $resourceRoot) {
    Remove-Item -LiteralPath $resourceRoot -Recurse -Force
}
New-Item -ItemType Directory -Path (Split-Path $resourceRoot -Parent) -Force | Out-Null
Move-Item -LiteralPath $stageRoot -Destination $resourceRoot

Write-Host "Synced $($managedFiles.Count) Vue frontend files to $resourceRoot"
