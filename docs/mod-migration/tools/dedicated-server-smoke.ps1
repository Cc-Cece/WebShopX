param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$ServerJar,
    [string]$LaunchArguments,
    [Parameter(Mandatory = $true)][string]$ModJar,
    [string]$FixtureModJar,
    [Parameter(Mandatory = $true)][string]$WorkingDirectory,
    [string]$ExpectedMinecraft,
    [string]$ExpectedLoader,
    [string]$HealthCommand,
    [string[]]$ProbeCommand = @(),
    [string[]]$ExpectedProbePattern = @(),
    [string]$Node,
    [string]$PlayerClientScript,
    [string]$MccExecutable,
    [switch]$VerifyInventory,
    [switch]$VerifyItemFixtures,
    [switch]$ExpectInventoryRecovery,
    [string[]]$OnlinePlayerCommand = @(),
    [ValidatePattern('^[A-Za-z0-9_]{3,16}$')][string]$PlayerUsername = 'WebShopXProbe',
    [ValidatePattern('^[A-Za-z0-9_-]*$')][string]$EvidencePrefix = '',
    [int]$Port = 25622,
    [int]$TimeoutSeconds = 480
)

$ErrorActionPreference = 'Stop'
$work = [IO.Path]::GetFullPath($WorkingDirectory)
$server = if ($ServerJar) { [IO.Path]::GetFullPath($ServerJar) } else { $null }
$mod = [IO.Path]::GetFullPath($ModJar)
$fixtureMod = if ($FixtureModJar) { [IO.Path]::GetFullPath($FixtureModJar) } else { $null }
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Java not found: $Java" }
if (-not $server -and -not $LaunchArguments) { throw 'ServerJar or LaunchArguments is required' }
if ($server -and -not (Test-Path -LiteralPath $server -PathType Leaf)) { throw "Server JAR not found: $server" }
if (-not (Test-Path -LiteralPath $mod -PathType Leaf)) { throw "Mod JAR not found: $mod" }
if ($fixtureMod -and -not (Test-Path -LiteralPath $fixtureMod -PathType Leaf)) {
    throw "Fixture Mod JAR not found: $fixtureMod"
}
if ($PlayerClientScript) {
    if (-not $Node -or -not (Test-Path -LiteralPath $Node -PathType Leaf)) {
        throw "Node executable is required for player verification: $Node"
    }
    $PlayerClientScript = [IO.Path]::GetFullPath($PlayerClientScript)
    if (-not (Test-Path -LiteralPath $PlayerClientScript -PathType Leaf)) {
        throw "Player client script not found: $PlayerClientScript"
    }
    if (-not $ExpectedMinecraft) { throw 'ExpectedMinecraft is required for player verification' }
}
if ($MccExecutable) {
    $MccExecutable = [IO.Path]::GetFullPath($MccExecutable)
    if (-not (Test-Path -LiteralPath $MccExecutable -PathType Leaf)) {
        throw "MCC executable not found: $MccExecutable"
    }
    $mccHash = (Get-FileHash -LiteralPath $MccExecutable -Algorithm SHA256).Hash.ToLowerInvariant()
    $approvedMccHashes = @(
        '32c0ef4cd8a7cffabcc8267479cb03f244960451da3c2677f375bc4f2bfb9604',
        '8736c0d7979fe6cd1bacfa669a2a0d301978171afb4c18d5a10112435dc01578'
    )
    if ($mccHash -notin $approvedMccHashes) { throw "MCC executable hash is not approved: $mccHash" }
    if (-not $PlayerClientScript) { throw 'MCC verification requires PlayerClientScript' }
}
if (($VerifyInventory -or $ExpectInventoryRecovery) -and -not $PlayerClientScript) {
    throw 'Inventory verification requires the automated Minecraft client'
}
if ($ExpectInventoryRecovery -and -not $VerifyInventory) {
    throw 'ExpectInventoryRecovery requires VerifyInventory'
}
if ($VerifyItemFixtures -and (-not $VerifyInventory -or $ExpectInventoryRecovery)) {
    throw 'VerifyItemFixtures requires the first-start inventory verification cycle'
}
if ($VerifyItemFixtures -and (-not $fixtureMod -or $OnlinePlayerCommand.Count -eq 0)) {
    throw 'VerifyItemFixtures requires FixtureModJar and OnlinePlayerCommand'
}

