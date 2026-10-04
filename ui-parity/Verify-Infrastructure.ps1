# Изолированная проверка без Maven и без записи в target других модулей.
param(
    [string]$CoreClasses = '',
    [string]$JunitJar = '',
    [string]$MavenRepository = '',
    [string]$JunitPlatformVersion = ''
)
$ErrorActionPreference = 'Stop'

# Находит существующий JUnit JAR до создания результатов; ничего не скачивает.
function Resolve-InfrastructureJunit {
    param([string]$Root, [string]$Jar, [string]$Repository, [string]$PlatformVersion)
    if (-not $Jar) {
        if (-not $PlatformVersion) {
            # Запрещаем DTD и внешние сущности при чтении версии из родительского POM.
            $settings = [Xml.XmlReaderSettings]::new()
            $settings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
            $settings.XmlResolver = $null
            $reader = $null
            try {
                # Один раз получаем абсолютный путь относительно текущей папки PowerShell.
                $Root = (Get-Item -LiteralPath $Root).FullName
                $reader = [Xml.XmlReader]::Create((Join-Path $Root 'pom.xml'), $settings)
                $pom = [Xml.XmlDocument]::new()
                $pom.XmlResolver = $null
                $pom.Load($reader)
                $namespaces = [Xml.XmlNamespaceManager]::new($pom.NameTable)
                $namespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
                $versionNode = $pom.SelectSingleNode('/m:project/m:properties/m:junit.version', $namespaces)
                if (-not $versionNode) { throw 'Missing junit.version' }
                $version = $versionNode.InnerText.Trim()
                # В JUnit 5 платформа имеет версию 1.x.y; начиная с JUnit 6 версии совпадают.
                if ($version -match '^5\.(\d+\.\d+(?:-[A-Za-z0-9.-]+)?)$') {
                    $PlatformVersion = '1.' + $Matches[1]
                } elseif ($version -match '^6\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') {
                    $PlatformVersion = $version
                } else { throw "Unsupported junit.version: $version" }
            } catch {
                throw "Cannot determine JUnit Platform version from root pom.xml; specify -JunitJar or -JunitPlatformVersion. $($_.Exception.Message)"
            } finally {
                if ($reader) { $reader.Dispose() }
            }
        }
        if ($PlatformVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') {
            throw 'Invalid -JunitPlatformVersion; expected a concrete version such as 1.14.4.'
        }
        if (-not $Repository) {
            $profileDirectory = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
            if (-not $profileDirectory) { throw 'Cannot locate user profile; specify -MavenRepository or -JunitJar.' }
            $Repository = Join-Path $profileDirectory '.m2/repository'
        }
        $Jar = Join-Path $Repository "org/junit/platform/junit-platform-console-standalone/$PlatformVersion/junit-platform-console-standalone-$PlatformVersion.jar"
    }
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf) -or [IO.Path]::GetExtension($Jar) -ine '.jar') {
        throw "JUnit console JAR not found: $Jar. Supply an existing -JunitJar or populate the cache selected by -MavenRepository."
    }
    return (Get-Item -LiteralPath $Jar).FullName
}

# Копирует содержимое папки, не превращая скобки в имени пути в шаблон.
function Copy-InfrastructureContents {
    param([string]$Source, [string]$Destination)
    foreach ($entry in Get-ChildItem -LiteralPath $Source -Force) {
        Copy-Item -LiteralPath $entry.FullName -Destination $Destination -Recurse -Force
    }
}

$taskRoot = Split-Path $PSScriptRoot -Parent
$taskJunit = Resolve-InfrastructureJunit -Root $taskRoot -Jar $JunitJar -Repository $MavenRepository -PlatformVersion $JunitPlatformVersion
$taskOutput = Join-Path $PSScriptRoot ('target/parity/compile-' + [guid]::NewGuid().ToString())
$taskMain = Join-Path $taskOutput 'core'
$taskTests = Join-Path $taskOutput 'tests'
New-Item -ItemType Directory -Path $taskMain, $taskTests -Force | Out-Null
if ($CoreClasses) {
    # При параллельной работе используем зафиксированную сборку S2, не читаем недописанные исходники другого владельца.
    Copy-InfrastructureContents -Source $CoreClasses -Destination $taskMain
} else {
$taskCoreSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'core/src/main/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
# Дескриптор нужен фиктивному клиенту, который проверяет настоящий модульный запуск S0.
& javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d $taskMain $taskCoreSources
if ($LASTEXITCODE -ne 0) { throw 'Isolated core compilation failed' }
}
Copy-InfrastructureContents -Source (Join-Path $taskRoot 'core/src/main/resources') -Destination $taskMain
if (Test-Path -LiteralPath (Join-Path $taskRoot 'core/src/main/resources-filtered')) {
    Copy-InfrastructureContents -Source (Join-Path $taskRoot 'core/src/main/resources-filtered') -Destination $taskMain
}
$taskParitySources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 25 -Xlint:all,-serial -d $taskTests -cp "$taskMain;$taskJunit" $taskParitySources
if ($LASTEXITCODE -ne 0) { throw 'Isolated ui-parity compilation failed' }
Copy-InfrastructureContents -Source (Join-Path $PSScriptRoot 'src/test/resources') -Destination $taskTests
& java -XX:-UsePerfData '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Duser.language=ru' '-Duser.country=RU' `
    '-Dcashprediction.ui.strictText=true' "-Dparity.reactor.root=$taskRoot" `
    -jar $taskJunit execute --disable-banner --details=summary -cp "$taskMain;$taskTests" `
    --scan-classpath '--include-classname=.*(Test|ParityPipelineIT)$'
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure tests failed' }
Write-Output "Verified isolated classes: $taskOutput"
