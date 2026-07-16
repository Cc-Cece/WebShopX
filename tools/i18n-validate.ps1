param(
  [string]$I18nRoot = "",
  [string[]]$StrictNamespaces = @("app", "admin", "market-algorithms")
)

$ErrorActionPreference = "Stop"

function Get-LeafStrings {
  param(
    [Parameter(Mandatory = $true)]$Node,
    [AllowEmptyString()][string]$Prefix = "",
    [Parameter(Mandatory = $true)][hashtable]$Result
  )

  if ($null -eq $Node) {
    return
  }

  if ($Node -is [string]) {
    $Result[$Prefix] = $Node
    return
  }

  if ($Node -is [System.Collections.IEnumerable] -and -not ($Node -is [hashtable]) -and -not ($Node -is [pscustomobject])) {
    $index = 0
    foreach ($item in $Node) {
      $next = if ([string]::IsNullOrWhiteSpace($Prefix)) { "[$index]" } else { "$Prefix[$index]" }
      Get-LeafStrings -Node $item -Prefix $next -Result $Result
      $index++
    }
    return
  }

  $props = @()
  if ($Node -is [hashtable]) {
    $props = $Node.GetEnumerator() | ForEach-Object {
      [PSCustomObject]@{ Name = [string]$_.Key; Value = $_.Value }
    }
  } else {
    $props = $Node.PSObject.Properties | ForEach-Object {
      [PSCustomObject]@{ Name = [string]$_.Name; Value = $_.Value }
    }
  }

  foreach ($prop in $props) {
    $nextPrefix = if ([string]::IsNullOrWhiteSpace($Prefix)) { $prop.Name } else { "$Prefix.$($prop.Name)" }
    Get-LeafStrings -Node $prop.Value -Prefix $nextPrefix -Result $Result
  }
}

function Extract-Placeholders {
  param([string]$Text)
  $set = New-Object System.Collections.Generic.HashSet[string]
  if ($null -eq $Text) {
    return $set
  }
  foreach ($m in [regex]::Matches($Text, "\{([a-zA-Z0-9_.-]+)\}")) {
    [void]$set.Add($m.Groups[1].Value)
  }
  return $set
}

function Set-Equals {
  param(
    [System.Collections.Generic.HashSet[string]]$A,
    [System.Collections.Generic.HashSet[string]]$B
  )
  if ($A.Count -ne $B.Count) {
    return $false
  }
  foreach ($item in $A) {
    if (-not $B.Contains($item)) {
      return $false
    }
  }
  return $true
}

if ([string]::IsNullOrWhiteSpace($I18nRoot)) {
  $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
  $siblingRoot = Join-Path $repoRoot "..\..\webshopx-web\src\i18n"
  $ciRoot = Join-Path $repoRoot ".frontend\webshopx-web\src\i18n"
  $I18nRoot = if (Test-Path $siblingRoot -PathType Container) { $siblingRoot } else { $ciRoot }
}
$root = Resolve-Path $I18nRoot
$namespaces = Get-ChildItem -Path $root -Directory

$errors = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

if ($namespaces.Count -eq 0) {
  $errors.Add("No i18n namespaces found under $I18nRoot")
}

foreach ($ns in $namespaces) {
  $isStrict = $StrictNamespaces -contains $ns.Name
  $files = Get-ChildItem -Path $ns.FullName -File -Filter "*.json"
  if ($files.Count -eq 0) {
    $warnings.Add("Namespace '$($ns.Name)' has no json files.")
    continue
  }

  $enFile = Join-Path $ns.FullName "en-US.json"
  if (-not (Test-Path $enFile)) {
    $errors.Add("Namespace '$($ns.Name)' is missing source file: en-US.json")
    continue
  }

  $enJsonRaw = Get-Content -Raw -Encoding utf8 $enFile
  $enJson = $null
  try {
    $enJson = $enJsonRaw | ConvertFrom-Json
  } catch {
    $errors.Add("Invalid JSON in '$enFile': $($_.Exception.Message)")
    continue
  }

  $enLeaves = @{}
  Get-LeafStrings -Node $enJson -Prefix "" -Result $enLeaves

  foreach ($file in $files) {
    $locale = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
    if ($locale -eq "en-US") {
      continue
    }

    $json = $null
    try {
      $json = (Get-Content -Raw -Encoding utf8 $file.FullName) | ConvertFrom-Json
    } catch {
      $errors.Add("Invalid JSON in '$($file.FullName)': $($_.Exception.Message)")
      continue
    }

    $leaves = @{}
    Get-LeafStrings -Node $json -Prefix "" -Result $leaves

    $enKeys = @($enLeaves.Keys)
    $localeKeys = @($leaves.Keys)
    $missing = $enKeys | Where-Object { -not $leaves.ContainsKey($_) }
    $extra = $localeKeys | Where-Object { -not $enLeaves.ContainsKey($_) }

    if ($missing.Count -gt 0) {
      $message = "[$($ns.Name)/$locale] Missing keys: $($missing -join ', ')"
      if ($isStrict) { $errors.Add($message) } else { $warnings.Add($message) }
    }
    if ($extra.Count -gt 0) {
      $message = "[$($ns.Name)/$locale] Extra keys: $($extra -join ', ')"
      if ($isStrict) { $errors.Add($message) } else { $warnings.Add($message) }
    }

    $common = $enKeys | Where-Object { $leaves.ContainsKey($_) }
    foreach ($key in $common) {
      $srcSet = Extract-Placeholders -Text ([string]$enLeaves[$key])
      $dstSet = Extract-Placeholders -Text ([string]$leaves[$key])
      if (-not (Set-Equals -A $srcSet -B $dstSet)) {
        $errors.Add("[$($ns.Name)/$locale] Placeholder mismatch at '$key' (en-US='{$([string]::Join(',', $srcSet))}' vs $locale='{$([string]::Join(',', $dstSet))}')")
      }
    }
  }
}

Write-Output "Validated i18n root: $I18nRoot"
Write-Output "Namespaces checked: $($namespaces.Count)"

if ($warnings.Count -gt 0) {
  Write-Output ""
  Write-Output "Warnings:"
  $warnings | ForEach-Object { Write-Output "  - $_" }
}

if ($errors.Count -gt 0) {
  Write-Output ""
  Write-Output "Errors:"
  $errors | ForEach-Object { Write-Output "  - $_" }
  exit 1
}

Write-Output "i18n validation passed."
