# Backend Module Responsibility Inventory

This document is the working inventory for the modular package refactor.
The refactor target is structure correctness, API compatibility, local-fast plan generation, and day-level route suggestion availability.

## Proposed Modules

Use these modules as the first refactor target:

- `planning`: trip draft generation, local-fast scheduling, LLM draft orchestration, copy polish, plan quality diagnostics.
- `catalog`: local POI catalogue loading, POI domain objects, place matching heuristics.
- `routing`: geocoding, route summaries, transport recommendation, day-level route suggestions, route prewarm.
- `weather`: weather forecast lookup and fallback policy.
- `planhistory`: persisted trip plans, trip days, stops, favorites, rename/delete/update operations.
- `user`: authentication, registration, login, JWT, refresh tokens, captcha challenge.
- `observability`: runtime metrics, daily API metrics, analysis endpoints.
- `infrastructure`: external API clients, cache configuration, web client configuration, Redis/Caffeine support, global exceptions.
- `common`: shared request/response objects and small cross-module value objects.

## Controllers

| Current file | Current responsibility | Target module | Notes |
| --- | --- | --- | --- |
| `Controller/PlanController.java` | Draft generation, day route suggestion, weather, raw AI generation, copy polish, history plan APIs, coordinate resolving | Split across `planning`, `routing`, `weather`, `planhistory` | Highest-risk controller. It should become several thin controllers. Keep existing URL paths during refactor. |
| `Controller/MapController.java` | Geocode, suggestions, unified route endpoint | `routing` | Mostly routing/geocoding API. |
| `Controller/AuthController.java` | Register, login, refresh, logout, reset password, current user | `user` | Move with auth request/response types. |
| `Controller/CacheAdminController.java` | Manual cache eviction endpoints | `infrastructure.cache` or `observability` | Admin/support endpoint, not business logic. |
| `Controller/AnalysisController.java` | Runtime and daily metrics API | `observability` | Keep independent from planning/routing logic. |

## Services

| Current file | Current responsibility | Target module | Notes |
| --- | --- | --- | --- |
| `service/PlanProcessorService.java` | Main draft pipeline, AI-first parsing/repair/validation, route-aware schedule normalization, local-fast mode selection, copy polish patching, coordinate resolving helpers | `planning.application` plus extracted services | Largest class. Do not move as-is long term; first move package, then extract validation, repair, schedule, coordinate and route helpers. |
| `service/LocalPlanGeneratorService.java` | Local-fast hotel/POI selection, area rotation, restaurant selection, schedule generation | `planning.localfast` | Core local-fast generator. |
| `service/LocalPlanQualityDiagnosticService.java` | Local-fast quality warnings | `planning.quality` | Keep close to local-fast output validation. |
| `service/LocalPlanQualityReport.java` | Local quality report DTO | `planning.quality` | Move with diagnostic service. |
| `service/PlanQualityMetricsService.java` | General plan feasibility score/issue calculation | `planning.quality` | Shared quality diagnostics. |
| `service/PlanQualityReport.java` | General quality report DTO | `planning.quality` | Move with quality service. |
| `service/TripAiService.java` | Qwen/LLM calls, raw plan generation, phased day generation, copy polish | `planning.ai` | Later can be hidden behind a `PlanningModel` interface. |
| `service/DaySkeletonService.java` | Day skeleton and pace-based stop count planning | `planning.scheduling` | Used by AI-first flow and potentially future planner. |
| `service/HotelVerificationService.java` | Hotel verification using Google Places | `catalog.verification` or `planning.validation` | Crosses planning and external place search. Prefer a catalog-facing verification service. |
| `service/RestaurantVerificationService.java` | Restaurant verification, replacement, dining repair | `catalog.verification` or `planning.validation` | Large class; likely split later into verification and meal repair. |
| `service/LocalPoiCatalogService.java` | Load local POI JSON catalog by city | `catalog.local` | Move with `LocalPoiCatalog` and `LocalPoiItem`. |
| `service/PlaceHeuristicService.java` | Place type/name/address heuristics | `catalog.heuristic` | Shared by planning and routing. |
| `service/MapService.java` | Geocoding, persisted place lookup, route calls, route summaries, suggestions | `routing.application` plus `catalog.geocoding` | Important mixed service. Split later into geocoding and route summary services. |
| `service/PlanPrewarmService.java` | Prewarm coordinates/routes for a persisted plan | `routing.prewarm` | It depends on persisted plan data but its behavior is routing/cache related. |
| `service/PlanService.java` | History plan list/detail/favorite/rename/delete/copy update | `planhistory.application` | Also contains AI plan cache key helper; that helper should move out later. |
| `service/AuthService.java` | User registration, login, password reset, refresh tokens | `user.application` | Move with user mappers/models. |
| `service/CaptchaChallengeService.java` | Simple captcha challenge state | `user.security` | Auth support. |
| `service/CacheSerive.java` | Cache eviction helpers | `infrastructure.cache` | Name typo can be fixed later, not during first move unless imports are touched anyway. |
| `service/RedisCacheShutdownCleaner.java` | Redis cleanup on shutdown | `infrastructure.cache` | Infrastructure. |
| `service/SingleFlightService.java` | Same-key in-flight request coalescing | `infrastructure.concurrency` | Shared anti-cache-breakdown helper. |
| `service/RuntimeMetricsService.java` | In-memory runtime counters | `observability` | Move with `DailyApiMetricsService`. |
| `service/DailyApiMetricsService.java` | Persist daily metrics | `observability` | Uses `ApiMetricDailyMapper`. |
| `service/PlanStageMetrics.java` | Plan generation stage timing value object | `planning.metrics` or `observability` | If only generation uses it, keep in planning. |

