package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.domain.PlanDraft;
import thesis.project.gu.planning.scheduling.DaySkeletonService;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class MealTimeWindowRepairService {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final int LUNCH_EARLIEST_START_MINUTES = 11 * 60 + 15;
    private static final int LUNCH_LATEST_START_MINUTES = 13 * 60;
    private static final int THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES = 14 * 60 + 30;
    private static final int DINNER_EARLIEST_START_MINUTES = 17 * 60 + 30;
    private static final int DINNER_LATEST_START_MINUTES = 20 * 60;
    private static final int THEME_PARK_DAY_DINNER_LATEST_START_MINUTES = 20 * 60 + 30;

    private final DaySkeletonService daySkeletonService;
    private final ThemeParkGovernanceService themeParkGovernanceService;

    public MealTimeWindowRepairService(
            DaySkeletonService daySkeletonService,
            ThemeParkGovernanceService themeParkGovernanceService
    ) {
        this.daySkeletonService = daySkeletonService;
        this.themeParkGovernanceService = themeParkGovernanceService;
    }

    public PlanDraftResponse repairResponse(PlanDraftResponse draft) {
        PlanDraft repaired = repair(PlanDraft.fromResponse(draft));
        return repaired == null ? null : repaired.toResponse();
    }

    public PlanDraft repair(PlanDraft draft) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return draft;
        }
        int minNonMealStops = minNonMealStopsPerDay(draft.pace());
        List<PlanDraft.DayPlan> updatedDays = new ArrayList<>();
        boolean changed = false;
        for (PlanDraft.DayPlan day : draft.daysPlan()) {
            if (day == null || day.stops() == null || day.stops().isEmpty()) {
                updatedDays.add(day);
                continue;
            }
            List<PlanDraft.Place> updatedStops = new ArrayList<>(day.stops());
            boolean dayChanged = false;
            for (int i = 0; i < updatedStops.size(); i++) {
                PlanDraft.Place stop = updatedStops.get(i);
                int start = parseTimeMinutes(stop == null ? null : stop.startTime());
                if (start < 0 || (!hasMealSlot(stop, "lunch") && !hasMealSlot(stop, "dinner"))) {
                    continue;
                }
                int earliest = mealEarliestStart(stop);
                int latest = mealLatestStart(updatedStops, i);
                if (start >= earliest && start <= latest) {
                    continue;
                }
                if (start < earliest) {
                    int stay = resolveStayMinutes(stop);
                    updatedStops.set(i, copyPlaceWithTimes(stop, formatMinutes(earliest), formatMinutes(earliest + stay), stay));
                    dayChanged = true;
                    changed = true;
                    continue;
                }
                if (repairLateMealByAdjustingPreviousStop(updatedStops, i, latest, minNonMealStops)) {
                    dayChanged = true;
                    changed = true;
                }
            }
            updatedDays.add(dayChanged
                    ? new PlanDraft.DayPlan(day.dayIndex(), day.hotel(), updatedStops, day.theme(), day.morningNote(), day.afternoonNote(), day.eveningNote(), day.note())
                    : day);
        }
        if (!changed) {
            return draft;
        }
        return new PlanDraft(
                draft.city(),
                draft.country(),
                draft.days(),
                draft.currency(),
                draft.party(),
                draft.pace(),
                draft.title(),
                draft.overview(),
                updatedDays,
                draft.copyPolishStatus()
        );
    }

    private boolean repairLateMealByAdjustingPreviousStop(List<PlanDraft.Place> stops, int mealIndex, int latestMealStart, int minNonMealStops) {
        if (stops == null || mealIndex <= 0 || mealIndex >= stops.size()) {
            return false;
        }
        PlanDraft.Place previous = stops.get(mealIndex - 1);
        if (previous == null || isFoodStop(previous)) {
            return false;
        }
        int previousStart = parseTimeMinutes(previous.startTime());
        int previousEnd = parseTimeMinutes(previous.endTime());
        int targetPreviousEnd = latestMealStart - transitionMinutes(false);
        int minPreviousStay = Math.min(45, Math.max(30, resolveStayMinutes(previous) / 2));
        if (previousStart >= 0 && previousEnd > previousStart && targetPreviousEnd >= previousStart + minPreviousStay && targetPreviousEnd < previousEnd) {
            stops.set(mealIndex - 1, copyPlaceWithTimes(previous, formatMinutes(previousStart), formatMinutes(targetPreviousEnd), targetPreviousEnd - previousStart));
            return true;
        }
        if (canDropDuplicateStopSafely(stops, previous, mealIndex - 1, minNonMealStops)) {
            stops.remove(mealIndex - 1);
            return true;
        }
        return false;
    }

    private int mealEarliestStart(PlanDraft.Place stop) {
        if (hasMealSlot(stop, "lunch")) {
            return LUNCH_EARLIEST_START_MINUTES;
        }
        if (hasMealSlot(stop, "dinner")) {
            return DINNER_EARLIEST_START_MINUTES;
        }
        return 0;
    }

    private int mealLatestStart(List<PlanDraft.Place> stops, int index) {
        PlanDraft.Place stop = stops == null || index < 0 || index >= stops.size() ? null : stops.get(index);
        if (hasMealSlot(stop, "lunch")) {
            boolean themeParkDay = stops != null && stops.stream().anyMatch(this::isThemeParkLikeStop);
            return themeParkDay ? THEME_PARK_DAY_LUNCH_LATEST_START_MINUTES : LUNCH_LATEST_START_MINUTES;
        }
        if (hasMealSlot(stop, "dinner")) {
            return hasThemeParkBeforeIndex(stops, index) ? THEME_PARK_DAY_DINNER_LATEST_START_MINUTES : DINNER_LATEST_START_MINUTES;
        }
        return Integer.MAX_VALUE;
    }

    private boolean canDropDuplicateStopSafely(List<PlanDraft.Place> stops, PlanDraft.Place stop, int stopIndex, int minNonMealStops) {
        if (wouldDropBelowMinNonMealStops(stops, stop, minNonMealStops)) {
            return false;
        }
        return !isOnlyNonMealStopInDayPhase(stops, stop, stopIndex);
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
        for (PlanDraft.Place candidate : stops) {
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

    private boolean wouldDropBelowMinNonMealStops(List<PlanDraft.Place> stops, PlanDraft.Place stop, int minNonMealStops) {
        return isCountedNonMealStop(stop) && countNonMealStops(stops) <= minNonMealStops;
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

    private boolean hasMealSlot(PlanDraft.Place stop, String slot) {
        return stop != null && (slot.equals(normalizeSlot(stop.mealType())) || slot.equals(normalizeSlot(stop.timeSlot())));
    }

    private boolean hasThemeParkBeforeIndex(List<PlanDraft.Place> stops, int index) {
        if (stops == null || index <= 0) {
            return false;
        }
        for (int i = 0; i < Math.min(index, stops.size()); i++) {
            if (isThemeParkLikeStop(stops.get(i))) {
                return true;
            }
        }
        return false;
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

    private String formatMinutes(int min) {
        int n = Math.max(0, Math.min(min, 1439));
        return LocalTime.of(n / 60, n % 60).format(TIME_FORMATTER);
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

    private int transitionMinutes(boolean first) {
        return first ? 0 : 20;
    }

    private String normalizeSlot(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private boolean isThemeParkLikeStop(PlanDraft.Place stop) {
        return themeParkGovernanceService.isThemeParkLikeStop(stop);
    }

    private PlanDraft.Place copyPlaceWithTimes(PlanDraft.Place stop, String start, String end, int stay) {
        return new PlanDraft.Place(
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
}
