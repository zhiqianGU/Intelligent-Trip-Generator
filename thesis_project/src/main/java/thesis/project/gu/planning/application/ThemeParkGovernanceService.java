package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.routing.domain.StopCoordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ThemeParkGovernanceService {
    private static final Logger log = LoggerFactory.getLogger(ThemeParkGovernanceService.class);
    private static final double THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS = 150_000D;
    private static final int THEME_PARK_AFTERNOON_CONTINUATION_MINUTES = 60;
    private static final int THEME_PARK_CONTINUATION_MAX_EXTENSION_MINUTES = 150;
    private static final int THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES = 120;

    private final GooglePlacesClient googlePlacesClient;
    private final PlaceHeuristicService placeHeuristicService;

    public ThemeParkGovernanceService(
            GooglePlacesClient googlePlacesClient,
            PlaceHeuristicService placeHeuristicService
    ) {
        this.googlePlacesClient = googlePlacesClient;
        this.placeHeuristicService = placeHeuristicService;
    }

    public PlanDraftResponse verifyStopsWithPlaces(PlanDraftResponse draft) {
        PlanDraft verified = verifyStopsWithPlaces(PlanDraft.fromResponse(draft));
        return verified == null ? null : verified.toResponse();
    }

    public PlanDraft verifyStopsWithPlaces(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || !googlePlacesClient.isEnabled()) {
            return draft;
        }
        boolean changed = false;
        int themeParkStopCount = 0;
        int replacedCount = 0;
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            List<PlanDraft.Place> updatedStops = new ArrayList<>();
            for (PlanDraft.Place stop : stops) {
                if (!isThemeParkLikeStop(stop)) {
                    updatedStops.add(stop);
                    continue;
                }
                themeParkStopCount++;
                GooglePlacesClient.PlaceCandidate candidate = resolveThemeParkWithPlaces(stop);
                if (candidate == null) {
                    updatedStops.add(stop);
                    continue;
                }
                updatedStops.add(copyThemeParkWithCandidate(stop, candidate));
                changed = true;
                replacedCount++;
            }
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(),
                    day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        if (themeParkStopCount > 0) {
            log.info("Theme park Places verification completed: city={} stops={} replaced={}",
                    draft.city(), themeParkStopCount, replacedCount);
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    public List<String> validateLocation(String requestedCity, int dayIndex, int stopIndex, PlanDraftResponse.Place stop) {
        return validateLocation(requestedCity, dayIndex, stopIndex, PlanDraft.Place.fromResponse(stop));
    }

    public List<String> validateLocation(String requestedCity, int dayIndex, int stopIndex, PlanDraft.Place stop) {
        if (!isThemeParkLikeStop(stop)) {
            return List.of();
        }
        StopCoordinate cityCenter = cityCenterCoordinate(requestedCity);
        StopCoordinate stopCoordinate = coordinateOf(stop);
        if (cityCenter == null || stopCoordinate == null) {
            return List.of();
        }
        double distanceMeters = haversineMeters(cityCenter.lat(), cityCenter.lon(), stopCoordinate.lat(), stopCoordinate.lon());
        if (distanceMeters > THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS) {
            return List.of("day-" + dayIndex + "-stop-" + stopIndex + "-theme-park-cross-city");
        }
        return List.of();
    }

    public PlanDraftResponse pruneOutOfRangeStops(PlanDraftResponse draft) {
        PlanDraft pruned = pruneOutOfRangeStops(PlanDraft.fromResponse(draft));
        return pruned == null ? null : pruned.toResponse();
    }

    public PlanDraft pruneOutOfRangeStops(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        StopCoordinate cityCenter = cityCenterCoordinate(draft.city());
        if (cityCenter == null) {
            return draft;
        }
        boolean changed = false;
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            List<PlanDraft.Place> keptStops = new ArrayList<>();
            for (PlanDraft.Place stop : stops) {
                if (isOutOfRangeThemeParkStop(cityCenter, stop)) {
                    log.info("Pruned out-of-range theme park stop city={} day={} stop={}",
                            draft.city(), day.dayIndex(), stop.name());
                    changed = true;
                    continue;
                }
                keptStops.add(stop);
            }
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), keptStops, day.theme(),
                    day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    public PlanDraftResponse pruneDayTrips(PlanDraftResponse draft) {
        PlanDraft pruned = pruneDayTrips(PlanDraft.fromResponse(draft));
        return pruned == null ? null : pruned.toResponse();
    }

    public PlanDraft pruneDayTrips(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            PlanDraft.Place themePark = stops.stream().filter(this::isThemeParkLikeStop).findFirst().orElse(null);
            if (themePark == null) {
                updatedDays.add(day);
                continue;
            }
            int keptSameClusterExtra = 0;
            List<PlanDraft.Place> keptStops = new ArrayList<>();
            for (PlanDraft.Place stop : stops) {
                if (stop == themePark) {
                    keptStops.add(stop);
                    continue;
                }
                if (hasMealSlot(stop, "lunch")) {
                    if (isThemeParkClusterMeal(themePark, stop)) {
                        keptStops.add(stop);
                        continue;
                    }
                    changed = true;
                    continue;
                }
                if (isStrictMealStop(stop)) {
                    keptStops.add(stop);
                    continue;
                }
                if (stops.indexOf(stop) < stops.indexOf(themePark)) {
                    changed = true;
                    continue;
                }
                if (isSameThemeParkCluster(themePark, stop) && keptSameClusterExtra < 1) {
                    keptStops.add(stop);
                    keptSameClusterExtra++;
                    continue;
                }
                changed = true;
            }
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), keptStops, day.theme(),
                    day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    public PlanDraftResponse expandDiningBreaks(PlanDraftResponse draft) {
        PlanDraft expanded = expandDiningBreaks(PlanDraft.fromResponse(draft));
        return expanded == null ? null : expanded.toResponse();
    }

    public PlanDraft expandDiningBreaks(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day.stops() == null ? List.of() : day.stops();
            PlanDraft.Place themePark = stops.stream().filter(this::isThemeParkLikeStop).findFirst().orElse(null);
            if (themePark == null || !isThemeParkSplitEligible(draft.pace())) {
                updatedDays.add(day);
                continue;
            }
            int lunchIndex = firstMealIndex(stops, "lunch");
            int themeParkIndex = stops.indexOf(themePark);
            if (lunchIndex <= themeParkIndex || lunchIndex < 0 || hasThemeParkAfterIndex(stops, lunchIndex)) {
                updatedDays.add(day);
                continue;
            }
            List<PlanDraft.Place> expandedStops = new ArrayList<>(stops);
            expandedStops.set(themeParkIndex, morningThemeParkStop(themePark));
            lunchIndex = firstMealIndex(expandedStops, "lunch");
            expandedStops.set(lunchIndex, themeParkLunchStop(themePark));
            expandedStops.add(lunchIndex + 1, themeParkContinuationStop(themePark));
            changed = true;
            updatedDays.add(new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), expandedStops, day.theme(),
                    day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note()));
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    public boolean isThemeParkLikeStop(PlanDraftResponse.Place stop) {
        return isThemeParkLikeStop(PlanDraft.Place.fromResponse(stop));
    }

    public boolean isThemeParkLikeStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String cat = normalizeSlot(stop.category());
        if ("theme_park".equals(cat)) {
            return true;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        return name.contains("disneyland")
                || name.contains("disney world")
                || name.contains("universal studios")
                || name.contains("theme park")
                || name.contains("water park");
    }

    public boolean isSameThemeParkCluster(PlanDraftResponse.Place themePark, PlanDraftResponse.Place stop) {
        return isSameThemeParkCluster(PlanDraft.Place.fromResponse(themePark), PlanDraft.Place.fromResponse(stop));
    }

    public boolean isSameThemeParkCluster(PlanDraft.Place themePark, PlanDraft.Place stop) {
        if (themePark == null || stop == null) {
            return false;
        }
        if (themePark.latitude() != null && themePark.longitude() != null && stop.latitude() != null && stop.longitude() != null) {
            double distanceMeters = haversineMeters(themePark.latitude(), themePark.longitude(), stop.latitude(), stop.longitude());
            return distanceMeters <= 2000;
        }
        String tpArea = themeParkAnchorArea(themePark);
        String stArea = themeParkAnchorArea(stop);
        return tpArea != null && tpArea.equals(stArea);
    }

    public boolean isContinuationStop(PlanDraftResponse.Place stop) {
        return isContinuationStop(PlanDraft.Place.fromResponse(stop));
    }

    public boolean isContinuationStop(PlanDraft.Place stop) {
        if (!isThemeParkLikeStop(stop) || !"afternoon".equals(normalizeSlot(stop.timeSlot()))) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        return name.contains("(afternoon)");
    }

    public boolean shouldExtendContinuationBeforeDinner(PlanDraftResponse.Place previous, PlanDraftResponse.Place current, int previousEnd, int currentStart) {
        return shouldExtendContinuationBeforeDinner(PlanDraft.Place.fromResponse(previous), PlanDraft.Place.fromResponse(current), previousEnd, currentStart);
    }

    public boolean shouldExtendContinuationBeforeDinner(PlanDraft.Place previous, PlanDraft.Place current, int previousEnd, int currentStart) {
        if (!isContinuationStop(previous) || !hasMealSlot(current, "dinner")) {
            return false;
        }
        if (previousEnd < 0 || currentStart < 0 || currentStart <= previousEnd) {
            return false;
        }
        return currentStart - previousEnd > THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES;
    }

    public PlanDraftResponse.Place extendContinuationBeforeDinner(PlanDraftResponse.Place previous, int dinnerStart) {
        PlanDraft.Place extended = extendContinuationBeforeDinner(PlanDraft.Place.fromResponse(previous), dinnerStart);
        return extended == null ? null : extended.toResponse();
    }

    public PlanDraft.Place extendContinuationBeforeDinner(PlanDraft.Place previous, int dinnerStart) {
        int previousStart = parseTimeMinutes(previous.startTime());
        if (previousStart < 0 || dinnerStart <= previousStart) {
            return previous;
        }
        int currentStay = resolveStayMinutes(previous);
        int targetStay = Math.min(
                THEME_PARK_CONTINUATION_MAX_EXTENSION_MINUTES,
                Math.max(currentStay, dinnerStart - THEME_PARK_CONTINUATION_TO_DINNER_TARGET_GAP_MINUTES - previousStart)
        );
        int targetEnd = Math.min(dinnerStart - 20, previousStart + targetStay);
        if (targetEnd <= previousStart || targetEnd <= parseTimeMinutes(previous.endTime())) {
            return previous;
        }
        return copyPlaceWithTimes(previous, formatMinutes(previousStart), formatMinutes(targetEnd), targetEnd - previousStart);
    }

    public String sanitizeCopy(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = value
                .replaceAll("(?i)\\bshuttles\\b", "transport options")
                .replaceAll("(?i)\\bshuttle\\b", "transport")
                .replaceAll("(?i)\\btimed entry\\b", "entry requirements")
                .replaceAll("(?i)\\btimed access\\b", "entry requirements")
                .replaceAll("(?i)\\bride access\\b", "attraction access")
                .replaceAll("(?i)\\bride schedules\\b", "current operating details")
                .replaceAll("(?i)\\bride schedule\\b", "current operating details")
                .replaceAll("(?i)\\bshow schedules\\b", "current operating details")
                .replaceAll("(?i)\\bshow schedule\\b", "current operating details")
                .replaceAll("(?i)\\bshows\\b", "activities")
                .replaceAll("(?i)\\bshow\\b", "activity")
                .trim();
        String sentenceSafe = removeRiskyNarrativeSentences(sanitized);
        return sentenceSafe == null || sentenceSafe.isBlank() ? sanitized : sentenceSafe;
    }

    private GooglePlacesClient.PlaceCandidate resolveThemeParkWithPlaces(PlanDraft.Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || !googlePlacesClient.isEnabled()) {
            return null;
        }
        for (String query : themeParkPlaceSearchQueries(stop)) {
            GooglePlacesClient.PlaceCandidate candidate = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(place -> Double.isFinite(place.lat()) && Double.isFinite(place.lng()))
                    .filter(place -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
                    .map(place -> new RankedPlaceCoordinate(place, scoreThemeParkCandidate(stop, place)))
                    .filter(this::isAcceptableThemeParkCandidate)
                    .max(java.util.Comparator.comparingInt(RankedPlaceCoordinate::score))
                    .map(RankedPlaceCoordinate::candidate)
                    .orElse(null);
            if (candidate != null) {
                return candidate;
            }
        }
        for (String query : genericThemeParkPlaceSearchQueries(stop)) {
            GooglePlacesClient.PlaceCandidate candidate = googlePlacesClient.searchText(query, stop.city()).stream()
                    .filter(place -> Double.isFinite(place.lat()) && Double.isFinite(place.lng()))
                    .filter(place -> placeHeuristicService.isCoordinatePlausibleForCity(new StopCoordinate(place.lat(), place.lng()), stop.city()))
                    .map(place -> new RankedPlaceCoordinate(place, scoreGenericThemeParkCandidate(place)))
                    .filter(this::isAcceptableGenericThemeParkCandidate)
                    .max(java.util.Comparator.comparingInt(RankedPlaceCoordinate::score))
                    .map(RankedPlaceCoordinate::candidate)
                    .orElse(null);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isAcceptableThemeParkCandidate(RankedPlaceCoordinate ranked) {
        if (ranked == null || ranked.candidate() == null || ranked.score() < 140) {
            return false;
        }
        if (!hasStrictThemeParkPlaceType(ranked.candidate())) {
            return false;
        }
        String status = ranked.candidate().businessStatus() == null ? "" : ranked.candidate().businessStatus().toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private boolean isAcceptableGenericThemeParkCandidate(RankedPlaceCoordinate ranked) {
        if (ranked == null || ranked.candidate() == null || ranked.score() < 180) {
            return false;
        }
        if (!hasStrictThemeParkPlaceType(ranked.candidate())) {
            return false;
        }
        String status = ranked.candidate().businessStatus() == null ? "" : ranked.candidate().businessStatus().toUpperCase(Locale.ROOT);
        return !status.contains("CLOSED");
    }

    private boolean hasStrictThemeParkPlaceType(GooglePlacesClient.PlaceCandidate candidate) {
        if (candidate == null || candidate.types() == null) {
            return false;
        }
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        return types.contains("amusement_park") || types.contains("theme_park") || types.contains("water_park");
    }

    private int scoreThemeParkCandidate(PlanDraft.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String expectedName = placeHeuristicService.normalizeSearchText(stop.name());
        String expectedAddress = placeHeuristicService.normalizeSearchText(stop.addressLine());
        String candidateName = placeHeuristicService.normalizeSearchText(candidate.name());
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        int score = placeHeuristicService.commonSignificantTokenCount(expectedName, candidateText) * 80;
        score += placeHeuristicService.commonSignificantTokenCount(expectedAddress, candidateText) * 20;
        if (!expectedName.isBlank() && !candidateName.isBlank() && (candidateName.contains(expectedName) || expectedName.contains(candidateName))) {
            score += 120;
        }
        if (types.contains("amusement_park") || types.contains("theme_park") || types.contains("water_park")) {
            score += 160;
        }
        if (types.contains("tourist_attraction")) {
            score += 60;
        }
        if (types.contains("point_of_interest") || types.contains("establishment")) {
            score += 20;
        }
        if (types.contains("restaurant") || types.contains("lodging") || types.contains("shopping_mall")) {
            score -= 120;
        }
        return score;
    }

    private int scoreGenericThemeParkCandidate(GooglePlacesClient.PlaceCandidate candidate) {
        String candidateText = placeHeuristicService.normalizeSearchText(candidate.name() + " " + candidate.formattedAddress() + " " + String.join(" ", candidate.types()));
        String types = String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (types.contains("amusement_park") || types.contains("theme_park") || types.contains("water_park")) {
            score += 220;
        }
        if (types.contains("tourist_attraction")) {
            score += 50;
        }
        if (candidateText.contains("theme park") || candidateText.contains("amusement park") || candidateText.contains("water park")
                || candidateText.contains("luna park") || candidateText.contains("raging waters")) {
            score += 80;
        }
        if (types.contains("restaurant") || types.contains("lodging") || types.contains("shopping_mall")) {
            score -= 160;
        }
        return score;
    }

    private PlanDraft.Place copyThemeParkWithCandidate(PlanDraft.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String addressLine = candidate.formattedAddress() == null || candidate.formattedAddress().isBlank() ? stop.addressLine() : candidate.formattedAddress();
        String name = candidate.name() == null || candidate.name().isBlank() ? stop.name() : candidate.name();
        String url = candidate.googleMapsUri() == null || candidate.googleMapsUri().isBlank() ? stop.url() : candidate.googleMapsUri();
        ParsedAddress parsedAddress = parseAustralianAddress(candidate.formattedAddress(), stop);
        String suburb = parsedAddress.suburb().isBlank() ? stop.suburb() : parsedAddress.suburb();
        String state = parsedAddress.state().isBlank() ? stop.state() : parsedAddress.state();
        String postcode = parsedAddress.postcode().isBlank() ? stop.postcode() : parsedAddress.postcode();
        String country = parsedAddress.country().isBlank() ? stop.country() : parsedAddress.country();
        String themeParkAddressLine = parsedAddress.addressLine().isBlank() ? addressLine : parsedAddress.addressLine();
        String preferredArea = suburb == null || suburb.isBlank() ? stop.preferredArea() : suburb;
        return new PlanDraft.Place(name, themeParkAddressLine, suburb, stop.city(), state, postcode, country, "theme_park",
                stop.stayMinutes(), stop.timeSlot(), stop.startTime(), stop.endTime(), stop.mealType(), preferredArea,
                stop.cuisine(), stop.vibe(), stop.budgetLevel(), sanitizeThemeParkReason(stop), sanitizeThemeParkTip(),
                candidate.websiteUri(), candidate.googleMapsUri(), candidate.businessStatus(), url,
                Double.isNaN(candidate.lat()) ? stop.latitude() : candidate.lat(),
                Double.isNaN(candidate.lng()) ? stop.longitude() : candidate.lng());
    }

    private List<String> themeParkPlaceSearchQueries(PlanDraft.Place stop) {
        List<String> queries = new ArrayList<>();
        if (stop == null) {
            return queries;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        String coreName = themeParkCorePoiName(name);
        String address = stop.addressLine() == null ? "" : stop.addressLine().trim();
        String suburb = stop.suburb() == null ? "" : stop.suburb().trim();
        String city = stop.city() == null ? "" : stop.city().trim();
        if (!coreName.isBlank() && !address.isBlank()) addUnique(queries, coreName + ", " + address);
        if (!name.isBlank() && !address.isBlank() && !name.equalsIgnoreCase(coreName)) addUnique(queries, name + ", " + address);
        if (!coreName.isBlank() && !suburb.isBlank()) addUnique(queries, coreName + ", " + suburb);
        if (!name.isBlank() && !suburb.isBlank()) addUnique(queries, name + ", " + suburb);
        if (!coreName.isBlank() && !city.isBlank()) addUnique(queries, coreName + ", " + city);
        if (!name.isBlank() && !city.isBlank()) addUnique(queries, name + ", " + city);
        if (!coreName.isBlank()) addUnique(queries, coreName);
        if (!name.isBlank()) addUnique(queries, name);
        return queries;
    }

    private List<String> genericThemeParkPlaceSearchQueries(PlanDraft.Place stop) {
        List<String> queries = new ArrayList<>();
        String city = stop == null || stop.city() == null ? "" : stop.city().trim();
        if (!city.isBlank()) {
            addUnique(queries, "theme park");
            addUnique(queries, "amusement park");
            addUnique(queries, "water park");
            addUnique(queries, "family amusement park");
        }
        return queries;
    }

    private boolean isThemeParkClusterMeal(PlanDraft.Place themePark, PlanDraft.Place stop) {
        if (themePark == null || stop == null || !hasMealSlot(stop, "lunch")) {
            return false;
        }
        String stopName = nullToEmpty(stop.name()).toLowerCase(Locale.ROOT);
        String themeParkName = nullToEmpty(themePark.name()).toLowerCase(Locale.ROOT);
        if (!themeParkName.isBlank() && stopName.contains(themeParkName)) {
            return true;
        }
        String stopMapsUri = nullToEmpty(stop.googleMapsUri());
        String themeMapsUri = nullToEmpty(themePark.googleMapsUri());
        if (!stopMapsUri.isBlank() && stopMapsUri.equals(themeMapsUri)) {
            return true;
        }
        return isSameThemeParkCluster(themePark, stop);
    }

    private boolean isOutOfRangeThemeParkStop(StopCoordinate cityCenter, PlanDraft.Place stop) {
        if (!isThemeParkLikeStop(stop)) {
            return false;
        }
        StopCoordinate stopCoordinate = coordinateOf(stop);
        if (cityCenter == null || stopCoordinate == null) {
            return false;
        }
        return haversineMeters(cityCenter.lat(), cityCenter.lon(), stopCoordinate.lat(), stopCoordinate.lon()) > THEME_PARK_MAX_DAY_TRIP_DISTANCE_METERS;
    }

    private boolean isThemeParkSplitEligible(String pace) {
        String p = normalizeSlot(pace);
        return "relaxed".equals(p) || "normal".equals(p);
    }

    private int firstMealIndex(List<PlanDraft.Place> stops, String mealType) {
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), mealType)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasThemeParkAfterIndex(List<PlanDraft.Place> stops, int index) {
        for (int i = index + 1; i < stops.size(); i++) {
            if (isThemeParkLikeStop(stops.get(i))) {
                return true;
            }
        }
        return false;
    }

    private PlanDraft.Place morningThemeParkStop(PlanDraft.Place themePark) {
        return new PlanDraft.Place(themePark.name() + " (Morning)", themePark.addressLine(), themePark.suburb(), themePark.city(),
                themePark.state(), themePark.postcode(), themePark.country(), themePark.category(), 180, "morning",
                themePark.startTime(), "12:00", null, themePark.preferredArea(), null, null, null, themePark.reason(),
                themePark.tip(), themePark.websiteUri(), themePark.googleMapsUri(), themePark.businessStatus(),
                themePark.url(), themePark.latitude(), themePark.longitude());
    }

    private PlanDraft.Place themeParkLunchStop(PlanDraft.Place themePark) {
        String parkName = nullToEmpty(themePark.name()).isBlank() ? "Theme Park" : themePark.name();
        return new PlanDraft.Place(parkName + " Internal Dining Break", themePark.addressLine(), themePark.suburb(), themePark.city(),
                themePark.state(), themePark.postcode(), themePark.country(), "dining", 60, "lunch", "12:00", "13:00",
                "lunch", themePark.preferredArea(), "theme park dining", "casual", "midrange",
                "This is a controlled in-park dining break, not a separate restaurant recommendation.",
                "Choose an available in-park or immediately adjacent option on the day.", null, null, null, null,
                themePark.latitude(), themePark.longitude());
    }

    private PlanDraft.Place themeParkContinuationStop(PlanDraft.Place themePark) {
        return new PlanDraft.Place(themePark.name() + " (Afternoon)", themePark.addressLine(), themePark.suburb(), themePark.city(),
                themePark.state(), themePark.postcode(), themePark.country(), themePark.category(),
                THEME_PARK_AFTERNOON_CONTINUATION_MINUTES, "afternoon", "13:00",
                formatMinutes(13 * 60 + THEME_PARK_AFTERNOON_CONTINUATION_MINUTES), null, themePark.preferredArea(),
                null, null, null, "Continue exploring the park at a flexible pace.", sanitizeCopy(themePark.tip()),
                themePark.websiteUri(), themePark.googleMapsUri(), themePark.businessStatus(), themePark.url(),
                themePark.latitude(), themePark.longitude());
    }

    private String sanitizeThemeParkReason(PlanDraft.Place stop) {
        String area = displayArea(stop);
        String name = stop == null || stop.name() == null || stop.name().isBlank() ? "This theme park" : stop.name();
        return name + " works as the main theme park focus for the day around " + area + ".";
    }

    private String sanitizeThemeParkTip() {
        return "Check current opening hours and ticket details before committing to the day.";
    }

    private String themeParkCorePoiName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim();
        String[] parts = cleaned.split("\\s+(?:-|\\||:)\\s*");
        return parts.length == 0 ? cleaned : parts[0].trim();
    }

    private String themeParkAnchorArea(PlanDraft.Place themePark) {
        if (themePark == null) {
            return null;
        }
        String area = normalizeSlot(themePark.preferredArea());
        if (area.isEmpty()) {
            area = normalizeSlot(themePark.suburb());
        }
        return area.isEmpty() ? null : area;
    }

    private ParsedAddress parseAustralianAddress(String formattedAddress, PlanDraft.Place fallback) {
        String address = formattedAddress == null ? "" : formattedAddress.trim();
        String fallbackAddressLine = fallback == null ? "" : nullToEmpty(fallback.addressLine()).trim();
        String fallbackSuburb = fallback == null ? "" : nullToEmpty(fallback.suburb()).trim();
        String fallbackState = fallback == null ? "" : nullToEmpty(fallback.state()).trim();
        String fallbackPostcode = fallback == null ? "" : nullToEmpty(fallback.postcode()).trim();
        String fallbackCountry = fallback == null ? "" : nullToEmpty(fallback.country()).trim();
        if (address.isBlank()) {
            return new ParsedAddress(fallbackAddressLine, fallbackSuburb, fallbackState, fallbackPostcode, fallbackCountry);
        }
        String[] parts = address.split(",");
        String addressLine = parts.length > 0 ? parts[0].trim() : address;
        String suburb = "";
        String state = "";
        String postcode = "";
        String country = parseCountryFromAddressParts(parts, fallbackCountry);
        Pattern statePostcodePattern = Pattern.compile("\\b([A-Z]{2,3})\\s+(\\d{4})\\b");
        for (String part : parts) {
            String trimmed = part.trim();
            Matcher matcher = statePostcodePattern.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            state = matcher.group(1);
            postcode = matcher.group(2);
            String beforeState = trimmed.substring(0, matcher.start()).trim();
            if (!beforeState.isBlank() && !looksLikeStreetAddress(beforeState)) {
                suburb = beforeState;
            }
        }
        return new ParsedAddress(addressLine.isBlank() ? fallbackAddressLine : addressLine,
                suburb.isBlank() ? fallbackSuburb : suburb,
                state.isBlank() ? fallbackState : state,
                postcode.isBlank() ? fallbackPostcode : postcode,
                country.isBlank() ? fallbackCountry : country);
    }

    private boolean looksLikeStreetAddress(String value) {
        return value != null && !value.isBlank()
                && value.matches("(?i).*\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|way|terrace|tce|place|pl|promenade|highway|hwy|parade|pde|circuit|crt)\\b.*");
    }

    private String parseCountryFromAddressParts(String[] parts, String fallbackCountry) {
        if (parts != null) {
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i] == null ? "" : parts[i].trim();
                if (part.equalsIgnoreCase("Australia")) {
                    return "Australia";
                }
            }
        }
        return fallbackCountry == null || fallbackCountry.isBlank() ? "Australia" : fallbackCountry;
    }

    private StopCoordinate cityCenterCoordinate(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        return switch (city.trim().toLowerCase(Locale.ROOT)) {
            case "brisbane" -> new StopCoordinate(-27.4705, 153.0260);
            case "sydney" -> new StopCoordinate(-33.8688, 151.2093);
            case "melbourne" -> new StopCoordinate(-37.8136, 144.9631);
            default -> null;
        };
    }

    private StopCoordinate coordinateOf(PlanDraft.Place stop) {
        if (stop == null || stop.latitude() == null || stop.longitude() == null) {
            return null;
        }
        return new StopCoordinate(stop.latitude(), stop.longitude());
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000D;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private boolean hasMealSlot(PlanDraft.Place stop, String slot) {
        String normalized = normalizeSlot(slot);
        return stop != null && (normalized.equals(normalizeSlot(stop.mealType())) || normalized.equals(normalizeSlot(stop.timeSlot())));
    }

    private boolean isStrictMealStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        String mealType = normalizeSlot(stop.mealType());
        String category = normalizeSlot(stop.category());
        return "lunch".equals(slot) || "dinner".equals(slot)
                || "lunch".equals(mealType) || "dinner".equals(mealType)
                || "restaurant".equals(category) || "cafe".equals(category) || "food".equals(category) || "dining".equals(category);
    }

    private String displayArea(PlanDraft.Place stop) {
        if (stop == null) {
            return "the area";
        }
        String area = stop.preferredArea();
        if (area == null || area.isBlank()) {
            area = stop.suburb();
        }
        return area == null || area.isBlank() ? "the area" : area;
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
    }

    private PlanDraft withDays(PlanDraft draft, List<PlanDraft.DayPlan> days) {
        return new PlanDraft(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                days == null ? List.of() : days,
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private int resolveStayMinutes(PlanDraft.Place stop) {
        if (stop == null || stop.stayMinutes() == null || stop.stayMinutes() <= 0) {
            return 60;
        }
        return stop.stayMinutes();
    }

    private PlanDraft.Place copyPlaceWithTimes(PlanDraft.Place s, String start, String end, int stay) {
        return new PlanDraft.Place(s.name(), s.addressLine(), s.suburb(), s.city(), s.state(), s.postcode(), s.country(),
                s.category(), stay, s.timeSlot(), start, end, s.mealType(), s.preferredArea(), s.cuisine(), s.vibe(),
                s.budgetLevel(), s.reason(), s.tip(), s.websiteUri(), s.googleMapsUri(), s.businessStatus(), s.url(),
                s.latitude(), s.longitude());
    }

    private int parseTimeMinutes(String value) {
        if (value == null) {
            return -1;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return -1;
        }
        String[] parts = text.split(":");
        if (parts.length != 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatMinutes(int minutes) {
        int bounded = Math.max(0, Math.min(23 * 60 + 59, minutes));
        return String.format(Locale.ROOT, "%02d:%02d", bounded / 60, bounded % 60);
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        boolean exists = values.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized));
        if (!exists) {
            values.add(normalized);
        }
    }

    private String removeRiskyNarrativeSentences(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        StringBuilder kept = new StringBuilder();
        for (String sentence : value.split("(?<=[.!?])\\s+|;\\s*")) {
            String trimmed = sentence.trim();
            if (trimmed.isBlank() || containsRiskyNarrativeClaim(trimmed)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append(' ');
            }
            kept.append(trimmed);
        }
        return kept.toString().trim();
    }

    private boolean containsRiskyNarrativeClaim(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.contains("priority access")
                || text.contains("timed entry")
                || text.contains("timed ticket")
                || text.contains("timed tickets")
                || text.contains("opening hours")
                || text.contains("open daily")
                || text.contains("last entry")
                || text.contains("opens at")
                || text.contains("closes at")
                || text.contains("book ahead")
                || text.contains("reserve ahead")
                || text.contains("guaranteed")
                || text.contains("shuttle")
                || text.contains("ride access")
                || text.contains("ride schedule")
                || text.contains("show schedule")
                || text.contains("wait time");
    }

    private record ParsedAddress(String addressLine, String suburb, String state, String postcode, String country) {}

    private record RankedPlaceCoordinate(GooglePlacesClient.PlaceCandidate candidate, int score) {}
}
