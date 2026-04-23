# Backend Optimization Notes

Context: Spring Boot AI travel planning system. Keep `PlanController` thin. Keep itinerary orchestration, validation, retry, and post-processing inside `PlanProcessorService` unless a focused service extraction is explicitly planned.

## Current Pipeline Strength

- AI raw generation is separated from backend post-processing.
- `/draft` and `/raw` use the same `PlanProcessorService.processDraft(...)` path.
- The backend now performs deterministic schedule normalization, meal verification, hotel verification, route-aware scheduling, pruning, validation, retry, and copy polish.
- The pipeline is suitable to describe in the thesis as a multi-stage backend validation and optimization layer on top of AI generation.

## High-Value Next Optimizations

1. Add selected style coverage validation.
   - If `market_shopping` is selected, final draft should contain at least one market/shopping-like anchor.
   - If `theme_park` is selected, final draft should contain a verified theme-park day.
   - If `nature` is selected, final draft should contain meaningful park/nature/beach/walk anchors.
   - If `culture` is selected, final draft should contain museum/gallery/heritage/cultural anchors.

2. Improve validation issue diagnostics.
   - Log issue code, day index, stop index, stop name, previous stop name, actual gap, allowed gap, and route duration where available.
   - This will make failures such as `day-2-stop-3-gap-too-large` easier to debug.

3. Improve fast/rush pace area consistency.
   - Fast pace should mean denser days, not unrealistic cross-city zigzags.
   - Add max distinct nearby areas per day.
   - Avoid patterns like remote -> city -> remote or St Kilda -> Brighton -> Dandenongs -> Prahran -> St Kilda.

4. Add route feasibility scoring.
   - Compute daily route feasibility using total transfer time, max single transfer, cross-area jumps, walking burden, and remote day-trip distance.
   - Expose a simple quality label such as `compact`, `moderate`, `long-distance`, or `risky`.

5. Add general copy safety sanitizer.
   - Theme-park copy already has safety cleanup.
   - Extend copy cleanup for unstable claims such as fixed schedules, exact shuttle/timed-entry claims, exact seating promises, freshest/best claims, payment assumptions, and specific opening-hour claims.

6. Improve meal alignment.
   - Keep ordinary lunch preferred at or after 11:30, with validation grace at 11:15.
   - Keep lunch close to previous/current area.
   - For market days, prefer lunch near or inside the market cluster where feasible.
   - For theme-park days, lunch must remain inside or near the theme-park cluster.

## Useful Features To Add

1. Plan quality score.
   - Route feasibility score.
   - Style match score.
   - Pace fit score.
   - Meal quality score.
   - Verification coverage score.

2. "Why this plan works" explanation.
   - Explain route clustering, style fulfillment, verified meals, route gap checks, and market/theme-park anchors.

3. Plan variants.
   - More relaxed.
   - More attractions.
   - Less walking.
   - More food-focused.
   - More public-transport friendly.

4. Manual lock/replace stop.
   - Lock a stop.
   - Replace lunch/dinner.
   - Remove a stop.
   - Regenerate around locked stops.

5. Route health panel.
   - Per-day route label.
   - Total transfer estimate.
   - Longest transfer estimate.
   - Recommended mode summary.

6. Export/share features.
   - Export PDF.
   - Copy itinerary.
   - Share link.
   - Calendar export.

## Redis / Reliability Notes

Do not present Redis as the primary persistence database for user plans. Keep long-term plan history, favorites, rename, and delete in the database through `PlanService`.

Use Redis as an acceleration and protection layer:

- Geocode cache.
- Google Places text-search cache.
- Route summary cache.
- Weather cache.
- Short-lived draft cache.
- Duplicate request guard.
- Rate-limit counters.

Redis RDB + AOF hybrid persistence can be useful for demo and experiment stability:

- `appendonly yes`
- `appendfsync everysec`
- `aof-use-rdb-preamble yes`
- `save 900 1`
- `save 300 10`
- `save 60 10000`

Suggested thesis wording:

Redis is used as a cache and backend protection layer rather than the source of truth. Hybrid RDB-AOF persistence can reduce cache and rate-limit state loss after Redis restarts during experiments or demo runs.

## API Protection Notes

Add rate limiting before deeper reliability work:

- Anonymous `/draft` and `/raw`: low per-IP limit.
- Logged-in `/draft` and `/raw`: per-user limit.
- `/route-suggestions`: higher per-user/IP limit.
- Weather/geocode endpoints: moderate per-user/IP limit.

Add external API resilience:

- AI provider timeout and fallback.
- Copy polish timeout and fallback.
- Google Places timeout and fallback.
- Route API timeout and fallback.
- Weather timeout and fallback.

Candidate library:

- Resilience4j for timeout, retry, circuit breaker, and bulkhead.
- Bucket4j with Redis for distributed rate limiting.

## Lower Priority For Now

- Full `AttractionVerificationService`.
- Large `PlanProcessorService` split before pipeline rules stabilize.
- Redis as primary persistence.
- Heavy microservice-style resilience architecture.

