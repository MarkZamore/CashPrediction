<# Выполняет реальные S4 gates последовательно; профиль сам по себе не является доказательством. #>
param(
    [ValidateSet('UI','E2E')][string]$Mode = 'UI',
    [string]$EvidenceRoot = (Join-Path $PSScriptRoot '../../ui-parity/target/gates'),
    [string]$Browser
)
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path ([IO.Path]::GetFullPath($EvidenceRoot)) ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $run | Out-Null
$build = Join-Path $run 'actual'
$publish = Join-Path $run 'upload'
New-Item -ItemType Directory -Path $publish | Out-Null
# Вход сборщиков заранее пуст и имеет квитанцию именно этого запуска, не произвольную TEMP-папку.
$observations = Join-Path $build 'parity'
New-Item -ItemType Directory -Path $observations | Out-Null
$sources = @('core','ui-fx','ui-swing','web') | ForEach-Object {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $repository "$_/target") -File -Filter 'cashprediction-*.jar' |
        Where-Object { $_.Name -notmatch '-(tests|sources|javadoc)\.jar$' })
    if ($jars.Count -ne 1) { throw 'Exactly one built production jar per module required.' }
    [ordered]@{ name = $jars[0].Name; sha256 = (Get-FileHash -LiteralPath $jars[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
[ordered]@{ schema = 1; runId = (Split-Path $run -Leaf); input = [IO.Path]::GetFullPath($observations);
    startedAtEpochMillis = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); sources = @($sources) } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $observations 'fresh-run.json') -Encoding utf8
$script:step = 0

# Один редактор применяется к консоли, XML и каждому загружаемому текстовому наблюдению.
. (Join-Path $PSScriptRoot 'Protect-GateText.ps1')
& (Join-Path $PSScriptRoot 'Test-GateRedaction.ps1')

# Изолированная папка отчётов исключает принятие XML предыдущего запуска.
function Invoke-ActualTest([string]$Class, [int]$Count, [string]$Method, [string[]]$Properties = @(), [string]$Module = 'ui-parity', [string]$Selector = '') {
    $script:step++
    $reports = Join-Path $run ('reports-{0:D2}-{1}' -f $script:step,$Class)
    $selection = if ($Selector) { $Selector } else { $Class }
    $arguments = @('-B','-ntp',"-P$(if ($Mode -eq 'E2E') {'e2e'} else {'ui-tests'})",'-pl',$Module,
        "-Dtest=$selection",'-Dsurefire.failIfNoSpecifiedTests=true',"-Dparity.reports.directory=$reports",
        "-Dparity.build.directory=$build") + $Properties
    if ($Browser) { $arguments += "-Dparity.browser=$Browser" }
    # Создание без Force отклоняет уже существующий каталог, включая старые XML.
    New-Item -ItemType Directory -Path $reports | Out-Null
    try {
        & mvn @arguments test 2>&1 | ForEach-Object { Protect-GateText "$_" } |
            Tee-Object -FilePath (Join-Path $publish "$Class.log") | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Gate failed: $Class" }
        $files = @(Get-ChildItem -LiteralPath $reports -File -Filter 'TEST-*.xml')
        if ($files.Count -ne 1) { throw "Expected one fresh XML suite: $Class" }
        [xml]$xml = Get-Content -LiteralPath $files[0].FullName -Raw
        $suite = $xml.testsuite
        if ($suite.name -notmatch "\.$([regex]::Escape($Class))$" -or [int]$suite.failures -ne 0 -or
            [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or [int]$suite.tests -lt 1) { throw "Invalid actual suite: $Class" }
        $cases = @($suite.testcase)
        if ([int]$suite.tests -ne $cases.Count -or @($cases | Where-Object { $_.SelectNodes('failure|error|skipped').Count -gt 0 }).Count) {
            throw "Testcase payload contradicts suite counters: $Class"
        }
        if ($Count -gt 0 -and $cases.Count -ne $Count) { throw "Unexpected case count: $Class" }
        if ($Method -and @($cases | Where-Object { $_.name -eq $Method }).Count -ne 1) { throw "Required actual method absent: $Class.$Method" }
    } finally {
        # Даже ошибочный или повреждённый XML сохраняется как текст после удаления секретов.
        # Экспорт не подтверждает gate: исключение строгой проверки продолжает распространяться.
        $freshReports = @(Get-ChildItem -LiteralPath $reports -File -Filter 'TEST-*.xml' | Sort-Object Name)
        for ($index = 0; $index -lt $freshReports.Count; $index++) {
            $name = if ($freshReports.Count -eq 1) { "$Class.xml" } else { '{0}-{1:D2}.xml' -f $Class,($index + 1) }
            Protect-GateText (Get-Content -LiteralPath $freshReports[$index].FullName -Raw) |
                Set-Content -LiteralPath (Join-Path $publish $name) -Encoding utf8
        }
    }
}

Push-Location $repository
try {
    # После default flip остаточные legacy class/resources в jar блокируют оба режима до GUI-прогонов.
    Invoke-ActualTest 'NoLegacyJarAuditTest' 15 ''
    Invoke-ActualTest 'NoLegacyModuleJarsTest' 1 'actualModuleJarsContainNoLegacy' @('-Dparity.noLegacy=true')
    # Очистка дочернего Edge допускает только целый аргумент уникального профиля.
    Invoke-ActualTest 'BrowserProfileOwnershipTest' 2 ''
    Invoke-ActualTest 'GateCoverageDesktopTest' 1 'actualDesktopFocusRoundTrip' @('-Dparity.desktopPreflight=true')
    # Негативные тесты проверяют строгость сбора свидетельств до дорогой GUI-матрицы.
    Invoke-ActualTest 'GateCoverageValidationTest' 8 ''
    # Синтетические негативные guards не подменяют отдельный реальный census выбора папки.
    Invoke-ActualTest 'DirectoryChooserSpecificationTest' 4 ''
    Invoke-ActualTest 'DirectoryChooserProbeTest' 4 '' @() 'ui-parity' 'DirectoryChooserProbeTest#scriptUsesActualFolderCommandAndCancellation+doneCannotHideFailedOrMissingSteps+directoryEvidenceRejectsMissingZeroNegativeAndInjectedMetadata+directoryIdentityCannotBeRelabelled'
    Invoke-ActualTest 'FxInputFocusTest' 1 'completedFillProducesRealBlurBeforeReturning' @('-Dfx.focusProof=true') 'ui-fx'
    # У spinner нет второго клиентского debounce поверх общего ToolsFlow.
    Invoke-ActualTest 'FxMenuSpinnerCommitTest' 1 'editorActionReachesIntentWithoutClientDebounce' @('-Dfx.spinnerCommitProof=true') 'ui-fx'
    # Реальное наведение не должно смешиваться с искусственным событием над другой строкой меню.
    Invoke-ActualTest 'FxMenuHoverTest' 1 'movingFromPngToSaveShowsOnlySaveTooltip' @('-Dfx.menuHoverProof=true') 'ui-fx'
    # Реальные состояния ячейки и radio skin, цвета/размеры PNG: не только семантические дампы.
    Invoke-ActualTest 'FxComputedGeometryTest' 3 '' @('-Dfx.geometryProof=true') 'ui-fx' 'FxComputedGeometryTest#recycledTableCellClearsActualAccessibility+formRadioButtonsUseSharedSkinWhenOptionsAreRebuilt+sharedInlineIconsFollowActualTextColorAndKeepLogicalText'
    # Все страницы реальных форм измеряются по PNG и тексту после CSS, без старых отступов глифа.
    Invoke-ActualTest 'FxFormDialogGeometryTest' 1 'actualFormsMeasureSharedIconWithoutLegacyGlyphPadding' @('-Dfx.formGeometryProof=true') 'ui-fx' 'FxFormDialogGeometryTest#actualFormsMeasureSharedIconWithoutLegacyGlyphPadding'
    # Снимок должен вернуть настоящую геометрию alert до регистрации; обычный выход сохраняет код ядра.
    Invoke-ActualTest 'FxAlertsGeometryTest' 1 'realAlertRestoresBoundsBeforeRegistrationAndKeepsLiveGeometry' @('-Dfx.alertGeometryProof=true') 'ui-fx'
    # Явные позиции значков сохраняют пользовательский текст и доступность настоящего TextFlow.
    Invoke-ActualTest 'FxDecoratedTooltipTest' 1 'actualTextFlowUsesOnlyDeclaredUtf16Positions' @('-Dfx.decoratedTooltipProof=true') 'ui-fx'
    Invoke-ActualTest 'FxCleanExitTest' 1 'nativePortReturnsNonzeroCodeAfterLaunch' @('-Dfx.cleanExitProof=true') 'ui-fx' 'FxCleanExitTest#nativePortReturnsNonzeroCodeAfterLaunch'
    # Ошибочный текст числового поля сохраняется после реальной потери фокуса и восстановления.
    Invoke-ActualTest 'SwingSpinnerFocusTest' 2 '' @('-Dcashprediction.swing.spinnerFocus=true') 'ui-swing'
    # Повторный физический ввод в quick-edit не должен попадать в фильтр главной таблицы.
    Invoke-ActualTest 'SwingQuickEditFocusTest' 2 '' @('-Dcashprediction.swing.quickEditFocus=true') 'ui-swing'
    if ($Mode -eq 'UI') {
        # Читаем физические PNG через настоящий сервер и проверяем выбранные radio в живом DOM.
        Invoke-ActualTest 'SharedIconBrowserContractTest' 1 'actualDomResourcesAndRadioDumpKeepSharedIconContract' @('-Dparity.realClients=true','-Dparity.sharedIcons=true')
        # Дополнительная DOM-проба контролирует aria, фокус spinner, tooltip и пользовательские имена.
        Invoke-ActualTest 'SharedIconsBrowserTest' 1 'iconsOnlyProducesFreshCompleteDomEvidence(Path)' @('-Dparity.iconsProof=true')
        Invoke-ActualTest 'WebRevisionResyncBrowserIT' 1 'revisionsSurviveLiveFormRecreation(Path)' @('-Dparity.revisionProof=true')
        Invoke-ActualTest 'FormShowEvidenceTest' 4 ''
        Invoke-ActualTest 'WebAllowanceTest' 9 '' @('-Dparity.realClients=true','-Dparity.clients=web')
        Invoke-ActualTest 'NativeAllowanceReferenceTest' 2 '' @('-Dparity.realClients=true','-Dparity.allowance.desktopReleased=true')
        Invoke-ActualTest 'ControlledSnapshotNativeTest' 2 '' @('-Dparity.realClients=true','-Dparity.allowance.desktopReleased=true')
        $fresh = Join-Path $run 'fresh-evidence'
        Invoke-ActualTest 'FreshAllowanceEvidenceTest' 1 'aggregateActualObservations' @('-Dparity.freshEvidence=true',"-Dparity.freshEvidence.input=$build/parity","-Dparity.freshEvidence.output=$fresh","-Dparity.freshEvidence.runId=$(Split-Path $run -Leaf)")
        Invoke-ActualTest 'ParityTest' 1 'realClientsAgainstGoldens' @('-Dparity.realClients=true','-Dparity.clients=fx,swing,web','-Dparity.scenarios=',"-Dparity.allowance.evidence=$fresh/evidence-manifest.json")
        Invoke-ActualTest 'HotkeyParityTest' 221 '' @('-Dparity.realClients=true','-Dparity.clients=fx,swing,web','-Dparity.hotkeys=','-Dparity.hotkey.residual=false')
        Invoke-ActualTest 'ClassUsageTest' 2 'realFxScenariosInstantiateRequired23' @('-Dparity.realClients=true','-Dparity.clients=fx','-Dparity.scenarios=')
        Invoke-ActualTest 'VisualParityTest' 3 'realScreenshots' @('-Dparity.realClients=true','-Dparity.visual=true','-Dparity.clients=fx,swing,web','-Dparity.visual.scenarios=s02-sample-table,s03-chart,s05-forms-plan,s08-alerts','-Dparity.visual.checkpoints=first')
        Invoke-ActualTest 'WebContextShotIT' 1 '' @('-Dparity.web.contextShot=true')
        Invoke-ActualTest 'WebReconnectBrowserIT' 1 'reconnectProbeHasFreshFrozenInputsAndStrictResult' @('-Dparity.web.reconnect=true')
        Invoke-ActualTest 'GateCoverageTest' 1 'actualArtifactsAreComplete' @('-Dparity.gateCoverage=true',"-Dparity.gateCoverage.root=$build/parity")
    } else {
        Invoke-ActualTest 'NativeStartupRescueTest' 1 'actualNativeCancelFailureAndSuccessPreserveOriginalPayload' @('-Dparity.e2e.nativeRescue=true',"-Dparity.native.directory=$build/native-rescue") 'ui-swing'
        Invoke-ActualTest 'NativeFxStartupRescueTest' 1 'actualNativeCancelFailureAndSuccessPreserveOriginalPayload' @('-Dparity.e2e.nativeRescue=true',"-Dparity.native.directory=$build/native-rescue") 'ui-fx'
        # Число кейсов выведено из текущих @MethodSource/@ValueSource, не из минимального одного теста.
        Invoke-ActualTest 'LaunchedClientReaperTest' 1 'cleanHandleReportAlsoSettlesProcessReaper'
        Invoke-ActualTest 'CrashRestoreE2ETest' 20 '' @('-Dparity.e2e=true')
        Invoke-ActualTest 'AlreadyRunningE2ETest' 2 '' @('-Dparity.e2e=true')
        Invoke-ActualTest 'UnsavedPlanRestoreE2ETest' 6 '' @('-Dparity.e2e=true')
        Invoke-ActualTest 'WebCrashRestoreE2ETest' 1 'originalDocumentReconnectsAndRestoresTypedWindows' @('-Dparity.e2e=true')
    }
} finally {
    Pop-Location
    $manifest = Join-Path $run 'fresh-evidence/evidence-manifest.json'
    if (Test-Path -LiteralPath $manifest) {
        $pairs = (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json).pairs
        $proof = [ordered]@{ manifestSha256 = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash;
            pairs = @($pairs | ForEach-Object { [ordered]@{ label = $_.label; scope = $_.scope;
                referenceSha256 = $_.reference.sha256; actualSha256 = $_.actual.sha256 } }) }
        $proof | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $publish 'fresh-evidence-hashes.json') -Encoding utf8
    }
    # Публикуются только явно разрешённые наблюдения, не CashMemory, профили и handshake с токенами.
    if (Test-Path -LiteralPath $build) {
        # SHA снимков сохраняются без загрузки исполняемых jar или браузерных профилей.
        $hashes = @(Get-ChildItem -LiteralPath $build -Recurse -File -Filter '*.jar' | ForEach-Object {
            [ordered]@{ path = [IO.Path]::GetRelativePath($build, $_.FullName); size = $_.Length;
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
        })
        ConvertTo-Json -InputObject $hashes -Depth 4 | Set-Content -LiteralPath (Join-Path $publish 'frozen-hashes.json') -Encoding utf8
        foreach ($file in Get-ChildItem -LiteralPath $build -Recurse -File) {
            $relative = [IO.Path]::GetRelativePath($build, $file.FullName)
            $safe = $file.Name -eq 'selftest.log' -or $file.Name -eq 'class-census.txt' -or
                $file.Name -eq 'report-preflight.txt' -or $file.Name -like '*.raw.json' -or
                $file.Extension -eq '.png' -or $file.Name -like 'report*.html'
            if ($safe -and $relative -notmatch '(?i)(profile|CashMemory|web-reconnect|bootstrap)') {
                $destination = Join-Path $publish $relative
                New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
                if ($file.Extension -eq '.png') {
                    Copy-Item -LiteralPath $file.FullName -Destination $destination
                } else {
                    Protect-GateText (Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8) |
                        Set-Content -LiteralPath $destination -Encoding utf8
                }
            }
        }
    }
}
