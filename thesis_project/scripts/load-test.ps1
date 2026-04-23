param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Iterations = 10,
    [int[]]$ConcurrencyLevels = @(1, 3, 5),
    [int]$HeavyIterations = 1,
    [int[]]$HeavyConcurrencyLevels = @(1),
    [ValidateSet("mock", "real")]
    [string]$AiMode = "mock",
    [switch]$IncludePlanDraft = $true,
    [switch]$IncludeHeavyConcurrency = $false,
    [switch]$SkipCacheClear = $false
)

$ErrorActionPreference = "Stop"

function Get-BenchmarkScenarios {
    $scenarios = @(
        [pscustomobject]@{
            id = "melbourne_market"
            city = "Melbourne"
            geocodeAddress = "Queen Victoria Market"
            routeType = "transit"
            origin = "-37.820340,144.965530"
            destination = "-37.806916,144.958949"
            draft = @{
                city = "Melbourne"
                days = 2
                budget = 1200
                party = @{ adults = 2; kids = 0 }
                style = @("market_shopping", "culture")
                pace = "normal"
            }
            routeSuggestions = New-RouteSuggestionsFixture `
                -City "Melbourne" `
                -Country "Australia" `
                -OriginName "Queen Victoria Market" `
                -OriginLat -37.806916 `
                -OriginLng 144.958949 `
                -DestinationName "State Library Victoria" `
                -DestinationLat -37.809808 `
                -DestinationLng 144.965190
        },
        [pscustomobject]@{
            id = "sydney_harbour"
            city = "Sydney"
            geocodeAddress = "Taronga Zoo"
            routeType = "transit"
            origin = "-33.862384,151.203503"
            destination = "-33.843832,151.241375"
            draft = @{
                city = "Sydney"
                days = 3
                budget = 1800
                party = @{ adults = 2; kids = 0 }
                style = @("nature", "market_shopping")
                pace = "normal"
            }
            routeSuggestions = New-RouteSuggestionsFixture `
                -City "Sydney" `
                -Country "Australia" `
                -OriginName "Circular Quay" `
                -OriginLat -33.861111 `
                -OriginLng 151.212778 `
                -DestinationName "Taronga Zoo Sydney" `
                -DestinationLat -33.843832 `
                -DestinationLng 151.241375
        },
        [pscustomobject]@{
            id = "brisbane_culture"
            city = "Brisbane"
            geocodeAddress = "Gallery of Modern Art"
            routeType = "transit"
            origin = "-27.471233,153.028883"
            destination = "-27.470802,153.017115"
            draft = @{
                city = "Brisbane"
                days = 2
                budget = 1000
                party = @{ adults = 2; kids = 1 }
                style = @("culture", "nature")
                pace = "relaxed"
            }
            routeSuggestions = New-RouteSuggestionsFixture `
                -City "Brisbane" `
                -Country "Australia" `
                -OriginName "Gallery of Modern Art" `
                -OriginLat -27.470560 `
                -OriginLng 153.017133 `
                -DestinationName "Queensland Museum" `
                -DestinationLat -27.473109 `
                -DestinationLng 153.018189
        }
    )
    return $scenarios
}

function New-RouteSuggestionsFixture {
    param(
        [string]$City,
        [string]$Country,
        [string]$OriginName,
        [double]$OriginLat,
        [double]$OriginLng,
        [string]$DestinationName,
        [double]$DestinationLat,
        [double]$DestinationLng
    )

    return @{
        budget = 1000
        departureDate = "2026-05-01"
        draft = @{
            city = $City
            country = $Country
            days = 1
            currency = "AUD"
            party = @{ adults = 2; kids = 0 }
            pace = "normal"
            title = "$City route fixture"
            overview = "Static route-suggestions benchmark fixture."
            daysPlan = @(
                @{
                    dayIndex = 1
                    hotel = @{
                        name = "$City Test Hotel"
                        addressLine = "$City CBD"
                        city = $City
                        country = $Country
                        category = "hotel"
                        latitude = $OriginLat
                        longitude = $OriginLng
                    }
                    stops = @(
                        @{
                            name = $OriginName
                            addressLine = $OriginName
                            city = $City
                            country = $Country
                            category = "attraction"
                            stayMinutes = 60
                            timeSlot = "morning"
                            startTime = "09:00"
                            endTime = "10:00"
                            latitude = $OriginLat
                            longitude = $OriginLng
                        },
                        @{
                            name = $DestinationName
                            addressLine = $DestinationName
                            city = $City
                            country = $Country
                            category = "attraction"
                            stayMinutes = 60
                            timeSlot = "afternoon"
                            startTime = "11:00"
                            endTime = "12:00"
                            latitude = $DestinationLat
                            longitude = $DestinationLng
                        }
                    )
                    theme = "Route benchmark"
                    morningNote = "Static benchmark route."
                    afternoonNote = "Static benchmark route."
                    eveningNote = ""
                    note = "Used only for route-suggestions stress testing."
                }
            )
        }
    }
}

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

