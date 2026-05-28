param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$City = "Brisbane",
    [int[]]$Days = @(5, 10, 15, 20),
    [string[]]$Paces = @("normal"),
    [int]$Iterations = 5,
    [int]$Adults = 2,
    [int]$Kids = 0,
    [int]$Budget = 2000,
    [string[]]$Style = @("culture", "nature"),
    [ValidateSet("both", "local-fast", "llm-sync")]
    [string]$Mode = "both",
    [string]$LlmModel = "qwen-max",
    [string]$DepartureDate = "2026-06-01",
    [int]$TimeoutSec = 240,
    [switch]$SkipWarmup
)

$ErrorActionPreference = "Stop"

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    if ($sorted.Count -eq 1) { return [math]::Round($sorted[0], 2) }

    $rank = ($Percentile / 100) * ($sorted.Count - 1)
    $lower = [math]::Floor($rank)
    $upper = [math]::Ceiling($rank)
    if ($lower -eq $upper) { return [math]::Round($sorted[$lower], 2) }

    $weight = $rank - $lower
    return [math]::Round(($sorted[$lower] * (1 - $weight)) + ($sorted[$upper] * $weight), 2)
}

function New-DraftBody {
    param(
        [string]$MainModel,
        [int]$TripDays,
        [string]$Pace
    )

    return @{
        city = $City
        days = $TripDays
        budget = $Budget
        party = @{
            adults = $Adults
            kids = $Kids
        }
        style = $Style
        pace = $Pace
        mainModel = $MainModel
        departureDate = $DepartureDate
    }
}

