package thesis.project.gu.planning.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.heuristic.PlaceHeuristicService;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DraftValidationService {
    private static final Logger log = LoggerFactory.getLogger(DraftValidationService.class);
    private static final int CULTURAL_POI_LATEST_END_MINUTES = 17 * 60;

    private final DaySkeletonService daySkeletonService;
    private final PlaceHeuristicService placeHeuristicService;
    private final ThemeParkGovernanceService themeParkGovernanceService;

    public DraftValidationService(
            DaySkeletonService daySkeletonService,
            PlaceHeuristicService placeHeuristicService,
            ThemeParkGovernanceService themeParkGovernanceService
    ) {
        this.daySkeletonService = daySkeletonService;
        this.placeHeuristicService = placeHeuristicService;
        this.themeParkGovernanceService = themeParkGovernanceService;
    }

    public List<String> validateResponse(PlanDraftResponse draft) {
        return validate(PlanDraft.fromResponse(draft));
    }

    public List<String> validateResponse(PlanDraftResponse draft, CreatePlanReq req) {
        return validate(PlanDraft.fromResponse(draft), req);
    }

    public List<String> validateResponse(PlanDraftResponse draft, CreatePlanReq req, Map<Integer, Integer> effectiveMinByDay) {
        return validate(PlanDraft.fromResponse(draft), req, effectiveMinByDay);
    }

    public List<String> validate(PlanDraft draft) {
        return validate(draft, null, null);
    }

    public List<String> validate(PlanDraft draft, CreatePlanReq req) {
        return validate(draft, req, null);
    }

    public List<String> validate(PlanDraft draft, CreatePlanReq req, Map<Integer, Integer> effectiveMinByDay) {
        List<String> issues = new ArrayList<>();
        issues.addAll(validateRequestedDayCount(draft, req));
        issues.addAll(validateSelectedStyleCoverage(draft, req));
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            issues.add("plan-empty-days");
            return issues;
        }
        Map<String, SeenPoiStop> seenPoiStops = new java.util.LinkedHashMap<>();
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            List<PlanDraft.Place> stops = day == null || day.stops() == null ? List.of() : day.stops();
            int dayIndex = day == null ? 0 : day.dayIndex();
            if (stops.isEmpty()) {
                issues.add("day-" + dayIndex + "-empty-stops");
                continue;
            }
            if (stops.stream().noneMatch(stop -> hasVerifiedMealStop(stop, "lunch"))) {
                issues.add("day-" + dayIndex + "-missing-lunch");
            }
            if (stops.stream().noneMatch(stop -> hasVerifiedMealStop(stop, "dinner"))) {
                issues.add("day-" + dayIndex + "-missing-dinner");
            }
            Map<String, SeenPoiStop> sameDaySeen = new java.util.LinkedHashMap<>();
            int previousEnd = -1;
            for (int i = 0; i < stops.size(); i++) {
                PlanDraft.Place stop = stops.get(i);
                int start = parseTimeMinutes(stop == null ? null : stop.startTime());
                int end = parseTimeMinutes(stop == null ? null : stop.endTime());
                if (start >= 0 && end >= 0 && end <= start) {
                    issues.add("day-" + dayIndex + "-stop-" + (i + 1) + "-time-overlap");
                }
                if (previousEnd >= 0 && start >= 0 && start < previousEnd) {
                    issues.add("day-" + dayIndex + "-stop-" + (i + 1) + "-time-overlap");
                }
                previousEnd = Math.max(previousEnd, end);
                issues.addAll(validateTimeSensitiveStop(dayIndex, i + 1, stop, start, end));
                issues.addAll(validateThemeParkLocation(draft.city(), dayIndex, i + 1, stop));

                List<String> duplicateKeys = crossDayDuplicatePoiKeys(stop);
                SeenPoiStop sameDayDuplicate = findSeenPoi(duplicateKeys, sameDaySeen);
                if (sameDayDuplicate != null) {
                    issues.add("day-" + dayIndex + "-stop-" + (i + 1) + "-duplicate-poi-same-day");
                } else {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(dayIndex, i + 1, safeStopName(stop)), sameDaySeen);
                }
                SeenPoiStop crossDayDuplicate = findCrossDaySeenPoi(duplicateKeys, dayIndex, seenPoiStops);
                if (crossDayDuplicate != null) {
                    issues.add("day-" + dayIndex + "-stop-" + (i + 1) + "-duplicate-poi-across-days");
                } else {
                    registerSeenPoiKeys(duplicateKeys, new SeenPoiStop(dayIndex, i + 1, safeStopName(stop)), seenPoiStops);
                }
            }
            int defaultMin = minNonMealStopsPerDay(draft.pace());
            int minNonMealStops = effectiveMinByDay == null
                    ? defaultMin
                    : effectiveMinByDay.getOrDefault(dayIndex, defaultMin);
            if (countNonMealStops(stops) < minNonMealStops) {
                issues.add("day-" + dayIndex + "-too-few-non-meal-stops");
            }
        }
        return issues.stream().distinct().toList();
    }

    private List<String> validateRequestedDayCount(PlanDraft draft, CreatePlanReq req) {
        if (draft == null || req == null || req.days() < 1 || draft.daysPlan() == null) {
            return List.of();
        }
        int expectedDays = req.days();
        int actualDays = draft.daysPlan().size();
        Integer declaredDays = draft.days();
        List<String> issues = new ArrayList<>();
        if (actualDays != expectedDays) {
            issues.add("expected-" + expectedDays + "-days-but-got-" + actualDays);
        }
        if (declaredDays != null && declaredDays > 0 && declaredDays != expectedDays) {
            issues.add("declared-days-" + declaredDays + "-does-not-match-request-" + expectedDays);
        }
        return issues;
    }

    private List<String> validateSelectedStyleCoverage(PlanDraft draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || req == null || req.style() == null || req.style().isEmpty()) {
            return List.of();
        }
        List<PlanDraft.Place> stops = draft.daysPlan().stream()
                .filter(day -> day != null && day.stops() != null)
                .flatMap(day -> day.stops().stream())
                .toList();
        List<String> issues = new ArrayList<>();
        for (String style : req.style()) {
            String normalized = normalizeSlot(style);
            if (normalized.isBlank()) {
                continue;
            }
            boolean covered = switch (normalized) {
                case "market_shopping" -> stops.stream().anyMatch(this::isMarketShoppingLikeStop);
                case "theme_park" -> stops.stream().anyMatch(this::isThemeParkLikeStop);
                case "nature" -> stops.stream().anyMatch(this::isNatureCoverageStop);
                case "culture" -> stops.stream().anyMatch(this::isCultureCoverageStop);
                default -> true;
            };
            if (!covered) {
                String issue = "style-missing-" + normalized.replace('_', '-');
                if (isSoftMissingMarketShoppingUnderThemePark(normalized, stops, req)) {
                    logSoftValidationDiagnostic("style-soft-missing-market-shopping-after-theme-prune",
                            "requestedStyle=" + normalized + " reason=theme_park_route_cluster_priority");
                    continue;
                }
                addValidationIssue(issues, issue, -1, -1, null, null, "requestedStyle=" + normalized);
            }
        }
        return issues;
    }

    private boolean isSoftMissingMarketShoppingUnderThemePark(String normalizedStyle, List<PlanDraft.Place> stops, CreatePlanReq req) {
        if (!"market_shopping".equals(normalizedStyle) || stops == null || stops.stream().noneMatch(this::isThemeParkLikeStop)) {
            return false;
        }
        return req != null && req.style() != null && req.style().stream()
                .map(this::normalizeSlot)
                .anyMatch("theme_park"::equals);
    }

    private List<String> validateThemeParkLocation(String requestedCity, int dayIndex, int stopIndex, PlanDraft.Place stop) {
        return themeParkGovernanceService.validateLocation(requestedCity, dayIndex, stopIndex, stop);
    }

    private boolean isThemeParkLikeStop(PlanDraft.Place stop) {
        return themeParkGovernanceService.isThemeParkLikeStop(stop);
    }

    private List<String> validateTimeSensitiveStop(int dayIndex, int stopIndex, PlanDraft.Place stop, int startMinutes, int endMinutes) {
        List<String> issues = new ArrayList<>();
        String name = stop == null || stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        String timeSlot = normalizeSlot(stop == null ? null : stop.timeSlot());

        boolean museumLike = isCulturalOpeningHoursConstrained(stop);
        if (museumLike && startMinutes >= 0 && startMinutes < 10 * 60) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-early");
        }
        if (museumLike && endMinutes > CULTURAL_POI_LATEST_END_MINUTES) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-late");
        }

        boolean penguinLike = name.contains("penguin");
        if (penguinLike && startMinutes >= 0 && startMinutes < 16 * 60 + 30) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-too-early");
        }
        if (penguinLike
                && !timeSlot.isBlank()
                && !"sunset".equals(timeSlot)
                && !"evening".equals(timeSlot)
                && !"night".equals(timeSlot)) {
            issues.add("day-" + dayIndex + "-stop-" + stopIndex + "-time-sensitive-slot-mismatch");
        }
        return issues;
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

    private void addValidationIssue(List<String> issues, String issue, int dayIndex, int stopIndex, PlanDraft.Place stop, PlanDraft.Place previousStop, String detail) {
        issues.add(issue);
        if (log.isDebugEnabled()) {
            log.debug("Validation issue code={} day={} stopIndex={} stop={} previous={} detail={}",
                    issue,
                    dayIndex,
                    stopIndex,
                    safeStopName(stop),
                    safeStopName(previousStop),
                    detail);
        }
    }

    private void logSoftValidationDiagnostic(String code, String detail) {
        if (log.isDebugEnabled()) {
            log.debug("Validation diagnostic code={} detail={}", code, detail);
        }
    }

    private boolean hasVerifiedMealStop(PlanDraft.Place stop, String mealSlot) {
        if (!hasMealSlot(stop, mealSlot) || stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return isMealCategory(category);
    }

    private boolean hasMealSlot(PlanDraft.Place stop, String slot) {
        return stop != null && (slot.equals(normalizeSlot(stop.mealType())) || slot.equals(normalizeSlot(stop.timeSlot())));
    }

    private int countNonMealStops(List<PlanDraft.Place> stops) {
        if (stops == null || stops.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (PlanDraft.Place stop : stops) {
            if (isCountedNonMealStop(stop)) {
                count++;
            }
        }
        return count;
    }

    private boolean isCountedNonMealStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private boolean isStrictMealStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return isMealCategory(category) && (stop.mealType() != null || "lunch".equals(normalizeSlot(stop.timeSlot())) || "dinner".equals(normalizeSlot(stop.timeSlot())));
    }

    private boolean isFoodStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        return isMealCategory(normalizeSlot(stop.category()));
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private boolean isMarketShoppingLikeStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if ("shop".equals(category) || "shopping".equals(category) || "market".equals(category)) {
            return true;
        }
        String text = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase(Locale.ROOT);
        return text.contains("market")
                || text.contains("arcade")
                || text.contains("shopping")
                || text.contains("retail")
                || text.contains("food hall")
                || text.contains("bazaar");
    }

    private boolean isNatureCoverageStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = normalizeSlot(joinText(stop.name(), stop.category(), stop.suburb(), stop.preferredArea()));
        return "park".equals(category)
                || "nature".equals(category)
                || text.contains("garden")
                || text.contains("botanic")
                || text.contains("beach")
                || text.contains("lookout")
                || text.contains("reserve")
                || text.contains("trail")
                || text.contains("river")
                || text.contains("coastal")
                || text.contains("foreshore");
    }

    private boolean isCultureCoverageStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = normalizeSlot(joinText(stop.name(), stop.category(), stop.suburb(), stop.preferredArea()));
        return "museum".equals(category)
                || "gallery".equals(category)
                || "heritage".equals(category)
                || text.contains("museum")
                || text.contains("gallery")
                || text.contains("heritage")
                || text.contains("library")
                || text.contains("memorial")
                || text.contains("shrine")
                || text.contains("cultural")
                || text.contains("historic");
    }

    private boolean isCulturalOpeningHoursConstrained(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "museum".equals(category) || "gallery".equals(category) || "zoo".equals(category);
    }

    private int minNonMealStopsPerDay(String pace) {
        return daySkeletonService.nonMealRangeForPace(pace).min();
    }

    private int parseTimeMinutes(String value) {
        if (value == null || !value.matches("^\\d{2}:\\d{2}$")) {
            return -1;
        }
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
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
        return tokens.stream().sorted().collect(Collectors.joining(" "));
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
        if (isLikelyAcronymPhrase(outside) && !parenthetical.isEmpty()) {
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

    private String normalizeCoordinateCategory(PlanDraft.Place stop) {
        if (stop == null) {
            return "";
        }
        String category = normalizeSlot(stop.category());
        return switch (category) {
            case "museum", "gallery", "cultural" -> "cultural";
            case "park", "nature", "outdoor" -> "park";
            case "lookout", "viewpoint", "landmark", "attraction" -> "attraction";
            case "restaurant", "cafe", "food", "dining" -> "food";
            default -> category;
        };
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

    private String normalizeSlot(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String joinText(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String safeStopName(PlanDraft.Place stop) {
        return stop == null || stop.name() == null ? "" : stop.name();
    }

    private record SeenPoiStop(int dayIndex, int stopIndex, String stopName) {}
}