## External Clients and Configuration

| Current file | Current responsibility | Target module | Notes |
| --- | --- | --- | --- |
| `client/GooglePlacesClient.java` | Google Places text search/details | `infrastructure.external.google` | Business services should depend on an interface later. |
| `client/GoogleGeocodingClient.java` | Google geocoding | `infrastructure.external.google` | Used by routing/geocoding. |
| `client/AmapClient.java` | Geoapify/Amap style geocode/route client | `infrastructure.external.routing` | Naming can be clarified later. |
| `client/WeatherApiClient.java` | WeatherAPI forecast client | `weather.infrastructure` or `infrastructure.external.weather` | Keep weather policy separate from raw client. |
| `config/*Properties.java` | External API and app property records | `infrastructure.config` | Group by related module if useful. |
| `config/CacheConfig.java` | Cache definitions | `infrastructure.cache` | Shared infrastructure. |
| `config/CacheKeyConfig.java` | Cache key generation | `infrastructure.cache` | Shared infrastructure. |
| `config/WebClientConfig.java` | WebClient and executor beans | `infrastructure.http` | Shared infrastructure. |
| `config/SecurityConfig.java` | Spring Security setup | `user.security` | Move with auth/security. |
| `config/WebStaticConfig.java` | Static file routing | `infrastructure.web` | Frontend/static support. |
| `config/AppConfig.java` | General app beans | `infrastructure.config` | Review bean contents before moving. |

## Models, DTOs, Requests, Responses

| Current file/group | Current responsibility | Target module | Notes |
| --- | --- | --- | --- |
| `response/PlanDraftResponse.java` | Main generated plan contract | `planning.api.dto` or `common.plan` | Shared by planning, route suggestion, persistence update, and frontend. Move carefully. |
| `req/CreatePlanReq.java` | Plan generation request | `planning.api.dto` | Used by controller, processor, cache key. |
| `model/LocalPoiCatalog.java`, `model/LocalPoiItem.java` | Local POI catalog domain | `catalog.local` | Move with catalog loader. |
| `model/RouteChoice.java`, `ModeSummary.java`, `RouteRecommendationContext.java`, `RouteSegmentSuggestion.java`, `StopCoordinate.java` | Routing value objects | `routing.domain` | Used by route suggestion and map services. |
| `dto/RouteSummary.java` | Route summary DTO | `routing.dto` | There is also `MapService.RouteSummary`; consider unifying later. |
| `response/GeoResponse.java`, `response/GeoRouteResponse.java` | External geocode/route response models | `routing.infrastructure.dto` | Coupled to external route/geocode API response shape. |
| `dto/PlaceDto.java`, `dto/PlaceSuggestionDto.java`, `response/PlaceDetail.java`, `model/Place.java`, `model/PlaceAnchorOverride.java` | Place/geocode/persisted place objects | `catalog.domain` or `routing.geocoding` | Split between place catalog and geocoding persistence later. |
| `model/TripPlan.java`, `TripDay.java`, `TripDayStop.java`, `TripPlanSummary.java`, `TripDayView.java`, `TripStopView.java` | Persisted plan entities/views | `planhistory.domain` | Move with `TripPlanMapper`. |
| `model/AppUser.java`, `UserCredential.java`, `UserIdentifier.java`, `dto/UserRefreshToken.java` | User/auth persistence models | `user.domain` | `UserRefreshToken` is currently under dto but behaves like a model. |
| `req/LoginReq.java`, `RegisterReq.java`, `ResetPasswordReq.java`, `response/LoginResult.java` | Auth API contracts | `user.api.dto` | Move with auth controller. |
| `model/GooglePlacesTextSearchCache.java` | Google Places text search cache row | `infrastructure.external.google.cache` | Cache persistence model. |
| `model/ApiMetricDaily.java` | Daily metric persistence model | `observability.domain` | Move with metrics mapper/service. |