function Measure-DraftRequest {
    param(
        [string]$ExperimentMode,
        [int]$TripDays,
        [string]$Pace,
        [int]$Run,
        [bool]$Warmup = $false
    )

    $mainModel = if ($ExperimentMode -eq "local-fast") { "local-fast" } else { $LlmModel }
    $body = New-DraftBody -MainModel $mainModel -TripDays $TripDays -Pace $Pace
    $jsonBody = $body | ConvertTo-Json -Depth 5
    $headers = @{}
    if ($ExperimentMode -eq "local-fast") {
        $headers["X-Defer-Copy-Polish"] = "true"
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $statusCode = $null
    $errorMessage = $null

    try {
        $response = Invoke-WebRequest `
            -Uri "$BaseUrl/api/v1/plans/draft" `
            -Method Post `
            -Body $jsonBody `
            -ContentType "application/json" `
            -Headers $headers `
            -UseBasicParsing `
            -TimeoutSec $TimeoutSec

        $sw.Stop()
        $statusCode = [int]$response.StatusCode
        $parsed = $response.Content | ConvertFrom-Json
        $actualDays = if ($parsed.daysPlan) { @($parsed.daysPlan).Count } else { $null }
        $stopCount = if ($parsed.daysPlan) {
            (@($parsed.daysPlan) | ForEach-Object { if ($_.stops) { @($_.stops).Count } else { 0 } } | Measure-Object -Sum).Sum
        } else {
            $null
        }

        return [pscustomobject]@{
            mode = $ExperimentMode
            main_model = $mainModel
            city = $City
            days = $TripDays
            pace = $Pace
            adults = $Adults
            kids = $Kids
            run = $Run
            warmup = $Warmup
            success = $true
            timeout = $false
            status_code = $statusCode
            latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            actual_days = $actualDays
            stop_count = $stopCount
            copy_polish_status = $parsed.copyPolishStatus
            error = $null
        }
    } catch {
        $sw.Stop()
        $errorMessage = $_.Exception.Message
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        $isTimeout = $errorMessage -match "timed out|timeout|operation has timed out|aborted"
        if ($sw.Elapsed.TotalSeconds -ge ($TimeoutSec - 1)) {
            $isTimeout = $true
        }

        return [pscustomobject]@{
            mode = $ExperimentMode
            main_model = $mainModel
            city = $City
            days = $TripDays
            pace = $Pace
            adults = $Adults
            kids = $Kids
            run = $Run
            warmup = $Warmup
            success = $false
            timeout = $isTimeout
            status_code = $statusCode
            latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            actual_days = $null
            stop_count = $null
            copy_polish_status = $null
            error = $errorMessage
        }
    }
}

function New-SummaryRows {
    param([object[]]$Rows)

    return $Rows |
        Where-Object { -not $_.warmup } |
        Group-Object mode, days, pace, kids |
        ForEach-Object {
            $group = @($_.Group)
            $successRows = @($group | Where-Object success)
            $successLatencies = @($successRows | ForEach-Object { [double]$_.latency_ms })
            $allLatencies = @($group | ForEach-Object { [double]$_.latency_ms })
            $requests = $group.Count
            $success = $successRows.Count
            $timeouts = @($group | Where-Object timeout).Count

            [pscustomobject]@{
                mode = $group[0].mode
                main_model = $group[0].main_model
                city = $group[0].city
                days = $group[0].days
                pace = $group[0].pace
                kids = $group[0].kids
                requests = $requests
                success = $success
                failures = $requests - $success
                timeout_count = $timeouts
                success_rate_pct = if ($requests -gt 0) { [math]::Round(($success / $requests) * 100, 2) } else { 0 }
                avg_latency_ms = if ($success -gt 0) { [math]::Round((($successLatencies | Measure-Object -Average).Average), 2) } else { $null }
                min_latency_ms = if ($success -gt 0) { [math]::Round((($successLatencies | Measure-Object -Minimum).Minimum), 2) } else { $null }
                max_latency_ms = if ($success -gt 0) { [math]::Round((($successLatencies | Measure-Object -Maximum).Maximum), 2) } else { $null }
                p50_latency_ms = if ($success -gt 0) { Get-Percentile -Values $successLatencies -Percentile 50 } else { $null }
                p95_latency_ms = if ($success -gt 0) { Get-Percentile -Values $successLatencies -Percentile 95 } else { $null }
                p99_latency_ms = if ($success -gt 0) { Get-Percentile -Values $successLatencies -Percentile 99 } else { $null }
                avg_all_attempt_latency_ms = [math]::Round((($allLatencies | Measure-Object -Average).Average), 2)
            }
        } |
        Sort-Object mode, days, pace, kids
}

$modesToRun = if ($Mode -eq "both") { @("local-fast", "llm-sync") } else { @($Mode) }
$outputDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

$allRows = @()

foreach ($experimentMode in $modesToRun) {
    foreach ($pace in $Paces) {
        foreach ($tripDays in $Days) {
            if (-not $SkipWarmup) {
                Write-Host "Warmup: mode=$experimentMode days=$tripDays pace=$pace"
                $allRows += Measure-DraftRequest -ExperimentMode $experimentMode -TripDays $tripDays -Pace $pace -Run 0 -Warmup $true
            }

            for ($run = 1; $run -le $Iterations; $run++) {
                Write-Host "RQ1 run: mode=$experimentMode days=$tripDays pace=$pace run=$run/$Iterations"
                $allRows += Measure-DraftRequest -ExperimentMode $experimentMode -TripDays $tripDays -Pace $pace -Run $run
            }
        }
    }
}

$summaryRows = @(New-SummaryRows -Rows $allRows)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$rawCsvPath = Join-Path $outputDir "rq1-latency-raw-$timestamp.csv"
$summaryCsvPath = Join-Path $outputDir "rq1-latency-summary-$timestamp.csv"
$jsonPath = Join-Path $outputDir "rq1-latency-$timestamp.json"

$allRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $rawCsvPath
$summaryRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $summaryCsvPath

$result = [pscustomobject]@{
    generated_at = (Get-Date).ToString("s")
    metadata = @{
        script = "scripts/rq1-latency-test.ps1"
        base_url = $BaseUrl
        city = $City
        days = $Days
        paces = $Paces
        iterations = $Iterations
        adults = $Adults
        kids = $Kids
        budget = $Budget
        style = $Style
        mode = $Mode
        llm_model = $LlmModel
        departure_date = $DepartureDate
        timeout_seconds = $TimeoutSec
        warmup_enabled = -not [bool]$SkipWarmup
        endpoint = "/api/v1/plans/draft"
        local_fast_policy = "mainModel=local-fast and X-Defer-Copy-Polish=true"
        llm_sync_policy = "mainModel set to LlmModel and no defer header"
        latency_summary_policy = "Latency percentiles are calculated from successful requests only. avg_all_attempt_latency_ms includes failed attempts."
    }
    summary = $summaryRows
    raw = $allRows
}

$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $jsonPath

Write-Host ""
Write-Host "RQ1 latency summary"
$summaryRows | Format-Table -AutoSize
Write-Host ""
Write-Host "Saved raw CSV to $rawCsvPath"
Write-Host "Saved summary CSV to $summaryCsvPath"
Write-Host "Saved JSON to $jsonPath"
