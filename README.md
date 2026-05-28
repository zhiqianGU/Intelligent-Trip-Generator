# Intelligent Trip Planning and Route Recommendation System

This repository contains a Spring Boot based travel planning system for generating multi-day trip itineraries, recommending daily routes, validating places, querying weather, and managing saved travel plans.

The current default generation path uses a `local-fast` planner: the backend loads a local POI catalogue, applies deterministic scheduling rules, returns the first itinerary quickly, and optionally runs asynchronous copy polishing afterwards. External map, weather, and AI services are used for enrichment, route summaries, validation, and text refinement instead of blocking the main itinerary generation flow.

## Features

- Multi-day itinerary generation with hotels, attractions, restaurants, daily notes, and timed stops.
- `local-fast` rule-based planner using local POI data, area rotation, travel pace, family constraints, meal windows, and distance-aware scheduling.
- Optional LLM generation and asynchronous copy polishing with Qwen models.
- Place validation and enrichment through Google Places, Google Geocoding, and Geoapify.
- Day-level route recommendation endpoint with lazy loading and cache support.
- Weather forecast integration with a fallback for unavailable forecast days.
- User authentication, JWT refresh flow, saved plans, favorites, and history management.
- Caffeine, Redis, and database-backed caching for repeated geocoding, place lookup, route, and plan data.
- Resilience4j circuit breakers and rate limiters for external API protection.
- Research scripts for latency, quality, and concurrency experiments.

## Tech Stack

- Java 21
- Spring Boot 3.5.5
- Spring MVC, Spring Security, Spring Validation, Spring Cache
- MyBatis + MySQL
- Redis + Spring Session
- Caffeine local cache
- Resilience4j circuit breaker and rate limiter
- DashScope Qwen SDK
- OkHttp
- Static HTML/CSS/JavaScript frontend served by Spring Boot
- JUnit 5 and Testcontainers for tests

## Repository Layout

```text
.
+-- README.md
+-- thesis_project/
    +-- pom.xml
    +-- scripts/
    |   +-- rq1-latency-test.ps1
    |   +-- rq3-localfast-concurrency-test.ps1
    +-- src/
        +-- main/java/thesis/project/gu/
        |   +-- Controller/
        |   +-- config/
        |   +-- mapper/
        |   +-- model/
        |   +-- req/
        |   +-- service/
        |   +-- util/
        +-- main/resources/
        |   +-- application.yml
        |   +-- local-poi/
        |   +-- mappers/
        |   +-- static/
        +-- test/
```

## Main Backend Flow

1. The frontend submits a draft request to `POST /api/v1/plans/draft`.
2. If `mainModel` is `local-fast`, the backend loads local POI data and generates the plan with deterministic rules and heuristics.
3. The first plan response is returned immediately. If deferred polishing is enabled, the response includes `copyPolishStatus`.
4. The frontend can call `POST /api/v1/plans/copy-polish` to polish text fields asynchronously without changing the itinerary structure.
5. Route suggestions are loaded per day through `POST /api/v1/plans/route-suggestions/day`, cached by day, and prefetched for the next day.

## Local POI Planner

The local planner currently relies on curated POI JSON files under:

```text
thesis_project/src/main/resources/local-poi/
```

The generator uses:

- hotel selection
- area rotation to avoid repetitive areas
- pace and family constraints to choose daily stop counts
- style matching for attractions
- restaurant selection near the current day's stops
- distance-based transfer-time estimates
- protected lunch and dinner windows
- safeguards against overloading long-distance half-day trips
- quality diagnostics for duplicate POIs, late meals, dense days, area jumps, and restaurant distance

## Key API Endpoints

| Endpoint | Method | Purpose |
| --- | --- | --- |
| `/api/v1/plans/draft` | POST | Generate a plan draft |
| `/api/v1/plans/copy-polish` | POST | Polish textual descriptions without changing plan structure |
| `/api/v1/plans/route-suggestions/day` | POST | Generate route suggestions for one day |
| `/api/v1/plans/weather` | POST | Query weather forecast |
| `/api/v1/plans/raw` | POST | Raw AI generation/debug endpoint |
| `/api/v1/plans/me` | GET | List current user's plans |
| `/api/v1/plans/me/favorites` | GET | List favorite plans |
| `/api/v1/plans/{planId}` | GET | Get saved plan detail |
| `/api/v1/map/geocode` | GET | Geocode a place |
| `/api/v1/map/route` | GET | Query route information |
| `/api/v1/analysis/metrics` | GET | Runtime metrics |
| `/auth/register` | POST | Register |
| `/auth/login` | POST | Login |
| `/auth/refresh` | POST | Refresh token |
| `/auth/logout` | POST | Logout |

## Prerequisites

- JDK 21
- Maven, or the included Maven wrapper
- MySQL 8.x
- Redis
- Optional external API keys:
  - `GOOGLE_MAPS_API_KEY`
  - `GOOGLE_GEOCODING_API_KEY`
  - `WEATHER_API_KEY`
  - `GEMINI_API_KEY`
  - Qwen/DashScope API key configured through application properties or environment-specific configuration

## Configuration

The main configuration file is:

```text
thesis_project/src/main/resources/application.yml
```

For local development, configure:

- MySQL connection: `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`
- Redis connection: `spring.data.redis.host`, `spring.data.redis.port`
- JWT secret: `JWT_SECRET`
- reset-password token: `RESET_PASSWORD_TOKEN`
- external API keys through environment variables

Do not commit real API keys or production secrets. Prefer environment variables or a local profile-specific configuration file ignored by Git.

## Database Setup

Create the local database:

```sql
CREATE DATABASE thesis_project DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Then apply the SQL files in:

```text
thesis_project/src/main/resources/
```

The project includes SQL files for core tables, Google Places text-search cache fields, metrics tables, and Google place metadata fields.

## Run Locally

From the project module directory:

```powershell
cd thesis_project
.\mvnw.cmd spring-boot:run
```

The application starts on:

```text
http://localhost:8080
```

Useful pages:

- Main travel planner: `http://localhost:8080/index.html`
- Analysis dashboard: `http://localhost:8080/analysis-dashboard.html`
- Map page: `http://localhost:8080/map.html`

## Run Tests

```powershell
cd thesis_project
.\mvnw.cmd test
```

Selected tests cover:

- local POI catalogue loading
- local plan generation
- quality diagnostics
- restaurant verification
- prompt template constraints
- plan processing behavior

## Experiment Scripts

The `scripts` directory contains PowerShell scripts used for thesis experiments.

RQ1 latency test:

```powershell
.\scripts\rq1-latency-test.ps1 `
  -BaseUrl "http://localhost:8080" `
  -Mode local-fast `
  -Days 5 `
  -Iterations 5
```

RQ3 local-fast concurrency test:

```powershell
.\scripts\rq3-localfast-concurrency-test.ps1 `
  -BaseUrl "http://localhost:8080" `
  -ConcurrencyLevels 1,10,20,50
```

Generated experiment results are written to `scripts/results/` and should normally stay out of source control.

## Notes

- `local-fast` is the recommended mode for demos and latency experiments.
- LLM-based generation remains available for comparison and text refinement, but it is slower and depends on provider quota and network stability.
- Route suggestions are intentionally loaded per day to reduce initial page latency and avoid unnecessary route API calls.
- Weather forecasts may be unavailable for dates beyond the provider's forecast range; the system falls back to a default sunny display for missing days.
