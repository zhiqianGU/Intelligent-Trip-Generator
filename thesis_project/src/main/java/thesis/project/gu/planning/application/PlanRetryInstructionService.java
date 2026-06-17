package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.api.dto.PlanDraftResponse;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.DayPlan;
import thesis.project.gu.planning.api.dto.PlanDraftResponse.Place;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PlanRetryInstructionService {
    private static final int RETRY_ISSUE_SUMMARY_LIMIT = 12;
    private static final int RETRY_ISSUE_SUMMARY_MAX_CHARS = 900;
    private static final int RETRY_SKELETON_DAY_HINTS_LIMIT = 6;
    private static final int RETRY_SKELETON_HINTS_MAX_CHARS = 1200;
    private static final int RETRY_SCOPED_DAY_CONTEXT_MAX_CHARS = 2200;
    private static final int RETRY_STABLE_FIELDS_MAX_CHARS = 1200;
    private static final Pattern STOP_ISSUE_PATTERN = Pattern.compile("^day-(\\d+)-stop-(\\d+)-(.+)$");

    public String wholePlanInstruction(
            CreatePlanReq req,
            List<String> validationIssues,
            SkeletonHints skeletonHints,
            PlanDraftResponse failedDraft,
            Operations operations
    ) {
        int requestedDays = req == null ? 0 : req.days();
        String dayInstruction = requestedDays > 0
                ? " Please return exactly " + requestedDays + " days: days=" + requestedDays + " and daysPlan length=" + requestedDays + "."
                : "";
        String issueInstruction = buildWholePlanIssueRetryInstruction(validationIssues);
        String duplicateInstruction = buildWholePlanDuplicateRetryInstruction(validationIssues);
        String skeletonInstruction = compactSkeletonHints(skeletonHints, validationIssues);
        String skeletonClause = skeletonInstruction.isBlank()
                ? ""
                : " Follow these day-level skeleton constraints strictly: " + skeletonInstruction + ".";
        String scopedRepairClause = buildWholePlanScopedRetryInstruction(failedDraft, validationIssues, skeletonHints, operations);
        String stableFieldClause = buildWholePlanStableFieldInstruction(failedDraft, validationIssues, operations);
        String hardConstraintClause = " Hard constraints: include exactly one real lunch and one real dinner per day, keep adjacent stop gaps tight, keep non-meal sightseeing POIs unique across days, and avoid remote district out-and-back routing on the same day.";
        String compactOutputClause = " Keep all strings minimal. Use the shortest valid title, overview, theme, note, reason, and tip text that still satisfies the schema. Do not expand unchanged content.";
        return "Please generate a valid itinerary."
                + hardConstraintClause
                + compactOutputClause
                + dayInstruction
                + issueInstruction
                + duplicateInstruction
                + scopedRepairClause
                + stableFieldClause
                + skeletonClause;
    }

    public String compactSkeletonHints(SkeletonHints skeletonHints, List<String> validationIssues) {
        if (skeletonHints == null) {
            return "";
        }
        List<Integer> dayIndexes = extractRetryDayIndexes(validationIssues);
        if (dayIndexes.isEmpty()) {
            return trimToMaxChars(skeletonHints.promptHintsText(), RETRY_SKELETON_HINTS_MAX_CHARS);
        }
        List<Integer> scopedDays = dayIndexes.stream()
                .distinct()
                .sorted()
                .limit(RETRY_SKELETON_DAY_HINTS_LIMIT)
                .toList();
        List<String> dayHints = scopedDays.stream()
                .map(skeletonHints::promptHintForDay)
                .filter(hint -> hint != null && !hint.isBlank())
                .toList();
        if (dayHints.isEmpty()) {
            return trimToMaxChars(skeletonHints.promptHintsText(), RETRY_SKELETON_HINTS_MAX_CHARS);
        }
        String compact = String.join("; ", dayHints);
        if (dayIndexes.size() > scopedDays.size()) {
            compact = compact + "; ...(+" + (dayIndexes.size() - scopedDays.size()) + " more days)";
        }
        return trimToMaxChars(compact, RETRY_SKELETON_HINTS_MAX_CHARS);
    }

    public String dayInstruction(
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse failedDraft,
            SkeletonHints skeletonHints,
            Operations operations
    ) {
        String issueInstruction = buildRetryIssueInstructionForDay(dayIndex, dayIssues, failedDraft, operations);
        String dayContext = buildRetryDayContext(failedDraft, dayIndex, skeletonHints, operations);
        String contextClause = dayContext.isBlank()
                ? ""
                : " Current day context: " + dayContext + ".";
        String preservationClause = buildRetryPreservationInstruction(failedDraft, dayIndex, dayIssues, operations);
        String skeletonInstruction = skeletonHints == null ? "" : skeletonHints.promptHintForDay(dayIndex);
        String skeletonClause = skeletonInstruction.isBlank()
                ? ""
                : " Follow this day skeleton strictly: " + skeletonInstruction + ".";
        return "Please regenerate only day " + dayIndex + " as a valid itinerary day."
                + preservationClause
                + contextClause
                + issueInstruction
                + skeletonClause;
    }

    public String wholePlanDayInstruction(
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse failedDraft,
            SkeletonHints skeletonHints,
            String nonDayIssueInstruction,
            Operations operations
    ) {
        String baseInstruction = dayInstruction(dayIndex, dayIssues, failedDraft, skeletonHints, operations);
        String wholeTripClause = nonDayIssueInstruction == null || nonDayIssueInstruction.isBlank()
                ? ""
                : " " + nonDayIssueInstruction;
        String compactClause = " Keep strings minimal and do not rewrite already-valid content unless needed to resolve the listed whole-trip issues.";
        return baseInstruction + wholeTripClause + compactClause;
    }

    public String wholePlanNonDayIssueInstruction(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<String> nonDayIssues = validationIssues.stream()
                .filter(issue -> issue != null && !issue.isBlank())
                .filter(issue -> extractDayIndex(issue) == null)
                .distinct()
                .limit(RETRY_ISSUE_SUMMARY_LIMIT)
                .toList();
        String duplicateInstruction = buildWholePlanDuplicateRetryInstruction(validationIssues);
        if (nonDayIssues.isEmpty()) {
            return duplicateInstruction.isBlank() ? "" : duplicateInstruction.trim();
        }
        String summary = trimToMaxChars(String.join(", ", nonDayIssues), RETRY_ISSUE_SUMMARY_MAX_CHARS);
        if (duplicateInstruction.isBlank()) {
            return "Help resolve these whole-trip issues while keeping this day stable: " + summary + ".";
        }
        return "Help resolve these whole-trip issues while keeping this day stable: "
                + summary
                + ". "
                + duplicateInstruction.trim();
    }

    public List<Integer> extractRetryDayIndexes(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return List.of();
        }
        List<Integer> dayIndexes = new ArrayList<>();
        for (String issue : validationIssues) {
            Integer dayIndex = extractDayIndex(issue);
            if (dayIndex == null || dayIndex < 1) {
                continue;
            }
            if (!dayIndexes.contains(dayIndex)) {
                dayIndexes.add(dayIndex);
            }
        }
        return dayIndexes;
    }

    public Map<Integer, List<String>> groupIssuesByDay(List<String> validationIssues) {
        Map<Integer, List<String>> issuesByDay = new java.util.LinkedHashMap<>();
        if (validationIssues == null || validationIssues.isEmpty()) {
            return issuesByDay;
        }
        for (String issue : validationIssues) {
            Integer dayIndex = extractDayIndex(issue);
            if (dayIndex == null || dayIndex < 1) {
                continue;
            }
            issuesByDay.computeIfAbsent(dayIndex, key -> new ArrayList<>()).add(issue);
        }
        return issuesByDay;
    }

    public Integer extractDayIndex(String issue) {
        if (issue == null || !issue.startsWith("day-")) {
            return null;
        }
        int secondDash = issue.indexOf('-', 4);
        if (secondDash < 0) {
            return null;
        }
        String token = issue.substring(4, secondDash);
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String buildWholePlanIssueRetryInstruction(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<String> normalizedIssues = validationIssues.stream()
                .filter(issue -> issue != null && !issue.isBlank())
                .distinct()
                .toList();
        if (normalizedIssues.isEmpty()) {
            return "";
        }
        int cappedSize = Math.min(RETRY_ISSUE_SUMMARY_LIMIT, normalizedIssues.size());
        String summary = String.join(", ", normalizedIssues.subList(0, cappedSize));
        if (normalizedIssues.size() > cappedSize) {
            summary = summary + ", ...(+" + (normalizedIssues.size() - cappedSize) + " more)";
        }
        return " Fix these highest-priority validation issues first: "
                + trimToMaxChars(summary, RETRY_ISSUE_SUMMARY_MAX_CHARS)
                + ".";
    }

    private String buildWholePlanDuplicateRetryInstruction(List<String> validationIssues) {
        if (!hasAnyIssue(validationIssues, "-duplicate-poi-across-days")) {
            return "";
        }
        return " Eliminate all cross-day duplicate POIs. Every non-meal attraction, museum, park, lookout, landmark, or similar sightseeing stop must appear on only one day.";
    }

    private String buildWholePlanScopedRetryInstruction(
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            SkeletonHints skeletonHints,
            Operations operations
    ) {
        if (failedDraft == null || validationIssues == null || validationIssues.isEmpty()) {
            return "";
        }
        List<Integer> scopedDays = extractRetryDayIndexes(validationIssues).stream()
                .distinct()
                .sorted()
                .limit(RETRY_SKELETON_DAY_HINTS_LIMIT)
                .toList();
        if (scopedDays.isEmpty()) {
            return "";
        }
        Map<Integer, List<String>> issuesByDay = groupIssuesByDay(validationIssues);
        List<String> scopedClauses = new ArrayList<>();
        for (Integer dayIndex : scopedDays) {
            List<String> dayIssues = issuesByDay.get(dayIndex);
            if (dayIssues == null || dayIssues.isEmpty()) {
                continue;
            }
            String dayContext = buildRetryDayContext(failedDraft, dayIndex, skeletonHints, operations);
            String issueInstruction = buildRetryIssueInstructionForDay(dayIndex, dayIssues, failedDraft, operations);
            String preservation = buildRetryPreservationInstruction(failedDraft, dayIndex, dayIssues, operations);
            String clause = "day " + dayIndex
                    + "{context=" + dayContext
                    + "; fixes=" + issueInstruction
                    + "; preserve=" + preservation
                    + "}";
            scopedClauses.add(clause);
        }
        if (scopedClauses.isEmpty()) {
            return "";
        }
        String dayList = scopedDays.stream().map(String::valueOf).collect(Collectors.joining(", "));
        String scopedSummary = " Prioritize repairing only these failed days first: " + dayList + ". Keep other days unchanged unless a listed failed day cannot be fixed otherwise. "
                + String.join(" ", scopedClauses);
        return " " + trimToMaxChars(scopedSummary, RETRY_SCOPED_DAY_CONTEXT_MAX_CHARS);
    }

    private String buildWholePlanStableFieldInstruction(
            PlanDraftResponse failedDraft,
            List<String> validationIssues,
            Operations operations
    ) {
        if (failedDraft == null || failedDraft.daysPlan() == null || failedDraft.daysPlan().isEmpty()) {
            return "";
        }
        List<Integer> failedDays = extractRetryDayIndexes(validationIssues);
        List<String> hotelLocks = new ArrayList<>();
        List<String> mealLocks = new ArrayList<>();
        for (DayPlan day : failedDraft.daysPlan()) {
            if (day == null) {
                continue;
            }
            if (day.hotel() != null && day.hotel().name() != null && !day.hotel().name().isBlank()) {
                hotelLocks.add("D" + day.dayIndex() + "=" + day.hotel().name().trim());
            }
            if (failedDays.contains(day.dayIndex())) {
                continue;
            }
            List<Place> stops = day.stops() == null ? List.of() : day.stops();
            for (Place stop : stops) {
                if (!operations.isStrictMealStop(stop)) {
                    continue;
                }
                String mealType = operations.normalizeSlot(stop.mealType());
                if (!"lunch".equals(mealType) && !"dinner".equals(mealType)) {
                    continue;
                }
                if (!operations.hasVerifiedMealStop(stop, mealType)) {
                    continue;
                }
                String name = operations.safeStopName(stop);
                mealLocks.add("D" + day.dayIndex() + " " + mealType + "=" + name);
            }
        }
        String hotelClause = hotelLocks.isEmpty()
                ? ""
                : " Preserve these hotels exactly unless a listed validation issue directly requires a hotel change: "
                + trimToMaxChars(String.join("; ", hotelLocks), RETRY_STABLE_FIELDS_MAX_CHARS / 2) + ".";
        String mealClause = mealLocks.isEmpty()
                ? ""
                : " Preserve these already-verified meal venues on unaffected days: "
                + trimToMaxChars(String.join("; ", mealLocks), RETRY_STABLE_FIELDS_MAX_CHARS / 2) + ".";
        if (hotelClause.isBlank() && mealClause.isBlank()) {
            return "";
        }
        return hotelClause + mealClause;
    }

    private String buildRetryIssueInstructionForDay(
            int dayIndex,
            List<String> dayIssues,
            PlanDraftResponse failedDraft,
            Operations operations
    ) {
        if (dayIssues == null || dayIssues.isEmpty()) {
            return "";
        }
        List<String> instructions = new ArrayList<>();
        if (hasAnyIssue(dayIssues, "-missing-lunch")) {
            instructions.add("Add exactly one real lunch venue in the midday window with category restaurant, cafe, food, or dining.");
        }
        if (hasAnyIssue(dayIssues, "-missing-dinner")) {
            instructions.add("Add exactly one real dinner venue in the evening window with category restaurant, cafe, food, or dining.");
        }
        if (hasAnyIssue(dayIssues, "-too-few-non-meal-stops")) {
            instructions.add("Increase the number of non-meal stops for this day until it satisfies the skeleton effective range, while keeping the route compact.");
        }
        if (hasAnyIssue(dayIssues, "-duplicate-poi-across-days")) {
            instructions.add("Replace any stop that duplicates a POI already used on another day with a different real POI in the same area and of a similar visit type. Do not reuse the same attraction, museum, park, lookout, or landmark across multiple days unless no realistic alternative exists.");
            String duplicateDetail = operations.dayDuplicateRetryInstruction(dayIndex, failedDraft);
            if (!duplicateDetail.isBlank()) {
                instructions.add(duplicateDetail);
            }
        }
        if (hasAnyIssue(dayIssues, "-duplicate-poi-same-day")) {
            instructions.add("Remove or replace same-day duplicate POIs so each attraction, museum, park, lookout, or landmark appears at most once within this day.");
        }
        if (hasAnyIssue(dayIssues, "-gap-too-large")) {
            instructions.add("Tighten the schedule so adjacent stops do not have oversized idle gaps; prefer compact same-area sequencing.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-too-early")) {
            instructions.add("Move time-sensitive stops later into a suitable window without creating oversized gaps.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-too-late")) {
            instructions.add("Move time-sensitive stops earlier so they occur within suitable operating hours and do not drift late in the day.");
        }
        if (hasAnyIssue(dayIssues, "-time-sensitive-slot-mismatch")) {
            instructions.add("Retime or replace the mismatched stop so its slot and time window fit the venue type.");
        }
        if (hasAnyIssue(dayIssues, "-lunch-time-invalid")) {
            instructions.add("Keep lunch start time inside 11:15-13:00 unless this is a theme-park day.");
        }
        if (hasAnyIssue(dayIssues, "-dinner-time-invalid")) {
            instructions.add("Keep dinner start time inside 17:30-20:00 unless this is a theme-park day.");
        }
        if (hasAnyIssue(dayIssues, "-theme-park-cross-city")) {
            instructions.add("Keep the theme-park day in one remote cluster only. Remove any cross-city jump that leaves the cluster and then returns later.");
        }
        if (instructions.isEmpty()) {
            return " Fix these validation issues for day " + dayIndex + ": " + String.join(", ", dayIssues) + ".";
        }
        return " For day " + dayIndex + ", apply all of these corrections: " + String.join(" ", instructions);
    }

    private String buildRetryDayContext(
            PlanDraftResponse draft,
            int dayIndex,
            SkeletonHints skeletonHints,
            Operations operations
    ) {
        DayPlan day = findDayPlan(draft, dayIndex);
        if (day == null) {
            return "";
        }
        List<Place> stops = day.stops() == null ? List.of() : day.stops();
        String stopOrder = stops.isEmpty()
                ? "stops=none"
                : "stops=" + stops.stream()
                .map(this::summarizeRetryStop)
                .collect(Collectors.joining(" -> "));
        boolean hasLunch = stops.stream().anyMatch(stop -> operations.hasMealSlot(stop, "lunch"));
        boolean hasDinner = stops.stream().anyMatch(stop -> operations.hasMealSlot(stop, "dinner"));
        String mealStatus = "meals[lunch=" + (hasLunch ? "present" : "missing")
                + ",dinner=" + (hasDinner ? "present" : "missing") + "]";
        String skeleton = skeletonHints == null ? "" : skeletonHints.promptHintForDay(dayIndex);
        String skeletonRange = extractEffectiveRangeFromSkeletonHint(skeleton);
        String nonMealStatus = "nonMeal=count " + operations.countNonMealStops(stops)
                + (skeletonRange.isBlank() ? "" : ", target " + skeletonRange);
        return stopOrder + "; " + mealStatus + "; " + nonMealStatus;
    }

    private String buildRetryPreservationInstruction(
            PlanDraftResponse draft,
            int dayIndex,
            List<String> dayIssues,
            Operations operations
    ) {
        DayPlan day = findDayPlan(draft, dayIndex);
        if (day == null || day.stops() == null || day.stops().isEmpty()) {
            return " Preserve unchanged stops when possible.";
        }
        List<Place> stops = day.stops();
        RetryAdjustmentBuckets buckets = collectRetryAdjustmentBuckets(dayIssues, stops, operations);
        List<String> mustKeep = new ArrayList<>();
        List<String> mayRetime = new ArrayList<>();
        List<String> mayReplace = new ArrayList<>();
        List<String> mayInsertAround = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Place stop = stops.get(i);
            String summary = summarizeRetryStop(stop);
            boolean retime = buckets.mayRetime().contains(i);
            boolean replace = buckets.mayReplace().contains(i);
            boolean insertAround = buckets.mayInsertAround().contains(i);
            if (!retime && !replace && !insertAround) {
                mustKeep.add(summary);
                continue;
            }
            if (retime) {
                mayRetime.add(summary);
            }
            if (replace) {
                mayReplace.add(summary);
            }
            if (insertAround) {
                mayInsertAround.add(summary);
            }
        }
        String mustKeepClause = mustKeep.isEmpty()
                ? "mustKeep=none"
                : "mustKeep=" + String.join(" | ", mustKeep);
        String mayRetimeClause = mayRetime.isEmpty()
                ? "mayRetime=none"
                : "mayRetime=" + String.join(" | ", mayRetime);
        String mayReplaceClause = mayReplace.isEmpty()
                ? "mayReplace=none"
                : "mayReplace=" + String.join(" | ", mayReplace);
        String mayInsertAroundClause = mayInsertAround.isEmpty()
                ? "mayInsertAround=none"
                : "mayInsertAround=" + String.join(" | ", mayInsertAround);
        return " Preserve unchanged stops when possible. Keep the must-keep stops stable unless they block a valid repair. "
                + "Stop preservation: " + mustKeepClause + "; "
                + mayRetimeClause + "; "
                + mayReplaceClause + "; "
                + mayInsertAroundClause + ".";
    }

    private RetryAdjustmentBuckets collectRetryAdjustmentBuckets(
            List<String> dayIssues,
            List<Place> stops,
            Operations operations
    ) {
        java.util.Set<Integer> mayRetime = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> mayReplace = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> mayInsertAround = new java.util.LinkedHashSet<>();
        if (dayIssues == null || dayIssues.isEmpty()) {
            return new RetryAdjustmentBuckets(mayRetime, mayReplace, mayInsertAround);
        }
        for (String issue : dayIssues) {
            Matcher matcher = STOP_ISSUE_PATTERN.matcher(issue == null ? "" : issue);
            if (matcher.matches()) {
                int stopIndex = Integer.parseInt(matcher.group(2)) - 1;
                if (stopIndex >= 0 && stopIndex < stops.size()) {
                    if (issue.endsWith("-gap-too-large")
                            || issue.endsWith("-time-sensitive-too-early")
                            || issue.endsWith("-time-sensitive-too-late")
                            || issue.endsWith("-time-sensitive-slot-mismatch")) {
                        mayRetime.add(stopIndex);
                    } else {
                        mayReplace.add(stopIndex);
                    }
                }
                continue;
            }
            if (issue.endsWith("-missing-lunch")) {
                addMealStopIndexes(mayInsertAround, stops, "lunch", operations);
            }
            if (issue.endsWith("-missing-dinner")) {
                addMealStopIndexes(mayInsertAround, stops, "dinner", operations);
            }
            if (issue.endsWith("-too-few-non-meal-stops")) {
                for (int i = 0; i < stops.size(); i++) {
                    if (operations.isCountedNonMealStop(stops.get(i))) {
                        mayReplace.add(i);
                    }
                }
                mayInsertAround.addAll(mayReplace);
            }
            if (issue.endsWith("-theme-park-cross-city")) {
                for (int i = 0; i < stops.size(); i++) {
                    if (!operations.isStrictMealStop(stops.get(i))) {
                        mayReplace.add(i);
                    }
                }
            }
        }
        return new RetryAdjustmentBuckets(mayRetime, mayReplace, mayInsertAround);
    }

    private void addMealStopIndexes(java.util.Set<Integer> indexes, List<Place> stops, String slot, Operations operations) {
        for (int i = 0; i < stops.size(); i++) {
            if (operations.hasMealSlot(stops.get(i), slot)) {
                indexes.add(i);
            }
        }
    }

    private DayPlan findDayPlan(PlanDraftResponse draft, int dayIndex) {
        if (draft == null || draft.daysPlan() == null || draft.daysPlan().isEmpty()) {
            return null;
        }
        return draft.daysPlan().stream()
                .filter(day -> day != null && day.dayIndex() == dayIndex)
                .findFirst()
                .orElse(null);
    }

    private String summarizeRetryStop(Place stop) {
        if (stop == null) {
            return "unknown";
        }
        String name = stop.name() == null || stop.name().isBlank() ? "unnamed" : stop.name().trim();
        String slot = stop.timeSlot() == null || stop.timeSlot().isBlank() ? "unslotted" : stop.timeSlot().trim();
        String time = joinNonBlank("-", stop.startTime(), stop.endTime());
        return name + "[" + slot + (time.isBlank() ? "" : "," + time) + "]";
    }

    private String extractEffectiveRangeFromSkeletonHint(String skeletonHint) {
        if (skeletonHint == null || skeletonHint.isBlank()) {
            return "";
        }
        String marker = "effectiveNonMeal=";
        int start = skeletonHint.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int end = skeletonHint.indexOf(',', from);
        if (end < 0) {
            end = skeletonHint.indexOf('}', from);
        }
        if (end < 0 || end <= from) {
            return "";
        }
        return skeletonHint.substring(from, end).trim();
    }

    private String trimToMaxChars(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0 || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        int end = Math.max(0, maxChars - 3);
        return value.substring(0, end).trim() + "...";
    }

    private boolean hasAnyIssue(List<String> issues, String suffix) {
        if (issues == null || issues.isEmpty() || suffix == null || suffix.isBlank()) {
            return false;
        }
        return issues.stream().anyMatch(issue -> issue != null && issue.endsWith(suffix));
    }

    private String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }

    public record SkeletonHints(
            Map<Integer, Integer> effectiveMinByDay,
            String promptHints,
            Map<Integer, String> promptHintsByDay
    ) {
        String promptHintsText() {
            return promptHints == null ? "" : promptHints;
        }

        String promptHintForDay(int dayIndex) {
            if (promptHintsByDay == null || promptHintsByDay.isEmpty()) {
                return "";
            }
            return promptHintsByDay.getOrDefault(dayIndex, "");
        }
    }

    public abstract static class Operations {
        abstract boolean isStrictMealStop(Place stop);

        abstract boolean hasVerifiedMealStop(Place stop, String mealType);

        abstract boolean hasMealSlot(Place stop, String slot);

        abstract boolean isCountedNonMealStop(Place stop);

        abstract int countNonMealStops(List<Place> stops);

        abstract String normalizeSlot(String value);

        abstract String safeStopName(Place stop);

        abstract String dayDuplicateRetryInstruction(int dayIndex, PlanDraftResponse failedDraft);
    }

    private record RetryAdjustmentBuckets(
            java.util.Set<Integer> mayRetime,
            java.util.Set<Integer> mayReplace,
            java.util.Set<Integer> mayInsertAround
    ) {}
}
