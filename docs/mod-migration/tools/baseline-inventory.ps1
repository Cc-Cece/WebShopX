param(
    [string]$Output = "",
    [string]$FrontendDir = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $repoRoot "docs\mod-migration\implementation\reports\M0-inventory.json"
} elseif (-not [System.IO.Path]::IsPathRooted($Output)) {
    $Output = Join-Path $repoRoot $Output
}

function Relative-Path([string]$Path) {
    $rootUri = [Uri]($repoRoot.TrimEnd("\") + "\")
    $pathUri = [Uri]$Path
    return [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString())
}

function Match-All([string]$Text, [string]$Pattern, [int]$Group = 1) {
    return @([regex]::Matches($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase) |
        ForEach-Object { $_.Groups[$Group].Value } | Sort-Object -Unique)
}

$javaFiles = @(Get-ChildItem (Join-Path $repoRoot "src\main\java") -Recurse -Filter "*.java")
$testFiles = @(Get-ChildItem (Join-Path $repoRoot "src\test\java") -Recurse -Filter "*.java")
$coupling = foreach ($file in $javaFiles) {
    $text = Get-Content $file.FullName -Raw -Encoding utf8
    $categories = [System.Collections.Generic.List[string]]::new()
    if ($text -match "org\.bukkit|io\.papermc") { $categories.Add("bukkit-paper") }
    if ($text -match "\b(ItemStack|Inventory|PersistentDataContainer|Material)\b") { $categories.Add("item-inventory") }
    if ($text -match "\b(CommandSender|CommandExecutor|TabCompleter)\b") { $categories.Add("command") }
    if ($text -match "\b(Listener|EventHandler)\b") { $categories.Add("event") }
    if ($text -match "\b(BukkitScheduler|GlobalRegionScheduler|RegionScheduler|EntityScheduler)\b") { $categories.Add("scheduler") }
    if ($text -match "net\.milkbowl|\bVault\b") { $categories.Add("economy-provider") }
    if ($categories.Count -gt 0) {
        [ordered]@{ path = Relative-Path $file.FullName; categories = @($categories) }
    }
}

$pluginPath = Join-Path $repoRoot "src\main\resources\plugin.yml"
$pluginText = Get-Content $pluginPath -Raw -Encoding utf8
$commands = Match-All $pluginText "(?m)^  ([a-z0-9_.-]+):\r?\n    description:"
$permissions = Match-All $pluginText "(?m)^  (webshop\.[a-z0-9_.-]+):"

$webPath = Join-Path $repoRoot "src\main\java\com\webshopx\EmbeddedWebServer.java"
$webText = Get-Content $webPath -Raw -Encoding utf8
$httpRoutes = @(
    Match-All $webText 'createContext\(\s*"([^\"]+)"'
    Match-All $webText 'pathEquals\([^,]+,\s*"([^\"]+)"'
    Match-All $webText 'pathStartsWith\([^,]+,\s*"([^\"]+)"'
) | Sort-Object -Unique

$relayPath = Join-Path $repoRoot "src\main\java\com\webshopx\RelayRpcRouter.java"
$relayText = Get-Content $relayPath -Raw -Encoding utf8
$relayMethods = @(
    Match-All $relayText 'case\s+"([^\"]+)"'
    Match-All $relayText '"(webshopx\.[a-z0-9_.-]+)"'
) | Sort-Object -Unique

$schemaPath = Join-Path $repoRoot "src\main\resources\db\sqlite\schema.sql"
$schemaText = Get-Content $schemaPath -Raw -Encoding utf8
$tables = Match-All $schemaText "CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+([a-zA-Z0-9_]+)"
$indexes = Match-All $schemaText "CREATE\s+(?:UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\s+([a-zA-Z0-9_]+)"

$buildText = Get-Content (Join-Path $repoRoot "build.gradle") -Raw -Encoding utf8
$jarTasks = Match-All $buildText "tasks\.register\(\s*'([^']+)'\s*,\s*(?:ShadowJar|Jar)"
$services = @($javaFiles | Where-Object BaseName -Like "*Service" | ForEach-Object BaseName | Sort-Object -Unique)
$listeners = @($javaFiles | Where-Object BaseName -Like "*Listener" | ForEach-Object BaseName | Sort-Object -Unique)

$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$gitBranch = (& git -C $repoRoot branch --show-current).Trim()
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
$gradleVersionLine = (& (Join-Path $repoRoot "gradlew.bat") --version --console=plain 2>&1 |
    Where-Object { $_ -match "^Gradle " } | Select-Object -First 1).ToString()
$ErrorActionPreference = $savedErrorPreference
$nodeVersion = (& node --version).Trim()
$pnpmVersion = (& pnpm --version).Trim()
if ([string]::IsNullOrWhiteSpace($FrontendDir)) {
    $FrontendDir = Join-Path $repoRoot "..\webshopx-web"
}
$frontendRoot = (Resolve-Path $FrontendDir).Path
$frontendCommit = (& git -C $frontendRoot rev-parse HEAD).Trim()
$frontendDirty = @(& git -C $frontendRoot status --porcelain)
$plainArtifact = Join-Path $repoRoot "build\tmp\plain-plugin\WebShopX-dev-v3.0.0-plain-plugin.jar"
$hashTargets = @($pluginPath, (Join-Path $repoRoot "src\main\resources\config.yml"), $schemaPath)
if (Test-Path $plainArtifact) { $hashTargets += $plainArtifact }
$hashes = [ordered]@{}
foreach ($hashTarget in $hashTargets) {
    $hashes[(Relative-Path $hashTarget)] = (Get-FileHash -LiteralPath $hashTarget -Algorithm SHA256).Hash.ToLowerInvariant()
}

$inventory = [ordered]@{
    schemaVersion = 1
    generatedAt = [DateTimeOffset]::UtcNow.ToString("o")
    baseline = [ordered]@{
        commit = $gitCommit
        branch = $gitBranch
        os = [System.Runtime.InteropServices.RuntimeInformation]::OSDescription
        java = $javaVersion
        gradle = $gradleVersionLine
        node = $nodeVersion
        pnpm = $pnpmVersion
        frontendCommit = $frontendCommit
        frontendDirtyPaths = @($frontendDirty)
    }
    source = [ordered]@{
        productionJavaFiles = $javaFiles.Count
        testJavaFiles = $testFiles.Count
        services = $services
        listeners = $listeners
        platformCoupling = @($coupling)
    }
    external = [ordered]@{
        commands = $commands
        permissions = $permissions
        httpRoutes = $httpRoutes
        relayMethods = $relayMethods
    }
    database = [ordered]@{
        sqliteSchema = Relative-Path $schemaPath
        tables = $tables
        indexes = $indexes
    }
    build = [ordered]@{
        targetRuntimes = @("1.18.2+", "1.20.6+", "26.1+", "26.2+")
        jarTasks = $jarTasks
        baselineTask = "paperBaseline"
    }
    evidence = [ordered]@{
        sha256 = $hashes
        databaseTests = @(
            "SqliteSchemaScriptTest",
            "SqliteBusinessSqlSmokeTest",
            "SqliteConcurrencyRetryTest",
            "SqliteOfficialSnapshotMigrationTest",
            "MySqlBusinessSqlSmokeTest"
        )
    }
}

$outputDirectory = Split-Path $Output -Parent
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$inventory | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
Write-Output "Wrote $(Relative-Path $Output)"
