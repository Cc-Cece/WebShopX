param(
    [Parameter(Mandatory = $true)][string]$Java,
    [Parameter(Mandatory = $true)][string]$ServerJar,
    [Parameter(Mandatory = $true)][string]$PluginJar,
    [Parameter(Mandatory = $true)][ValidateSet('paper', 'folia')][string]$ExpectedPlatform,
    [Parameter(Mandatory = $true)][string]$ExpectedMinecraft,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [Parameter(Mandatory = $true)][string]$Node,
    [Parameter(Mandatory = $true)][string]$PlayerClientScript,
    [Parameter(Mandatory = $true)][string]$MccExecutable,
    [int]$MinecraftPort = 25583,
    [int]$HttpPort = 8819,
    [int]$TimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath($WorkingDirectory)
$server = [IO.Path]::GetFullPath($ServerJar)
$plugin = [IO.Path]::GetFullPath($PluginJar)
foreach ($file in @($Java, $server, $plugin, $Node, $PlayerClientScript, $MccExecutable)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file is absent: $file" }
}
[IO.Directory]::CreateDirectory((Join-Path $work 'plugins')) | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $work 'plugins/WebShopX.jar') -Force
Set-Content -LiteralPath (Join-Path $work 'eula.txt') -Encoding ascii -Value 'eula=true'
@(
    'online-mode=false'
    "server-port=$MinecraftPort"
    'view-distance=2'
    'simulation-distance=2'
    'level-name=world-smoke'
) | Set-Content -LiteralPath (Join-Path $work 'server.properties') -Encoding ascii

function Send-Line([Diagnostics.Process]$Process, [string]$Line) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Line + "`n")
    $Process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $Process.StandardInput.BaseStream.Flush()
}

function Invoke-Cycle([string]$Name, [switch]$WithPlayer) {
    $stdout = Join-Path $work "console-$Name.log"
    $stderr = Join-Path $work "console-$Name-error.log"
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Java
    $start.Arguments = "-Xms512M -Xmx1G -jar `"$server`" --nogui"
    $start.WorkingDirectory = $work
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $outTask = $null
    $errTask = $null
    try {
        if (-not $process.Start()) { throw 'PaperMC server did not start' }
        Send-Line $process ''
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        $health = $null
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
            Start-Sleep -Milliseconds 500
            $latest = Join-Path $work 'logs/latest.log'
            if (-not (Test-Path -LiteralPath $latest -PathType Leaf) -or
                (Get-Content -LiteralPath $latest -Raw) -notmatch 'Done \(') { continue }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { break }
            } catch { $health = $null }
        }
        if (-not $health -or $health.status -ne 'UP') {
            throw "$ExpectedPlatform did not expose a ready WebShopX health contract"
        }
        $identity = [string]$health.platform.platform
        if ($identity -and $identity -notmatch $ExpectedPlatform) {
            throw "Unexpected platform identity: $identity"
        }
        $playerEvidence = $null
        if ($WithPlayer) {
            $playerFile = Join-Path $work "player-$Name-evidence.json"
            $readyFile = Join-Path $work "player-$Name-ready.json"
            $disconnectFile = Join-Path $work "player-$Name-disconnect.signal"
            $mccHash = (Get-FileHash -LiteralPath $MccExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
            $arguments = @(
                "`"$PlayerClientScript`"", '--host=127.0.0.1', "--port=$MinecraftPort",
                "--version=$ExpectedMinecraft", "--username=WebShopXPaper",
                "--ready-file=`"$readyFile`"", "--disconnect-file=`"$disconnectFile`"",
                "--evidence-file=`"$playerFile`"", '--timeout-ms=60000',
                "--mcc-executable=`"$MccExecutable`"", "--mcc-sha256=$mccHash",
                "--client-log=`"$(Join-Path $work "player-$Name-mcc.log")`"",
                "--client-error-log=`"$(Join-Path $work "player-$Name-mcc-error.log")`""
            ) -join ' '
            $player = Start-Process -FilePath $Node -ArgumentList $arguments -WorkingDirectory $work -PassThru
            $playerDeadline = [DateTime]::UtcNow.AddSeconds(75)
            while ([DateTime]::UtcNow -lt $playerDeadline -and -not (Test-Path $readyFile) -and -not $player.HasExited) {
                Start-Sleep -Milliseconds 100
            }
            if (-not (Test-Path $readyFile)) { throw 'Automated player did not join PaperMC server' }
            Set-Content -LiteralPath $disconnectFile -Encoding ascii -Value 'disconnect'
            if (-not $player.WaitForExit(30000) -or $player.ExitCode -ne 0) {
                throw 'Automated player did not disconnect cleanly'
            }
            $playerEvidence = Get-Content -LiteralPath $playerFile -Raw | ConvertFrom-Json
            if ($playerEvidence.status -ne 'passed') { throw 'Automated player evidence is invalid' }
        }
        Send-Line $process 'version WebShopX'
        Start-Sleep -Seconds 1
        Send-Line $process 'stop'
        if (-not $process.WaitForExit(60000)) { throw "$ExpectedPlatform did not stop cleanly" }
        if ($process.ExitCode -ne 0) { throw "$ExpectedPlatform exited with $($process.ExitCode)" }
        $database = Join-Path $work 'plugins/WebShopX/webshopx.db'
        if (-not (Test-Path -LiteralPath $database -PathType Leaf) -or (Get-Item $database).Length -eq 0) {
            throw 'Paper WebShopX SQLite database was not persisted'
        }
        return [ordered]@{
            cycle = $Name
            health = $health
            player = $playerEvidence
            databaseSha256 = (Get-FileHash $database -Algorithm SHA256).Hash.ToLowerInvariant()
            databaseBytes = (Get-Item $database).Length
        }
    } finally {
        if (-not $process.HasExited) { try { Send-Line $process 'stop' } catch {}; $process.WaitForExit(30000) | Out-Null }
        if (-not $process.HasExited) { $process.Kill($true) }
        if ($outTask) { $outTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stdout -Encoding utf8 }
        if ($errTask) { $errTask.GetAwaiter().GetResult() | Set-Content -LiteralPath $stderr -Encoding utf8 }
        $process.Dispose()
    }
}

$first = Invoke-Cycle -Name 'first-start' -WithPlayer
$restart = Invoke-Cycle -Name 'restart'
[ordered]@{
    status = 'passed'
    platform = $ExpectedPlatform
    minecraft = $ExpectedMinecraft
    firstStart = $first
    restart = $restart
} | ConvertTo-Json -Depth 12
