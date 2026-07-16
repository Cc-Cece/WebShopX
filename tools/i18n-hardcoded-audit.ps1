param(
  [string]$ProjectRoot = (Get-Location).Path,
  [string]$OutputFile = ".docs/reports/i18n-hardcoded-report.md"
)

$ErrorActionPreference = "Stop"

function Resolve-AbsolutePath {
  param([string]$Base, [string]$PathText)
  if ([System.IO.Path]::IsPathRooted($PathText)) {
    return $PathText
  }
  return [System.IO.Path]::GetFullPath((Join-Path $Base $PathText))
}

function Get-RelativePath {
  param([string]$Root, [string]$Target)
  $rootUri = [Uri]((Resolve-Path $Root).Path.TrimEnd('\') + "\")
  $targetUri = [Uri](Resolve-Path $Target).Path
  return [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($targetUri).ToString()).Replace('/', '\')
}

function Is-ExcludedPath {
  param([string]$RelativePath)
  $normalized = $RelativePath.Replace('\', '/')
  return $false
}

function Extract-Literals {
  param([string]$Line)
  $results = @()
  $patterns = @(
    "'([^'\\]|\\.)*[\u4e00-\u9fff]([^'\\]|\\.)*'",
    '"([^"\\]|\\.)*[\u4e00-\u9fff]([^"\\]|\\.)*"',
    '`([^`]|``)*[\u4e00-\u9fff]([^`]|``)*`'
  )
  foreach ($pattern in $patterns) {
    foreach ($m in [regex]::Matches($Line, $pattern)) {
      $results += $m.Value
    }
  }
  return $results
}

$root = Resolve-AbsolutePath -Base (Get-Location).Path -PathText $ProjectRoot
$outputPath = Resolve-AbsolutePath -Base $root -PathText $OutputFile
$outputDir = Split-Path -Parent $outputPath
if (-not (Test-Path $outputDir)) {
  New-Item -Path $outputDir -ItemType Directory -Force | Out-Null
}

$targets = @()
$targets += Get-ChildItem -Path (Join-Path $root "src/main/java") -Recurse -File -Include *.java

$records = New-Object System.Collections.Generic.List[object]
$literals = New-Object System.Collections.Generic.List[string]

foreach ($file in $targets) {
  $relative = Get-RelativePath -Root $root -Target $file.FullName
  if (Is-ExcludedPath -RelativePath $relative) {
    continue
  }
  $lines = Get-Content -Path $file.FullName -Encoding utf8
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -notmatch "[\u4e00-\u9fff]") {
      continue
    }
    $literalHits = Extract-Literals -Line $line
    foreach ($lit in $literalHits) {
      $literals.Add($lit)
    }
    $records.Add([PSCustomObject]@{
      File = $relative
      Line = $i + 1
      Text = $line.Trim()
    })
  }
}

$grouped = $records | Group-Object File | Sort-Object Count -Descending
$topLiterals = $literals | Group-Object | Sort-Object Count -Descending | Select-Object -First 30

$totalFiles = ($grouped | Measure-Object).Count
$totalHits = ($records | Measure-Object).Count
$now = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"

$builder = New-Object System.Text.StringBuilder
[void]$builder.AppendLine("# Hardcoded i18n Audit")
[void]$builder.AppendLine("")
[void]$builder.AppendLine("- Generated at: $now")
[void]$builder.AppendLine("- Scope: built web assets + java (excluding generated bundles and `web/i18n`)")
[void]$builder.AppendLine("- Files with CJK hits: $totalFiles")
[void]$builder.AppendLine("- Total CJK line hits: $totalHits")
[void]$builder.AppendLine("")
[void]$builder.AppendLine("## By File")
[void]$builder.AppendLine("")
foreach ($item in $grouped) {
  [void]$builder.AppendLine("- $($item.Name): $($item.Count)")
}
[void]$builder.AppendLine("")
[void]$builder.AppendLine("## Top Literal Candidates")
[void]$builder.AppendLine("")
foreach ($item in $topLiterals) {
  $value = $item.Name
  if ($value.Length -gt 120) {
    $value = $value.Substring(0, 117) + "..."
  }
  [void]$builder.AppendLine("- $value ($($item.Count))")
}
[void]$builder.AppendLine("")
[void]$builder.AppendLine("## First 200 Detailed Hits")
[void]$builder.AppendLine("")
$records | Select-Object -First 200 | ForEach-Object {
  [void]$builder.AppendLine("- $($_.File):$($_.Line) :: $($_.Text)")
}

[System.IO.File]::WriteAllText($outputPath, $builder.ToString(), [System.Text.Encoding]::UTF8)
Write-Output "Report written: $outputPath"
Write-Output "Files with hits: $totalFiles"
Write-Output "Total hits: $totalHits"
