param(
    [Parameter(Mandatory = $true)][ValidateSet('fabric','forge','neoforge')][string]$Platform,
    [Parameter(Mandatory = $true)][string]$Minecraft,
    [Parameter(Mandatory = $true)][string]$Loader,
    [string]$FabricApi,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [Parameter(Mandatory = $true)][string]$Java
)

$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath($WorkingDirectory)
New-Item -ItemType Directory -Force -Path $work, (Join-Path $work 'mods') | Out-Null

function Download([string]$Url, [string]$Destination) {
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        $last = $null
        foreach ($attempt in 1..3) {
            try {
                Invoke-WebRequest -Uri $Url -OutFile $Destination
                return
            } catch {
                $last = $_
                if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
                if ($attempt -lt 3) { Start-Sleep -Seconds (2 * $attempt) }
            }
        }
        throw $last
    }
}

function ResponseText($Response) {
    if ($Response.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($Response.Content)
    }
    return [string]$Response.Content
}

function DownloadMavenArtifact(
    [string]$Repository,
    [string]$GroupPath,
    [string]$Artifact,
    [string]$Version,
    [string]$Directory
) {
    $encodedVersion = [Uri]::EscapeDataString($Version)
    $name = "$Artifact-$Version.jar"
    $url = "$Repository/$GroupPath/$Artifact/$encodedVersion/$name"
    $destination = Join-Path $Directory $name
    Download $url $destination
    $expected = (ResponseText (Invoke-WebRequest -UseBasicParsing "$url.sha256")).Trim().ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "Checksum mismatch for $name (expected $expected, actual $actual)"
    }
    return $destination
}

if ($Platform -eq 'fabric') {
    if ([string]::IsNullOrWhiteSpace($FabricApi)) {
        throw 'FabricApi is required because WebShopX installs Fabric lifecycle and block callbacks'
    }
    $server = Join-Path $work "fabric-server-$Minecraft-$Loader.jar"
    $url = "https://meta.fabricmc.net/v2/versions/loader/$Minecraft/$Loader/1.1.1/server/jar"
    Download $url $server
    $fabricRepository = 'https://maven.fabricmc.net'
    $fabricGroup = 'net/fabricmc/fabric-api'
    $encodedVersion = [Uri]::EscapeDataString($FabricApi)
    $api = DownloadMavenArtifact $fabricRepository $fabricGroup 'fabric-api' $FabricApi (Join-Path $work 'mods')
    # Older Fabric API umbrella artifacts are Maven aggregators rather than bundled distributions.
    # Resolve their pinned component graph into mods/ so required callbacks are actually present.
    if ((Get-Item -LiteralPath $api).Length -lt 100KB) {
        $pomUrl = "$fabricRepository/$fabricGroup/fabric-api/$encodedVersion/fabric-api-$FabricApi.pom"
        $pom = [xml](ResponseText (Invoke-WebRequest -UseBasicParsing $pomUrl))
        foreach ($dependency in @($pom.project.dependencies.dependency)) {
            if ([string]$dependency.groupId -ne 'net.fabricmc.fabric-api') { continue }
            DownloadMavenArtifact `
                $fabricRepository `
                $fabricGroup `
                ([string]$dependency.artifactId) `
                ([string]$dependency.version) `
                (Join-Path $work 'mods') | Out-Null
        }
    }
    [ordered]@{ serverJar = $server; launchArguments = $null } | ConvertTo-Json
    exit 0
}

$coordinate = if ($Platform -eq 'forge') {
    [ordered]@{
        groupPath = 'net/minecraftforge/forge'
        artifact = 'forge'
        version = "$Minecraft-$Loader"
    }
} elseif ($Minecraft -eq '1.20.1') {
    [ordered]@{
        groupPath = 'net/neoforged/forge'
        artifact = 'forge'
        version = "$Minecraft-$Loader"
    }
} else {
    [ordered]@{
        groupPath = 'net/neoforged/neoforge'
        artifact = 'neoforge'
        version = $Loader
    }
}
$installerName = "$($coordinate.artifact)-$($coordinate.version)-installer.jar"
$installer = Join-Path $work $installerName
$repository = if ($Platform -eq 'forge') { 'https://maven.minecraftforge.net' } else { 'https://maven.neoforged.net/releases' }
Download "$repository/$($coordinate.groupPath)/$($coordinate.version)/$installerName" $installer
$argumentName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'win_args.txt' } else { 'unix_args.txt' }
$argumentFile = Get-ChildItem -LiteralPath (Join-Path $work 'libraries') -Recurse -File -Filter $argumentName `
    -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match [regex]::Escape($coordinate.artifact) } |
    Select-Object -First 1
if (-not $argumentFile) {
    $install = $null
    foreach ($attempt in 1..3) {
        $install = Start-Process -FilePath $Java -ArgumentList @('-jar', $installer, '--installServer') `
            -WorkingDirectory $work -Wait -PassThru -NoNewWindow `
            -RedirectStandardOutput (Join-Path $work "installer-$attempt.log") `
            -RedirectStandardError (Join-Path $work "installer-$attempt-error.log")
        if ($install.ExitCode -eq 0) { break }
        if ($attempt -lt 3) { Start-Sleep -Seconds (3 * $attempt) }
    }
    if ($install.ExitCode -ne 0) {
        throw "$Platform installer exited with $($install.ExitCode); see installer logs in $work"
    }
    $argumentFile = Get-ChildItem -LiteralPath (Join-Path $work 'libraries') -Recurse -File -Filter $argumentName `
        -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match [regex]::Escape($coordinate.artifact) } |
        Select-Object -First 1
}
if (-not $argumentFile) { throw "Installed server did not create $argumentName" }
$workPrefix = $work.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) +
    [IO.Path]::DirectorySeparatorChar
$argumentPath = [IO.Path]::GetFullPath($argumentFile.FullName)
if (-not $argumentPath.StartsWith($workPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Installed server argument file escaped working directory: $argumentPath"
}
$relative = $argumentPath.Substring($workPrefix.Length).Replace('\', '/')
[ordered]@{ serverJar = $null; launchArguments = "@`"$relative`"" } | ConvertTo-Json
