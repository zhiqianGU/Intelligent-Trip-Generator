param(
    [string]$BaseUrl = "http://localhost:8080",
    [int[]]$ConcurrencyLevels = @(1, 5, 10, 20),
    [int]$Iterations = 3,
    [int]$Days = 5,
    [int]$Adults = 2,
    [int]$Kids = 0,
    [int]$Budget = 2000,
    [string]$DepartureDate = "2026-06-01",
    [int]$TimeoutSec = 120
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

function Get-Rq3Scenarios {
    $styles = @(
        [pscustomobject]@{ id = "museum"; style = @("culture"); keywords = @("museum", "gallery", "art", "heritage", "cultural", "history") },
        [pscustomobject]@{ id = "nature"; style = @("nature"); keywords = @("park", "garden", "nature", "river", "lookout", "mount", "botanic", "wildlife") },
        [pscustomobject]@{ id = "shopping"; style = @("market_shopping"); keywords = @("market", "shopping", "mall", "queen street", "emporium", "boutique") },
        [pscustomobject]@{ id = "theme_park"; style = @("theme_park"); keywords = @("theme", "park", "zoo", "wildlife", "adventure", "ride") }
    )
    $paces = @(
        [pscustomobject]@{ id = "relaxed"; requestPace = "relaxed"; expectedNonMeal = 2 },
        [pscustomobject]@{ id = "normal"; requestPace = "normal"; expectedNonMeal = 3 },
        [pscustomobject]@{ id = "rush"; requestPace = "rush"; expectedNonMeal = 4 }
    )

    $scenarios = @()
    foreach ($style in $styles) {
        foreach ($pace in $paces) {
            $scenarios += [pscustomobject]@{
                id = "$($style.id)_$($pace.id)"
                style_id = $style.id
                style = $style.style
                keywords = $style.keywords
                pace = $pace.requestPace
                expected_non_meal = $pace.expectedNonMeal
            }
        }
    }
    return $scenarios
}

function New-RequestTemplate {
    param(
        [object]$Scenario,
        [int]$Concurrency,
        [int]$Run
    )

    $body = @{
        city = "Brisbane"
        days = $Days
        budget = $Budget
        party = @{
            adults = $Adults
            kids = $Kids
        }
        style = $Scenario.style
        pace = $Scenario.pace
        mainModel = "local-fast"
        departureDate = $DepartureDate
    } | ConvertTo-Json -Depth 5

    return [pscustomobject]@{
        base_url = $BaseUrl
        scenario = $Scenario.id
        style_id = $Scenario.style_id
        keywords = $Scenario.keywords
        pace = $Scenario.pace
        expected_non_meal = $Scenario.expected_non_meal
        concurrency = $Concurrency
        run = $Run
        body = $body
        timeout_sec = $TimeoutSec
    }
}

function Start-Rq3RequestJob {
    param([object]$Request)

    Start-Job -ArgumentList $Request -ScriptBlock {
        param($Request)

        function Normalize-Text {
            param([object]$Value)
            if ($null -eq $Value) { return "" }
            return ([string]$Value).ToLowerInvariant()
        }

        function Is-Meal {
            param([object]$Stop)
            $mealType = Normalize-Text $Stop.mealType
            $timeSlot = Normalize-Text $Stop.timeSlot
            return $mealType -eq "lunch" -or $mealType -eq "dinner" -or $timeSlot -eq "lunch" -or $timeSlot -eq "dinner"
        }

        function Stop-Key {
            param([object]$Stop)
            return ((Normalize-Text $Stop.name) + "|" + (Normalize-Text $Stop.addressLine))
        }

        function Stop-Text {
            param([object]$Stop, [object]$Day)
            return @(
                $Stop.name,
                $Stop.category,
                $Stop.reason,
                $Stop.tip,
                $Stop.preferredArea,
                $Day.theme,
                $Day.note
            ) -join " "
        }

        function Analyze-Draft {
            param([object]$Draft, [object]$Request)

            $warnings = New-Object System.Collections.Generic.List[string]
            $paceDayMatches = 0
            $dayCount = 0
            $styleMatched = $false
            $seen = @{}

            if ($null -eq $Draft -or $null -eq $Draft.daysPlan) {
                return [pscustomobject]@{
                    actual_days = $null
                    stop_count = $null
                    avg_non_meal_per_day = $null
                    pace_compliant = $false
                    style_matched = $false
                    client_warning_count = 1
                    client_warnings = "draft-missing"
                    copy_polish_status = $null
                }
            }

            $days = @($Draft.daysPlan)
            $stopCount = 0
            $nonMealTotal = 0
            foreach ($day in $days) {
                $dayCount++
                $stops = @($day.stops)
                $stopCount += $stops.Count
                $nonMeal = @($stops | Where-Object { -not (Is-Meal $_) })
                $nonMealTotal += $nonMeal.Count
                if ($nonMeal.Count -eq [int]$Request.expected_non_meal) {
                    $paceDayMatches++
                } else {
                    $warnings.Add("pace-mismatch-day-$($day.dayIndex)-nonmeal-$($nonMeal.Count)")
                }

                $lunchCount = @($stops | Where-Object { (Normalize-Text $_.mealType) -eq "lunch" -or (Normalize-Text $_.timeSlot) -eq "lunch" }).Count
                $dinnerCount = @($stops | Where-Object { (Normalize-Text $_.mealType) -eq "dinner" -or (Normalize-Text $_.timeSlot) -eq "dinner" }).Count
                if ($lunchCount -ne 1) { $warnings.Add("lunch-count-day-$($day.dayIndex)-$lunchCount") }
                if ($dinnerCount -ne 1) { $warnings.Add("dinner-count-day-$($day.dayIndex)-$dinnerCount") }

                foreach ($stop in $nonMeal) {
                    $key = Stop-Key $stop
                    if ($key.Trim() -ne "|" -and $seen.ContainsKey($key)) {
                        $warnings.Add("duplicate-poi-day-$($day.dayIndex)")
                    }
                    $seen[$key] = $true

                    $text = Normalize-Text (Stop-Text $stop $day)
                    foreach ($keyword in @($Request.keywords)) {
                        if ($text.Contains((Normalize-Text $keyword))) {
                            $styleMatched = $true
                        }
                    }
                }
            }

            if (-not $styleMatched) {
                $warnings.Add("style-not-matched-$($Request.style_id)")
            }

            $avgNonMeal = if ($dayCount -gt 0) { [math]::Round($nonMealTotal / $dayCount, 2) } else { $null }
            return [pscustomobject]@{
                actual_days = $days.Count
                stop_count = $stopCount
                avg_non_meal_per_day = $avgNonMeal
                pace_compliant = ($dayCount -gt 0 -and $paceDayMatches -eq $dayCount)
                style_matched = $styleMatched
                client_warning_count = $warnings.Count
                client_warnings = ($warnings -join ";")
                copy_polish_status = $Draft.copyPolishStatus
            }
        }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-WebRequest `
                -Uri "$($Request.base_url)/api/v1/plans/draft" `
                -Method Post `
                -Body $Request.body `
                -ContentType "application/json" `
                -Headers @{ "X-Defer-Copy-Polish" = "true" } `
                -UseBasicParsing `
                -TimeoutSec $Request.timeout_sec
            $sw.Stop()
            $draft = $response.Content | ConvertFrom-Json
            $analysis = Analyze-Draft -Draft $draft -Request $Request
            [pscustomobject]@{
                scenario = $Request.scenario
                style_id = $Request.style_id
                pace = $Request.pace
                expected_non_meal = $Request.expected_non_meal
                concurrency = $Request.concurrency
                run = $Request.run
                success = $true
                timeout = $false
                status_code = [int]$response.StatusCode
                latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
                actual_days = $analysis.actual_days
                stop_count = $analysis.stop_count
                avg_non_meal_per_day = $analysis.avg_non_meal_per_day
                pace_compliant = $analysis.pace_compliant
                style_matched = $analysis.style_matched
                client_warning_count = $analysis.client_warning_count
                client_warnings = $analysis.client_warnings
                copy_polish_status = $analysis.copy_polish_status
                error = $null
            }
        } catch {
            $sw.Stop()
            $message = $_.Exception.Message
            $statusCode = $null
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            $isTimeout = $message -match "timed out|timeout|operation has timed out|aborted"
            if ($sw.Elapsed.TotalSeconds -ge ($Request.timeout_sec - 1)) {
                $isTimeout = $true
            }
            [pscustomobject]@{
                scenario = $Request.scenario
                style_id = $Request.style_id
                pace = $Request.pace
                expected_non_meal = $Request.expected_non_meal
                concurrency = $Request.concurrency
                run = $Request.run
                success = $false
                timeout = $isTimeout
                status_code = $statusCode
                latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
                actual_days = $null
                stop_count = $null
                avg_non_meal_per_day = $null
                pace_compliant = $false
                style_matched = $false
                client_warning_count = 1
                client_warnings = "request-failed"
                copy_polish_status = $null
                error = $message
            }
        }
    }
}

function Invoke-BoundedRequests {
    param(
        [object[]]$Requests,
        [int]$Concurrency
    )

    $results = @()
    $jobs = @()
    foreach ($request in $Requests) {
        while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $Concurrency) {
            $done = Wait-Job -Job $jobs -Any -Timeout 1
            if ($done) {
                $results += Receive-Job -Job $done
                Remove-Job -Job $done
                $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
            }
        }
        $jobs += Start-Rq3RequestJob -Request $request
    }

    while ($jobs.Count -gt 0) {
        $done = Wait-Job -Job $jobs -Any
        $results += Receive-Job -Job $done
        Remove-Job -Job $done
        $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
    }
    return $results
}

function New-SummaryRows {
    param([object[]]$Rows)

    return $Rows |
        Group-Object concurrency |
        ForEach-Object {
            $group = @($_.Group)
            $latencies = @($group | ForEach-Object { [double]$_.latency_ms })
            $successRows = @($group | Where-Object success)
            $requests = $group.Count
            $success = $successRows.Count
            [pscustomobject]@{
                concurrency = [int]$group[0].concurrency
                requests = $requests
                success = $success
                failures = $requests - $success
                timeout_count = @($group | Where-Object timeout).Count
                success_rate_pct = if ($requests -gt 0) { [math]::Round(($success / $requests) * 100, 2) } else { 0 }
                avg_latency_ms = [math]::Round((($latencies | Measure-Object -Average).Average), 2)
                p50_latency_ms = Get-Percentile -Values $latencies -Percentile 50
                p95_latency_ms = Get-Percentile -Values $latencies -Percentile 95
                p99_latency_ms = Get-Percentile -Values $latencies -Percentile 99
                pace_compliance_rate_pct = if ($success -gt 0) { [math]::Round((@($successRows | Where-Object pace_compliant).Count / $success) * 100, 2) } else { 0 }
                style_match_rate_pct = if ($success -gt 0) { [math]::Round((@($successRows | Where-Object style_matched).Count / $success) * 100, 2) } else { 0 }
                avg_client_warnings = if ($success -gt 0) { [math]::Round((($successRows | ForEach-Object { [double]$_.client_warning_count } | Measure-Object -Average).Average), 2) } else { $null }
            }
        } |
        Sort-Object concurrency
}

function New-SummaryByScenarioRows {
    param([object[]]$Rows)

    return $Rows |
        Group-Object concurrency, scenario |
        ForEach-Object {
            $group = @($_.Group)
            $latencies = @($group | ForEach-Object { [double]$_.latency_ms })
            $successRows = @($group | Where-Object success)
            $requests = $group.Count
            $success = $successRows.Count
            [pscustomobject]@{
                concurrency = [int]$group[0].concurrency
                scenario = $group[0].scenario
                style_id = $group[0].style_id
                pace = $group[0].pace
                requests = $requests
                success_rate_pct = if ($requests -gt 0) { [math]::Round(($success / $requests) * 100, 2) } else { 0 }
                avg_latency_ms = [math]::Round((($latencies | Measure-Object -Average).Average), 2)
                p95_latency_ms = Get-Percentile -Values $latencies -Percentile 95
                pace_compliance_rate_pct = if ($success -gt 0) { [math]::Round((@($successRows | Where-Object pace_compliant).Count / $success) * 100, 2) } else { 0 }
                style_match_rate_pct = if ($success -gt 0) { [math]::Round((@($successRows | Where-Object style_matched).Count / $success) * 100, 2) } else { 0 }
                avg_client_warnings = if ($success -gt 0) { [math]::Round((($successRows | ForEach-Object { [double]$_.client_warning_count } | Measure-Object -Average).Average), 2) } else { $null }
            }
        } |
        Sort-Object concurrency, scenario
}

$outputDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

$scenarios = Get-Rq3Scenarios
$allRows = @()

foreach ($level in $ConcurrencyLevels) {
    Write-Host "RQ3 concurrency level $level"
    $requests = @()
    for ($run = 1; $run -le $Iterations; $run++) {
        foreach ($scenario in $scenarios) {
            $requests += New-RequestTemplate -Scenario $scenario -Concurrency $level -Run $run
        }
    }
    $allRows += Invoke-BoundedRequests -Requests $requests -Concurrency $level
}

$summaryRows = @(New-SummaryRows -Rows $allRows)
$summaryByScenarioRows = @(New-SummaryByScenarioRows -Rows $allRows)
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$rawCsvPath = Join-Path $outputDir "rq3-localfast-concurrency-raw-$timestamp.csv"
$summaryCsvPath = Join-Path $outputDir "rq3-localfast-concurrency-summary-$timestamp.csv"
$scenarioCsvPath = Join-Path $outputDir "rq3-localfast-concurrency-by-scenario-$timestamp.csv"
$jsonPath = Join-Path $outputDir "rq3-localfast-concurrency-$timestamp.json"

$allRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $rawCsvPath
$summaryRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $summaryCsvPath
$summaryByScenarioRows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $scenarioCsvPath

$result = [pscustomobject]@{
    generated_at = (Get-Date).ToString("s")
    metadata = @{
        script = "scripts/rq3-localfast-concurrency-test.ps1"
        base_url = $BaseUrl
        city = "Brisbane"
        days = $Days
        iterations = $Iterations
        concurrency_levels = $ConcurrencyLevels
        adults = $Adults
        kids = $Kids
        styles = @("culture/museum", "nature", "market_shopping", "theme_park")
        paces = @("relaxed", "normal", "rush")
        endpoint = "/api/v1/plans/draft"
        policy = "local-fast with X-Defer-Copy-Polish=true"
        comparison = "A=low concurrency local-fast, B=high concurrency local-fast stress condition"
    }
    summary = $summaryRows
    summary_by_scenario = $summaryByScenarioRows
    raw = $allRows
}

$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path $jsonPath

Write-Host ""
Write-Host "RQ3 summary"
$summaryRows | Format-Table -AutoSize
Write-Host ""
Write-Host "Saved raw CSV to $rawCsvPath"
Write-Host "Saved summary CSV to $summaryCsvPath"
Write-Host "Saved scenario CSV to $scenarioCsvPath"
Write-Host "Saved JSON to $jsonPath"
