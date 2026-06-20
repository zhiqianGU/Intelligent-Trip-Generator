package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class PlanFinalizationService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int THEME_PARK_AFTERNOON_CONTINUATION_MINUTES = 60;

    public PlanDraftResponse finalizeCopyPolishDraft(PlanDraftResponse draft) {
        PlanDraftResponse safeDraft = finalScheduleSafetyPass(draft);
        return sanitizeFinalCopy(safeDraft);
    }

    private PlanDraftResponse finalScheduleSafetyPass(PlanDraftResponse draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        List<DayPlan> adjustedDays = draft.daysPlan().stream()
                .map(day -> {
                    List<Place> stops = day.stops() == null ? List.of() : day.stops();
                    List<Place> adjustedStops = enforceLargeAttractionContinuationMinimum(stops);
                    return new DayPlan(
                            day.dayIndex(),
                            day.hotel(),
                            adjustedStops,
                            day.theme(),
                            day.morningNote(),
                            day.afternoonNote(),
                            day.eveningNote(),
                            day.note()
                    );
                })
                .toList();
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                adjustedDays,
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

    private PlanDraftResponse sanitizeFinalCopy(PlanDraftResponse draft) {
        if (draft == null) {
            return null;
        }
        List<DayPlan> days = draft.daysPlan() == null ? List.of() : draft.daysPlan().stream()
                .map(this::sanitizeFinalDayCopy)
                .toList();
        return new PlanDraftResponse(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                sanitizeNarrativeCopyStrict(draft.title(), finalTitleFallback(draft)),
                sanitizeNarrativeCopyStrict(draft.overview(), finalOverviewFallback(draft)),
                days,
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

    private DayPlan sanitizeFinalDayCopy(DayPlan day) {
        if (day == null) {
            return null;
        }
        List<Place> stops = day.stops() == null ? List.of() : day.stops().stream()
                .map(stop -> sanitizeFinalPlaceCopy(stop, day))
                .toList();
        Place hotel = sanitizeFinalPlaceCopy(day.hotel());
        return new DayPlan(
                day.dayIndex(),
                hotel,
                stops,
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.theme(), day), "Day " + day.dayIndex()),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.morningNote(), day), finalDayCopyFallback(day, "morning")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.afternoonNote(), day), finalDayCopyFallback(day, "afternoon")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.eveningNote(), day), finalDayCopyFallback(day, "evening")),
                sanitizeNarrativeCopyStrict(cleanUnsupportedDayReferences(day.note(), day), finalDayCopyFallback(day, "day"))
        );
    }

    private Place sanitizeFinalPlaceCopy(Place stop) {
        return sanitizeFinalPlaceCopy(stop, null);
    }

    private Place sanitizeFinalPlaceCopy(Place stop, DayPlan day) {
        if (stop == null) {
            return null;
        }
        String reasonFallback = finalReasonFallback(stop);
        String tipFallback = finalTipFallback(stop);
        return new Place(
                stop.name(),
                stop.addressLine(),
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                stop.category(),
                stop.stayMinutes(),
                stop.timeSlot(),
                stop.startTime(),
                stop.endTime(),
                stop.mealType(),
                stop.preferredArea(),
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                sanitizeNarrativeCopyStrict(cleanUnsupportedPlaceReferences(stop.reason(), stop, day), reasonFallback),
                sanitizeNarrativeCopyStrict(cleanUnsupportedPlaceReferences(stop.tip(), stop, day), tipFallback),
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
    }

    private String sanitizeNarrativeCopyStrict(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank() ? "" : fallback.trim();
        }
        String sanitized = value
                .replaceAll("(?i)\\bwalkable\\b", "compact")
                .replaceAll("(?i)\\btransit-friendly\\b", "manageable")
                .replaceAll("(?i)\\btransit friendly\\b", "manageable")
                .replaceAll("(?i)\\btour access\\b", "scheduled access")
                .replaceAll("(?i)\\btours\\b", "scheduled visits")
                .replaceAll("(?i)\\btour\\b", "scheduled visit")
                .trim();
        sanitized = removeRiskyNarrativeSentences(sanitized);
        if (!sanitized.isBlank()) {
            return sanitized;
        }
        return fallback == null || fallback.isBlank() ? "" : fallback.trim();
    }

    private String finalTitleFallback(PlanDraftResponse draft) {
        String city = draft == null || draft.city() == null || draft.city().isBlank() ? "Trip" : draft.city().trim();
        int days = draft == null || draft.days() <= 0 ? 1 : draft.days();
        return city + " " + days + "-Day Itinerary";
    }

    private String finalOverviewFallback(PlanDraftResponse draft) {
        String city = draft == null || draft.city() == null || draft.city().isBlank() ? "the destination" : draft.city().trim();
        return "A practical itinerary for " + city + " built around the confirmed stops and meal breaks.";
    }

    private String finalReasonFallback(Place stop) {
        String name = stop == null || stop.name() == null || stop.name().isBlank() ? "This stop" : stop.name();
        if (stop != null && isStrictMealStop(stop)) {
            return name + " provides a practical meal break for this part of the day.";
        }
        if (stop != null && "hotel".equals(normalizeSlot(stop.category()))) {
            return name + " provides a practical base for this itinerary.";
        }
        if (stop != null && isThemeParkLikeStop(stop)) {
            return name + " is the main theme park focus for this day.";
        }
        return name + " fits the day's route and keeps the schedule manageable.";
    }

    private String finalTipFallback(Place stop) {
        if (stop != null && isStrictMealStop(stop)) {
            return "Keep this meal stop flexible if timing changes during the day.";
        }
        if (stop != null && isThemeParkLikeStop(stop)) {
            return "Keep the visit flexible around weather, energy, and park conditions.";
        }
        return "Keep this stop flexible if the day starts running late.";
    }

    private String finalDayCopyFallback(DayPlan day, String part) {
        String names = day == null || day.stops() == null ? "" : day.stops().stream()
                .filter(stop -> stop != null && !isStrictMealStop(stop))
                .map(Place::name)
                .filter(name -> name != null && !name.isBlank())
                .limit(2)
                .collect(Collectors.joining(" and "));
        if (names.isBlank()) {
            return "Keep this part of the day flexible around the confirmed stops.";
        }
        return switch (part) {
            case "morning" -> "Start with " + names + " while keeping the route compact.";
            case "afternoon" -> "Continue around the confirmed stops without adding extra backtracking.";
            case "evening" -> "Finish with the planned meal stop and keep timing flexible.";
            default -> "This day follows the confirmed stop order around " + names + ".";
        };
    }

    private String cleanUnsupportedDayReferences(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null) {
            return value;
        }
        String cleaned = value;
        if (!dayHasStopContaining(day, "royal botanic garden")) {
            cleaned = removeNarrativeSegments(cleaned, "royal botanic garden", "botanic garden");
        }
        if (!dayHasStopContaining(day, "harbour bridge pedestrian")
                && !dayHasStopContaining(day, "harbour bridge pylon")
                && !dayHasStopContaining(day, "harbour bridge walkway")) {
            cleaned = removeNarrativeSegments(cleaned, "harbour bridge pedestrian", "bridge pedestrian path", "scenic bridge walk", "scenic walk across the harbour bridge");
        }
        if (!dayHasStopContaining(day, "lavender bay reserve")) {
            cleaned = removeNarrativeSegments(cleaned, "lavender bay reserve");
        }
        if (!dayHasStopContaining(day, "cadman")) {
            cleaned = removeNarrativeSegments(cleaned, "cadman's cottage", "cadmans cottage", "cadman");
        }
        if (!dayHasStopContaining(day, "circular quay lookout")) {
            cleaned = removeNarrativeSegments(cleaned, "circular quay lookout");
        }
        if (!dayHasStopContaining(day, "big dipper")) {
            cleaned = removeNarrativeSegments(cleaned, "big dipper");
        }
        if (!dayHasStopContaining(day, "taronga")) {
            cleaned = removeNarrativeSegments(cleaned, "taronga zoo", "taronga");
        }
        cleaned = removeNarrativeSegments(cleaned,
                "ferry from",
                "ferry to",
                "small galleries",
                "leafy streets",
                "entrance plaza",
                "quiet bayside moments");
        if (!dayHasZooOrWildlifeStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "animal encounters", "wildlife");
        }
        cleaned = removeUnsupportedMealVenueClaims(cleaned, day);
        cleaned = fixMarketLunchOrderNarrative(cleaned, day);
        if (!dayHasViewStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "sunset pause", "sunset views", "golden hour", "harbour lookouts", "harbor lookouts", "scenic views", "skyline views", "scenic dinner");
        }
        return normalizeNarrativePunctuation(cleaned);
    }

    private String cleanUnsupportedPlaceReferences(String value, Place stop, DayPlan day) {
        if (value == null || value.isBlank() || stop == null) {
            return value;
        }
        String cleaned = value;
        String stopText = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
        if (!stopText.contains("taronga") && joinText(cleaned).toLowerCase(Locale.ROOT).contains("taronga")) {
            return "";
        }
        if (day != null && !dayHasZooOrWildlifeStop(day)) {
            cleaned = removeNarrativeSegments(cleaned, "animal encounters", "wildlife");
        }
        cleaned = removeNarrativeSegments(cleaned, "ferry to");
        return normalizeNarrativePunctuation(cleaned);
    }

    private String fixMarketLunchOrderNarrative(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null || !lunchComesBeforeMarket(day)) {
            return value;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.contains("market") || !lower.contains("lunch")) {
            return value;
        }
        int marketPos = lower.indexOf("market");
        int lunchPos = lower.indexOf("lunch");
        if (marketPos < lunchPos) {
            return "After lunch, continue with the market stop and afternoon visit.";
        }
        return value;
    }

    private String removeUnsupportedMealVenueClaims(String value, DayPlan day) {
        if (value == null || value.isBlank() || day == null) {
            return value;
        }
        String lunchName = mealStopName(day, "lunch");
        String dinnerName = mealStopName(day, "dinner");
        String[] sentences = value.split("(?<=[.!?])\\s+");
        List<String> keptSentences = new ArrayList<>();
        for (String sentence : sentences) {
            String[] clauses = sentence.split("\\s*,\\s*|\\s+then\\s+|\\s+followed by\\s+|\\s+and then\\s+");
            List<String> keptClauses = new ArrayList<>();
            for (String clause : clauses) {
                if (!isUnsupportedMealVenueClaim(clause, lunchName, dinnerName) && !clause.isBlank()) {
                    keptClauses.add(clause.trim());
                }
            }
            if (!keptClauses.isEmpty()) {
                keptSentences.add(String.join(", ", keptClauses));
            }
        }
        return String.join(" ", keptSentences).trim();
    }

    private boolean isUnsupportedMealVenueClaim(String clause, String lunchName, String dinnerName) {
        if (clause == null || clause.isBlank()) {
            return false;
        }
        String lower = clause.toLowerCase(Locale.ROOT);
        if (containsMealVenuePhrase(lower, "lunch")) {
            return !mentionsMealStop(clause, lunchName);
        }
        if (containsMealVenuePhrase(lower, "dinner")
                || lower.contains("dine at ")
                || lower.contains("dine in ")
                || lower.contains("dining at ")) {
            return !mentionsMealStop(clause, dinnerName);
        }
        return false;
    }

    private boolean containsMealVenuePhrase(String lower, String meal) {
        return lower.contains(meal + " at ")
                || lower.contains(meal + " in ")
                || lower.contains("have " + meal + " at ")
                || lower.contains("have " + meal + " in ")
                || lower.contains("enjoy " + meal + " at ")
                || lower.contains("enjoy " + meal + " in ")
                || lower.contains("end the day with " + meal + " at ")
                || lower.contains("finish with " + meal + " at ");
    }

    private String mealStopName(DayPlan day, String mealType) {
        if (day == null || day.stops() == null || mealType == null) {
            return "";
        }
        String target = mealType.toLowerCase(Locale.ROOT);
        return day.stops().stream()
                .filter(stop -> stop != null && isStrictMealStop(stop))
                .filter(stop -> target.equals(normalizeSlot(stop.mealType())) || target.equals(normalizeSlot(stop.timeSlot())))
                .map(Place::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse("");
    }

    private boolean mentionsMealStop(String clause, String mealName) {
        if (clause == null || mealName == null || mealName.isBlank()) {
            return false;
        }
        String normalizedClause = normalizeNameForNarrativeMatch(clause);
        String normalizedMeal = normalizeNameForNarrativeMatch(mealName);
        if (normalizedMeal.isBlank() || normalizedClause.contains(normalizedMeal)) {
            return true;
        }
        List<String> significant = List.of(normalizedMeal.split(" ")).stream()
                .filter(token -> token.length() >= 4)
                .filter(token -> !List.of("restaurant", "cafe", "bistro", "grill", "bar", "and", "the").contains(token))
                .limit(2)
                .toList();
        return !significant.isEmpty() && significant.stream().allMatch(normalizedClause::contains);
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

    private String removeNarrativeSegments(String value, String... lowerNeedles) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String[] sentences = value.split("(?<=[.!?])\\s+");
        List<String> keptSentences = new ArrayList<>();
        for (String sentence : sentences) {
            String cleaned = removeNarrativeClauses(sentence, lowerNeedles);
            if (!cleaned.isBlank()) {
                keptSentences.add(cleaned);
            }
        }
        return String.join(" ", keptSentences).trim();
    }

    private String removeNarrativeClauses(String sentence, String... lowerNeedles) {
        if (sentence == null || sentence.isBlank()) {
            return "";
        }
        String[] clauses = sentence.split("\\s*,\\s*|\\s+then\\s+|\\s+followed by\\s+|\\s+and then\\s+");
        List<String> kept = new ArrayList<>();
        for (String clause : clauses) {
            String lower = clause.toLowerCase(Locale.ROOT);
            boolean unsupported = false;
            for (String needle : lowerNeedles) {
                if (needle != null && !needle.isBlank() && lower.contains(needle)) {
                    unsupported = true;
                    break;
                }
            }
            if (!unsupported && !clause.isBlank()) {
                kept.add(clause.trim());
            }
        }
        if (kept.isEmpty()) {
            return "";
        }
        return String.join(", ", kept).trim();
    }

    private String normalizeNarrativePunctuation(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("\\s+,", ",")
                .replaceAll(",\\s*\\.", ".")
                .replaceAll("(?i)\\bcompact\\s*,\\s*compact\\b", "compact")
                .replaceAll("(?i)\\bmanageable\\s*,\\s*manageable\\b", "manageable")
                .replaceAll("(?i)\\bflexible\\s*,\\s*flexible\\b", "flexible")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("\\s+([.!?])", "$1")
                .trim();
    }

    private boolean dayHasStopContaining(DayPlan day, String needle) {
        if (day == null || needle == null || needle.isBlank()) {
            return false;
        }
        String target = needle.toLowerCase(Locale.ROOT);
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        return stops.stream().anyMatch(stop -> {
            String text = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
            return text.contains(target);
        });
    }

    private boolean dayHasViewStop(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        return day.stops().stream().anyMatch(stop -> isLateDayViewStop(stop)
                || joinText(stop.name(), stop.category(), stop.reason()).toLowerCase(Locale.ROOT).contains("view"));
    }

    private boolean dayHasZooOrWildlifeStop(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        return day.stops().stream().anyMatch(stop -> {
            String text = joinText(stop.name(), stop.category(), stop.preferredArea()).toLowerCase(Locale.ROOT);
            return text.contains("zoo") || text.contains("wildlife") || text.contains("animal");
        });
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
                || text.contains("ferry terminal")
                || text.contains("ferry from")
                || text.contains("ferry to")
                || text.contains("opening hours")
                || text.contains("open daily")
                || text.contains("open weekends")
                || text.contains("weekend-only")
                || text.contains("weekends only")
                || text.contains("last entry")
                || text.contains("opens at")
                || text.contains("closes at")
                || text.contains("open late")
                || text.contains("adjusted to")
                || text.contains("book ahead")
                || text.contains("reserve ahead")
                || text.contains("reserve in advance")
                || text.contains("reserve dinner ahead")
                || text.contains("guarantee seating")
                || text.contains("accepts walk-ins")
                || text.contains("accepts walk ins")
                || text.contains("signature dish")
                || text.contains("signature dishes")
                || text.contains("must-try")
                || text.contains("must try")
                || text.contains("freshest")
                || text.contains("best in")
                || text.contains("best seating")
                || text.contains("preferred seating")
                || text.contains("window-side")
                || text.contains("window side")
                || text.contains("window seats")
                || text.contains("outdoor seating")
                || text.contains("balcony seating")
                || text.contains("upper-level tables")
                || text.contains("upper level tables")
                || text.contains("guaranteed")
                || text.contains("complimentary")
                || text.contains("only verified")
                || text.contains("operationally active")
                || text.contains("verified, accessible")
                || text.contains("elevated terraces")
                || text.contains("classic attractions like")
                || text.contains("small galleries")
                || text.contains("leafy streets")
                || text.contains("entrance plaza")
                || text.contains("quiet bayside moments")
                || text.contains("harbour lookouts")
                || text.contains("harbor lookouts")
                || text.contains("scenic views")
                || text.contains("skyline views")
                || text.contains("scenic dinner")
                || text.contains("cash only")
                || text.contains("card accepted")
                || text.contains("cards accepted")
                || text.contains("payment")
                || text.matches(".*\\b\\d{1,2}:\\d{2}\\s*(am|pm)?\\b.*")
                || text.matches(".*\\b\\d{1,2}\\s*(am|pm)\\b.*")
                || text.matches(".*\\b\\d+\\s*[- ]?minute\\b.*")
                || text.matches(".*\\b\\d+\\s*min\\b.*")
                || text.contains(" bus ")
                || text.contains(" taxi")
                || text.contains(" train")
                || text.contains(" ferry")
                || text.contains(" tram")
                || text.contains(" by bus")
                || text.contains(" by taxi")
                || text.contains(" by train")
                || text.contains(" by ferry")
                || text.contains(" by tram")
                || text.contains("parking hassle")
                || text.contains("shuttle")
                || text.contains("ride access")
                || text.contains("ride schedule")
                || text.contains("ride schedules")
                || text.contains("timed access")
                || text.contains("show schedule")
                || text.contains("show schedules")
                || text.contains("wait time");
    }

    private List<Place> enforceLargeAttractionContinuationMinimum(List<Place> stops) {
        if (stops == null || stops.size() < 2) {
            return stops == null ? List.of() : stops;
        }
        List<Place> adjusted = new ArrayList<>(stops);
        boolean changed = false;
        for (int i = 0; i < adjusted.size(); i++) {
            Place stop = adjusted.get(i);
            if (!isLargeAttractionContinuationStop(stop)) {
                continue;
            }
            int start = parseTimeMinutes(stop.startTime());
            int end = parseTimeMinutes(stop.endTime());
            if (start < 0 || end < 0 || end - start >= THEME_PARK_AFTERNOON_CONTINUATION_MINUTES) {
                continue;
            }
            int targetEnd = start + THEME_PARK_AFTERNOON_CONTINUATION_MINUTES;
            adjusted.set(i, copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(targetEnd), THEME_PARK_AFTERNOON_CONTINUATION_MINUTES));
            changed = true;
            int previousEnd = targetEnd;
            for (int j = i + 1; j < adjusted.size(); j++) {
                Place next = adjusted.get(j);
                int nextStart = parseTimeMinutes(next.startTime());
                int nextEnd = parseTimeMinutes(next.endTime());
                int nextStay = resolveStayMinutes(next);
                int minStart = Math.max(timeSensitiveEarliestStart(next), previousEnd + transitionMinutes(false));
                if (nextStart < minStart || nextEnd <= nextStart) {
                    adjusted.set(j, copyPlaceWithTimes(next, formatMinutes(minStart), formatMinutes(minStart + nextStay), nextStay));
                    previousEnd = minStart + nextStay;
                } else {
                    previousEnd = nextEnd;
                }
            }
        }
        return changed ? adjusted : stops;
    }

    private boolean isLargeAttractionContinuationStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String name = nullToEmpty(stop.name()).toLowerCase(Locale.ROOT);
        String slot = normalizeSlot(stop.timeSlot());
        return "afternoon".equals(slot)
                && (name.contains("afternoon visit")
                || name.contains("continued visit")
                || name.contains("continuation")
                || name.contains("return visit"));
    }

    private int timeSensitiveEarliestStart(Place stop) {
        if (stop == null) {
            return 0;
        }
        if (isCulturalOpeningHoursConstrained(stop)) {
            return 10 * 60;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(Locale.ROOT);
        if (name.contains("penguin")) {
            return 16 * 60 + 30;
        }
        return 0;
    }

    private boolean isCulturalOpeningHoursConstrained(Place stop) {
        if (stop == null) return false;
        String category = normalizeSlot(stop.category());
        return "museum".equals(category) || "gallery".equals(category) || "zoo".equals(category);
    }

    private boolean isLateDayViewStop(Place stop) {
        if (stop == null) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        String category = normalizeSlot(stop.category());
        String text = joinText(stop.name(), stop.reason(), stop.tip());
        boolean viewLike = "lookout".equals(category)
                || "viewpoint".equals(category)
                || "landmark".equals(category)
                || text.contains("lookout")
                || text.contains("sunset")
                || text.contains("golden hour")
                || text.contains("harbour view")
                || text.contains("skyline view");
        return viewLike && ("sunset".equals(slot) || "afternoon".equals(slot) || "evening".equals(slot) || slot.isBlank());
    }

    private boolean lunchComesBeforeMarket(DayPlan day) {
        if (day == null || day.stops() == null) {
            return false;
        }
        int lunchIndex = firstMealIndex(day.stops(), "lunch");
        int marketIndex = firstMarketShoppingIndex(day.stops());
        return lunchIndex >= 0 && marketIndex >= 0 && lunchIndex < marketIndex;
    }

    private int firstMarketShoppingIndex(List<Place> stops) {
        if (stops == null) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            if (isMarketShoppingLikeStop(stops.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private int firstMealIndex(List<Place> stops, String mealType) {
        if (stops == null) {
            return -1;
        }
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), mealType)) return i;
        }
        return -1;
    }

    private boolean isMarketShoppingLikeStop(Place stop) {
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

    private boolean isThemeParkLikeStop(Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        String text = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase(Locale.ROOT);
        return "theme_park".equals(category)
                || "amusement".equals(category)
                || "amusement_park".equals(category)
                || text.contains("theme park")
                || text.contains("amusement park")
                || text.contains("water park");
    }

    private boolean isStrictMealStop(Place stop) {
        if (stop == null) return false;
        String category = normalizeSlot(stop.category());
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "lunch".equals(normalizeSlot(stop.mealType()))
                || "dinner".equals(normalizeSlot(stop.mealType()))
                || "lunch".equals(normalizeSlot(stop.timeSlot()))
                || "dinner".equals(normalizeSlot(stop.timeSlot()));
    }

    private boolean hasMealSlot(Place stop, String slot) {
        return stop != null && (slot.equals(normalizeSlot(stop.mealType())) || slot.equals(normalizeSlot(stop.timeSlot())));
    }

    private int parseTimeMinutes(String value) {
        if (value == null) return -1;
        String text = value.trim();
        if (!text.matches("^\\d{2}:\\d{2}$")) return -1;
        int hours = Integer.parseInt(text.substring(0, 2));
        int minutes = Integer.parseInt(text.substring(3, 5));
        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return -1;
        return hours * 60 + minutes;
    }

    private String formatMinutes(int minutes) {
        int normalized = Math.max(0, Math.min(minutes, 1439));
        return LocalTime.of(normalized / 60, normalized % 60).format(TIME_FORMATTER);
    }

    private int resolveStayMinutes(Place stop) {
        if (stop == null) return 60;
        if (stop.stayMinutes() != null && stop.stayMinutes() > 0) return stop.stayMinutes();
        int start = parseTimeMinutes(stop.startTime());
        int end = parseTimeMinutes(stop.endTime());
        return (start >= 0 && end > start) ? (end - start) : 60;
    }

    private int transitionMinutes(boolean first) {
        return first ? 0 : 20;
    }

    private Place copyPlaceWithTimes(Place stop, String start, String end, int stay) {
        return new Place(
                stop.name(),
                stop.addressLine(),
                stop.suburb(),
                stop.city(),
                stop.state(),
                stop.postcode(),
                stop.country(),
                stop.category(),
                stay,
                stop.timeSlot(),
                start,
                end,
                stop.mealType(),
                stop.preferredArea(),
                stop.cuisine(),
                stop.vibe(),
                stop.budgetLevel(),
                stop.reason(),
                stop.tip(),
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
    }

    private String joinText(String... parts) {
        if (parts == null || parts.length == 0) {
            return "";
        }
        return String.join(" ", java.util.Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .toList());
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