function New-RequestSummary {
    param(
        [object[]]$Group,
        [string]$Phase,
        [Nullable[double]]$DurationSeconds = $null,
        [string]$Scenario = $null,
        [string]$Name = $null
    )

    $latencies = @($Group | ForEach-Object { [double]$_.latency_ms })
    $requests = $Group.Count
    $success = @($Group | Where-Object success).Count
    $failures = @($Group | Where-Object { -not $_.success }).Count
    $duration = if ($DurationSeconds -and $DurationSeconds -gt 0) { [double]$DurationSeconds } else { [math]::Round((($latencies | Measure-Object -Sum).Sum / 1000), 4) }

    $row = [ordered]@{
        phase = $Phase
    }
    if ($Scenario) { $row["scenario"] = $Scenario }
    $row["name"] = $Name
    $row["requests"] = $requests
    $row["success"] = $success
    $row["failures"] = $failures
    $row["success_rate_pct"] = if ($requests -gt 0) { [math]::Round(($success / $requests) * 100, 2) } else { 0 }
    $row["avg_latency_ms"] = [math]::Round((($latencies | Measure-Object -Average).Average), 2)
    $row["min_latency_ms"] = [math]::Round((($latencies | Measure-Object -Minimum).Minimum), 2)
    $row["max_latency_ms"] = [math]::Round((($latencies | Measure-Object -Maximum).Maximum), 2)
    $row["p50_latency_ms"] = Get-Percentile -Values $latencies -Percentile 50
    $row["p95_latency_ms"] = Get-Percentile -Values $latencies -Percentile 95
    $row["p99_latency_ms"] = Get-Percentile -Values $latencies -Percentile 99
    $row["duration_seconds"] = [math]::Round($duration, 4)
    $row["throughput_rps"] = if ($duration -gt 0) { [math]::Round($requests / $duration, 4) } else { 0 }

    return [pscustomobject]$row
}

function Measure-Request {
    param(
        [string]$Name,
        [string]$ScenarioId,
        [string]$Method,
        [string]$Url,
        [string]$Body = $null,
        [string]$ContentType = "application/json"
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if ($Method -eq "GET") {
            Invoke-RestMethod -Uri $Url -Method Get | Out-Null
        } else {
            Invoke-RestMethod -Uri $Url -Method $Method -Body $Body -ContentType $ContentType | Out-Null
        }
        $sw.Stop()
        return [pscustomobject]@{
            name = $Name
            scenario = $ScenarioId
            success = $true
            latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
        }
    } catch {
        $sw.Stop()
        return [pscustomobject]@{
            name = $Name
            scenario = $ScenarioId
            success = $false
            latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
        }
    }
}

function Summarize-Requests {
    param(
        [object[]]$Requests,
        [string]$Phase
    )

    return $Requests |
        Group-Object scenario, name |
        ForEach-Object {
            $group = $_.Group
            New-RequestSummary -Group $group -Phase $Phase -Scenario $group[0].scenario -Name $group[0].name
        } |
        Sort-Object scenario, name
}

