package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Service
public class PostRoutePlanRepairService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int DAY_START_MINUTES = 9 * 60;
    private static final int LUNCH_EARLIEST_START_MINUTES = 11 * 60 + 15;
    private static final int LUNCH_PREFERRED_EARLIEST_START_MINUTES = 11 * 60 + 30;
    private static final int LUNCH_LATEST_START_MINUTES = 13 * 60;
    private static final int DINNER_EARLIEST_START_MINUTES = 17 * 60 + 30;
    private static final int DINNER_LATEST_START_MINUTES = 20 * 60;
    private static final int CULTURAL_POI_LATEST_END_MINUTES = 17 * 60;

    private final ThemeParkGovernanceService themeParkGovernanceService;
    private final DaySkeletonService daySkeletonService;

    public PostRoutePlanRepairService(
            ThemeParkGovernanceService themeParkGovernanceService,
            DaySkeletonService daySkeletonService
    ) {
        this.themeParkGovernanceService = themeParkGovernanceService;
        this.daySkeletonService = daySkeletonService;
    }

    public PlanDraft repair(
            PlanDraft draft,
            CreatePlanReq req,
            String attemptLabel,
            StringBuilder stageSummary,
            StringBuilder timingSummary,
            Operations operations
    ) {
        long stageStartedAt = System.currentTimeMillis();
        PlanDraft beforePostRouteRepair = draft;

        draft = pruneFlexibleFoodStops(draft);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-flexible-prune", draft);

        draft = pruneUnselectedShoppingStops(draft, req);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-shopping-prune", draft);

        draft = pruneAreaInconsistentFlexibleStops(draft, req);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-area-prune", draft);

        draft = pruneExcessNonMealStops(draft, req);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-excess-prune", draft);

        Set<Integer> postRouteChangedDays = operations.detectChangedDayIndexes(beforePostRouteRepair, draft);
        if (!postRouteChangedDays.isEmpty()) {
            draft = operations.normalizeDraftScheduleWithRouteDurations(draft, postRouteChangedDays);
        }
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-reschedule", draft);

        PlanDraft beforeDuplicateRepair = draft;
        draft = operations.repairCrossDayDuplicatePois(draft);
        Set<Integer> duplicateRepairChangedDays = operations.detectChangedDayIndexes(beforeDuplicateRepair, draft);
        if (!duplicateRepairChangedDays.isEmpty()) {
            draft = operations.normalizeDraftScheduleWithRouteDurations(draft, duplicateRepairChangedDays);
        }

        Set<Integer> changedNarrativeDays = new java.util.LinkedHashSet<>(postRouteChangedDays);
        changedNarrativeDays.addAll(duplicateRepairChangedDays);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-route-duplicate-repair", draft);

        PlanDraft beforeTimeSensitiveRepair = draft;
        draft = operations.repairTimeSensitiveLateStops(draft);
        changedNarrativeDays.addAll(operations.detectChangedDayIndexes(beforeTimeSensitiveRepair, draft));
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-time-sensitive-repair", draft);

        PlanDraft beforeGapClamp = draft;
        draft = operations.clampOversizedGaps(draft);
        Set<Integer> gapClampChangedDays = operations.detectChangedDayIndexes(beforeGapClamp, draft);
        changedNarrativeDays.addAll(gapClampChangedDays);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "post-gap-clamp", draft);

        draft = rewriteDayNarratives(draft, changedNarrativeDays);
        operations.appendStageTiming(timingSummary, attemptLabel + "/post-route-prune-narrative", System.currentTimeMillis() - stageStartedAt);
        operations.logPlanStageCounts(stageSummary, attemptLabel, "final", draft);
        return draft;
    }

    public interface Operations {
        Set<Integer> detectChangedDayIndexes(PlanDraft before, PlanDraft after);

        PlanDraft normalizeDraftScheduleWithRouteDurations(PlanDraft draft, Set<Integer> targetDayIndexes);

        PlanDraft repairCrossDayDuplicatePois(PlanDraft draft);

        PlanDraft repairTimeSensitiveLateStops(PlanDraft draft);

        PlanDraft clampOversizedGaps(PlanDraft draft);

        void appendStageTiming(StringBuilder timingSummary, String stage, long ms);

        void logPlanStageCounts(StringBuilder stageSummary, String attemptLabel, String stage, PlanDraft draft);
    }

    PlanDraft pruneAreaInconsistentFlexibleStops(PlanDraft draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }

        java.util.List<PlanDraft.DayPlan> updatedDays = new java.util.ArrayList<>();
        boolean changed = false;
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : day.stops();
            java.util.List<PlanDraft.Place> workingStops = new java.util.ArrayList<>(stops);
            boolean dayChanged;
            do {
                dayChanged = false;
                for (int i = 0; i < workingStops.size(); i++) {
                    PlanDraft.Place stop = workingStops.get(i);
                    if (isSelectedMarketShoppingAnchor(stop, req)) {
                        continue;
                    }
                    if (countNonMealStops(workingStops) <= minNonMealStops
                            && isCountedNonMealStop(stop)
                            && !themeParkGovernanceService.isThemeParkLikeStop(stop)) {
                        continue;
                    }
                    if (shouldDropAreaInconsistentFlexibleStop(workingStops, i)) {
                        workingStops.remove(i);
                        dayChanged = true;
                        changed = true;
                        break;
                    }
                }
            } while (dayChanged);

            updatedDays.add(new PlanDraft.DayPlan(
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

        return changed ? withDays(draft, updatedDays) : draft;
    }

    PlanDraft pruneFlexibleFoodStops(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }

        java.util.List<PlanDraft.DayPlan> updatedDays = new java.util.ArrayList<>();
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        boolean anyChanged = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : day.stops();
            if (stops.isEmpty()) {
                updatedDays.add(day);
                continue;
            }

            java.util.List<PlanDraft.Place> workingStops = new java.util.ArrayList<>(stops);
            boolean changed;
            do {
                changed = false;
                for (int i = 0; i < workingStops.size(); i++) {
                    PlanDraft.Place stop = workingStops.get(i);
                    if (isNonMealOccupyingMealSlot(stop)) {
                        workingStops.set(i, clearMealSlotForNonMealStop(stop));
                        changed = true;
                        break;
                    }
                    if (isCompoundAttractionStop(stop)) {
                        PlanDraft.Place simplified = simplifyCompoundStop(stop);
                        if (simplified != null) {
                            workingStops.set(i, simplified);
                        } else {
                            if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                                continue;
                            }
                            workingStops.remove(i);
                        }
                        changed = true;
                        break;
                    }
                    if (isSoftActivityStop(stop)) {
                        if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                            continue;
                        }
                        if (i > 0) {
                            PlanDraft.Place previous = workingStops.get(i - 1);
                            workingStops.set(i - 1, mergeSoftActivityIntoPrevious(previous, stop));
                        }
                        workingStops.remove(i);
                        changed = true;
                        break;
                    }
                    if (shouldCreateCulturalClosingProblem(stop)) {
                        int removableIndex = removableFlexibleIndexBefore(workingStops, i);
                        if (removableIndex >= 0) {
                            PlanDraft.Place removable = workingStops.get(removableIndex);
                            if (wouldDropBelowMinNonMealStops(workingStops, removable, minNonMealStops)) {
                                continue;
                            }
                            workingStops.remove(removableIndex);
                            changed = true;
                            break;
                        }
                    }
                    if (!isFlexibleStop(stop)) {
                        continue;
                    }
                    if (wouldDropBelowMinNonMealStops(workingStops, stop, minNonMealStops)) {
                        continue;
                    }
                    if (shouldDropFlexibleStop(workingStops, i)) {
                        workingStops.remove(i);
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    workingStops = new java.util.ArrayList<>(normalizeDaySchedule(new PlanDraft.DayPlan(
                            day.dayIndex(),
                            day.hotel(),
                            workingStops,
                            day.theme(),
                            day.morningNote(),
                            day.afternoonNote(),
                            day.eveningNote(),
                            day.note()
                    )).stops());
                    anyChanged = true;
                }
            } while (changed);

            updatedDays.add(new PlanDraft.DayPlan(
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

        return anyChanged ? withDays(draft, updatedDays) : draft;
    }

    private PlanDraft.DayPlan normalizeDaySchedule(PlanDraft.DayPlan day) {
        java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : new java.util.ArrayList<>(day.stops());
        if (stops.isEmpty()) {
            return day;
        }
        stops = reorderStopsByTimeSlotIfMealOrderInvalid(stops);
        java.util.List<PlanDraft.Place> normalized = new java.util.ArrayList<>();
        int previousEnd = DAY_START_MINUTES;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            int stay = resolveStayMinutes(stop);
            int rollingStart = previousEnd + transitionMinutes(i == 0);
            int preferredStart = preferredStartMinutes(stop.timeSlot(), i == 0);
            int start = chooseScheduledStart(rollingStart, preferredStart, stop);
            int end = start + stay;
            normalized.add(copyPlaceWithTimes(stop, formatMinutes(start), formatMinutes(end), stay));
            previousEnd = end;
        }
        return new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), normalized, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note());
    }

    private boolean isNonMealOccupyingMealSlot(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String timeSlot = normalizeSlot(stop.timeSlot());
        if (!"lunch".equals(timeSlot) && !"dinner".equals(timeSlot)) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private PlanDraft.Place clearMealSlotForNonMealStop(PlanDraft.Place stop) {
        String fallbackSlot = "dinner".equals(normalizeSlot(stop.timeSlot())) ? "evening" : "afternoon";
        return new PlanDraft.Place(
                stop.name(), stop.addressLine(), stop.suburb(), stop.city(), stop.state(), stop.postcode(), stop.country(),
                stop.category(), stop.stayMinutes(), fallbackSlot, stop.startTime(), stop.endTime(), null,
                stop.preferredArea(), stop.cuisine(), stop.vibe(), stop.budgetLevel(), stop.reason(), stop.tip(),
                stop.websiteUri(), stop.googleMapsUri(), stop.businessStatus(), stop.url(), stop.latitude(), stop.longitude()
        );
    }

    private boolean isCompoundAttractionStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        if (!name.matches("(?i).+\\s(&|and|\\+|/|\\|)\\s.+")) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (!("attraction".equals(category) || "park".equals(category) || "nature".equals(category) || "museum".equals(category))) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains("lookout")
                || lower.contains("planetarium")
                || lower.contains("garden")
                || lower.contains("museum")
                || lower.contains("gallery")
                || lower.contains("park")
                || lower.contains("market")
                || lower.contains("beach")
                || lower.contains("zoo");
    }

    private PlanDraft.Place simplifyCompoundStop(PlanDraft.Place stop) {
        if (stop == null) {
            return null;
        }
        String name = stop.name() == null ? "" : stop.name().trim();
        if (name.isBlank()) {
            return null;
        }
        String[] parts = name.split("(?i)\\s+(?:&|and|\\+|/|\\|)\\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String chosen = chooseCompoundStopName(parts[0].trim(), parts[1].trim());
        if (chosen == null || chosen.isBlank()) {
            return null;
        }
        String tip = stop.tip() == null || stop.tip().isBlank()
                ? "Use this as a single navigable stop rather than combining multiple nearby attractions."
                : stop.tip().trim() + " Keep this as a single navigable stop rather than combining multiple nearby attractions.";
        return new PlanDraft.Place(
                chosen, stop.addressLine(), stop.suburb(), stop.city(), stop.state(), stop.postcode(), stop.country(),
                stop.category(), stop.stayMinutes(), stop.timeSlot(), stop.startTime(), stop.endTime(), stop.mealType(),
                stop.preferredArea(), stop.cuisine(), stop.vibe(), stop.budgetLevel(), stop.reason(), tip,
                stop.websiteUri(), stop.googleMapsUri(), stop.businessStatus(), stop.url(), stop.latitude(), stop.longitude()
        );
    }

    private String chooseCompoundStopName(String first, String second) {
        String firstLower = first == null ? "" : first.toLowerCase();
        String secondLower = second == null ? "" : second.toLowerCase();
        if (firstLower.contains("lookout")) return first;
        if (secondLower.contains("lookout")) return second;
        if (firstLower.contains("museum") || firstLower.contains("gallery") || firstLower.contains("planetarium")) return first;
        if (secondLower.contains("museum") || secondLower.contains("gallery") || secondLower.contains("planetarium")) return second;
        if (firstLower.contains("garden") || firstLower.contains("park")) return first;
        if (secondLower.contains("garden") || secondLower.contains("park")) return second;
        return first == null || first.isBlank() ? second : first;
    }

    private boolean isSoftActivityStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if (!("attraction".equals(category) || "park".equals(category) || "nature".equals(category))) {
            return false;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        boolean activityName = name.contains("stroll")
                || name.contains("rainforest walk")
                || name.contains("arbour")
                || name.contains("arbor")
                || (name.contains("lookout") && name.contains("botanic"))
                || name.contains("coastal walk")
                || name.contains("sunset walk")
                || name.contains("scenic walk")
                || name.contains("riverwalk")
                || name.contains(" walk ")
                || name.contains("promenade")
                || name.contains("walking path")
                || name.contains("riverside walk");
        if (!activityName) {
            return false;
        }
        boolean hasVerifiedPlace = stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank();
        boolean hasCoordinates = stop.latitude() != null && stop.longitude() != null;
        return !hasVerifiedPlace || !hasCoordinates;
    }

    private PlanDraft.Place mergeSoftActivityIntoPrevious(PlanDraft.Place previous, PlanDraft.Place softActivity) {
        String reason = (previous.reason() == null || previous.reason().isBlank())
                ? softActivity.reason()
                : previous.reason();
        return new PlanDraft.Place(
                previous.name(), previous.addressLine(), previous.suburb(), previous.city(), previous.state(), previous.postcode(), previous.country(),
                previous.category(), previous.stayMinutes(), previous.timeSlot(), previous.startTime(), previous.endTime(), previous.mealType(),
                previous.preferredArea(), previous.cuisine(), previous.vibe(), previous.budgetLevel(), reason, previous.tip(),
                previous.websiteUri(), previous.googleMapsUri(), previous.businessStatus(), previous.url(), previous.latitude(), previous.longitude()
        );
    }

    private boolean shouldCreateCulturalClosingProblem(PlanDraft.Place stop) {
        if (stop == null || !isCulturalOpeningHoursConstrained(stop)) {
            return false;
        }
        int endMinutes = parseTimeMinutes(stop.endTime());
        return endMinutes > CULTURAL_POI_LATEST_END_MINUTES;
    }

    private boolean isCulturalOpeningHoursConstrained(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "museum".equals(category) || "gallery".equals(category) || "zoo".equals(category);
    }

    private int removableFlexibleIndexBefore(java.util.List<PlanDraft.Place> stops, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (isFlexibleStop(stops.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isFlexibleStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category) || "nature".equals(category) || "shop".equals(category) || "market".equals(category);
    }

    private boolean shouldDropFlexibleStop(java.util.List<PlanDraft.Place> stops, int index) {
        PlanDraft.Place stop = stops.get(index);
        if (isParkLikeStop(stop)) {
            return shouldDropParkLikeStop(stops, index);
        }
        if (isNonStrictFoodStop(stop)) {
            return shouldDropFlexibleFoodStop(stops, index);
        }
        return false;
    }

    private boolean shouldDropParkLikeStop(java.util.List<PlanDraft.Place> stops, int index) {
        PlanDraft.Place stop = stops.get(index);
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) {
            return false;
        }
        if (index > 0 && isParkLikeStop(stops.get(index - 1))) {
            return true;
        }
        return index < stops.size() - 1 && isParkLikeStop(stops.get(index + 1));
    }

    private boolean isNonStrictFoodStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop)) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "cafe".equals(category) || "bakery".equals(category) || "bar".equals(category) || "food".equals(category);
    }

    private boolean shouldDropFlexibleFoodStop(java.util.List<PlanDraft.Place> stops, int index) {
        PlanDraft.Place stop = stops.get(index);
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) {
            return false;
        }
        String timeSlot = normalizeSlot(stop.timeSlot());
        if ("lunch".equals(timeSlot) || "dinner".equals(timeSlot)) {
            return false;
        }
        int mealCount = 0;
        for (PlanDraft.Place s : stops) {
            if (isStrictMealStop(s)) {
                mealCount++;
            }
        }
        return mealCount >= 2;
    }

    private boolean shouldDropAreaInconsistentFlexibleStop(java.util.List<PlanDraft.Place> stops, int index) {
        if (index <= 0 || index >= stops.size() - 1) {
            return false;
        }
        PlanDraft.Place stop = stops.get(index);
        if (themeParkGovernanceService.isThemeParkLikeStop(stop)) {
            return false;
        }
        if (shouldDropThemeParkNearbyFlexibleStop(stops, index)) {
            return true;
        }
        if (isAreaReturnFlexibleStop(stops, index) && isAreaReturnDroppableStop(stop)) {
            return true;
        }
        if (!isFlexibleStop(stop)) {
            return false;
        }
        PlanDraft.Place previous = stops.get(index - 1);
        PlanDraft.Place next = stops.get(index + 1);
        if (isStrictMealStop(previous) && isStrictMealStop(next)) {
            return false;
        }
        if (previous.latitude() == null || previous.longitude() == null
                || stop.latitude() == null || stop.longitude() == null
                || next.latitude() == null || next.longitude() == null) {
            return false;
        }
        double prevToCurrent = haversineMeters(previous.latitude(), previous.longitude(), stop.latitude(), stop.longitude());
        double currentToNext = haversineMeters(stop.latitude(), stop.longitude(), next.latitude(), next.longitude());
        double prevToNext = haversineMeters(previous.latitude(), previous.longitude(), next.latitude(), next.longitude());
        double detourMeters = prevToCurrent + currentToNext - prevToNext;
        return detourMeters > 12_000 && Math.max(prevToCurrent, currentToNext) > 18_000;
    }

    private boolean shouldDropThemeParkNearbyFlexibleStop(java.util.List<PlanDraft.Place> stops, int index) {
        if (stops == null || index <= 0 || index >= stops.size() - 1) {
            return false;
        }
        PlanDraft.Place stop = stops.get(index);
        if (!isThemeParkDayConnectorCandidate(stop)) {
            return false;
        }
        PlanDraft.Place themeParkAnchor = previousThemeParkAnchor(stops, index);
        if (themeParkAnchor == null) {
            return false;
        }
        PlanDraft.Place next = stops.get(index + 1);
        if (!isStrictMealStop(next)) {
            return false;
        }
        if (themeParkAnchor.latitude() == null || themeParkAnchor.longitude() == null
                || stop.latitude() == null || stop.longitude() == null
                || next.latitude() == null || next.longitude() == null) {
            return false;
        }

        double anchorToCurrent = haversineMeters(themeParkAnchor.latitude(), themeParkAnchor.longitude(), stop.latitude(), stop.longitude());
        double currentToNext = haversineMeters(stop.latitude(), stop.longitude(), next.latitude(), next.longitude());
        double anchorToNext = haversineMeters(themeParkAnchor.latitude(), themeParkAnchor.longitude(), next.latitude(), next.longitude());
        double detourMeters = anchorToCurrent + currentToNext - anchorToNext;
        boolean closeToThemeParkOrDinner = anchorToCurrent <= 1_500 || currentToNext <= 1_500;
        if (!closeToThemeParkOrDinner && detourMeters > 1_500) {
            return true;
        }
        return anchorToNext <= 1_500 && detourMeters > 1_500;
    }

    private boolean isThemeParkDayConnectorCandidate(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || themeParkGovernanceService.isThemeParkLikeStop(stop)) {
            return false;
        }
        String slot = normalizeSlot(stop.timeSlot());
        return "sunset".equals(slot)
                || isParkLikeStop(stop)
                || isFlexibleStop(stop)
                || isSoftActivityStop(stop);
    }

    private PlanDraft.Place previousThemeParkAnchor(java.util.List<PlanDraft.Place> stops, int index) {
        for (int i = index - 1; i >= 0; i--) {
            PlanDraft.Place candidate = stops.get(i);
            if (themeParkGovernanceService.isThemeParkLikeStop(candidate)) {
                return candidate;
            }
            if (isStrictMealStop(candidate) && !"lunch".equals(normalizeSlot(candidate.mealType()))
                    && !"lunch".equals(normalizeSlot(candidate.timeSlot()))) {
                return null;
            }
        }
        return null;
    }

    private boolean isAreaReturnFlexibleStop(java.util.List<PlanDraft.Place> stops, int index) {
        if (index <= 0 || index >= stops.size() - 1) {
            return false;
        }
        PlanDraft.Place stop = stops.get(index);
        PlanDraft.Place previous = stops.get(index - 1);
        PlanDraft.Place next = stops.get(index + 1);
        if (isStrictMealStop(stop) || isStrictMealStop(previous)) {
            return false;
        }
        String currentArea = normalizedAreaLabel(stop);
        String previousArea = normalizedAreaLabel(previous);
        String nextArea = normalizedAreaLabel(next);
        if (currentArea.isBlank() || previousArea.isBlank() || nextArea.isBlank()) {
            return false;
        }
        if (areasEquivalent(currentArea, previousArea) || !areasEquivalent(currentArea, nextArea)) {
            return false;
        }
        for (int i = 0; i < index - 1; i++) {
            PlanDraft.Place earlier = stops.get(i);
            if (!isStrictMealStop(earlier) && areasEquivalent(currentArea, normalizedAreaLabel(earlier))) {
                return true;
            }
        }
        return false;
    }

    private boolean isAreaReturnDroppableStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category)
                || "nature".equals(category)
                || "shop".equals(category)
                || "market".equals(category)
                || "attraction".equals(category);
    }

    private String normalizedAreaLabel(PlanDraft.Place stop) {
        if (stop == null) {
            return "";
        }
        String area = normalizeSlot(stop.preferredArea());
        if (area.isEmpty()) {
            area = normalizeSlot(stop.suburb());
        }
        return area;
    }

    private boolean areasEquivalent(String left, String right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        return left.contains(right) || right.contains(left);
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

    private boolean wouldDropBelowMinNonMealStops(java.util.List<PlanDraft.Place> stops, PlanDraft.Place stop, int minNonMealStops) {
        return isCountedNonMealStop(stop) && countNonMealStops(stops) <= minNonMealStops;
    }

    private java.util.List<PlanDraft.Place> reorderStopsByTimeSlotIfMealOrderInvalid(java.util.List<PlanDraft.Place> stops) {
        if (stops == null || stops.size() < 2) {
            return stops == null ? java.util.List.of() : stops;
        }
        int lunchIndex = firstMealIndex(stops, "lunch");
        int dinnerIndex = firstMealIndex(stops, "dinner");
        boolean lunchAfterDinner = lunchIndex >= 0 && dinnerIndex >= 0 && lunchIndex > dinnerIndex;
        boolean lunchAfterLateDayStop = lunchIndex >= 0 && hasLateDayNonMealBefore(stops, lunchIndex);
        boolean dinnerBeforeMiddayStop = dinnerIndex >= 0 && hasMiddayOrAfternoonStopAfter(stops, dinnerIndex);
        if (!lunchAfterDinner && !lunchAfterLateDayStop && !dinnerBeforeMiddayStop) {
            return stops;
        }
        java.util.List<PlanDraft.Place> reordered = new java.util.ArrayList<>(stops);
        reordered.sort((left, right) -> Integer.compare(slotSortOrder(left), slotSortOrder(right)));
        return reordered;
    }

    private int firstMealIndex(java.util.List<PlanDraft.Place> stops, String meal) {
        for (int i = 0; i < stops.size(); i++) {
            if (hasMealSlot(stops.get(i), meal)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasLateDayNonMealBefore(java.util.List<PlanDraft.Place> stops, int index) {
        for (int i = 0; i < index; i++) {
            PlanDraft.Place stop = stops.get(i);
            if (isStrictMealStop(stop)) {
                continue;
            }
            String slot = normalizeSlot(stop.timeSlot());
            if ("afternoon".equals(slot) || "sunset".equals(slot) || "evening".equals(slot) || "night".equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMiddayOrAfternoonStopAfter(java.util.List<PlanDraft.Place> stops, int index) {
        for (int i = index + 1; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            if (isStrictMealStop(stop)) {
                continue;
            }
            String slot = normalizeSlot(stop.timeSlot());
            if ("morning".equals(slot) || "brunch".equals(slot) || "afternoon".equals(slot) || "sunset".equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private int slotSortOrder(PlanDraft.Place stop) {
        String mealType = normalizeSlot(stop == null ? null : stop.mealType());
        String slot = normalizeSlot(stop == null ? null : stop.timeSlot());
        if ("lunch".equals(mealType) || "lunch".equals(slot)) {
            return 30;
        }
        if ("dinner".equals(mealType) || "dinner".equals(slot)) {
            return 70;
        }
        return switch (slot) {
            case "morning" -> 10;
            case "brunch" -> 20;
            case "afternoon" -> 40;
            case "sunset" -> 50;
            case "evening" -> 60;
            case "night" -> 80;
            default -> 100;
        };
    }

    private boolean hasMealSlot(PlanDraft.Place stop, String slot) {
        return stop != null && (slot.equals(normalizeSlot(stop.mealType())) || slot.equals(normalizeSlot(stop.timeSlot())));
    }

    private String formatMinutes(int min) {
        int n = Math.max(0, Math.min(min, 1439));
        return LocalTime.of(n / 60, n % 60).format(TIME_FORMATTER);
    }

    private int transitionMinutes(boolean first) {
        return first ? 0 : 20;
    }

    private int preferredStartMinutes(String slot, boolean first) {
        return switch (normalizeSlot(slot)) {
            case "morning" -> first ? DAY_START_MINUTES : 9 * 60 + 30;
            case "brunch" -> 10 * 60 + 30;
            case "lunch" -> 12 * 60 + 15;
            case "afternoon" -> 14 * 60;
            case "sunset" -> 16 * 60 + 30;
            case "dinner", "evening" -> 18 * 60;
            case "night" -> 20 * 60;
            default -> first ? DAY_START_MINUTES : DAY_START_MINUTES + 60;
        };
    }

    private int chooseScheduledStart(int rollingStart, int preferredStart, PlanDraft.Place stop) {
        String normalizedSlot = normalizeSlot(stop == null ? null : stop.timeSlot());
        int earliestAllowed = timeSensitiveEarliestStart(stop);
        rollingStart = Math.max(rollingStart, earliestAllowed);
        preferredStart = Math.max(preferredStart, earliestAllowed);
        if ("lunch".equals(normalizedSlot)) {
            return chooseLunchStart(rollingStart, preferredStart);
        }
        if ("dinner".equals(normalizedSlot) || "evening".equals(normalizedSlot)) {
            int earliestDinnerStart = Math.max(rollingStart, DINNER_EARLIEST_START_MINUTES);
            if (earliestDinnerStart > DINNER_LATEST_START_MINUTES) {
                return earliestDinnerStart;
            }
            if (preferredStart <= earliestDinnerStart) {
                return earliestDinnerStart;
            }
            if (preferredStart - rollingStart > maxPreferredWaitMinutes(normalizedSlot)) {
                return earliestDinnerStart;
            }
            return Math.min(preferredStart, DINNER_LATEST_START_MINUTES);
        }
        if (preferredStart <= rollingStart) {
            return rollingStart;
        }
        int maxExtraWait = maxPreferredWaitMinutes(normalizedSlot);
        if (preferredStart - rollingStart > maxExtraWait) {
            return rollingStart;
        }
        return preferredStart;
    }

    private int timeSensitiveEarliestStart(PlanDraft.Place stop) {
        if (stop == null) {
            return 0;
        }
        if (isCulturalOpeningHoursConstrained(stop)) {
            return 10 * 60;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("penguin")) {
            return 16 * 60 + 30;
        }
        return 0;
    }

    private int chooseLunchStart(int rollingStart, int preferredStart) {
        int earliestLunchStart = Math.max(rollingStart, LUNCH_EARLIEST_START_MINUTES);
        int preferredWindowStart = Math.max(earliestLunchStart, LUNCH_PREFERRED_EARLIEST_START_MINUTES);
        int cappedPreferred = Math.max(LUNCH_PREFERRED_EARLIEST_START_MINUTES, Math.min(preferredStart, LUNCH_LATEST_START_MINUTES));
        if (rollingStart <= LUNCH_LATEST_START_MINUTES) {
            if (cappedPreferred <= preferredWindowStart) {
                return preferredWindowStart;
            }
            int maxExtraWait = maxPreferredWaitMinutes("lunch");
            if (cappedPreferred - preferredWindowStart > maxExtraWait) {
                return preferredWindowStart;
            }
            return cappedPreferred;
        }
        return rollingStart;
    }

    private int maxPreferredWaitMinutes(String slot) {
        return switch (slot) {
            case "lunch" -> 45;
            case "afternoon" -> 30;
            case "sunset" -> 45;
            case "dinner", "evening" -> 60;
            default -> 20;
        };
    }

    private PlanDraft.Place copyPlaceWithTimes(PlanDraft.Place stop, String start, String end, int stay) {
        return new PlanDraft.Place(
                stop.name(), stop.addressLine(), stop.suburb(), stop.city(), stop.state(), stop.postcode(), stop.country(),
                stop.category(), stay, stop.timeSlot(), start, end, stop.mealType(),
                stop.preferredArea(), stop.cuisine(), stop.vibe(), stop.budgetLevel(), stop.reason(), stop.tip(),
                stop.websiteUri(), stop.googleMapsUri(), stop.businessStatus(), stop.url(), stop.latitude(), stop.longitude()
        );
    }

    private PlanDraft rewriteDayNarratives(PlanDraft draft, Set<Integer> targetDayIndexes) {
        if (draft == null || draft.daysPlan() == null) {
            return draft;
        }
        java.util.List<PlanDraft.DayPlan> days = draft.daysPlan().stream().map(day -> {
            if (day == null) {
                return null;
            }
            if (targetDayIndexes != null && !targetDayIndexes.isEmpty() && !targetDayIndexes.contains(day.dayIndex())) {
                return day;
            }
            java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : day.stops().stream().map(this::normalizeNarrative).toList();
            boolean themeParkDay = stops.stream().anyMatch(themeParkGovernanceService::isThemeParkLikeStop);
            String morningNote = themeParkDay ? themeParkGovernanceService.sanitizeCopy(day.morningNote()) : day.morningNote();
            String afternoonNote = themeParkDay ? themeParkGovernanceService.sanitizeCopy(day.afternoonNote()) : day.afternoonNote();
            String eveningNote = themeParkDay ? themeParkGovernanceService.sanitizeCopy(day.eveningNote()) : day.eveningNote();
            String note = themeParkDay ? themeParkGovernanceService.sanitizeCopy(day.note()) : day.note();
            return new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), stops, day.theme(), morningNote, afternoonNote, eveningNote, note);
        }).toList();
        return withDays(draft, days);
    }

    PlanDraft pruneUnselectedShoppingStops(PlanDraft draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty() || isMarketShoppingSelected(req)) {
            return draft;
        }

        java.util.List<PlanDraft.DayPlan> updatedDays = new java.util.ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : day.stops();
            java.util.List<PlanDraft.Place> keptStops = new java.util.ArrayList<>();
            for (PlanDraft.Place stop : stops) {
                if (shouldDropUnselectedShoppingStop(stop)) {
                    changed = true;
                    continue;
                }
                keptStops.add(stop);
            }
            updatedDays.add(new PlanDraft.DayPlan(
                    day.dayIndex(),
                    day.hotel(),
                    keptStops,
                    day.theme(),
                    day.morningNote(),
                    day.afternoonNote(),
                    day.eveningNote(),
                    day.note()
            ));
        }

        return changed ? withDays(draft, updatedDays) : draft;
    }

    PlanDraft pruneExcessNonMealStops(PlanDraft draft, CreatePlanReq req) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int maxNonMealStops = maxNonMealStopsPerDay(draft.pace());
        java.util.List<PlanDraft.DayPlan> updatedDays = new java.util.ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            java.util.List<PlanDraft.Place> stops = day.stops() == null ? java.util.List.of() : day.stops();
            java.util.List<PlanDraft.Place> workingStops = new java.util.ArrayList<>(stops);
            while (countNonMealStops(workingStops) > maxNonMealStops) {
                int dropIndex = lowestValueNonMealStopIndex(workingStops, req);
                if (dropIndex < 0) {
                    break;
                }
                workingStops.remove(dropIndex);
                changed = true;
            }
            updatedDays.add(new PlanDraft.DayPlan(
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
        return changed ? withDays(draft, updatedDays) : draft;
    }

    private int maxNonMealStopsPerDay(String pace) {
        return daySkeletonService.nonMealRangeForPace(pace).max();
    }

    private int minNonMealStopsPerDay(String pace) {
        return daySkeletonService.nonMealRangeForPace(pace).min();
    }

    private int countNonMealStops(java.util.List<PlanDraft.Place> stops) {
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

    private int lowestValueNonMealStopIndex(java.util.List<PlanDraft.Place> stops, CreatePlanReq req) {
        int bestIndex = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            PlanDraft.Place stop = stops.get(i);
            if (!isCountedNonMealStop(stop) || themeParkGovernanceService.isThemeParkLikeStop(stop) || isSelectedMarketShoppingAnchor(stop, req)) {
                continue;
            }
            int score = nonMealStopValueScore(stops, i);
            if (score < bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private boolean isCountedNonMealStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return !isMealCategory(normalizeSlot(stop.category()));
    }

    private int nonMealStopValueScore(java.util.List<PlanDraft.Place> stops, int index) {
        PlanDraft.Place stop = stops.get(index);
        PlanDraft.Place previousStop = index > 0 ? stops.get(index - 1) : null;
        int score = 50 + attractionStrength(stop, previousStop) * 10;
        String category = normalizeSlot(stop.category());
        int stayMinutes = resolveStayMinutes(stop);
        if (isStrongPoiCandidate(stop)) score += 40;
        if (isParkLikeStop(stop)) score += 20;
        if ("museum".equals(category)) score += 30;
        if ("attraction".equals(category)) score += 5;
        if (stayMinutes <= 30) score -= 25;
        if (stayMinutes <= 45) score -= 10;
        if ("sunset".equals(normalizeSlot(stop.timeSlot()))) score -= 10;
        if (isLightweightStop(stop, previousStop)) score -= 30;
        if (index == 0) score += 8;
        String reason = nullToEmpty(stop.reason()).toLowerCase();
        if (reason.contains("punctuation mark") || reason.contains("brief stop")) {
            score -= 20;
        }
        return score;
    }

    private int attractionStrength(PlanDraft.Place stop, PlanDraft.Place previousStop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return 10;
        }
        String category = normalizeSlot(stop.category());
        int score = switch (category) {
            case "museum", "gallery", "theme_park" -> 5;
            case "park", "nature" -> 4;
            case "shop", "market" -> 2;
            case "attraction" -> 2;
            default -> 2;
        };

        int stayMinutes = resolveStayMinutes(stop);
        if (stayMinutes <= 30) {
            score -= 2;
        } else if (stayMinutes <= 50) {
            score -= 1;
        } else if (stayMinutes >= 75) {
            score += 1;
        }

        String name = nullToEmpty(stop.name()).toLowerCase();
        String text = name + " " + nullToEmpty(stop.addressLine()).toLowerCase();
        if (hasLightweightKeyword(text)) {
            score -= 2;
        }
        if (isAttachedToPreviousStop(stop, previousStop)) {
            score -= 2;
        }
        if (isStrongPoiCandidate(stop)) {
            score += 2;
        }
        if ("museum".equals(category) && stayMinutes >= 60) {
            score += 2;
        }
        return score;
    }

    private boolean hasLightweightKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("rooftop")
                || text.contains("terrace")
                || text.contains("forecourt")
                || text.contains("plaza")
                || text.contains("mall")
                || text.contains(" riverwalk")
                || text.contains("walk ")
                || text.contains("promenade")
                || text.contains("lagoon")
                || text.contains("square")
                || text.contains("exterior");
    }

    private boolean isAttachedToPreviousStop(PlanDraft.Place stop, PlanDraft.Place previousStop) {
        if (stop == null || previousStop == null) {
            return false;
        }
        String currentName = nullToEmpty(stop.name()).toLowerCase();
        String previousName = nullToEmpty(previousStop.name()).toLowerCase();
        if (currentName.isBlank() || previousName.isBlank()) {
            return false;
        }
        boolean nameLooksAttached = currentName.contains(previousName)
                || previousName.contains(currentName)
                || sharesMeaningfulToken(currentName, previousName);
        boolean sameArea = !nullToEmpty(stop.addressLine()).isBlank()
                && !nullToEmpty(previousStop.addressLine()).isBlank()
                && normalizedAddressAnchor(stop.addressLine()).equals(normalizedAddressAnchor(previousStop.addressLine()));
        return nameLooksAttached && sameArea;
    }

    private boolean sharesMeaningfulToken(String left, String right) {
        for (String token : left.split("[^a-z0-9]+")) {
            if (token.length() >= 4 && right.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedAddressAnchor(String value) {
        if (value == null) return "";
        String[] parts = value.split(",");
        if (parts.length == 0) return "";
        String first = parts[0].trim().toLowerCase();
        return first.replaceAll("^\\d+\\s+", "");
    }

    private boolean isStrongPoiCandidate(PlanDraft.Place stop) {
        if (stop == null) return false;
        if (stop.googleMapsUri() != null && !stop.googleMapsUri().isBlank()) return true;
        String category = normalizeSlot(stop.category());
        if ("museum".equals(category) || "gallery".equals(category) || "theme_park".equals(category) || "zoo".equals(category)) {
            return true;
        }
        String name = stop.name() == null ? "" : stop.name().toLowerCase();
        return name.contains("cathedral") || name.contains("parliament") || name.contains("opera house") || name.contains("bridge") && name.contains("harbour");
    }

    private boolean isLightweightStop(PlanDraft.Place stop, PlanDraft.Place previousStop) {
        return attractionStrength(stop, previousStop) <= 2;
    }

    private boolean isSelectedMarketShoppingAnchor(PlanDraft.Place stop, CreatePlanReq req) {
        if (!isMarketShoppingSelected(req) || stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        return isMarketShoppingLikeStop(stop);
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
        ).toLowerCase(java.util.Locale.ROOT);
        return text.contains("market")
                || text.contains("arcade")
                || text.contains("shopping")
                || text.contains("retail")
                || text.contains("food hall")
                || text.contains("bazaar");
    }

    private int resolveStayMinutes(PlanDraft.Place stop) {
        if (stop == null) {
            return 60;
        }
        if (stop.stayMinutes() != null && stop.stayMinutes() > 0) {
            return stop.stayMinutes();
        }
        int start = parseTimeMinutes(stop.startTime());
        int end = parseTimeMinutes(stop.endTime());
        return (start >= 0 && end > start) ? (end - start) : 60;
    }

    private int parseTimeMinutes(String val) {
        if (val == null || !val.matches("^\\d{2}:\\d{2}$")) {
            return -1;
        }
        return Integer.parseInt(val.substring(0, 2)) * 60 + Integer.parseInt(val.substring(3, 5));
    }

    private boolean shouldDropUnselectedShoppingStop(PlanDraft.Place stop) {
        if (stop == null || isStrictMealStop(stop) || "hotel".equals(normalizeSlot(stop.category()))) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        if ("shop".equals(category) || "shopping".equals(category) || "market".equals(category)) {
            return true;
        }
        String primaryText = String.join(" ",
                nullToEmpty(stop.name()),
                nullToEmpty(stop.addressLine()),
                category
        ).toLowerCase();
        return primaryText.contains("shopping")
                || primaryText.contains("retail")
                || primaryText.contains(" mall")
                || primaryText.contains("arcade")
                || primaryText.contains("outlet")
                || primaryText.contains("marketplace")
                || primaryText.contains("shopping centre")
                || primaryText.contains("shopping center");
    }

    private boolean isMarketShoppingSelected(CreatePlanReq req) {
        if (req == null || req.style() == null) {
            return false;
        }
        return req.style().stream()
                .map(this::normalizeSlot)
                .anyMatch("market_shopping"::equals);
    }

    private PlanDraft.Place normalizeNarrative(PlanDraft.Place stop) {
        if (stop == null || isMealCategory(normalizeSlot(stop.category()))) {
            return stop;
        }
        String category = normalizeSlot(stop.category());
        String reason = stop.reason();
        String tip = stop.tip();
        String area = displayArea(stop);
        if (themeParkGovernanceService.isThemeParkLikeStop(stop)) {
            reason = themeParkGovernanceService.sanitizeCopy(reason);
            tip = themeParkGovernanceService.sanitizeCopy(tip);
        } else if ("museum".equals(category)) {
            reason = stop.name() + " works as the day's main cultural block around " + area + ", giving the route substance before the lighter stops.";
            tip = "Check current exhibitions and ticket details before you set out.";
        } else if (isParkLikeStop(stop)) {
            reason = stop.name() + " gives the route an outdoor reset around " + area + " without adding another heavy indoor stop.";
            tip = "Keep this flexible for weather and energy, and shorten it if the day starts running late.";
        } else if ("attraction".equals(category)) {
            reason = stop.name() + " adds a compact sightseeing stop around " + area + " without making the day too dense.";
            tip = "Confirm current access details before visiting if it depends on scheduled entry or events.";
        }
        return copyPlaceWithNarrative(stop, reason, tip);
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private boolean isStrictMealStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "restaurant".equals(category) || "cafe".equals(category) || "food".equals(category);
    }

    private boolean isParkLikeStop(PlanDraft.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalizeSlot(stop.category());
        return "park".equals(category) || "nature".equals(category);
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

    private PlanDraft.Place copyPlaceWithNarrative(PlanDraft.Place stop, String reason, String tip) {
        return new PlanDraft.Place(
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
                reason,
                tip,
                stop.websiteUri(),
                stop.googleMapsUri(),
                stop.businessStatus(),
                stop.url(),
                stop.latitude(),
                stop.longitude()
        );
    }

    private PlanDraft withDays(PlanDraft draft, java.util.List<PlanDraft.DayPlan> days) {
        return new PlanDraft(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                days == null ? java.util.List.of() : days,
                draft.copyPolishStatus()
        );
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