function Invoke-InventoryProbe {
    param(
        [Parameter(Mandatory = $true)][Diagnostics.Process]$ServerProcess,
        [Parameter(Mandatory = $true)][string]$Mode,
        [Parameter(Mandatory = $true)][string]$PlayerId,
        [Parameter(Mandatory = $true)][string]$DataDirectory,
        [int]$MinimumAssertions
    )
    $evidenceFile = Join-Path $DataDirectory "inventory-probe-$Mode.json"
    if (Test-Path -LiteralPath $evidenceFile) { Remove-Item -LiteralPath $evidenceFile -Force }
    Send-ProcessLine -Target $ServerProcess -Line "webshopx-inventory-$Mode-probe $PlayerId"
    $probeDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $probeDeadline -and
        -not (Test-Path -LiteralPath $evidenceFile -PathType Leaf) -and -not $ServerProcess.HasExited) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $evidenceFile -PathType Leaf)) {
        throw "Native inventory $Mode probe did not produce evidence"
    }
    $evidence = Get-Content -LiteralPath $evidenceFile -Raw | ConvertFrom-Json
    if ($evidence.status -ne 'passed' -or $evidence.mode -ne $Mode -or
        $evidence.playerId -ne $PlayerId -or $evidence.assertions -lt $MinimumAssertions) {
        throw "Native inventory $Mode probe evidence is invalid: $($evidence.reason)"
    }
    return $evidence
}

function Send-ProcessLine {
    param(
        [Parameter(Mandatory = $true)][Diagnostics.Process]$Target,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Line
    )
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Line + "`n")
    $Target.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $Target.StandardInput.BaseStream.Flush()
}

New-Item -ItemType Directory -Force -Path $work, (Join-Path $work 'mods') | Out-Null
Copy-Item -LiteralPath $mod -Destination (Join-Path $work 'mods/webshopx.jar') -Force
if ($fixtureMod) {
    Copy-Item -LiteralPath $fixtureMod -Destination (Join-Path $work 'mods/webshopx-fixture.jar') -Force
}
Set-Content -LiteralPath (Join-Path $work 'eula.txt') -Encoding ascii -Value 'eula=true'
@(
    'online-mode=false'
    "server-port=$Port"
    'view-distance=2'
    'simulation-distance=2'
    'level-name=world-smoke'
) | Set-Content -LiteralPath (Join-Path $work 'server.properties') -Encoding ascii