function Summarize-ByApi {
    param(
        [object[]]$Requests,
        [string]$Phase,
        [double]$DurationSeconds
    )

    return $Requests |
        Group-Object name |
        ForEach-Object {
            $group = $_.Group
            $row = New-RequestSummary -Group $group -Phase $Phase -DurationSeconds $DurationSeconds -Name $_.Name
            $row | Add-Member -NotePropertyName scenario_count -NotePropertyValue (($group | Select-Object -ExpandProperty scenario -Unique).Count) -PassThru
        } |
        Sort-Object name
}

function Invoke-ScenarioSet {
    param(
        [string]$Label,
        [int]$Iterations,
        [string]$BaseUrl,
        [bool]$IncludePlanDraft
    )

    $scenarios = Get-BenchmarkScenarios
    $requests = @()
    $phaseStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

    foreach ($scenario in $scenarios) {
        $draftBody = $scenario.draft | ConvertTo-Json -Depth 4
        $routeSuggestionsBody = $scenario.routeSuggestions | ConvertTo-Json -Depth 12
        $geocodeAddress = [System.Uri]::EscapeDataString($scenario.geocodeAddress)
        $city = [System.Uri]::EscapeDataString($scenario.city)

        for ($i = 1; $i -le $Iterations; $i++) {
            $requests += Measure-Request `
                -Name "geocode" `
                -ScenarioId $scenario.id `
                -Method "GET" `
                -Url "$BaseUrl/api/v1/map/geocode?address=$geocodeAddress&city=$city"

            $requests += Measure-Request `
                -Name "route" `
                -ScenarioId $scenario.id `
                -Method "GET" `
                -Url "$BaseUrl/api/v1/map/route?type=$($scenario.routeType)&origin=$($scenario.origin)&destination=$($scenario.destination)&city=$city"

            $requests += Measure-Request `
                -Name "route_suggestions" `
                -ScenarioId $scenario.id `
                -Method "POST" `
                -Url "$BaseUrl/api/v1/plans/route-suggestions" `
                -Body $routeSuggestionsBody

            if ($IncludePlanDraft) {
                $requests += Measure-Request `
                    -Name "plan_draft" `
                    -ScenarioId $scenario.id `
                    -Method "POST" `
                    -Url "$BaseUrl/api/v1/plans/draft" `
                    -Body $draftBody
            }
        }
    }
    $phaseStopwatch.Stop()
    $durationSeconds = [math]::Round($phaseStopwatch.Elapsed.TotalSeconds, 4)

    $byScenario = Summarize-Requests -Requests $requests -Phase $Label
    $byApi = Summarize-ByApi -Requests $requests -Phase $Label -DurationSeconds $durationSeconds

    return [pscustomobject]@{
        duration_seconds = $durationSeconds
        requests = $requests
        byScenario = $byScenario
        byApi = $byApi
    }
}

function Clear-BenchmarkCaches {
    param([string]$BaseUrl)
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/v1/cache/benchmark/all" -Method Delete | Out-Null
        Write-Host "Benchmark caches cleared."
    } catch {
        Write-Warning "Failed to clear benchmark caches automatically. Cold run may contain warm cache data."
    }
}

function Build-Comparison {
    param(
        [object[]]$ColdRows,
        [object[]]$WarmRows,
        [string[]]$Keys
    )

    $comparison = foreach ($coldRow in $ColdRows) {
        $warmRow = $WarmRows | Where-Object {
            $matched = $true
            foreach ($key in $Keys) {
                if ($_.($key) -ne $coldRow.($key)) {
                    $matched = $false
                    break
                }
            }
            $matched
        } | Select-Object -First 1

        if ($null -eq $warmRow) { continue }

        $improvement = 0
        if ($coldRow.avg_latency_ms -gt 0) {
            $improvement = [math]::Round((($coldRow.avg_latency_ms - $warmRow.avg_latency_ms) / $coldRow.avg_latency_ms) * 100, 2)
        }

        $row = [ordered]@{}
        foreach ($key in $Keys) {
            $row[$key] = $coldRow.($key)
        }
        $row["cold_avg_latency_ms"] = $coldRow.avg_latency_ms
        $row["warm_avg_latency_ms"] = $warmRow.avg_latency_ms
        $row["cold_p50_latency_ms"] = $coldRow.p50_latency_ms
        $row["warm_p50_latency_ms"] = $warmRow.p50_latency_ms
        $row["cold_p95_latency_ms"] = $coldRow.p95_latency_ms
        $row["warm_p95_latency_ms"] = $warmRow.p95_latency_ms
        $row["cold_p99_latency_ms"] = $coldRow.p99_latency_ms
        $row["warm_p99_latency_ms"] = $warmRow.p99_latency_ms
        $row["cold_throughput_rps"] = $coldRow.throughput_rps
        $row["warm_throughput_rps"] = $warmRow.throughput_rps
        $row["improvement_pct"] = $improvement
        $row["cold_failures"] = $coldRow.failures
        $row["warm_failures"] = $warmRow.failures
        [pscustomobject]$row
    }

    return $comparison
}