## Mappers

| Current file | Target module | Notes |
| --- | --- | --- |
| `mapper/TripPlanMapper.java` and XML | `planhistory.persistence` | Update MyBatis namespace/imports when moved. |
| `mapper/PlaceMapper.java` and XML | `catalog.persistence` or `routing.persistence` | Used for geocoding persistence. |
| `mapper/PlaceAnchorOverrideMapper.java` and XML | `catalog.persistence` | Place anchor overrides. |
| `mapper/GooglePlacesTextSearchCacheMapper.java` and XML | `infrastructure.external.google.cache` | External API cache persistence. |
| `mapper/AppUserMapper.java`, `UserCredentialMapper.java`, `UserIdentifierMapper.java`, `UserRefreshTokenMapper.java` and XML | `user.persistence` | Move as one group. |
| `mapper/ApiMetricDailyMapper.java` and XML | `observability.persistence` | Move with metrics. |

## Utilities and Exceptions

| Current file | Current responsibility | Target module | Notes |
| --- | --- | --- | --- |
| `util/TripPromptTemplate.java` | AI prompt construction | `planning.ai.prompt` | Planning-specific, not generic util. |
| `util/PlanningContext.java` | Request-derived planning policy/context | `planning.context` | Planning-specific. |
| `util/JwtUtil.java` | JWT issuing/verification | `user.security` | Move with auth. |
| `exception/ErrorCode.java`, `NavigatorException.java`, `GlobalExceptionHandler.java` | App-wide error handling | `common.exception` or `infrastructure.web.exception` | Shared across modules. |
| `security/JwtAuthFilter.java` | JWT authentication filter | `user.security` | Move with security config. |

## Resource Files

| Current file/group | Target module/concept | Notes |
| --- | --- | --- |
| `resources/local-poi/*.json` | `catalog.local` data | Keep path stable at first to avoid resource loading breakage. |
| `resources/mappers/*.xml` | Persistence mappers | Update namespaces only when Java mapper packages move. |
| `application*.yml/properties` | Infrastructure config | No move needed initially. |
| `resources/perf/*.json` | Test/mock AI raw drafts | `planning.ai` test/perf support | Can stay until tests are reorganized. |
| `resources/static/*` | Static frontend | Out of scope for backend package refactor. |

## First Refactor Order

1. Move low-risk API/domain DTO groups first: auth DTOs, route value objects, local POI records.
2. Move `user` module as a contained vertical slice: auth controller, auth service, JWT filter/util, security config, user mappers/models.
3. Move `catalog.local`: local POI records and catalog loader.
4. Move `planning.localfast` and `planning.quality`: local-fast generator and diagnostics.
5. Move `planhistory`: persisted plan service, entities, mapper.
6. Move `routing` APIs and services: map controller/service, route value objects, route prewarm.
7. Split `PlanController` into thin controllers while preserving existing paths.
8. Move or extract pieces from `PlanProcessorService` after the smaller modules compile.

## High-Risk Areas

- `PlanProcessorService.java` is too large to safely rewrite in one pass. Move/extract it incrementally.
- `PlanController.java` mixes planning, route suggestion, weather, and plan history endpoints. Split it only after dependent DTOs/services are moved.
- MyBatis mapper package moves require matching XML namespace/result type updates.
- Tests under `src/test/java/thesis/project/gu/service` will need package/import updates.
- `PlanDraftResponse` is a shared contract; moving it affects many modules at once.
- Existing uncommitted local files should not be mixed into the refactor commit.
