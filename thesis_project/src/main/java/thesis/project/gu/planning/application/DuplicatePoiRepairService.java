package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.infrastructure.external.google.GooglePlacesClient;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DuplicatePoiRepairService {
    private static final Logger log = LoggerFactory.getLogger(DuplicatePoiRepairService.class);

    private final GooglePlacesClient googlePlacesClient;
    private final PlaceHeuristicService placeHeuristicService;
    private final DaySkeletonService daySkeletonService;

    public DuplicatePoiRepairService(
            GooglePlacesClient googlePlacesClient,
            PlaceHeuristicService placeHeuristicService,
            DaySkeletonService daySkeletonService
    ) {
        this.googlePlacesClient = googlePlacesClient;
        this.placeHeuristicService = placeHeuristicService;
        this.daySkeletonService = daySkeletonService;
    }

    public PlanDraftResponse repairSameDayDuplicatePois(PlanDraftResponse draft) {
        PlanDraft repaired = repairSameDayDuplicatePois(PlanDraft.fromResponse(draft));
        return repaired == null ? null : repaired.toResponse();
    }

    public PlanDraft repairSameDayDuplicatePois(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        Set<String> retainedPoiNames = new java.util.LinkedHashSet<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null) {
                continue;
            }
            day.stops().forEach(stop -> registerRetainedPoiName(retainedPoiNames, stop));
        }

        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
            List<PlanDraft.Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            int index = 0;
            while (index < updatedStops.size()) {
                PlanDraft.Place stop = updatedStops.get(index);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    index++;
                    continue;
                }
                SeenPoiStop firstSeen = findSeenPoi(duplicateKeys, seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(stop)), seenStops);
                    index++;
                    continue;
                }
                if (canDropDuplicateStopSafely(updatedStops, stop, index, minNonMealStops)) {
                    updatedStops.remove(index);
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                PlanDraft.Place resolvedReplacement = resolveCrossDayDuplicateReplacementStop(
                        stop,
                        day.dayIndex(),
                        index + 1,
                        retainedPoiNames
                );
                updatedStops.set(index, resolvedReplacement);
                registerSeenPoiKeys(
                        crossDayDuplicatePoiKeys(resolvedReplacement),
                        new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(resolvedReplacement)),
                        seenStops
                );
                registerRetainedPoiName(retainedPoiNames, resolvedReplacement);
                dayChanged = true;
                changed = true;
                index++;
            }
            updatedDays.add(dayChanged
                    ? new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note())
                    : day);
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    public PlanDraftResponse repairCrossDayDuplicatePois(PlanDraftResponse draft) {
        PlanDraft repaired = repairCrossDayDuplicatePois(PlanDraft.fromResponse(draft));
        return repaired == null ? null : repaired.toResponse();
    }

    public PlanDraft repairCrossDayDuplicatePois(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        Map<String, SeenPoiStop> seenStops = new java.util.LinkedHashMap<>();
        Set<String> retainedPoiNames = new java.util.LinkedHashSet<>();
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<PlanDraft.Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            int index = 0;
            while (index < updatedStops.size()) {
                PlanDraft.Place stop = updatedStops.get(index);
                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                if (duplicateKeys.isEmpty()) {
                    registerRetainedPoiName(retainedPoiNames, stop);
                    index++;
                    continue;
                }
                SeenPoiStop firstSeen = findCrossDaySeenPoi(duplicateKeys, day.dayIndex(), seenStops);
                if (firstSeen == null) {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(stop)), seenStops);
                    registerRetainedPoiName(retainedPoiNames, stop);
                    index++;
                    continue;
                }
                if (canDropDuplicateStopSafely(updatedStops, stop, index, minNonMealStops)) {
                    updatedStops.remove(index);
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                PlanDraft.Place resolvedReplacement = resolveCrossDayDuplicateReplacementStop(
                        stop,
                        day.dayIndex(),
                        index + 1,
                        retainedPoiNames
                );
                updatedStops.set(index, resolvedReplacement);
                registerSeenPoiKeys(
                        crossDayDuplicatePoiKeys(resolvedReplacement),
                        new SeenPoiStop(day.dayIndex(), index + 1, safeStopName(resolvedReplacement)),
                        seenStops
                );
                registerRetainedPoiName(retainedPoiNames, resolvedReplacement);
                dayChanged = true;
                changed = true;
                index++;
            }
            updatedDays.add(dayChanged
                    ? new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note())
                    : day);
        }
        return changed ? withDays(draft, updatedDays) : draft;
    }

    private PlanDraft.Place resolveCrossDayDuplicateReplacementStop(
            PlanDraft.Place stop,
            int dayIndex,
            int stopIndex,
            Set<String> retainedPoiNames
    ) {
        PlanDraft.Place realCandidate = tryResolveRealAreaLevelDuplicateReplacement(stop, retainedPoiNames);
        if (realCandidate != null) {
            registerRetainedPoiName(retainedPoiNames, realCandidate);
            return realCandidate;
        }
        return buildCrossDayDuplicateFallbackStop(stop, dayIndex, stopIndex, retainedPoiNames);
    }

    private PlanDraft.Place tryResolveRealAreaLevelDuplicateReplacement(PlanDraft.Place stop, Set<String> retainedPoiNames) {
        if (stop == null || stop.city() == null || stop.city().isBlank() || !googlePlacesClient.isEnabled()) {
            return null;
        }
        for (String query : crossDayDuplicateReplacementQueries(stop)) {
            List<GooglePlacesClient.PlaceCandidate> candidates;
            try {
                candidates = googlePlacesClient.searchText(query, stop.city());
            } catch (Exception e) {
                log.debug("Duplicate replacement candidate search skipped query={} city={}", query, stop.city(), e);
                continue;
            }
            GooglePlacesClient.PlaceCandidate best = candidates.stream()
                    .filter(candidate -> isUsableCrossDayDuplicateCandidate(stop, candidate, retainedPoiNames))
                    .max(java.util.Comparator.comparingInt(candidate -> scoreCrossDayDuplicateCandidate(stop, candidate)))
                    .orElse(null);
            if (best != null) {
                return copyCrossDayDuplicateWithCandidate(stop, best);
            }
        }
        return null;
    }

    private List<String> crossDayDuplicateReplacementQueries(PlanDraft.Place stop) {
        List<String> queries = new ArrayList<>();
        if (stop == null) {
            return queries;
        }
        String area = displayArea(stop);
        String category = normalizeSlot(stop.category());
        switch (category) {
            case "museum", "gallery", "cultural" -> {
                addUnique(queries, "museum near " + area);
                addUnique(queries, "art gallery near " + area);
            }
            case "park", "nature", "outdoor" -> {
                addUnique(queries, "park near " + area);
                addUnique(queries, "botanic garden near " + area);
            }
            case "lookout", "viewpoint", "landmark" -> {
                addUnique(queries, "landmark near " + area);
                addUnique(queries, "lookout near " + area);
            }
            case "market", "shop", "shopping" -> {
                addUnique(queries, "market near " + area);
                addUnique(queries, "cultural centre near " + area);
            }
            default -> {
                addUnique(queries, "tourist attraction near " + area);
                addUnique(queries, "landmark near " + area);
            }
        }
        addUnique(queries, "tourist attraction near " + area);
        return queries;
    }

    private boolean isUsableCrossDayDuplicateCandidate(
            PlanDraft.Place originalStop,
            GooglePlacesClient.PlaceCandidate candidate,
            Set<String> retainedPoiNames
    ) {
        if (candidate == null || candidate.name() == null || candidate.name().isBlank()) {
            return false;
        }
        String businessStatus = normalizeSlot(candidate.businessStatus());
        if (!businessStatus.isBlank() && !"operational".equals(businessStatus)) {
            return false;
        }
        String candidateNameKey = normalizedPoiIdentity(candidate.name());
        if (candidateNameKey.isBlank()) {
            return false;
        }
        if (candidateNameKey.equals(normalizedPoiIdentity(originalStop.name()))) {
            return false;
        }
        boolean clashesWithRetained = retainedPoiNames.stream()
                .map(this::normalizedPoiIdentity)
                .anyMatch(existing -> existing.equals(candidateNameKey));
        if (clashesWithRetained) {
            return false;
        }
        String types = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("restaurant")
                || types.contains("food")
                || types.contains("cafe")
                || types.contains("lodging")
                || types.contains("hotel")
                || types.contains("shopping_mall")
                || types.contains("store")
                || types.contains("supermarket")
                || types.contains("transit_station")
                || types.contains("bus_station")
                || types.contains("train_station")) {
            return false;
        }
        return types.contains("museum")
                || types.contains("art_gallery")
                || types.contains("park")
                || types.contains("tourist_attraction")
                || types.contains("point_of_interest")
                || types.contains("landmark")
                || types.contains("cultural")
                || types.contains("botanical_garden");
    }

    private int scoreCrossDayDuplicateCandidate(PlanDraft.Place originalStop, GooglePlacesClient.PlaceCandidate candidate) {
        String originalCategory = normalizeSlot(originalStop.category());
        String candidateTypes = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        String candidateText = placeHeuristicService.normalizeSearchText(
                candidate.name() + " " + candidate.formattedAddress() + " " + candidateTypes
        );
        int score = placeHeuristicService.commonSignificantTokenCount(displayArea(originalStop), candidateText) * 20;
        if ("museum".equals(originalCategory) || "gallery".equals(originalCategory) || "cultural".equals(originalCategory)) {
            if (candidateTypes.contains("museum") || candidateTypes.contains("art_gallery")) score += 120;
        } else if ("park".equals(originalCategory) || "nature".equals(originalCategory) || "outdoor".equals(originalCategory)) {
            if (candidateTypes.contains("park") || candidateTypes.contains("botanical_garden")) score += 120;
        } else if ("lookout".equals(originalCategory) || "viewpoint".equals(originalCategory) || "landmark".equals(originalCategory)) {
            if (candidateTypes.contains("landmark") || candidateTypes.contains("tourist_attraction")) score += 100;
        } else {
            if (candidateTypes.contains("tourist_attraction") || candidateTypes.contains("point_of_interest")) score += 80;
        }
        if (candidateTypes.contains("point_of_interest")) score += 20;
        if (candidate.googleMapsUri() != null && !candidate.googleMapsUri().isBlank()) score += 10;
        return score;
    }

    private PlanDraft.Place copyCrossDayDuplicateWithCandidate(PlanDraft.Place stop, GooglePlacesClient.PlaceCandidate candidate) {
        String addressLine = candidate.formattedAddress() == null || candidate.formattedAddress().isBlank()
                ? stop.addressLine()
                : candidate.formattedAddress();
        ParsedAddress parsedAddress = parseAustralianAddress(candidate.formattedAddress(), stop);
        String suburb = parsedAddress.suburb().isBlank() ? stop.suburb() : parsedAddress.suburb();
        String state = parsedAddress.state().isBlank() ? stop.state() : parsedAddress.state();
        String postcode = parsedAddress.postcode().isBlank() ? stop.postcode() : parsedAddress.postcode();
        String country = parsedAddress.country().isBlank() ? stop.country() : parsedAddress.country();
        String normalizedAddressLine = parsedAddress.addressLine().isBlank() ? addressLine : parsedAddress.addressLine();
        String preferredArea = suburb == null || suburb.isBlank() ? stop.preferredArea() : suburb;
        String resolvedCategory = normalizeCrossDayDuplicateCandidateCategory(stop, candidate);
        String reason = candidate.name() + " keeps this " + normalizeSlot(stop.timeSlot()) + " block grounded around " + displayArea(stop) + " without repeating a previously used sight.";
        String tip = "Trim this stop first if transfers start compressing the rest of the day.";
        String url = candidate.googleMapsUri() == null || candidate.googleMapsUri().isBlank()
                ? stop.url()
                : candidate.googleMapsUri();
        return new PlanDraft.Place(
                candidate.name(),
                normalizedAddressLine,
                suburb,
                stop.city(),
                state,
                postcode,
                country,
                resolvedCategory,
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                preferredArea,
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                reason,
                tip,
                candidate.websiteUri(),
                candidate.googleMapsUri(),
                candidate.businessStatus(),
                url,
                Double.isNaN(candidate.lat()) ? stop.latitude() : candidate.lat(),
                Double.isNaN(candidate.lng()) ? stop.longitude() : candidate.lng()
        );
    }

    private String normalizeCrossDayDuplicateCandidateCategory(PlanDraft.Place originalStop, GooglePlacesClient.PlaceCandidate candidate) {
        String types = candidate.types() == null ? "" : String.join(" ", candidate.types()).toLowerCase(Locale.ROOT);
        if (types.contains("museum") || types.contains("art_gallery")) {
            return "museum";
        }
        if (types.contains("park") || types.contains("botanical_garden")) {
            return "park";
        }
        if (types.contains("landmark") || types.contains("tourist_attraction")) {
            return "attraction";
        }
        return normalizeSlot(originalStop.category()).isBlank() ? "attraction" : originalStop.category();
    }

    private PlanDraft.Place buildCrossDayDuplicateFallbackStop(PlanDraft.Place stop, int dayIndex, int stopIndex, Set<String> retainedPoiNames) {
        if (stop == null) {
            return null;
        }
        String area = displayArea(stop);
        String slot = normalizeSlot(stop.timeSlot());
        String theme = switch (normalizeSlot(stop.category())) {
            case "museum", "gallery", "cultural" -> "Heritage Stroll";
            case "park", "nature" -> "Garden Stroll";
            case "lookout", "viewpoint", "landmark" -> "Scenic Stroll";
            case "shop", "market" -> "Local Stroll";
            default -> "Neighborhood Stroll";
        };
        String slotLabel = switch (slot) {
            case "morning" -> "Morning ";
            case "afternoon" -> "Afternoon ";
            case "sunset" -> "Sunset ";
            case "evening", "dinner" -> "Evening ";
            default -> "";
        };
        String uniqueName = (area + " " + slotLabel + theme).replaceAll("\\s+", " ").trim();
        uniqueName = ensureUniqueFallbackPoiName(uniqueName, stop, retainedPoiNames, dayIndex, stopIndex);
        return new PlanDraft.Place(
                uniqueName,
                stop.addressLine(),
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                "walk",
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                stop.preferredArea(),
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                "Flexible nearby filler after duplicate removal.",
                "Trim this block first if timing gets tight.",
                null,
                null,
                null,
                null,
                stop.latitude(),
                stop.longitude()
        );
    }

    private void registerRetainedPoiName(Set<String> retainedPoiNames, PlanDraft.Place stop) {
        if (retainedPoiNames == null || stop == null || stop.name() == null || stop.name().isBlank()) {
            return;
        }
        String normalized = normalizedPoiIdentity(stop.name());
        if (!normalized.isBlank()) {
            retainedPoiNames.add(normalized);
        }
    }

    private String ensureUniqueFallbackPoiName(String candidate, PlanDraft.Place originalStop, Set<String> retainedPoiNames, int dayIndex, int stopIndex) {
        String uniqueName = candidate == null ? "" : candidate.trim();
        String normalizedCandidate = normalizedPoiIdentity(uniqueName);
        String normalizedOriginal = originalStop == null ? "" : normalizedPoiIdentity(originalStop.name());
        int suffix = 2;
        while (!normalizedCandidate.isBlank() && (retainedPoiNames.contains(normalizedCandidate) || normalizedCandidate.equals(normalizedOriginal))) {
            uniqueName = candidate.trim() + " Alt " + suffix;
            normalizedCandidate = normalizedPoiIdentity(uniqueName);
            suffix++;
        }
        if (!normalizedCandidate.isBlank()) {
            retainedPoiNames.add(normalizedCandidate);
        }
        return uniqueName;
    }

    private boolean canDropDuplicateStopSafely(List<PlanDraft.Place> stops, PlanDraft.Place stop, int stopIndex, int minNonMealStops) {
        if (wouldDropBelowMinNonMealStops(stops, stop, minNonMealStops)) {
            return false;
        }
        return !isOnlyNonMealStopInDayPhase(stops, stop, stopIndex);
    }

    private boolean wouldDropBelowMinNonMealStops(List<PlanDraft.Place> stops, PlanDraft.Place stop, int minNonMealStops) {
        if (stops == null || stop == null || isFoodStop(stop)) {
            return false;
        }
        int nonMealCount = 0;
        for (PlanDraft.Place candidate : stops) {
            if (!isFoodStop(candidate)) {
                nonMealCount++;
            }
        }
        return nonMealCount - 1 < minNonMealStops;
    }

    private boolean isOnlyNonMealStopInDayPhase(List<PlanDraft.Place> stops, PlanDraft.Place target, int targetIndex) {
        if (stops == null || stops.isEmpty() || target == null || isFoodStop(target)) {
            return false;
        }
        String phase = broadNonMealPhase(target);
        if (phase.isBlank()) {
            return false;
        }
        int samePhaseCount = 0;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place candidate = stops.get(i);
            if (isFoodStop(candidate) || !phase.equals(broadNonMealPhase(candidate))) {
                continue;
            }
            samePhaseCount++;
            if (samePhaseCount > 1) {
                return false;
            }
        }
        return samePhaseCount == 1;
    }

    private String broadNonMealPhase(PlanDraft.Place stop) {
        String slot = normalizeSlot(stop == null ? null : stop.timeSlot());
        return switch (slot) {
            case "morning" -> "morning";
            case "afternoon", "sunset" -> "afternoon";
            case "evening", "night" -> "evening";
            default -> "";
        };
    }

    private List<String> crossDayDuplicatePoiKeys(PlanDraft.Place stop) {
        if (stop == null || stop.name() == null || stop.name().isBlank() || stop.mealType() != null || isFoodStop(stop)) {
            return List.of();
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        String category = normalizeCoordinateCategory(stop);
        String city = normalizeSlot(stop.city());
        String mapRef = stableMapReference(stop.googleMapsUri());
        if (mapRef.isBlank()) {
            mapRef = stableMapReference(stop.url());
        }
        if (!mapRef.isBlank()) {
            keys.add("map|" + mapRef);
        }
        String normalizedName = normalizedPoiIdentity(stop.name());
        if (!normalizedName.isBlank() && normalizedName.length() >= 4) {
            keys.add("name|" + category + "|" + city + "|" + normalizedName);
        }
        String addressKey = duplicateAddressKey(stop);
        if (!addressKey.isBlank()) {
            keys.add("addr|" + category + "|" + city + "|" + addressKey);
        }
        String coordinateKey = duplicateCoordinateKey(stop);
        if (!coordinateKey.isBlank()) {
            keys.add("geo|" + category + "|" + city + "|" + coordinateKey);
        }
        return new ArrayList<>(keys);
    }

    private SeenPoiStop findCrossDaySeenPoi(List<String> duplicateKeys, int dayIndex, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStops == null || seenStops.isEmpty()) {
            return null;
        }
        for (String key : duplicateKeys) {
            SeenPoiStop seen = seenStops.get(key);
            if (seen != null && seen.dayIndex() != dayIndex) {
                return seen;
            }
        }
        return null;
    }

    private SeenPoiStop findSeenPoi(List<String> duplicateKeys, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStops == null || seenStops.isEmpty()) {
            return null;
        }
        for (String key : duplicateKeys) {
            SeenPoiStop seen = seenStops.get(key);
            if (seen != null) {
                return seen;
            }
        }
        return null;
    }

    private void registerSeenPoiKeys(List<String> duplicateKeys, SeenPoiStop seenStop, Map<String, SeenPoiStop> seenStops) {
        if (duplicateKeys == null || duplicateKeys.isEmpty() || seenStop == null || seenStops == null) {
            return;
        }
        for (String key : duplicateKeys) {
            if (key != null && !key.isBlank()) {
                seenStops.putIfAbsent(key, seenStop);
            }
        }
    }

    private String stableMapReference(String uri) {
        String value = uri == null ? "" : uri.trim();
        if (value.isBlank()) {
            return "";
        }
        Matcher cidMatcher = Pattern.compile("(?i)(?:cid|place_id)=([^&?#/]+)").matcher(value);
        if (cidMatcher.find()) {
            return normalizeSlot(cidMatcher.group(1));
        }
        String normalized = normalizeNameForNarrativeMatch(value);
        return normalized.length() >= 12 ? normalized : "";
    }

    private String duplicateAddressKey(PlanDraft.Place stop) {
        String address = normalizeNameForNarrativeMatch(String.join(" ",
                nullToEmpty(stop.addressLine()),
                nullToEmpty(stop.suburb()),
                nullToEmpty(stop.postcode())));
        return address.length() >= 10 ? address : "";
    }

    private String duplicateCoordinateKey(PlanDraft.Place stop) {
        if (stop.latitude() == null || stop.longitude() == null) {
            return "";
        }
        return String.format(Locale.ROOT, "%.4f,%.4f", stop.latitude(), stop.longitude());
    }

    private String normalizeCoordinateCategory(PlanDraft.Place stop) {
        String category = normalizeSlot(stop == null ? null : stop.category());
        return category.isBlank() ? "attraction" : category;
    }

    private String normalizedPoiIdentity(String value) {
        String source = duplicateNameSource(value);
        String normalized = normalizeNameForNarrativeMatch(placeHeuristicService.corePoiName(source));
        if (normalized.isBlank()) {
            return "";
        }
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            String clean = normalizeSlot(token);
            if (clean.length() < 2 || isLowSignalDuplicateToken(clean)) {
                continue;
            }
            tokens.add(clean);
        }
        if (tokens.size() < 2) {
            return normalized.length() >= 4 ? normalized : "";
        }
        return tokens.stream().sorted().collect(java.util.stream.Collectors.joining(" "));
    }

    private String duplicateNameSource(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\\(([^)]*)\\)").matcher(raw);
        StringBuilder parenthetical = new StringBuilder();
        while (matcher.find()) {
            String inside = matcher.group(1).trim();
            if (!inside.isBlank() && !isLikelyAcronymPhrase(inside)) {
                parenthetical.append(' ').append(inside);
            }
        }
        String outside = raw.replaceAll("\\([^)]*\\)", " ").trim();
        if (isLikelyAcronymPhrase(outside) && parenthetical.length() > 0) {
            return parenthetical.toString();
        }
        return (outside + " " + parenthetical).trim();
    }

    private boolean isLikelyAcronymPhrase(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank()) {
            return false;
        }
        String compact = candidate.replaceAll("[\\s&./-]+", "");
        return compact.length() >= 2 && compact.length() <= 8 && compact.matches("[A-Z0-9]+");
    }

    private boolean isLowSignalDuplicateToken(String token) {
        return switch (token) {
            case "the", "of", "and", "at", "in", "on", "for", "to", "a", "an",
                    "visit", "stop", "area", "precinct", "near", "nearby" -> true;
            default -> false;
        };
    }

    private boolean isFoodStop(PlanDraft.Place s) {
        if (s == null) {
            return false;
        }
        String cat = normalizeSlot(s.category());
        return "restaurant".equals(cat)
                || "cafe".equals(cat)
                || "food".equals(cat)
                || "dining".equals(cat);
    }

    private boolean hasMealSlot(PlanDraft.Place s, String slot) {
        return s != null && (slot.equals(normalizeSlot(s.mealType())) || slot.equals(normalizeSlot(s.timeSlot())));
    }

    private String normalizeSlot(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
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
                draft.copyPolishStatus()
        );
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String normalizeNameForNarrativeMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private String displayArea(PlanDraft.Place stop) {
        if (stop == null) {
            return "the area";
        }
        if (stop.suburb() != null && !stop.suburb().isBlank()) {
            return stop.suburb().trim();
        }
        if (stop.preferredArea() != null && !stop.preferredArea().isBlank()) {
            return stop.preferredArea().trim();
        }
        if (stop.city() != null && !stop.city().isBlank()) {
            return stop.city().trim();
        }
        return "the area";
    }

    private String safeStopName(PlanDraft.Place stop) {
        return stop == null || stop.name() == null || stop.name().isBlank()
                ? "unnamed stop"
                : stop.name().trim();
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
        return new ParsedAddress(
                addressLine.isBlank() ? fallbackAddressLine : addressLine,
                suburb.isBlank() ? fallbackSuburb : suburb,
                state.isBlank() ? fallbackState : state,
                postcode.isBlank() ? fallbackPostcode : postcode,
                country.isBlank() ? fallbackCountry : country
        );
    }

    private boolean looksLikeStreetAddress(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.matches("(?i).*\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|way|terrace|tce|place|pl|promenade|highway|hwy|parade|pde|circuit|crt)\\b.*");
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

    private int minNonMealStopsPerDay(String pace) {
        return daySkeletonService.nonMealRangeForPace(pace).min();
    }

    private void addUnique(List<String> list, String value) {
        if (list == null || value == null || value.isBlank() || list.contains(value)) {
            return;
        }
        list.add(value);
    }

    private record ParsedAddress(String addressLine, String suburb, String state, String postcode, String country) {}

    private record SeenPoiStop(int dayIndex, int stopIndex, String stopName) {}
}