$logStem = if ([string]::IsNullOrWhiteSpace($EvidencePrefix)) { 'console' } else { "console-$EvidencePrefix" }
$stdout = Join-Path $work "$logStem.log"
$stderr = Join-Path $work "$logStem-error.log"
$runtimeLock = Join-Path $work 'config/webshopx/runtime.lock'
$healthFile = Join-Path $work 'config/webshopx/health.json'
if (Test-Path -LiteralPath $runtimeLock -PathType Leaf) { Remove-Item -LiteralPath $runtimeLock -Force }
if (Test-Path -LiteralPath $healthFile -PathType Leaf) { Remove-Item -LiteralPath $healthFile -Force }
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $Java
$start.Arguments = if ($LaunchArguments) {
    "-Xms512M -Xmx1G -Dwebshopx.acceptance-probes.enabled=true $LaunchArguments nogui"
} else {
    $escapedServer = $server.Replace('"', '\"')
    "-Xms512M -Xmx1G -Dwebshopx.acceptance-probes.enabled=true -jar `"$escapedServer`" nogui"
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
$playerProcess = $null
$playerEvidence = $null
$inventoryEvidence = @()
try {
    if (-not $process.Start()) { throw 'Failed to start dedicated server' }
    $launched = $true
    # Windows PowerShell 5.1's Process.StandardInput writer emits a UTF-8 BOM on
    # its first write. Consume it on an empty line so it cannot prefix a command.
    Send-ProcessLine -Target $process -Line ''
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        Start-Sleep -Milliseconds 500
        $serverLog = Join-Path $work 'logs/latest.log'
        $text = if (Test-Path -LiteralPath $serverLog) { Get-Content -LiteralPath $serverLog -Raw } else { '' }
        $health = if (Test-Path -LiteralPath $healthFile) { Get-Content -LiteralPath $healthFile -Raw } else { '' }
        if ((Test-Path -LiteralPath $runtimeLock -PathType Leaf) -and
            $health -match '"state"\s*:\s*"READY"' -and $text -match 'Done \(') {
            $started = $true
            break
        }
    }
    if (-not $started) {
        if ($process.HasExited) {
            throw "Server exited with code $($process.ExitCode) before reporting readiness; see $stdout and $stderr"
        }
        throw "Server did not report both WebShopX readiness and Minecraft readiness within $TimeoutSeconds seconds"
    }
    if ($PlayerClientScript) {
        $playerStem = if ([string]::IsNullOrWhiteSpace($EvidencePrefix)) { 'player' } else { "player-$EvidencePrefix" }
        $readyFile = Join-Path $work "$playerStem-ready.json"
        $disconnectFile = Join-Path $work "$playerStem-disconnect.signal"
        $playerEvidenceFile = Join-Path $work "$playerStem-evidence.json"
        foreach ($file in @($readyFile, $disconnectFile, $playerEvidenceFile)) {
            if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
        }
        $playerStart = [Diagnostics.ProcessStartInfo]::new()
        $quote = { param([string]$value) '"' + $value.Replace('"', '\"') + '"' }
        $playerArguments = @(
            (& $quote $PlayerClientScript), '--host=127.0.0.1', "--port=$Port",
            "--version=$ExpectedMinecraft", "--username=$PlayerUsername",
            ('--ready-file=' + (& $quote $readyFile)),
            ('--disconnect-file=' + (& $quote $disconnectFile)),
            ('--evidence-file=' + (& $quote $playerEvidenceFile)), '--timeout-ms=60000'
        )
        if ($MccExecutable) {
            $playerArguments += '--mcc-executable=' + (& $quote $MccExecutable)
            $playerArguments += "--mcc-sha256=$mccHash"
            $playerArguments += '--client-log=' + (& $quote (Join-Path $work "$playerStem-mcc.log"))
            $playerArguments += '--client-error-log=' + (& $quote (Join-Path $work "$playerStem-mcc-error.log"))
        }
        $playerStart.FileName = $Node
        $playerStart.Arguments = $playerArguments -join ' '
        $playerStart.WorkingDirectory = Split-Path $PlayerClientScript -Parent
        $playerStart.UseShellExecute = $false
        $playerProcess = [Diagnostics.Process]::new()
        $playerProcess.StartInfo = $playerStart
        if (-not $playerProcess.Start()) { throw 'Failed to start automated Minecraft client' }
        $playerDeadline = [DateTime]::UtcNow.AddSeconds(60)
        while ([DateTime]::UtcNow -lt $playerDeadline -and
            -not (Test-Path -LiteralPath $readyFile -PathType Leaf) -and -not $playerProcess.HasExited) {
            Start-Sleep -Milliseconds 100
        }
        if (-not (Test-Path -LiteralPath $readyFile -PathType Leaf)) {
            throw 'Automated Minecraft client did not complete login'
        }
        $ready = Get-Content -LiteralPath $readyFile -Raw | ConvertFrom-Json
        if ($ready.status -ne 'joined' -or $ready.username -ne $PlayerUsername -or
            $ready.version -ne $ExpectedMinecraft -or -not $ready.uuid) {
            throw 'Automated Minecraft client returned invalid login evidence'
        }
        $joinDeadline = [DateTime]::UtcNow.AddSeconds(15)
        $joinPattern = [regex]::Escape($PlayerUsername) + ' joined the game'
        $playerLog = ''
        do {
            $playerLog = Get-Content -LiteralPath (Join-Path $work 'logs/latest.log') -Raw
            if ($playerLog -match $joinPattern) { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $joinDeadline -and -not $process.HasExited)
        if ($playerLog -notmatch $joinPattern) {
            throw 'Dedicated server did not record the real player join before probes'
        }
        if ($VerifyItemFixtures) {
            foreach ($onlineCommand in $OnlinePlayerCommand) {
                Send-ProcessLine -Target $process -Line $onlineCommand.Replace('{player}', $PlayerUsername)
            }
            Start-Sleep -Seconds 1
            $inventoryEvidence += Invoke-InventoryProbe -ServerProcess $process `
                -Mode 'fixture' -PlayerId $ready.uuid `
                -DataDirectory (Join-Path $work 'config/webshopx') `
                -MinimumAssertions 35
        }
        if ($VerifyInventory) {
            $inventoryMode = if ($ExpectInventoryRecovery) { 'recovery' } else { 'online' }
            $minimumAssertions = if ($ExpectInventoryRecovery) { 4 } else { 9 }
            $inventoryEvidence += Invoke-InventoryProbe -ServerProcess $process `
                -Mode $inventoryMode -PlayerId $ready.uuid `
                -DataDirectory (Join-Path $work 'config/webshopx') `
                -MinimumAssertions $minimumAssertions
        }
        Set-Content -LiteralPath $disconnectFile -Encoding ascii -Value 'disconnect'
        if (-not $playerProcess.WaitForExit(30000)) { throw 'Automated Minecraft client did not disconnect' }
        if ($playerProcess.ExitCode -ne 0) {
            throw "Automated Minecraft client failed with exit code $($playerProcess.ExitCode)"
        }
        if (-not (Test-Path -LiteralPath $playerEvidenceFile)) {
            throw 'Automated Minecraft client did not write evidence'
        } else {
            $playerEvidence = Get-Content -LiteralPath $playerEvidenceFile -Raw | ConvertFrom-Json
        }
        if ($playerEvidence.status -ne 'passed') { throw 'Automated Minecraft client evidence did not pass' }
        $lifecycleDeadline = [DateTime]::UtcNow.AddSeconds(15)
        $playerLog = ''
        do {
            $playerLog = Get-Content -LiteralPath (Join-Path $work 'logs/latest.log') -Raw
            if ($playerLog -match ([regex]::Escape($PlayerUsername) + ' joined the game') -and
                $playerLog -match ([regex]::Escape($PlayerUsername) + ' left the game')) { break }
            Start-Sleep -Milliseconds 100
        } while ([DateTime]::UtcNow -lt $lifecycleDeadline -and -not $process.HasExited)
        if ($playerLog -notmatch ([regex]::Escape($PlayerUsername) + ' joined the game') -or
            $playerLog -notmatch ([regex]::Escape($PlayerUsername) + ' left the game')) {
            throw 'Dedicated server did not record the complete player join/leave lifecycle'
        }
        if ($VerifyInventory -and -not $ExpectInventoryRecovery) {
            $inventoryEvidence += Invoke-InventoryProbe -ServerProcess $process `
                -Mode 'offline' -PlayerId $ready.uuid `
                -DataDirectory (Join-Path $work 'config/webshopx') `
                -MinimumAssertions 14
        }
    }
    if ($HealthCommand) {
        Send-ProcessLine -Target $process -Line $HealthCommand
    }
    foreach ($command in $ProbeCommand) {
        Send-ProcessLine -Target $process -Line $command
    }
    if ($HealthCommand -or $ProbeCommand.Count -gt 0) { Start-Sleep -Seconds 1 }
    Send-ProcessLine -Target $process -Line 'stop'
    if (-not $process.WaitForExit(30000)) { throw 'Server did not stop within 30 seconds' }
    $stdoutTask.Result | Set-Content -LiteralPath $stdout -Encoding utf8
    $stderrTask.Result | Set-Content -LiteralPath $stderr -Encoding utf8
    if ($process.ExitCode -ne 0) { throw "Server exited with code $($process.ExitCode)" }
    $stoppedHealth = if (Test-Path -LiteralPath $healthFile) { Get-Content -LiteralPath $healthFile -Raw } else { '' }
    if ($stoppedHealth -notmatch '"state"\s*:\s*"STOPPED"') {
        throw 'WebShopX did not persist STOPPED health after dedicated server shutdown'
    }
} finally {
    if ($playerProcess -and -not $playerProcess.HasExited) { $playerProcess.Kill(); $playerProcess.WaitForExit() }
    if ($playerProcess) { $playerProcess.Dispose() }
    # Windows PowerShell 5.1 targets .NET Framework, which has no Kill(Boolean) overload.
    if ($launched -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
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
if ($HealthCommand -and $combined -notmatch 'WebShopX state=READY') {
    throw "Health command did not return the READY identity line: $HealthCommand"
}
foreach ($pattern in $ExpectedProbePattern) {
    if ($combined -notmatch $pattern) {
        throw "Server output did not match required probe pattern: $pattern"
    }
}
$errorPattern = '(?m)^.*(?:\[[^]]*/ERROR\]|\sERROR\s|Exception in thread|Caused by: .*Exception).*$'
$unexpectedErrors = [regex]::Matches($combined, $errorPattern) | ForEach-Object { $_.Value } |
    Where-Object { $_ -notmatch 'Appender DebugFile|Only supported on (?:OSX/BSD|Linux)' }
if ($unexpectedErrors) {
    throw "Server output contains an error: $(@($unexpectedErrors)[0])"
}
$hash = (Get-FileHash -LiteralPath $mod -Algorithm SHA256).Hash.ToLowerInvariant()
$fixtureHash = if ($fixtureMod) {
    (Get-FileHash -LiteralPath $fixtureMod -Algorithm SHA256).Hash.ToLowerInvariant()
} else { $null }
[ordered]@{
    status = 'passed'
    artifact = [IO.Path]::GetFileName($mod)
    sha256 = $hash
    fixtureArtifact = if ($fixtureMod) { [IO.Path]::GetFileName($fixtureMod) } else { $null }
    fixtureSha256 = $fixtureHash
    port = $Port
    cleanStop = $true
    cycle = if ([string]::IsNullOrWhiteSpace($EvidencePrefix)) { 'single' } else { $EvidencePrefix }
    probes = @($ExpectedProbePattern)
    player = $playerEvidence
    inventory = @($inventoryEvidence)
    evidence = $stdout
} | ConvertTo-Json
