package thesis.project.gu.service;

import org.springframework.stereotype.Service;
import thesis.project.gu.response.PlanDraftResponse;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LocalPlanQualityDiagnosticService {
    private static final int LUNCH_EARLIEST_MINUTES = 11 * 60 + 30;
    private static final int LUNCH_LATEST_MINUTES = 13 * 60 + 30;
    private static final int DINNER_EARLIEST_MINUTES = 17 * 60;
    private static final int DINNER_LATEST_MINUTES = 20 * 60 + 30;
    private static final int MAX_NORMAL_NON_MEAL_STOPS = 4;
    private static final int MIN_NORMAL_NON_MEAL_STOPS = 2;
    private static final int MAX_REASONABLE_DAY_END_MINUTES = 21 * 60;
    private static final double LONG_JUMP_KM = 8.0;
    private static final double VERY_LONG_JUMP_KM = 12.0;
    private static final double FAR_MEAL_KM = 5.0;
    private static final int SHORT_TRANSFER_MINUTES = 45;
    private static final int MEDIUM_TRANSFER_MINUTES = 75;

    public LocalPlanQualityReport diagnose(PlanDraftResponse draft) {
        List<LocalPlanQualityReport.Warning> warnings = new ArrayList<>();
        if (draft == null) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "draft-null", null, "Plan draft is null.");
            return report(warnings);
        }
        if (draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "days-empty", null, "Plan has no day plans.");
            return report(warnings);
        }
        if (draft.days() != draft.daysPlan().size()) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "days-count-mismatch", null,
                    "Declared days does not match actual day plan count.");
        }

        Set<String> usedNonMealStops = new HashSet<>();
        for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
            diagnoseDay(day, usedNonMealStops, warnings);
        }
        return report(warnings);
    }

    private void diagnoseDay(
            PlanDraftResponse.DayPlan day,
            Set<String> usedNonMealStops,
            List<LocalPlanQualityReport.Warning> warnings
    ) {
        if (day == null) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "day-null", null, "A day plan is null.");
            return;
        }
        if (day.stops() == null || day.stops().isEmpty()) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "day-stops-empty", day.dayIndex(), "Day has no stops.");
            return;
        }

        int nonMealCount = 0;
        int lunchCount = 0;
        int dinnerCount = 0;
        Integer previousEnd = null;
        PlanDraftResponse.Place previous = null;
        Map<String, Integer> nonMealAreaCounts = new HashMap<>();
        List<PlanDraftResponse.Place> nonMealStops = new ArrayList<>();

        for (PlanDraftResponse.Place stop : day.stops()) {
            if (stop == null) {
                add(warnings, LocalPlanQualityReport.Severity.ERROR, "stop-null", day.dayIndex(), "Day contains a null stop.");
                continue;
            }
            Integer start = parseMinutes(stop.startTime());
            Integer end = parseMinutes(stop.endTime());
            if (start != null && end != null && end <= start) {
                add(warnings, LocalPlanQualityReport.Severity.ERROR, "time-invalid", day.dayIndex(),
                        stop.name() + " has an invalid time range.");
            }
            if (previousEnd != null && start != null && start < previousEnd) {
                add(warnings, LocalPlanQualityReport.Severity.ERROR, "time-overlap", day.dayIndex(),
                        stop.name() + " overlaps the previous stop.");
            }
            if (previous != null) {
                diagnoseTransfer(day.dayIndex(), previous, stop, warnings);
            }
            previousEnd = end;
            previous = stop;

            if (isMeal(stop, "lunch")) {
                lunchCount++;
                diagnoseMealWindow(day.dayIndex(), stop, "lunch", start, LUNCH_EARLIEST_MINUTES, LUNCH_LATEST_MINUTES, warnings);
                diagnoseMealDistance(day.dayIndex(), day.stops(), stop, warnings);
                continue;
            }
            if (isMeal(stop, "dinner")) {
                dinnerCount++;
                diagnoseMealWindow(day.dayIndex(), stop, "dinner", start, DINNER_EARLIEST_MINUTES, DINNER_LATEST_MINUTES, warnings);
                diagnoseMealDistance(day.dayIndex(), day.stops(), stop, warnings);
                continue;
            }

            nonMealCount++;
            nonMealStops.add(stop);
            String key = identityKey(stop);
            if (!key.isBlank() && !usedNonMealStops.add(key)) {
                add(warnings, LocalPlanQualityReport.Severity.ERROR, "duplicate-non-meal", day.dayIndex(),
                        stop.name() + " is reused as a non-meal stop.");
            }
            String area = normalizeArea(stop);
            if (!area.isBlank()) {
                nonMealAreaCounts.merge(area, 1, Integer::sum);
            }
        }

        if (lunchCount != 1) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "lunch-count", day.dayIndex(),
                    "Day should contain exactly one lunch stop.");
        }
        if (dinnerCount != 1) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, "dinner-count", day.dayIndex(),
                    "Day should contain exactly one dinner stop.");
        }
        if (nonMealCount < MIN_NORMAL_NON_MEAL_STOPS || nonMealCount > MAX_NORMAL_NON_MEAL_STOPS) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "day-density", day.dayIndex(),
                    "Day has " + nonMealCount + " non-meal stops; normal pace should usually stay between 2 and 4.");
        }
        if (previousEnd != null && previousEnd > MAX_REASONABLE_DAY_END_MINUTES) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "day-ends-late", day.dayIndex(),
                    "Day ends after 21:00.");
        }
        diagnoseThemeConsistency(day, nonMealAreaCounts, nonMealStops, warnings);
    }

    private void diagnoseTransfer(
            int dayIndex,
            PlanDraftResponse.Place previous,
            PlanDraftResponse.Place current,
            List<LocalPlanQualityReport.Warning> warnings
    ) {
        Double km = distanceKm(previous, current);
        Integer previousEnd = parseMinutes(previous.endTime());
        Integer currentStart = parseMinutes(current.startTime());
        Integer gap = previousEnd == null || currentStart == null ? null : currentStart - previousEnd;
        if (km == null || gap == null) {
            return;
        }
        if (km > VERY_LONG_JUMP_KM && gap < MEDIUM_TRANSFER_MINUTES) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "very-long-transfer", dayIndex,
                    current.name() + " is " + rounded(km) + "km after " + previous.name() + " with only " + gap + " minutes transfer.");
        } else if (km > LONG_JUMP_KM && gap < SHORT_TRANSFER_MINUTES) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "long-transfer", dayIndex,
                    current.name() + " is " + rounded(km) + "km after " + previous.name() + " with only " + gap + " minutes transfer.");
        }
    }

    private void diagnoseMealDistance(
            int dayIndex,
            List<PlanDraftResponse.Place> stops,
            PlanDraftResponse.Place meal,
            List<LocalPlanQualityReport.Warning> warnings
    ) {
        int mealIndex = stops.indexOf(meal);
        PlanDraftResponse.Place nearestNonMeal = null;
        for (int i = mealIndex - 1; i >= 0; i--) {
            if (!isMeal(stops.get(i))) {
                nearestNonMeal = stops.get(i);
                break;
            }
        }
        if (nearestNonMeal == null) {
            return;
        }
        Double km = distanceKm(nearestNonMeal, meal);
        Integer previousEnd = parseMinutes(nearestNonMeal.endTime());
        Integer mealStart = parseMinutes(meal.startTime());
        Integer gap = previousEnd == null || mealStart == null ? null : mealStart - previousEnd;
        if (km != null && gap != null && km > FAR_MEAL_KM && gap < SHORT_TRANSFER_MINUTES) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "meal-too-far", dayIndex,
                    meal.name() + " is " + rounded(km) + "km from the previous non-meal stop with only " + gap + " minutes transfer.");
        }
    }

    private void diagnoseMealWindow(
            int dayIndex,
            PlanDraftResponse.Place stop,
            String mealType,
            Integer start,
            int earliest,
            int latest,
            List<LocalPlanQualityReport.Warning> warnings
    ) {
        if (start == null) {
            add(warnings, LocalPlanQualityReport.Severity.ERROR, mealType + "-time-missing", dayIndex,
                    stop.name() + " has no " + mealType + " start time.");
            return;
        }
        if (start < earliest || start > latest) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, mealType + "-window", dayIndex,
                    stop.name() + " starts at " + stop.startTime() + ", outside the practical " + mealType + " window.");
        }
    }

    private void diagnoseThemeConsistency(
            PlanDraftResponse.DayPlan day,
            Map<String, Integer> nonMealAreaCounts,
            List<PlanDraftResponse.Place> nonMealStops,
            List<LocalPlanQualityReport.Warning> warnings
    ) {
        if (nonMealStops.isEmpty() || nonMealAreaCounts.isEmpty()) {
            return;
        }
        String dominantArea = nonMealAreaCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        long offAreaCount = nonMealStops.stream()
                .filter(stop -> !dominantArea.equals(normalizeArea(stop)))
                .count();
        if (offAreaCount > 0) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "theme-area-mixed", day.dayIndex(),
                    "Day mixes " + offAreaCount + " non-meal stop(s) outside the dominant area " + dominantArea + ".");
        }
        String themeText = normalize(day.theme() + " " + day.note());
        if (!dominantArea.isBlank() && !themeText.contains(dominantArea)) {
            add(warnings, LocalPlanQualityReport.Severity.WARNING, "theme-area-missing", day.dayIndex(),
                    "Theme/note does not mention the dominant area " + dominantArea + ".");
        }
    }

    private LocalPlanQualityReport report(List<LocalPlanQualityReport.Warning> warnings) {
        int errors = 0;
        int warningCount = 0;
        int penalty = 0;
        for (LocalPlanQualityReport.Warning warning : warnings) {
            if (warning.severity() == LocalPlanQualityReport.Severity.ERROR) {
                errors++;
                penalty += 15;
            } else {
                warningCount++;
                penalty += 2;
            }
        }
        return new LocalPlanQualityReport(Math.max(0, 100 - penalty), errors, warningCount, List.copyOf(warnings));
    }

    private void add(
            List<LocalPlanQualityReport.Warning> warnings,
            LocalPlanQualityReport.Severity severity,
            String code,
            Integer dayIndex,
            String message
    ) {
        warnings.add(new LocalPlanQualityReport.Warning(severity, code, dayIndex, message));
    }

    private boolean isMeal(PlanDraftResponse.Place stop) {
        return isMeal(stop, "lunch") || isMeal(stop, "dinner");
    }

    private boolean isMeal(PlanDraftResponse.Place stop, String mealType) {
        if (stop == null || mealType == null) {
            return false;
        }
        String target = normalize(mealType);
        return target.equals(normalize(stop.mealType())) || target.equals(normalize(stop.timeSlot()));
    }

    private String identityKey(PlanDraftResponse.Place stop) {
        return normalize(stop == null ? null : stop.name()) + "|" + normalize(stop == null ? null : stop.addressLine());
    }

    private String normalizeArea(PlanDraftResponse.Place stop) {
        if (stop == null) {
            return "";
        }
        String preferredArea = normalize(stop.preferredArea());
        return preferredArea.isBlank() ? normalize(stop.suburb()) : preferredArea;
    }

    private Integer parseMinutes(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            LocalTime parsed = LocalTime.parse(time.trim());
            return parsed.getHour() * 60 + parsed.getMinute();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double distanceKm(PlanDraftResponse.Place left, PlanDraftResponse.Place right) {
        if (left == null || right == null || left.latitude() == null || left.longitude() == null
                || right.latitude() == null || right.longitude() == null) {
            return null;
        }
        double lat1 = Math.toRadians(left.latitude());
        double lat2 = Math.toRadians(right.latitude());
        double deltaLat = Math.toRadians(right.latitude() - left.latitude());
        double deltaLon = Math.toRadians(right.longitude() - left.longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String rounded(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