function New-LightEndpointRequests {
    param(
        [string]$BaseUrl,
        [object]$Scenario
    )

    $city = [System.Uri]::EscapeDataString($Scenario.city)
    $geocodeAddress = [System.Uri]::EscapeDataString($Scenario.geocodeAddress)
    $routeSuggestionsBody = $Scenario.routeSuggestions | ConvertTo-Json -Depth 12

    return @(
        [pscustomobject]@{
            name = "geocode"
            scenario = $Scenario.id
            method = "GET"
            url = "$BaseUrl/api/v1/map/geocode?address=$geocodeAddress&city=$city"
            body = $null
        },
        [pscustomobject]@{
            name = "route"
            scenario = $Scenario.id
            method = "GET"
            url = "$BaseUrl/api/v1/map/route?type=$($Scenario.routeType)&origin=$($Scenario.origin)&destination=$($Scenario.destination)&city=$city"
            body = $null
        },
        [pscustomobject]@{
            name = "route_suggestions"
            scenario = $Scenario.id
            method = "POST"
            url = "$BaseUrl/api/v1/plans/route-suggestions"
            body = $routeSuggestionsBody
        }
    )
}

function Measure-RequestJob {
    param([object]$Request)

    Start-Job -ArgumentList $Request -ScriptBlock {
        param($Request)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            if ($Request.method -eq "GET") {
                Invoke-RestMethod -Uri $Request.url -Method Get | Out-Null
            } else {
                Invoke-RestMethod -Uri $Request.url -Method $Request.method -Body $Request.body -ContentType "application/json" | Out-Null
            }
            $sw.Stop()
            [pscustomobject]@{
                name = $Request.name
                scenario = $Request.scenario
                success = $true
                latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            }
        } catch {
            $sw.Stop()
            [pscustomobject]@{
                name = $Request.name
                scenario = $Request.scenario
                success = $false
                latency_ms = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            }
        }
    }
}

function Invoke-ConcurrencyBenchmark {
    param(
        [string]$BaseUrl,
        [int[]]$ConcurrencyLevels,
        [int]$Iterations
    )

    $scenarios = Get-BenchmarkScenarios
    $rows = @()
    $requestsPerLevel = [Math]::Max(1, $Iterations)

    foreach ($level in $ConcurrencyLevels) {
        foreach ($endpointName in @("geocode", "route", "route_suggestions")) {
            $requestTemplates = @()
            foreach ($scenario in $scenarios) {
                $requestTemplates += (New-LightEndpointRequests -BaseUrl $BaseUrl -Scenario $scenario | Where-Object { $_.name -eq $endpointName })
            }

            $pending = for ($i = 0; $i -lt ($requestsPerLevel * $requestTemplates.Count); $i++) {
                $requestTemplates[$i % $requestTemplates.Count]
            }

            $results = @()
            $jobs = @()
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            foreach ($request in $pending) {
                while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $level) {
                    $done = Wait-Job -Job $jobs -Any -Timeout 1
                    if ($done) {
                        $results += Receive-Job -Job $done
                        Remove-Job -Job $done
                        $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
                    }
                }
                $jobs += Measure-RequestJob -Request $request
            }

            while ($jobs.Count -gt 0) {
                $done = Wait-Job -Job $jobs -Any
                $results += Receive-Job -Job $done
                Remove-Job -Job $done
                $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
            }
            $sw.Stop()

            $summary = New-RequestSummary `
                -Group $results `
                -Phase "concurrency" `
                -DurationSeconds ([math]::Round($sw.Elapsed.TotalSeconds, 4)) `
                -Name $endpointName
            $summary | Add-Member -NotePropertyName concurrency -NotePropertyValue $level
            $rows += $summary
        }
    }

    return $rows | Sort-Object name, concurrency
}

