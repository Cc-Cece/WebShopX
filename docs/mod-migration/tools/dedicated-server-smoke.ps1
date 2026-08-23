param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$ServerJar,
    [string]$LaunchArguments,
    [Parameter(Mandatory = $true)][string]$ModJar,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [string]$ExpectedMinecraft,
    [string]$ExpectedLoader,
    [int]$Port = 25622,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath($WorkingDirectory)
$server = if ($ServerJar) { [IO.Path]::GetFullPath($ServerJar) } else { $null }
$mod = [IO.Path]::GetFullPath($ModJar)
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Java not found: $Java" }
if (-not $server -and -not $LaunchArguments) { throw 'ServerJar or LaunchArguments is required' }
if ($server -and -not (Test-Path -LiteralPath $server -PathType Leaf)) { throw "Server JAR not found: $server" }
if (-not (Test-Path -LiteralPath $mod -PathType Leaf)) { throw "Mod JAR not found: $mod" }

New-Item -ItemType Directory -Force -Path $work, (Join-Path $work 'mods') | Out-Null
Copy-Item -LiteralPath $mod -Destination (Join-Path $work 'mods/webshopx.jar') -Force
Set-Content -LiteralPath (Join-Path $work 'eula.txt') -Encoding ascii -Value 'eula=true'
@(
    'online-mode=false'
    "server-port=$Port"
    'view-distance=2'
    'simulation-distance=2'
    'level-name=world-smoke'
) | Set-Content -LiteralPath (Join-Path $work 'server.properties') -Encoding ascii

$stdout = Join-Path $work 'console.log'
$stderr = Join-Path $work 'console-error.log'
$runtimeLock = Join-Path $work 'config/webshopx/runtime.lock'
if (Test-Path -LiteralPath $runtimeLock -PathType Leaf) { Remove-Item -LiteralPath $runtimeLock -Force }
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $Java
$start.Arguments = if ($LaunchArguments) {
    "-Xms512M -Xmx1G $LaunchArguments nogui"
} else {
    $escapedServer = $server.Replace('"', '\"')
    "-Xms512M -Xmx1G -jar `"$escapedServer`" nogui"
}
$start.WorkingDirectory = $work
$start.UseShellExecute = $false
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start

$started = $false
$launched = $false
$stdoutTask = $null
$stderrTask = $null
try {
    if (-not $process.Start()) { throw 'Failed to start dedicated server' }
    $launched = $true
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        Start-Sleep -Milliseconds 500
        $serverLog = Join-Path $work 'logs/latest.log'
        $text = if (Test-Path -LiteralPath $serverLog) { Get-Content -LiteralPath $serverLog -Raw } else { '' }
        if ((Test-Path -LiteralPath $runtimeLock -PathType Leaf) -and $text -match 'Done \(') {
            $started = $true
            break
        }
    }
    if (-not $started) {
        throw "Server did not report both WebShopX readiness and Minecraft readiness within $TimeoutSeconds seconds"
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { throw 'Server did not stop within 30 seconds' }
    $stdoutTask.Result | Set-Content -LiteralPath $stdout -Encoding utf8
    $stderrTask.Result | Set-Content -LiteralPath $stderr -Encoding utf8
    if ($process.ExitCode -ne 0) { throw "Server exited with code $($process.ExitCode)" }
} finally {
    if ($launched -and -not $process.HasExited) { $process.Kill($true); $process.WaitForExit() }
    if ($stdoutTask) { $stdoutTask.Result | Set-Content -LiteralPath $stdout -Encoding utf8 }
    if ($stderrTask) { $stderrTask.Result | Set-Content -LiteralPath $stderr -Encoding utf8 }
    $process.Dispose()
}

$combined = (Get-Content -LiteralPath $stdout -Raw) + "`n" + (Get-Content -LiteralPath $stderr -Raw)
if ($ExpectedMinecraft -and $combined -notmatch ('minecraft=' + [regex]::Escape($ExpectedMinecraft) + '\b')) {
    throw "WebShopX did not report expected Minecraft version $ExpectedMinecraft"
}
if ($ExpectedLoader -and $combined -notmatch ('loaderVersion=' + [regex]::Escape($ExpectedLoader) + '\b')) {
    throw "WebShopX did not report expected Loader version $ExpectedLoader"
}
$errorPattern = '(?im)^.*(?:\[[^]]*/ERROR\]|\sERROR\s|Exception in thread|Caused by: .*Exception).*$'
$unexpectedErrors = [regex]::Matches($combined, $errorPattern) | ForEach-Object { $_.Value } |
    Where-Object { $_ -notmatch 'Appender DebugFile|Only supported on (?:OSX/BSD|Linux)' }
if ($unexpectedErrors) {
    throw "Server output contains an error: $($unexpectedErrors[0])"
}
$hash = (Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash.ToLowerInvariant()
[ordered]@{
    status = 'passed'
    artifact = [IO.Path]::GetFileName($mod)
    sha256 = $hash
    port = $Port
    cleanStop = $true
    evidence = $stdout
} | ConvertTo-Json
