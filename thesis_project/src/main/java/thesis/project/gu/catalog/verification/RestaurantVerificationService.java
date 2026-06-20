package thesis.project.gu.catalog.verification;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RestaurantVerificationService {
    private static final Pattern POSTCODE_PATTERN = Pattern.compile("\\b\\d{4,5}\\b");
    private static final int DAY_MEAL_SEARCH_MAX_CONCURRENCY = 4;
    private static final int GOOGLE_PLACES_SEARCH_BULKHEAD_CONCURRENCY = 6;
    private static final Duration GOOGLE_PLACES_SEARCH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Cache<String, List<GooglePlacesClient.PlaceCandidate>> GOOGLE_PLACES_SEARCH_L1_CACHE = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterWrite(GOOGLE_PLACES_SEARCH_CACHE_TTL)
            .build();
    private static final Semaphore GOOGLE_PLACES_SEARCH_BULKHEAD = new Semaphore(GOOGLE_PLACES_SEARCH_BULKHEAD_CONCURRENCY, true);
    private final GooglePlacesClient googlePlacesClient;

    public RestaurantVerificationService(GooglePlacesClient googlePlacesClient) {
        this.googlePlacesClient = googlePlacesClient;
    }

    public VerificationResult verifyAndNormalize(PlanDraftResponse draft) {
        return verifyAndNormalizeSelective(draft, null);
    }

    public VerificationResult verifyAndNormalizeSelective(
            PlanDraftResponse draft,
            Map<Integer, java.util.Set<Integer>> targetStopIndexesByDay
    ) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || !googlePlacesClient.isEnabled()) {
            return new VerificationResult(draft, List.of());
        }

        List<String> issues = new ArrayList<>();
        List<PlanDraftResponse.DayPlan> normalizedDays = new ArrayList<>();
        List<String> usedStrictMealVenues = new ArrayList<>();

        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            List<PlanDraftResponse.Place> normalizedStops = new ArrayList<>();
            List<PlanDraftResponse.Place> stops = day.stops() == null ? List.of() : day.stops();
            String fallbackArea = deriveArea(day.hotel(), draft.city());
            PlanDraftResponse.Place previousStop = null;
            List<String> dayStrictMealVenues = new ArrayList<>();
            ExecutorService daySearchExecutor = newDayMealSearchExecutor(stops);
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures = new ConcurrentHashMap<>();
            try {
                prewarmDayFoodSearches(stops, draft.city(), fallbackArea, day.dayIndex(), targetStopIndexesByDay, daySearchFutures, daySearchExecutor);
                for (int i = 0; i < stops.size(); i++) {
                    PlanDraftResponse.Place stop = stops.get(i);
                    if (isThemeParkInternalMeal(stop, stops)) {
                        PlanDraftResponse.Place themeParkStop = firstThemeParkStop(stops);
                        PlanDraftResponse.Place normalizedThemeParkMeal = buildThemeParkInternalLunch(themeParkStop, draft.city());
                        normalizedStops.add(normalizedThemeParkMeal);
                        previousStop = normalizedThemeParkMeal;
                        continue;
                    }
                    if (!isFoodStop(stop)) {
                        normalizedStops.add(stop);
                        previousStop = stop;
                        String derived = deriveArea(stop, draft.city());
                        if (!derived.isBlank()) {
                            fallbackArea = derived;
                        }
                        continue;
                    }
                    if (!shouldVerifyTargetedStop(day.dayIndex(), i, stop, targetStopIndexesByDay)) {
                        normalizedStops.add(stop);
                        previousStop = stop;
                        if (isStrictMealStop(stop)) {
                            String venueKey = venueKey(stop);
                            if (!venueKey.isBlank()) {
                                if (!dayStrictMealVenues.contains(venueKey)) {
                                    dayStrictMealVenues.add(venueKey);
                                }
                                if (!usedStrictMealVenues.contains(venueKey)) {
                                    usedStrictMealVenues.add(venueKey);
                                }
                            }
                        }
                        continue;
                    }
                    PlanDraftResponse.Place nextStop = nextNonMealStop(stops, i + 1);
                    VerifiedStop verified = verifyFoodStop(
                            stop,
                            draft.city(),
                            fallbackArea,
                            previousStop,
                            nextStop,
                            dayStrictMealVenues,
                            usedStrictMealVenues,
                            daySearchFutures,
                            daySearchExecutor
                    );
                    if (verified.issue != null) {
                        issues.add("day-" + day.dayIndex() + "-stop-" + (i + 1) + "-" + verified.issue);
                    }
                    PlanDraftResponse.Place normalizedStop = verified.place != null ? verified.place : stop;
                    normalizedStops.add(normalizedStop);
                    previousStop = normalizedStop;
                    if (isStrictMealStop(normalizedStop)) {
                        String venueKey = venueKey(normalizedStop);
                        if (!venueKey.isBlank()) {
                            if (!dayStrictMealVenues.contains(venueKey)) {
                                dayStrictMealVenues.add(venueKey);
                            }
                            if (!usedStrictMealVenues.contains(venueKey)) {
                                usedStrictMealVenues.add(venueKey);
                            }
                        }
                    }
                }
            } finally {
                daySearchExecutor.shutdownNow();
            }

            normalizedDays.add(new PlanDraftResponse.DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    normalizedStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        return new VerificationResult(new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                normalizedDays,
                draft.copyPolishStatus(),
                draft.routeStatus(),
                draft.planStatus(),
                draft.planningMode(),
                draft.catalogStatus(),
                draft.copyStatus(),
                draft.enhancementStatus(),
                draft.warnings(),
                draft.contextVersion(),
                draft.planVersion(),
                draft.basePlanVersion()
        ), issues);
    }

    private boolean shouldVerifyTargetedStop(
            int dayIndex,
            int stopIndex,
            PlanDraftResponse.Place stop,
            Map<Integer, java.util.Set<Integer>> targetStopIndexesByDay
    ) {
        if (!isFoodStop(stop)) {
            return false;
        }
        if (targetStopIndexesByDay == null || targetStopIndexesByDay.isEmpty()) {
            return true;
        }
        java.util.Set<Integer> targetedIndexes = targetStopIndexesByDay.get(dayIndex);
        if (targetedIndexes == null || targetedIndexes.isEmpty()) {
            return false;
        }
        return targetedIndexes.contains(stopIndex);
    }

    public PlanDraftResponse ensureRequiredMeals(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || !googlePlacesClient.isEnabled()) {
            return draft;
        }

        List<PlanDraftResponse.DayPlan> updatedDays = new ArrayList<>();
        List<String> usedStrictMealVenues = new ArrayList<>();
        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            List<PlanDraftResponse.Place> workingStops = new ArrayList<>(day.stops() == null ? List.of() : day.stops());
            List<String> dayStrictMealVenues = collectStrictMealVenueKeys(workingStops);
            ExecutorService daySearchExecutor = newDayMealSearchExecutor(workingStops);
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures = new ConcurrentHashMap<>();
            try {
            for (String venueKey : dayStrictMealVenues) {
                if (!venueKey.isBlank() && !usedStrictMealVenues.contains(venueKey)) {
                    usedStrictMealVenues.add(venueKey);
                }
            }

            if (!hasVerifiedMealStop(workingStops, "lunch")) {
                PlanDraftResponse.Place themeParkStop = firstThemeParkStop(workingStops);
                PlanDraftResponse.Place lunchStop = themeParkStop == null
                        ? synthesizeMealStop(day, workingStops, draft.city(), "lunch", dayStrictMealVenues, usedStrictMealVenues, daySearchFutures, daySearchExecutor)
                        : buildThemeParkInternalLunch(themeParkStop, draft.city());
                if (lunchStop != null) {
                    int lunchIndex = findMealInsertionIndex(workingStops, "lunch");
                    workingStops.add(lunchIndex, lunchStop);
                    String venueKey = venueKey(lunchStop);
                    if (!venueKey.isBlank()) {
                        dayStrictMealVenues.add(venueKey);
                        usedStrictMealVenues.add(venueKey);
                    }
                }
            }

            if (!hasVerifiedMealStop(workingStops, "dinner")) {
                PlanDraftResponse.Place dinnerStop = synthesizeMealStop(day, workingStops, draft.city(), "dinner", dayStrictMealVenues, usedStrictMealVenues, daySearchFutures, daySearchExecutor);
                if (dinnerStop != null) {
                    int dinnerIndex = findMealInsertionIndex(workingStops, "dinner");
                    workingStops.add(dinnerIndex, dinnerStop);
                    String venueKey = venueKey(dinnerStop);
                    if (!venueKey.isBlank()) {
                        dayStrictMealVenues.add(venueKey);
                        usedStrictMealVenues.add(venueKey);
                    }
                }
            }
            } finally {
                daySearchExecutor.shutdownNow();
            }

            updatedDays.add(new PlanDraftResponse.DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    workingStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                updatedDays,
                draft.copyPolishStatus(),
                draft.routeStatus(),
                draft.planStatus(),
                draft.planningMode(),
                draft.catalogStatus(),
                draft.copyStatus(),
                draft.enhancementStatus(),
                draft.warnings(),
                draft.contextVersion(),
                draft.planVersion(),
                draft.basePlanVersion()
        );
    }

    private VerifiedStop verifyFoodStop(
            PlanDraftResponse.Place stop,
            String city,
            String fallbackArea,
            PlanDraftResponse.Place previousStop,
            PlanDraftResponse.Place nextStop,
            List<String> dayStrictMealVenues,
            List<String> usedStrictMealVenues,
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures,
            ExecutorService daySearchExecutor
    ) {
        String searchCity = city == null || city.isBlank() ? stop.city() : city;
        boolean strictMealStop = isStrictMealStop(stop);
        if (strictMealStop && isReusableVerifiedStrictMeal(stop) && !shouldReverifyAfterThemePark(stop, previousStop)) {
            return new VerifiedStop(stop, null);
        }
        String mealSearchCity = strictMealStop
                ? effectiveMealSearchCity(searchCity, stop, previousStop)
                : searchCity;
        PlanDraftResponse.Place scoringStop = strictMealStop
                ? buildStrictMealPrototype(stop, mealSearchCity, fallbackArea, previousStop, nextStop)
                : stop;
        if (strictMealStop) {
            boolean hadCandidates = false;
            List<String> strictQueries = buildQueries(scoringStop, mealSearchCity, fallbackArea, previousStop);
            List<List<GooglePlacesClient.PlaceCandidate>> strictResults = resolveQueryBatchResults(strictQueries, mealSearchCity, daySearchFutures, daySearchExecutor);
            for (List<GooglePlacesClient.PlaceCandidate> strictCandidates : strictResults) {
                if (strictCandidates.isEmpty()) {
                    continue;
                }
                hadCandidates = true;
                VerifiedStop accepted = findAcceptableStrictMeal(scoringStop, strictCandidates, mealSearchCity, fallbackArea, dayStrictMealVenues, usedStrictMealVenues);
                if (accepted != null) {
                    return accepted;
                }
            }
            return new VerifiedStop(stop, hadCandidates ? "google-places-low-confidence" : "google-places-no-match");
        }

        List<GooglePlacesClient.PlaceCandidate> candidates = List.of();
        List<String> queries = buildQueries(scoringStop, searchCity, fallbackArea, previousStop);
        List<List<GooglePlacesClient.PlaceCandidate>> queryResults = resolveQueryBatchResults(queries, searchCity, daySearchFutures, daySearchExecutor);
        for (List<GooglePlacesClient.PlaceCandidate> result : queryResults) {
            candidates = result;
            if (!candidates.isEmpty()) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return new VerifiedStop(stop, "google-places-no-match");
        }

        GooglePlacesClient.PlaceCandidate best = candidates.stream()
                .map(candidate -> new RankedCandidate(candidate, score(scoringStop, candidate, dayStrictMealVenues, usedStrictMealVenues)))
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .map(RankedCandidate::candidate)
                .findFirst()
                .orElse(null);

        if (best == null) {
            return strictMealStop
                    ? new VerifiedStop(stop, "google-places-no-match")
                    : new VerifiedStop(stop, null);
        }

        int bestScore = score(scoringStop, best, dayStrictMealVenues, usedStrictMealVenues);
        if (bestScore < 120 || !isCandidatePlausibleForCity(best, searchCity)) {
            return new VerifiedStop(stop, null);
        }

        return new VerifiedStop(normalizeCandidate(scoringStop, best, searchCity, fallbackArea), null);
    }

    private VerifiedStop findAcceptableStrictMeal(
            PlanDraftResponse.Place stop,
            List<GooglePlacesClient.PlaceCandidate> candidates,
            String searchCity,
            String fallbackArea,
            List<String> dayStrictMealVenues,
            List<String> usedStrictMealVenues
    ) {
        List<RankedCandidate> rankedCandidates = candidates.stream()
                .map(candidate -> new RankedCandidate(candidate, score(stop, candidate, dayStrictMealVenues, usedStrictMealVenues)))
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .toList();

        VerifiedStop independentRestaurant = rankedCandidates.stream()
                .filter(ranked -> canAutoAcceptStrictMeal(ranked.candidate(), ranked.score(), stop))
                .filter(ranked -> isIndependentRestaurantCandidate(ranked.candidate()))
                .map(ranked -> new VerifiedStop(normalizeCandidate(stop, ranked.candidate(), searchCity, fallbackArea), null))
                .findFirst()
                .orElse(null);
        if (independentRestaurant != null) {
            return independentRestaurant;
        }

        if ("lunch".equals(normalize(stop.mealType())) || "lunch".equals(normalize(stop.timeSlot()))) {
            VerifiedStop fallbackCafe = rankedCandidates.stream()
                    .filter(ranked -> canAutoAcceptStrictMeal(ranked.candidate(), ranked.score(), stop))
                    .filter(ranked -> isFallbackCafeCandidate(ranked.candidate()))
                    .map(ranked -> new VerifiedStop(normalizeCandidate(stop, ranked.candidate(), searchCity, fallbackArea), null))
                    .findFirst()
                    .orElse(null);
            if (fallbackCafe != null) {
                return fallbackCafe;
            }
        }

        return rankedCandidates.stream()
                .map(ranked -> new RankedCandidate(ranked.candidate(), scoreRelaxedMeal(stop, ranked.candidate(), dayStrictMealVenues, usedStrictMealVenues)))
                .sorted((a, b) -> Integer.compare(b.score, a.score))
                .filter(ranked -> canAutoAcceptRelaxedMeal(ranked.candidate(), ranked.score(), stop))
                .map(ranked -> new VerifiedStop(normalizeCandidate(stop, ranked.candidate(), searchCity, fallbackArea), null))
                .findFirst()
                .orElse(null);
    }

    private PlanDraftResponse.Place normalizeCandidate(
            PlanDraftResponse.Place stop,
            GooglePlacesClient.PlaceCandidate candidate,
            String searchCity,
            String fallbackArea
    ) {
        boolean strictMealStop = isStrictMealStop(stop);
        String candidateArea = safe(extractCandidateArea(candidate.formattedAddress(), searchCity));
        String normalizedArea = !candidateArea.isBlank()
                ? candidateArea
                : (!safe(stop.preferredArea()).isBlank() ? safe(stop.preferredArea()) : safe(fallbackArea));
        AddressParts addressParts = parseAddressParts(candidate.formattedAddress(), searchCity, stop);
        return new PlanDraftResponse.Place(
                isBlank(candidate.name()) ? stop.name() : candidate.name(),
                isBlank(candidate.formattedAddress()) ? "" : candidate.formattedAddress(),
                strictMealStop ? addressParts.suburb() : stop.suburb(),
                isBlank(searchCity) ? stop.city() : searchCity,
                strictMealStop ? addressParts.state() : stop.state(),
                strictMealStop ? addressParts.postcode() : stop.postcode(),
                isBlank(stop.country()) ? "Australia" : stop.country(),
                stop.category(),
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                normalizedArea,
                normalizeCuisine(stop.cuisine(), candidate),
                normalizeVibe(stop.vibe(), candidate),
                stop.budgetLevel(),
                normalizeReason(stop, candidate, normalizedArea),
                normalizeTip(stop, candidate, normalizedArea),
                candidate.websiteUri(),
                candidate.googleMapsUri(),
                candidate.businessStatus(),
                !isBlank(candidate.websiteUri()) ? candidate.websiteUri() : (!isBlank(candidate.googleMapsUri()) ? candidate.googleMapsUri() : stop.url()),
                Double.isNaN(candidate.lat()) ? stop.latitude() : candidate.lat(),
                Double.isNaN(candidate.lng()) ? stop.longitude() : candidate.lng()
        );
    }

    private AddressParts parseAddressParts(String formattedAddress, String searchCity, PlanDraftResponse.Place fallback) {
        String address = safe(formattedAddress);
        String fallbackSuburb = fallback == null ? "" : safe(fallback.suburb());
        String fallbackState = fallback == null ? "" : safe(fallback.state());
        String fallbackPostcode = fallback == null ? "" : safe(fallback.postcode());
        if (address.isBlank()) {
            return new AddressParts(fallbackSuburb, fallbackState, fallbackPostcode);
        }

        String city = safe(searchCity);
        String suburb = "";
        String state = "";
        String postcode = "";
        Pattern statePostcodePattern = Pattern.compile("\\b([A-Z]{2,3})\\s+(\\d{4})\\b");
        for (String part : address.split(",")) {
            String trimmed = part.trim();
            Matcher matcher = statePostcodePattern.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            state = matcher.group(1);
            postcode = matcher.group(2);
            String beforeState = trimmed.substring(0, matcher.start()).trim();
            if (!beforeState.isBlank()
                    && !beforeState.equalsIgnoreCase(city)
                    && !beforeState.matches("(?i).*\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|way|terrace|tce|place|pl)\\b.*")) {
                suburb = beforeState;
            }
        }
        return new AddressParts(
                suburb.isBlank() ? fallbackSuburb : suburb,
                state.isBlank() ? fallbackState : state,
                postcode.isBlank() ? fallbackPostcode : postcode
        );
    }

    private boolean canAutoAcceptStrictMeal(GooglePlacesClient.PlaceCandidate candidate, int score, PlanDraftResponse.Place stop) {
        if (score < 20) return false;
        if (!isCandidatePlausibleForCity(candidate, stop.city())) return false;
        if (looksLikeAreaMealCandidate(candidate) || looksLikeBannedStrictMealName(candidate) || looksLikeTransitMealCandidate(candidate)) return false;
        if (!looksLikeFoodPlace(candidate)) return false;
        if (!looksMealSuitable(stop, candidate)) return false;
        if (looksLikeAttachedVenueCafe(candidate)) return false;
        String status = safe(candidate.businessStatus()).toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private PlanDraftResponse.Place synthesizeMealStop(
            PlanDraftResponse.DayPlan day,
            List<PlanDraftResponse.Place> stops,
            String city,
            String mealType,
            List<String> dayStrictMealVenues,
            List<String> usedStrictMealVenues,
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures,
            ExecutorService daySearchExecutor
    ) {
        AreaContext context = deriveMealAreaContext(day, stops, mealType, city);
        String mealSearchCity = effectiveMealSearchCity(city, mealType, context.previousStop());
        PlanDraftResponse.Place prototype = new PlanDraftResponse.Place(
                capitalize(mealType) + " Stop",
                "",
                "",
                safe(mealSearchCity),
                "",
                "",
                "Australia",
                "restaurant",
                "dinner".equals(mealType) ? 120 : 90,
                mealType,
                "",
                "",
                mealType,
                context.area(),
                "",
                "",
                "midrange",
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                null
        );

        List<String> queries = buildQueries(prototype, mealSearchCity, context.area(), context.previousStop());
        addRelaxedMealQueries(queries, prototype, mealSearchCity, context.area());
        List<List<GooglePlacesClient.PlaceCandidate>> queryResults = resolveQueryBatchResults(queries, mealSearchCity, daySearchFutures, daySearchExecutor);
        for (List<GooglePlacesClient.PlaceCandidate> candidates : queryResults) {
            GooglePlacesClient.PlaceCandidate best = candidates.stream()
                    .map(candidate -> new RankedCandidate(candidate, scoreRelaxedMeal(prototype, candidate, dayStrictMealVenues, usedStrictMealVenues)))
                    .sorted((a, b) -> Integer.compare(b.score, a.score))
                    .map(RankedCandidate::candidate)
                    .findFirst()
                    .orElse(null);
            if (best == null) {
                continue;
            }
            int score = scoreRelaxedMeal(prototype, best, dayStrictMealVenues, usedStrictMealVenues);
            if (!canAutoAcceptRelaxedMeal(best, score, prototype)) {
                continue;
            }
            return normalizeCandidate(prototype, best, mealSearchCity, context.area());
        }
        return null;
    }

    private ExecutorService newDayMealSearchExecutor(List<PlanDraftResponse.Place> stops) {
        int foodStops = (int) (stops == null ? 0 : stops.stream().filter(this::isFoodStop).count());
        int poolSize = Math.max(1, Math.min(DAY_MEAL_SEARCH_MAX_CONCURRENCY, Math.max(1, foodStops)));
        return Executors.newFixedThreadPool(poolSize);
    }

    private void prewarmDayFoodSearches(
            List<PlanDraftResponse.Place> stops,
            String city,
            String initialFallbackArea,
            int dayIndex,
            Map<Integer, java.util.Set<Integer>> targetStopIndexesByDay,
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures,
            ExecutorService daySearchExecutor
    ) {
        if (stops == null || stops.isEmpty()) {
            return;
        }
        String fallbackArea = safe(initialFallbackArea);
        PlanDraftResponse.Place previousStop = null;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraftResponse.Place stop = stops.get(i);
            if (!isFoodStop(stop)) {
                String derived = deriveArea(stop, city);
                if (!derived.isBlank()) {
                    fallbackArea = derived;
                }
                previousStop = stop;
                continue;
            }
            if (!shouldVerifyTargetedStop(dayIndex, i, stop, targetStopIndexesByDay)) {
                previousStop = stop;
                continue;
            }
            PlanDraftResponse.Place nextStop = nextNonMealStop(stops, i + 1);
            boolean strictMealStop = isStrictMealStop(stop);
            String searchCity = city == null || city.isBlank() ? stop.city() : city;
            String mealSearchCity = strictMealStop
                    ? effectiveMealSearchCity(searchCity, stop, previousStop)
                    : searchCity;
            PlanDraftResponse.Place scoringStop = strictMealStop
                    ? buildStrictMealPrototype(stop, mealSearchCity, fallbackArea, previousStop, nextStop)
                    : stop;
            List<String> queries = buildQueries(scoringStop, strictMealStop ? mealSearchCity : searchCity, fallbackArea, previousStop);
            for (String query : queries) {
                scheduleDaySearch(query, strictMealStop ? mealSearchCity : searchCity, daySearchFutures, daySearchExecutor);
            }
            previousStop = stop;
        }
    }

    private List<List<GooglePlacesClient.PlaceCandidate>> resolveQueryBatchResults(
            List<String> queries,
            String city,
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures,
            ExecutorService daySearchExecutor
    ) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> futures = queries.stream()
                .map(query -> scheduleDaySearch(query, city, daySearchFutures, daySearchExecutor))
                .toList();
        List<List<GooglePlacesClient.PlaceCandidate>> results = new ArrayList<>(futures.size());
        for (CompletableFuture<List<GooglePlacesClient.PlaceCandidate>> future : futures) {
            results.add(joinSearchResult(future));
        }
        return results;
    }

    private CompletableFuture<List<GooglePlacesClient.PlaceCandidate>> scheduleDaySearch(
            String query,
            String city,
            Map<String, CompletableFuture<List<GooglePlacesClient.PlaceCandidate>>> daySearchFutures,
            ExecutorService daySearchExecutor
    ) {
        String cacheKey = normalize(query) + "|" + normalize(city);
        List<GooglePlacesClient.PlaceCandidate> cached = GOOGLE_PLACES_SEARCH_L1_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return daySearchFutures.computeIfAbsent(cacheKey, ignored -> CompletableFuture.supplyAsync(() -> {
                    List<GooglePlacesClient.PlaceCandidate> result = withBulkhead(
                            GOOGLE_PLACES_SEARCH_BULKHEAD,
                            () -> googlePlacesClient.searchText(query, city),
                            List.of()
                    );
                    List<GooglePlacesClient.PlaceCandidate> safeResult = result == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(result));
                    GOOGLE_PLACES_SEARCH_L1_CACHE.put(cacheKey, safeResult);
                    return safeResult;
                },
                daySearchExecutor
        ));
    }

    private List<GooglePlacesClient.PlaceCandidate> joinSearchResult(CompletableFuture<List<GooglePlacesClient.PlaceCandidate>> future) {
        try {
            List<GooglePlacesClient.PlaceCandidate> result = future.join();
            return result == null ? List.of() : result;
        } catch (CompletionException ex) {
            return List.of();
        }
    }

    private <T> T withBulkhead(Semaphore semaphore, SupplierWithRuntimeException<T> supplier, T fallback) {
        boolean acquired = false;
        try {
            semaphore.acquire();
            acquired = true;
            return supplier.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (RuntimeException ex) {
            return fallback;
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    @FunctionalInterface
    private interface SupplierWithRuntimeException<T> {
        T get();
    }

    private String effectiveMealSearchCity(String defaultCity, PlanDraftResponse.Place mealStop, PlanDraftResponse.Place previousStop) {
        if (mealStop == null) {
            return safe(defaultCity);
        }
        String mealType = safe(!safe(mealStop.mealType()).isBlank() ? mealStop.mealType() : mealStop.timeSlot());
        return effectiveMealSearchCity(defaultCity, mealType, previousStop);
    }

    private String effectiveMealSearchCity(String defaultCity, String mealType, PlanDraftResponse.Place previousStop) {
        String normalizedMealType = safe(mealType);
        if ("lunch".equalsIgnoreCase(normalizedMealType) && isThemeParkLikeStop(previousStop)) {
            String previousCity = safe(previousStop.city());
            if (!previousCity.isBlank()) {
                return previousCity;
            }
        }
        if ("dinner".equalsIgnoreCase(normalizedMealType)) {
            String previousCity = safe(previousStop == null ? null : previousStop.city());
            if (!previousCity.isBlank() && !previousCity.equalsIgnoreCase(safe(defaultCity))) {
                return previousCity;
            }
        }
        return safe(defaultCity);
    }

    private void addRelaxedMealQueries(List<String> queries, PlanDraftResponse.Place prototype, String city, String area) {
        String mealType = safe(prototype.mealType());
        String areaOrCity = safe(area).isBlank() ? safe(city) : safe(area);
        addQuery(queries, join(" ", mealType, "restaurant", areaOrCity));
        if ("lunch".equalsIgnoreCase(mealType)) {
            addQuery(queries, join(" ", "lunch cafe", areaOrCity));
            addQuery(queries, join(" ", "lunch restaurant", safe(city)));
        } else {
            addQuery(queries, join(" ", "dinner restaurant", areaOrCity));
            addQuery(queries, join(" ", "dinner restaurant", safe(city)));
        }
        addQuery(queries, join(" ", mealType, "restaurant", safe(city)));
    }

    private int scoreRelaxedMeal(
            PlanDraftResponse.Place stop,
            GooglePlacesClient.PlaceCandidate candidate,
            List<String> dayStrictMealVenues,
            List<String> usedStrictMealVenues
    ) {
        int score = 0;
        String expectedArea = normalize(!safe(stop.preferredArea()).isBlank() ? stop.preferredArea() : stop.city());
        String candidateArea = normalize(extractCandidateArea(candidate.formattedAddress(), stop.city()));
        if (!expectedArea.isBlank() && !candidateArea.isBlank()) {
            score += commonTokenCount(expectedArea, candidateArea) * 10;
            if (candidateMatchesExpectedArea(expectedArea, candidate) || !isHardAreaMismatch(expectedArea, candidateArea)) {
                score += 12;
            }
        }
        if (looksLikeFoodPlace(candidate)) score += 60;
        if (isIndependentRestaurantCandidate(candidate)) score += 60;
        if (isFallbackCafeCandidate(candidate)) score += 25;
        if (looksLikeAttachedVenueCafe(candidate)) score -= 60;
        if (looksLikeTransitMealCandidate(candidate) || looksLikeAreaMealCandidate(candidate) || looksLikeBannedStrictMealName(candidate)) score -= 180;
        if (!looksMealSuitable(stop, candidate)) score -= 80;
        if (isSocialOnlyWebsite(candidate.websiteUri())) score -= 40;

        String venueKey = venueKey(candidate);
        if (!venueKey.isBlank() && dayStrictMealVenues.contains(venueKey)) {
            score -= 160;
        } else if (!venueKey.isBlank() && usedStrictMealVenues.contains(venueKey)) {
            score -= 60;
        }

        String status = safe(candidate.businessStatus()).toUpperCase(Locale.ROOT);
        if ("OPERATIONAL".equals(status)) score += 30;
        if (status.contains("CLOSED")) score -= 180;
        return score;
    }

    private boolean canAutoAcceptRelaxedMeal(GooglePlacesClient.PlaceCandidate candidate, int score, PlanDraftResponse.Place stop) {
        if (score < 25) return false;
        if (!isCandidatePlausibleForCity(candidate, stop.city())) return false;
        if (looksLikeTransitMealCandidate(candidate) || looksLikeAreaMealCandidate(candidate) || looksLikeBannedStrictMealName(candidate)) return false;
        if (!looksLikeFoodPlace(candidate)) return false;
        if (!looksMealSuitable(stop, candidate)) return false;
        String status = safe(candidate.businessStatus()).toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private boolean isCandidatePlausibleForCity(GooglePlacesClient.PlaceCandidate candidate, String city) {
        if (candidate == null || isBlank(city)) {
            return true;
        }
        if (Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lng())) {
            return isCoordinatePlausibleForCity(candidate.lat(), candidate.lng(), city);
        }
        return !hasConflictingAustralianState(candidate.formattedAddress(), city);
    }

    private boolean isCoordinatePlausibleForCity(double lat, double lng, String city) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || isBlank(city)) {
            return true;
        }
        String normalizedCity = city.trim().toLowerCase(Locale.ROOT);
        if ("brisbane".equals(normalizedCity)) {
            return lat >= -28.2 && lat <= -26.8 && lng >= 152.4 && lng <= 153.7;
        }
        if ("sydney".equals(normalizedCity)) {
            return lat >= -34.4 && lat <= -33.2 && lng >= 150.5 && lng <= 151.6;
        }
        if ("melbourne".equals(normalizedCity)) {
            return lat >= -38.5 && lat <= -37.2 && lng >= 144.2 && lng <= 145.6;
        }
        return true;
    }

    private boolean hasConflictingAustralianState(String formattedAddress, String city) {
        String expectedState = expectedStateForCity(city);
        if (expectedState.isBlank()) {
            return false;
        }
        String address = safe(formattedAddress).toUpperCase(Locale.ROOT);
        if (address.isBlank()) {
            return false;
        }
        for (String state : List.of("NSW", "VIC", "QLD", "WA", "SA", "TAS", "NT", "ACT")) {
            if (address.matches(".*\\b" + state + "\\b.*") && !state.equals(expectedState)) {
                return true;
            }
        }
        return false;
    }

    private String expectedStateForCity(String city) {
        String normalizedCity = safe(city).trim().toLowerCase(Locale.ROOT);
        return switch (normalizedCity) {
            case "brisbane" -> "QLD";
            case "sydney" -> "NSW";
            case "melbourne" -> "VIC";
            default -> "";
        };
    }

    private PlanDraftResponse.Place buildStrictMealPrototype(
            PlanDraftResponse.Place original,
            String city,
            String fallbackArea,
            PlanDraftResponse.Place previousStop,
            PlanDraftResponse.Place nextStop
    ) {
        String mealType = safe(original.mealType()).isBlank() ? safe(original.timeSlot()) : safe(original.mealType());
        String preferredArea = "";
        String sandwichArea = deriveSharedAreaAnchor(previousStop, nextStop);
        if (!sandwichArea.isBlank()) {
            preferredArea = sandwichArea;
        }
        if (preferredArea.isBlank()) {
            preferredArea = deriveArea(previousStop, city);
        }
        if (preferredArea.isBlank()) {
            preferredArea = safe(fallbackArea);
        }
        if (preferredArea.isBlank()) {
            preferredArea = safe(original.preferredArea());
        }
        return new PlanDraftResponse.Place(
                capitalize(mealType) + " Stop",
                "",
                "",
                safe(city),
                "",
                "",
                isBlank(original.country()) ? "Australia" : original.country(),
                "restaurant",
                original.stayMinutes() != null && original.stayMinutes() > 0 ? original.stayMinutes() : ("dinner".equalsIgnoreCase(mealType) ? 120 : 90),
                safe(original.timeSlot()).isBlank() ? mealType : original.timeSlot(),
                original.startTime(),
                original.endTime(),
                mealType,
                preferredArea,
                original.cuisine(),
                original.vibe(),
                original.budgetLevel(),
                "",
                "",
                "",
                "",
                "",
                "",
                original.latitude(),
                original.longitude()
        );
    }

    private PlanDraftResponse.Place nextNonMealStop(List<PlanDraftResponse.Place> stops, int startIndex) {
        if (stops == null) return null;
        for (int i = Math.max(0, startIndex); i < stops.size(); i++) {
            PlanDraftResponse.Place stop = stops.get(i);
            if (stop != null && !isFoodStop(stop)) {
                return stop;
            }
        }
        return null;
    }

    private AreaContext deriveMealAreaContext(PlanDraftResponse.DayPlan day, List<PlanDraftResponse.Place> stops, String mealType, String city) {
        PlanDraftResponse.Place themeParkStop = firstThemeParkStop(stops);
        if (themeParkStop != null) {
            String themeParkArea = deriveArea(themeParkStop, city);
            if (!themeParkArea.isBlank()) {
                return new AreaContext(themeParkArea, themeParkStop);
            }
        }
        PlanDraftResponse.Place previousStop = null;
        PlanDraftResponse.Place nextStop = null;
        if ("lunch".equals(mealType)) {
            for (PlanDraftResponse.Place stop : stops) {
                String slot = normalize(stop.timeSlot());
                if ("afternoon".equals(slot) || "sunset".equals(slot) || "evening".equals(slot) || "dinner".equals(slot) || "night".equals(slot)) {
                    nextStop = stop;
                    break;
                }
                previousStop = stop;
            }
        } else {
            for (PlanDraftResponse.Place stop : stops) {
                String slot = normalize(stop.timeSlot());
                if ("dinner".equals(slot) || "evening".equals(slot) || "night".equals(slot)) {
                    break;
                }
                previousStop = stop;
            }
        }

        String area = deriveArea(previousStop, city);
        if (area.isBlank()) {
            area = deriveArea(nextStop, city);
        }
        if (area.isBlank()) {
            area = deriveArea(day.hotel(), city);
        }
        return new AreaContext(area, previousStop);
    }

    private PlanDraftResponse.Place firstThemeParkStop(List<PlanDraftResponse.Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return null;
        }
        for (PlanDraftResponse.Place stop : stops) {
            if (isThemeParkLikeStop(stop)) {
                return stop;
            }
        }
        return null;
    }

    private PlanDraftResponse.Place buildThemeParkInternalLunch(PlanDraftResponse.Place themeParkStop, String fallbackCity) {
        String area = deriveArea(themeParkStop, fallbackCity);
        String parkName = safe(themeParkStop.name());
        String name = parkName.isBlank() ? "Theme Park Internal Dining Break" : parkName + " Internal Dining Break";
        return new PlanDraftResponse.Place(
                name,
                themeParkStop.addressLine(),
                themeParkStop.suburb(),
                themeParkStop.city(),
                themeParkStop.state(),
                themeParkStop.postcode(),
                themeParkStop.country(),
                "dining",
                60,
                "lunch",
                null,
                null,
                "lunch",
                area,
                "theme park dining",
                "casual",
                "midrange",
                "This is a controlled in-park dining break, not a separate restaurant recommendation.",
                "Choose an available in-park or immediately adjacent option on the day.",
                themeParkStop.websiteUri(),
                themeParkStop.googleMapsUri(),
                themeParkStop.businessStatus(),
                themeParkStop.url(),
                themeParkStop.latitude(),
                themeParkStop.longitude()
        );
    }

    private boolean isThemeParkInternalMeal(PlanDraftResponse.Place stop, List<PlanDraftResponse.Place> stops) {
        if (stop == null || stops == null || firstThemeParkStop(stops) == null) {
            return false;
        }
        String mealType = normalize(!safe(stop.mealType()).isBlank() ? stop.mealType() : stop.timeSlot());
        if (!"lunch".equals(mealType)) {
            return false;
        }
        String category = normalize(stop.category());
        String cuisine = normalize(stop.cuisine());
        String name = normalize(stop.name());
        return isMealCategory(category) && (cuisine.contains("theme park dining") || name.contains("dining break"));
    }

    private int findMealInsertionIndex(List<PlanDraftResponse.Place> stops, String mealType) {
        if ("lunch".equals(mealType)) {
            for (int i = 0; i < stops.size(); i++) {
                String slot = normalize(stops.get(i).timeSlot());
                if ("afternoon".equals(slot) || "sunset".equals(slot) || "evening".equals(slot) || "dinner".equals(slot) || "night".equals(slot)) {
                    return i;
                }
            }
            return Math.min(1, stops.size());
        }
        for (int i = 0; i < stops.size(); i++) {
            String slot = normalize(stops.get(i).timeSlot());
            if ("dinner".equals(slot) || "evening".equals(slot) || "night".equals(slot)) {
                return i;
            }
        }
        return stops.size();
    }

    private boolean hasVerifiedMealStop(List<PlanDraftResponse.Place> stops, String mealType) {
        return stops.stream().anyMatch(stop -> hasVerifiedMealStop(stop, mealType));
    }

    private boolean hasVerifiedMealStop(PlanDraftResponse.Place stop, String mealType) {
        if (stop == null) return false;
        String slot = normalize(stop.timeSlot());
        String actualMealType = normalize(stop.mealType());
        String category = normalize(stop.category());
        String status = safe(stop.businessStatus()).toUpperCase(Locale.ROOT);
        boolean matchesMeal = mealType.equals(slot) || mealType.equals(actualMealType);
        return matchesMeal
                && isMealCategory(category)
                && (status.isBlank() || "OPERATIONAL".equals(status));
    }

    private boolean isReusableVerifiedStrictMeal(PlanDraftResponse.Place stop) {
        if (stop == null || !isStrictMealStop(stop)) return false;
        String category = normalize(stop.category());
        String status = safe(stop.businessStatus()).toUpperCase(Locale.ROOT);
        if (!isMealCategory(category) || status.contains("CLOSED")) {
            return false;
        }
        boolean hasGoogleIdentity = !safe(stop.googleMapsUri()).isBlank()
                || safe(stop.url()).toLowerCase(Locale.ROOT).contains("google")
                || !status.isBlank();
        boolean hasResolvedLocation = !safe(stop.addressLine()).isBlank()
                && stop.latitude() != null
                && stop.longitude() != null;
        return hasGoogleIdentity && hasResolvedLocation;
    }

    private boolean shouldReverifyAfterThemePark(PlanDraftResponse.Place mealStop, PlanDraftResponse.Place previousStop) {
        if (mealStop == null || previousStop == null || !isStrictMealStop(mealStop)) {
            return false;
        }
        String mealType = normalize(!safe(mealStop.mealType()).isBlank() ? mealStop.mealType() : mealStop.timeSlot());
        if (!"dinner".equals(mealType)) {
            return false;
        }
        if (!isThemeParkContextStop(previousStop)) {
            return false;
        }
        if (hasNearbyCoordinates(previousStop, mealStop, 5_000)) {
            return false;
        }
        String previousArea = normalize(deriveArea(previousStop, previousStop.city()));
        String mealArea = normalize(deriveArea(mealStop, mealStop.city()));
        return !previousArea.isBlank()
                && !mealArea.isBlank()
                && !previousArea.contains(mealArea)
                && !mealArea.contains(previousArea);
    }

    private boolean isThemeParkContextStop(PlanDraftResponse.Place stop) {
        if (isThemeParkLikeStop(stop)) {
            return true;
        }
        String text = normalize(join(" ", stop.name(), stop.category(), stop.cuisine(), stop.preferredArea()));
        return text.contains("theme park")
                || text.contains("dining break")
                || text.contains("afternoon visit")
                || text.contains("continued visit");
    }

    private boolean hasNearbyCoordinates(PlanDraftResponse.Place a, PlanDraftResponse.Place b, double thresholdMeters) {
        if (a == null || b == null || a.latitude() == null || a.longitude() == null || b.latitude() == null || b.longitude() == null) {
            return false;
        }
        return haversineMeters(a.latitude(), a.longitude(), b.latitude(), b.longitude()) <= thresholdMeters;
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "cafe".equals(category);
    }

    private List<String> collectStrictMealVenueKeys(List<PlanDraftResponse.Place> stops) {
        List<String> venueKeys = new ArrayList<>();
        for (PlanDraftResponse.Place stop : stops) {
            if (!isStrictMealStop(stop)) continue;
            String venueKey = venueKey(stop);
            if (!venueKey.isBlank() && !venueKeys.contains(venueKey)) {
                venueKeys.add(venueKey);
            }
        }
        return venueKeys;
    }

    private boolean isFoodStop(PlanDraftResponse.Place stop) {
        String category = stop == null || stop.category() == null ? "" : stop.category().trim().toLowerCase(Locale.ROOT);
        return "restaurant".equals(category) || "food".equals(category) || "cafe".equals(category) || "dining".equals(category);
    }

    private boolean isStrictMealStop(PlanDraftResponse.Place stop) {
        if (stop == null) return false;
        String mealType = safe(stop.mealType()).toLowerCase(Locale.ROOT);
        String timeSlot = safe(stop.timeSlot()).toLowerCase(Locale.ROOT);
        return "lunch".equals(mealType)
                || "dinner".equals(mealType)
                || "lunch".equals(timeSlot)
                || "dinner".equals(timeSlot);
    }

    private boolean isThemeParkLikeStop(PlanDraftResponse.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalize(stop.category());
        String text = normalize(join(" ", stop.name(), stop.addressLine(), category));
        return "theme park".equals(category)
                || "theme_park".equals(category)
                || "amusement".equals(category)
                || "amusement park".equals(category)
                || text.contains("theme park")
                || text.contains("amusement park")
                || text.contains("movie world")
                || text.contains("dreamworld")
                || text.contains("sea world")
                || text.contains("wet n wild");
    }

    private boolean looksLikeFoodPlace(GooglePlacesClient.PlaceCandidate candidate) {
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return types.contains("restaurant")
                || types.contains("cafe")
                || types.contains("bakery")
                || types.contains("bar")
                || types.contains("food");
    }

    private int score(
            PlanDraftResponse.Place stop,
            GooglePlacesClient.PlaceCandidate candidate,
            List<String> dayStrictMealVenues,
            List<String> usedStrictMealVenues
    ) {
        boolean strictMealStop = isStrictMealStop(stop);
        int score = 0;
        String stopName = normalize(stop.name());
        String candidateName = normalize(candidate.name());
        String stopAddress = normalize(stop.addressLine());
        String candidateAddress = normalize(candidate.formattedAddress());
        if (strictMealStop) {
            String area = normalize(!safe(stop.preferredArea()).isBlank() ? stop.preferredArea() : stop.city());
            String candidateArea = normalize(extractCandidateArea(candidate.formattedAddress(), stop.city()));
            String cuisine = normalize(stop.cuisine());
            String mealType = normalize(stop.mealType().isBlank() ? stop.timeSlot() : stop.mealType());
            score += commonTokenCount(area, candidateArea) * 18;
            if (candidateMatchesExpectedArea(area, candidate)) {
                score += 36;
            }
            score += commonTokenCount(cuisine, candidateName) * 10;
            score += commonTokenCount(cuisine, String.join(" ", candidate.types())) * 14;
            if (!area.isBlank() && !candidateArea.isBlank() && isHardAreaMismatch(area, candidateArea) && !candidateMatchesExpectedArea(area, candidate)) {
                return -1000;
            }
            if ("lunch".equals(mealType)) score += 10;
            if ("dinner".equals(mealType)) score += 15;
        } else {
            if (!stopName.isBlank() && candidateName.contains(stopName)) score += 100;
            if (!candidateName.isBlank() && stopName.contains(candidateName)) score += 70;
            int commonNameTokens = commonTokenCount(stopName, candidateName);
            score += commonNameTokens * 20;
            int commonAddressTokens = commonTokenCount(stopAddress, candidateAddress);
            score += commonAddressTokens * 8;
        }
        if (looksLikeAreaMealCandidate(candidate)) score -= 140;
        if (!looksMealSuitable(stop, candidate)) score -= 110;
        if (looksLikeFoodPlace(candidate)) score += 50;
        if (isSocialOnlyWebsite(candidate.websiteUri())) score -= 80;
        if (strictMealStop) {
            String venueKey = venueKey(candidate);
            if (!venueKey.isBlank() && dayStrictMealVenues.contains(venueKey)) {
                score -= 220;
            } else if (!venueKey.isBlank() && usedStrictMealVenues.contains(venueKey)) {
                score -= 80;
            }
        }
        String status = candidate.businessStatus() == null ? "" : candidate.businessStatus().toUpperCase(Locale.ROOT);
        if ("OPERATIONAL".equals(status)) score += 30;
        if (status.contains("CLOSED")) score -= 120;
        return score;
    }

    private List<String> buildQueries(PlanDraftResponse.Place stop, String city, String fallbackArea, PlanDraftResponse.Place previousStop) {
        List<String> queries = new ArrayList<>();
        String preferredArea = safe(stop.preferredArea());
        String effectiveArea = !preferredArea.isBlank() ? preferredArea : safe(fallbackArea);
        String previousArea = deriveArea(previousStop, city);
        String cuisine = safe(stop.cuisine());
        String vibe = safe(stop.vibe());
        String rawMealType = safe(stop.mealType());
        String mealType = safe(rawMealType.isBlank() ? stop.timeSlot() : rawMealType);
        String budgetLevel = safe(stop.budgetLevel());
        String name = safe(stop.name());
        String address = safe(stop.addressLine());
        boolean strictMealStop = isStrictMealStop(stop);
        boolean themeParkLunch = strictMealStop
                && "lunch".equalsIgnoreCase(mealType)
                && isThemeParkLikeStop(previousStop);

        // Strict meal stops are retrieval-first: use dining intent, not model-provided venue names.
        if (!previousArea.isBlank()) {
            addQuery(queries, join(" ", mealType, cuisine, vibe, budgetLevel, "restaurant", previousArea));
            if (themeParkLunch) {
                addQuery(queries, join(" ", "restaurant", previousArea));
                addQuery(queries, join(" ", "cafe", previousArea));
                addQuery(queries, join(" ", "food", previousArea));
                addQuery(queries, join(" ", "restaurant near", safe(previousStop.name()), previousArea));
                addQuery(queries, join(" ", "food near", safe(previousStop.name()), previousArea));
                addQuery(queries, join(" ", "cafe near", safe(previousStop.name()), previousArea));
                addQuery(queries, join(" ", safe(previousStop.name()), "restaurant", previousArea));
                addQuery(queries, join(" ", safe(previousStop.name()), "food", previousArea));
                return queries;
            }
        }
        addQuery(queries, join(" ", mealType, cuisine, vibe, budgetLevel, "restaurant", effectiveArea.isBlank() ? city : effectiveArea));
        if (strictMealStop) {
            addQuery(queries, join(" ", mealType, cuisine, budgetLevel, "restaurant", city));
        } else if (!address.isBlank()) {
            addQuery(queries, join(" ", mealType, cuisine, "restaurant", address));
        } else {
            addQuery(queries, join(" ", name, effectiveArea.isBlank() ? city : effectiveArea));
        }
        return queries;
    }

    private String deriveArea(PlanDraftResponse.Place place, String fallbackCity) {
        if (place == null) return "";
        String preferredArea = safe(place.preferredArea());
        if (!preferredArea.isBlank()) return preferredArea;

        String suburb = safe(place.suburb());
        if (!suburb.isBlank()) return suburb;

        String address = safe(place.addressLine());
        if (!address.isBlank()) {
            String[] parts = address.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String locality = extractLocalityFromPart(parts[i], fallbackCity);
                if (!locality.isBlank()) {
                    return locality;
                }
            }
            for (int i = parts.length - 1; i >= 0; i--) {
                String rawPart = parts[i];
                String part = safe(rawPart);
                if (part.isBlank()) continue;
                String lower = part.toLowerCase(Locale.ROOT);
                if (fallbackCity != null && !fallbackCity.isBlank() && lower.equals(fallbackCity.trim().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (lower.contains("australia")) continue;
                if (looksLikeAreaNoise(part)) continue;
                return part;
            }
        }

        String city = safe(place.city());
        if (!city.isBlank()) return city;
        return safe(fallbackCity);
    }

    private String deriveSharedAreaAnchor(PlanDraftResponse.Place previousStop, PlanDraftResponse.Place nextStop) {
        String previousText = normalizedPlaceText(previousStop);
        String nextText = normalizedPlaceText(nextStop);
        if (previousText.isBlank() || nextText.isBlank()) return "";

        List<String> previousTokens = significantAreaTokens(previousText);
        List<String> nextTokens = significantAreaTokens(nextText);
        List<String> shared = new ArrayList<>();
        for (String token : previousTokens) {
            if (!shared.contains(token) && nextTokens.contains(token)) {
                shared.add(token);
            }
        }
        if (shared.size() < 2) return "";
        return String.join(" ", shared.subList(0, Math.min(shared.size(), 4)));
    }

    private String normalizedPlaceText(PlanDraftResponse.Place place) {
        if (place == null) return "";
        return normalize(join(" ",
                place.name(),
                place.addressLine(),
                place.suburb(),
                place.preferredArea(),
                place.city()
        ));
    }

    private List<String> significantAreaTokens(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String token : normalizedText.split("\\s+")) {
            if (token.length() <= 2) continue;
            if (isGenericAreaToken(token)) continue;
            if (isStreetOrServiceToken(token)) continue;
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean candidateMatchesExpectedArea(String expectedArea, GooglePlacesClient.PlaceCandidate candidate) {
        String expected = normalize(expectedArea);
        if (expected.isBlank()) return false;
        String text = normalize(join(" ",
                candidate.name(),
                candidate.formattedAddress(),
                String.join(" ", candidate.types())
        ));
        return commonTokenCount(expected, text) >= Math.min(2, Math.max(1, expected.split("\\s+").length));
    }

    private String extractCandidateArea(String formattedAddress, String fallbackCity) {
        if (isBlank(formattedAddress)) return safe(fallbackCity);
        String[] parts = formattedAddress.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String locality = extractLocalityFromPart(parts[i], fallbackCity);
            if (!locality.isBlank()) {
                return locality;
            }
        }
        for (int i = parts.length - 1; i >= 0; i--) {
            String rawPart = parts[i];
            String part = safe(rawPart);
            if (part.isBlank()) continue;
            String lower = part.toLowerCase(Locale.ROOT);
            if (!safe(fallbackCity).isBlank() && lower.equals(safe(fallbackCity).toLowerCase(Locale.ROOT))) continue;
            if (lower.contains("australia")) continue;
            if (looksLikeAreaNoise(part)) continue;
            return part;
        }
        return safe(fallbackCity);
    }

    private String extractLocalityFromPart(String rawPart, String fallbackCity) {
        String part = safe(rawPart);
        if (part.isBlank()) return "";
        String cleaned = part
                .replaceAll("(?i)\\b(NSW|VIC|QLD|WA|SA|TAS|NT|ACT)\\b\\s*\\d{4,5}", "")
                .replaceAll("(?i)\\bAustralia\\b", "")
                .trim();
        if (cleaned.isBlank()) return "";
        if (!safe(fallbackCity).isBlank() && cleaned.equalsIgnoreCase(safe(fallbackCity))) {
            return "";
        }
        if (looksLikeAreaNoise(cleaned)) return "";
        return cleaned;
    }

    private boolean looksLikeAreaNoise(String part) {
        String lower = safe(part).toLowerCase(Locale.ROOT);
        return lower.isBlank()
                || lower.matches(".*\\b(level|ground level|lower concourse|upper concourse|unit|shop|suite|bay|bays)\\b.*")
                || lower.matches(".*\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|highway|hwy|roadway|wharf roadway)\\b.*")
                || lower.matches(".*\\d+.*");
    }

    private boolean isHardAreaMismatch(String expectedArea, String candidateArea) {
        String expected = normalize(expectedArea);
        String candidate = normalize(candidateArea);
        if (expected.isBlank() || candidate.isBlank()) return false;
        if (expected.contains(candidate) || candidate.contains(expected)) return false;
        if (commonTokenCount(expected, candidate) > 0) return false;

        String expectedPostcode = extractPostcode(expectedArea);
        String candidatePostcode = extractPostcode(candidateArea);
        if (!expectedPostcode.isBlank() && expectedPostcode.equals(candidatePostcode)) return false;

        String expectedPrimary = primaryLocalityToken(expected);
        String candidatePrimary = primaryLocalityToken(candidate);
        if (!expectedPrimary.isBlank() && expectedPrimary.equals(candidatePrimary)) return false;

        if (isGenericMetroArea(expected) || isGenericMetroArea(candidate)) return false;
        return true;
    }

    private String extractPostcode(String value) {
        if (isBlank(value)) return "";
        Matcher matcher = POSTCODE_PATTERN.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private String primaryLocalityToken(String normalizedArea) {
        if (normalizedArea.isBlank()) return "";
        for (String token : normalizedArea.split("\\s+")) {
            if (token.length() <= 2) continue;
            if (isGenericAreaToken(token)) continue;
            return token;
        }
        return "";
    }

    private boolean isGenericMetroArea(String normalizedArea) {
        if (normalizedArea.isBlank()) return false;
        return normalizedArea.contains(" cbd")
                || normalizedArea.equals("cbd")
                || normalizedArea.contains(" central")
                || normalizedArea.contains(" downtown")
                || normalizedArea.contains(" city");
    }

    private boolean isGenericAreaToken(String token) {
        return token.equals("city")
                || token.equals("cbd")
                || token.equals("central")
                || token.equals("downtown")
                || token.equals("sydney")
                || token.equals("melbourne")
                || token.equals("brisbane")
                || token.equals("australia")
                || token.equals("nsw")
                || token.equals("vic")
                || token.equals("qld")
                || token.equals("the");
    }

    private boolean isStreetOrServiceToken(String token) {
        return token.equals("street")
                || token.equals("st")
                || token.equals("road")
                || token.equals("rd")
                || token.equals("avenue")
                || token.equals("ave")
                || token.equals("drive")
                || token.equals("dr")
                || token.equals("lane")
                || token.equals("place")
                || token.equals("car")
                || token.equals("park")
                || token.equals("parking")
                || token.equals("station")
                || token.equals("visitor")
                || token.equals("information")
                || token.equals("centre")
                || token.equals("center");
    }

    private String normalizeCuisine(String originalCuisine, GooglePlacesClient.PlaceCandidate candidate) {
        String inferred = inferCuisineFromCandidate(candidate);
        if (!inferred.isBlank()) {
            return inferred;
        }
        String normalized = safe(originalCuisine);
        return normalized.isBlank() ? "restaurant" : normalized;
    }

    private String normalizeVibe(String originalVibe, GooglePlacesClient.PlaceCandidate candidate) {
        String normalized = safe(originalVibe);
        if (!normalized.isBlank()) {
            return normalized;
        }
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("bar") || types.contains("brew")) return "lively";
        if (types.contains("cafe") || types.contains("bakery")) return "casual";
        if (types.contains("restaurant") || types.contains("food")) return "casual";
        return normalized.isBlank() ? "casual" : normalized;
    }

    private String inferCuisineFromCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("greek")) return "Greek";
        if (types.contains("italian")) return "Italian";
        if (types.contains("mediterranean")) return "Mediterranean";
        if (types.contains("latin_american")) return "Latin / South American";
        if (types.contains("japanese")) return "Japanese";
        if (types.contains("chinese")) return "Chinese";
        if (types.contains("thai")) return "Thai";
        if (types.contains("indian")) return "Indian";
        if (types.contains("mexican")) return "Mexican";
        if (types.contains("seafood")) return "Seafood";
        if (types.contains("steak")) return "Steakhouse";
        if (types.contains("pizza")) return "Pizza";
        if (types.contains("bakery")) return "Bakery";
        if (types.contains("cafe")) return "Cafe";
        if (types.contains("barbecue")) return "Barbecue";
        return "";
    }

    private String normalizeReason(PlanDraftResponse.Place stop, GooglePlacesClient.PlaceCandidate candidate, String normalizedArea) {
        String mealType = safe(stop.mealType()).toLowerCase(Locale.ROOT);
        String cuisine = normalizeCuisine(stop.cuisine(), candidate);
        String venueName = isBlank(candidate.name()) ? safe(stop.name()) : candidate.name();
        if ("lunch".equals(mealType)) {
            return venueName + " is a practical lunch stop in " + fallbackAreaLabel(normalizedArea, stop.city())
                    + ", keeping the route compact while providing a reliable meal break.";
        }
        if ("dinner".equals(mealType)) {
            return venueName + " gives the day a stronger evening finish in " + fallbackAreaLabel(normalizedArea, stop.city())
                    + ", with a more substantial meal after the main sightseeing stops.";
        }
        return venueName + " fits naturally into the route in " + fallbackAreaLabel(normalizedArea, stop.city())
                + " and adds a useful food-focused pause without a major detour.";
    }

    private String normalizeTip(PlanDraftResponse.Place stop, GooglePlacesClient.PlaceCandidate candidate, String normalizedArea) {
        String mealType = safe(stop.mealType()).toLowerCase(Locale.ROOT);
        boolean socialOnly = isSocialOnlyWebsite(candidate.websiteUri());
        if ("lunch".equals(mealType)) {
            return socialOnly
                    ? "Check the venue's latest opening details before visiting, as its online presence is limited."
                    : "Book ahead if you want a smoother lunch stop, especially on weekends and public holidays.";
        }
        if ("dinner".equals(mealType)) {
            return socialOnly
                    ? "Confirm current dinner service before you go, as the venue relies mainly on social updates."
                    : "Reserve in advance for dinner, especially if you want the best seating later in the day.";
        }
        return "Treat this as a flexible stop in " + fallbackAreaLabel(normalizedArea, stop.city())
                + " and check current trading details before visiting.";
    }

    private String fallbackAreaLabel(String normalizedArea, String city) {
        return !safe(normalizedArea).isBlank() ? normalizedArea : safe(city);
    }

    private boolean looksLikeAreaMealCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String name = safe(candidate.name()).toLowerCase(Locale.ROOT);
        return name.contains(" pier")
                || name.endsWith("pier")
                || name.contains(" quay")
                || name.endsWith("quay")
                || name.contains(" wharf")
                || name.endsWith("wharf")
                || name.contains(" precinct")
                || name.endsWith("precinct")
                || name.contains(" parklands")
                || name.endsWith("parklands")
                || name.contains(" market")
                || name.endsWith("market");
    }

    private boolean looksLikeBannedStrictMealName(GooglePlacesClient.PlaceCandidate candidate) {
        String name = safe(candidate.name()).toLowerCase(Locale.ROOT);
        return name.contains("pier")
                || name.contains("wharf")
                || name.contains("quay")
                || name.contains("terminal")
                || name.contains("market")
                || name.contains("parklands")
                || name.contains("precinct");
    }

    private boolean looksLikeTransitMealCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String name = safe(candidate.name()).toLowerCase(Locale.ROOT);
        String address = safe(candidate.formattedAddress()).toLowerCase(Locale.ROOT);
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return name.contains("station")
                || name.contains("terminal")
                || name.contains("ferry")
                || address.contains("station")
                || address.contains("terminal")
                || address.contains("ferry")
                || types.contains("transit_station");
    }

    private boolean looksLikeAttachedVenueCafe(GooglePlacesClient.PlaceCandidate candidate) {
        String name = safe(candidate.name()).toLowerCase(Locale.ROOT);
        String address = safe(candidate.formattedAddress()).toLowerCase(Locale.ROOT);
        return name.contains("museum cafe")
                || name.contains("gallery cafe")
                || name.contains("garden cafe")
                || name.contains("botanic")
                || name.contains("visitor centre cafe")
                || address.contains("botanic garden")
                || address.contains("museum")
                || address.contains("gallery")
                || address.contains("visitor centre");
    }

    private boolean isIndependentRestaurantCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return types.contains("restaurant")
                && !looksLikeAttachedVenueCafe(candidate)
                && !looksLikeTransitMealCandidate(candidate)
                && !looksLikeAreaMealCandidate(candidate)
                && !looksLikeBannedStrictMealName(candidate);
    }

    private boolean isFallbackCafeCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return (types.contains("cafe") || types.contains("food"))
                && !looksLikeAttachedVenueCafe(candidate)
                && !looksLikeTransitMealCandidate(candidate)
                && !looksLikeAreaMealCandidate(candidate)
                && !looksLikeBannedStrictMealName(candidate);
    }

    private boolean looksMealSuitable(PlanDraftResponse.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String mealType = safe(stop.mealType()).toLowerCase(Locale.ROOT);
        String name = safe(candidate.name()).toLowerCase(Locale.ROOT);
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        boolean hasRestaurantType = types.contains("restaurant");
        boolean hasCafeType = types.contains("cafe");
        boolean hasBarType = types.contains("bar");

        if ("lunch".equals(mealType)) {
            if ((name.contains("bar") || hasBarType) && !hasRestaurantType && !hasCafeType) return false;
            if (name.contains("steakhouse") || types.contains("steak")) return false;
            return !(name.contains("dinner") || name.contains("grill room"));
        }

        if ("dinner".equals(mealType)) {
            if (name.contains("bakery") || types.contains("bakery")) return false;
            if (hasCafeType && !hasRestaurantType) return false;
        }

        return true;
    }

    private void addQuery(List<String> queries, String query) {
        String normalized = safe(query);
        if (!normalized.isBlank() && !queries.contains(normalized)) {
            queries.add(normalized);
        }
    }

    private int commonTokenCount(String a, String b) {
        if (a.isBlank() || b.isBlank()) return 0;
        List<String> tokensA = List.of(a.split("\\s+"));
        List<String> tokensB = List.of(b.split("\\s+"));
        int count = 0;
        for (String token : tokensA) {
            if (token.length() > 2 && tokensB.contains(token)) count++;
        }
        return count;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String capitalize(String value) {
        String text = safe(value);
        if (text.isBlank()) return text;
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    private String venueKey(PlanDraftResponse.Place stop) {
        if (stop == null) return "";
        return normalize(stop.name()) + "|" + normalize(stop.addressLine());
    }

    private String venueKey(GooglePlacesClient.PlaceCandidate candidate) {
        if (candidate == null) return "";
        return normalize(candidate.name()) + "|" + normalize(candidate.formattedAddress());
    }

    private boolean isSocialOnlyWebsite(String websiteUri) {
        String website = safe(websiteUri).toLowerCase(Locale.ROOT);
        return website.contains("instagram.com")
                || website.contains("facebook.com")
                || website.contains("fb.com")
                || website.contains("linktr.ee")
                || website.contains("tiktok.com");
    }

    public record VerificationResult(PlanDraftResponse draft, List<String> issues) {}

    private record VerifiedStop(PlanDraftResponse.Place place, String issue) {}

    private record RankedCandidate(GooglePlacesClient.PlaceCandidate candidate, int score) {}

    private record AddressParts(String suburb, String state, String postcode) {}

    private record AreaContext(String area, PlanDraftResponse.Place previousStop) {}
}