function New-HeavyEndpointRequests {
    param(
        [string]$BaseUrl,
        [object]$Scenario
    )

    $draftBody = $Scenario.draft | ConvertTo-Json -Depth 4

    return @(
        [pscustomobject]@{
            name = "plan_draft"
            scenario = $Scenario.id
            method = "POST"
            url = "$BaseUrl/api/v1/plans/draft"
            body = $draftBody
        },
        [pscustomobject]@{
            name = "plan_raw"
            scenario = $Scenario.id
            method = "POST"
            url = "$BaseUrl/api/v1/plans/raw"
            body = $draftBody
        }
    )
}

function Invoke-HeavyConcurrencyBenchmark {
    param(
        [string]$BaseUrl,
        [int[]]$ConcurrencyLevels,
        [int]$Iterations
    )

    $scenarios = Get-BenchmarkScenarios
    $rows = @()
    $requestsPerLevel = [Math]::Max(1, $Iterations)

    foreach ($level in $ConcurrencyLevels) {
        foreach ($endpointName in @("plan_draft", "plan_raw")) {
            $requestTemplates = @()
            foreach ($scenario in $scenarios) {
                $requestTemplates += (New-HeavyEndpointRequests -BaseUrl $BaseUrl -Scenario $scenario | Where-Object { $_.name -eq $endpointName })
            }

            $pending = for ($i = 0; $i -lt ($requestsPerLevel * $requestTemplates.Count); $i++) {
                $requestTemplates[$i % $requestTemplates.Count]
            }

            $results = @()
            $jobs = @()
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            foreach ($request in $pending) {
                while (($jobs | Where-Object { $_.State -eq "Running" }).Count -ge $level) {
                    $done = Wait-Job -Job $jobs -Any -Timeout 1
                    if ($done) {
                        $results += Receive-Job -Job $done
                        Remove-Job -Job $done
                        $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
                    }
                }
                $jobs += Measure-RequestJob -Request $request
            }

            while ($jobs.Count -gt 0) {
                $done = Wait-Job -Job $jobs -Any
                $results += Receive-Job -Job $done
                Remove-Job -Job $done
                $jobs = @($jobs | Where-Object { $_.Id -ne $done.Id })
            }
            $sw.Stop()

            $summary = New-RequestSummary `
                -Group $results `
                -Phase "heavy_concurrency" `
                -DurationSeconds ([math]::Round($sw.Elapsed.TotalSeconds, 4)) `
                -Name $endpointName
            $summary | Add-Member -NotePropertyName concurrency -NotePropertyValue $level
            $summary | Add-Member -NotePropertyName endpoint_class -NotePropertyValue "heavy_ai_orchestration"
            $rows += $summary
        }
    }

    return $rows | Sort-Object name, concurrency
}

$outputDir = Join-Path $PSScriptRoot "results"
if (-not (Test-Path $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

if (-not $SkipCacheClear) {
    Clear-BenchmarkCaches -BaseUrl $BaseUrl
}

$cold = Invoke-ScenarioSet -Label "cold" -Iterations $Iterations -BaseUrl $BaseUrl -IncludePlanDraft $IncludePlanDraft
$warm = Invoke-ScenarioSet -Label "warm" -Iterations $Iterations -BaseUrl $BaseUrl -IncludePlanDraft $IncludePlanDraft
$concurrencyBreakdown = Invoke-ConcurrencyBenchmark -BaseUrl $BaseUrl -ConcurrencyLevels $ConcurrencyLevels -Iterations $Iterations
$heavyConcurrencyBreakdown = @()
if ($IncludeHeavyConcurrency) {
    $heavyConcurrencyBreakdown = Invoke-HeavyConcurrencyBenchmark -BaseUrl $BaseUrl -ConcurrencyLevels $HeavyConcurrencyLevels -Iterations $HeavyIterations
}

$comparisonByScenario = Build-Comparison -ColdRows $cold.byScenario -WarmRows $warm.byScenario -Keys @("scenario", "name")
$comparisonByApi = Build-Comparison -ColdRows $cold.byApi -WarmRows $warm.byApi -Keys @("name")

Write-Host ""
Write-Host "Cold run by scenario"
$cold.byScenario | Format-Table -AutoSize

Write-Host ""
Write-Host "Warm run by scenario"
$warm.byScenario | Format-Table -AutoSize

Write-Host ""
Write-Host "Comparison by scenario"
$comparisonByScenario | Format-Table -AutoSize

Write-Host ""
Write-Host "Cold run by API"
$cold.byApi | Format-Table -AutoSize

Write-Host ""
Write-Host "Warm run by API"
$warm.byApi | Format-Table -AutoSize

Write-Host ""
Write-Host "Comparison by API"
$comparisonByApi | Format-Table -AutoSize

Write-Host ""
Write-Host "Concurrency breakdown"
$concurrencyBreakdown | Format-Table -AutoSize

if ($IncludeHeavyConcurrency) {
    Write-Host ""
    Write-Host "Heavy endpoint low-concurrency breakdown"
    $heavyConcurrencyBreakdown | Format-Table -AutoSize
} else {
    Write-Host ""
    Write-Host "Heavy endpoint low-concurrency breakdown skipped. Use -IncludeHeavyConcurrency to run /draft and /raw."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$testedEndpoints = @(
    "/api/v1/map/geocode",
    "/api/v1/map/route",
    "/api/v1/plans/route-suggestions"
)
if ($IncludePlanDraft) {
    $testedEndpoints += "/api/v1/plans/draft"
}
if ($IncludeHeavyConcurrency) {
    $testedEndpoints += "/api/v1/plans/raw"
}
$result = [pscustomobject]@{
    generated_at = (Get-Date).ToString("s")
    iterations = $Iterations
    metadata = @{
        base_url = $BaseUrl
        script = "scripts/load-test.ps1"
        ai_mode = $AiMode
        concurrency = ($ConcurrencyLevels | Measure-Object -Maximum).Maximum
        concurrency_levels = $ConcurrencyLevels
        heavy_concurrency_levels = $HeavyConcurrencyLevels
        duration_seconds = [math]::Round(($cold.duration_seconds + $warm.duration_seconds), 4)
        requests_per_scenario = $Iterations
        heavy_requests_per_scenario = $HeavyIterations
        warmup_policy = "Cold run clears benchmark caches unless -SkipCacheClear is set; warm run reuses available cache state."
        duration_policy = "Fixed request count per scenario; concurrency breakdown uses bounded parallel jobs. Heavy AI endpoints are measured only when -IncludeHeavyConcurrency is set."
        percentiles_reported = $true
        service_rate_reported = $true
        concurrency_breakdown_reported = $true
        heavy_concurrency_breakdown_reported = [bool]$IncludeHeavyConcurrency
        endpoints = $testedEndpoints
    }
    scenarios = (Get-BenchmarkScenarios)
    cold = @{
        duration_seconds = $cold.duration_seconds
        byScenario = $cold.byScenario
        byApi = $cold.byApi
    }
    warm = @{
        duration_seconds = $warm.duration_seconds
        byScenario = $warm.byScenario
        byApi = $warm.byApi
    }
    comparison = @{
        byScenario = $comparisonByScenario
        byApi = $comparisonByApi
    }
    concurrencyBreakdown = $concurrencyBreakdown
    heavyConcurrencyBreakdown = $heavyConcurrencyBreakdown
}

$outputPath = Join-Path $outputDir "load-test-$timestamp.json"
$result | ConvertTo-Json -Depth 8 | Set-Content -Path $outputPath

Write-Host ""
Write-Host "Saved summary to $outputPath"
