package thesis.project.gu.service;

import org.springframework.stereotype.Service;
import thesis.project.gu.req.CreatePlanReq;
import thesis.project.gu.response.PlanDraftResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class PlanQualityMetricsService {

    public PlanStageMetrics evaluate(
            String stageName,
            PlanDraftResponse draft,
            CreatePlanReq req,
            List<String> validationIssues
    ) {
        List<String> normalizedIssues = dedupeIssues(validationIssues);
        int totalStops = 0;
        int mealStopCount = 0;
        int verifiedMealStopCount = 0;
        if (draft != null && draft.daysPlan() != null) {
            for (PlanDraftResponse.DayPlan day : draft.daysPlan()) {
                if (day == null || day.stops() == null) {
                    continue;
                }
                for (PlanDraftResponse.Place stop : day.stops()) {
                    totalStops++;
                    if (hasMealSlot(stop, "lunch") || hasMealSlot(stop, "dinner")) {
                        mealStopCount++;
                        if (isVerifiedMealStop(stop)) {
                            verifiedMealStopCount++;
                        }
                    }
                }
            }
        }

        int requestedStyleCount = requestedStyleCount(req);
        int missingStyleCount = (int) normalizedIssues.stream()
                .filter(issue -> issue.startsWith("style-missing-"))
                .count();
        int coveredStyleCount = Math.max(0, requestedStyleCount - missingStyleCount);

        return new PlanStageMetrics(
                stageName,
                totalStops,
                mealStopCount,
                verifiedMealStopCount,
                ratio(verifiedMealStopCount, mealStopCount),
                hallucinatedCorePoiCount(normalizedIssues),
                countMatching(normalizedIssues, "-gap-too-large"),
                requestedStyleCount,
                coveredStyleCount,
                requestedStyleCount == 0 ? 1.0 : ratio(coveredStyleCount, requestedStyleCount),
                normalizedIssues.size(),
                normalizedIssues
        );
    }

    public PlanQualityReport buildReport(
            CreatePlanReq req,
            PlanDraftResponse finalDraft,
            boolean retryUsed,
            boolean retryRescued,
            List<PlanStageMetrics> stages
    ) {
        List<String> requestedStyles = req == null || req.style() == null
                ? List.of()
                : req.style().stream()
                .filter(style -> style != null && !style.isBlank())
                .map(style -> style.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return new PlanQualityReport(
                finalDraft == null ? null : finalDraft.city(),
                finalDraft == null ? null : finalDraft.days(),
                requestedStyles,
                req == null ? null : req.pace(),
                retryUsed,
                retryRescued,
                stages == null ? List.of() : List.copyOf(stages)
        );
    }

    private List<String> dedupeIssues(List<String> validationIssues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return List.of();
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String issue : validationIssues) {
            if (issue != null && !issue.isBlank()) {
                deduped.add(issue.trim());
            }
        }
        return new ArrayList<>(deduped);
    }

    private int requestedStyleCount(CreatePlanReq req) {
        if (req == null || req.style() == null || req.style().isEmpty()) {
            return 0;
        }
        return (int) req.style().stream()
                .filter(style -> style != null && !style.isBlank())
                .map(style -> style.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .count();
    }

    private int hallucinatedCorePoiCount(List<String> issues) {
        return (int) issues.stream()
                .filter(issue -> issue.contains("-hotel-google-places-")
                        || issue.contains("-google-places-")
                        || issue.endsWith("-missing-real-lunch")
                        || issue.endsWith("-missing-real-dinner")
                        || issue.endsWith("-theme-park-cross-city"))
                .count();
    }

    private int countMatching(List<String> issues, String suffix) {
        return (int) issues.stream()
                .filter(issue -> issue.endsWith(suffix))
                .count();
    }

    private double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }

    private boolean hasMealSlot(PlanDraftResponse.Place stop, String slot) {
        if (stop == null || slot == null) {
            return false;
        }
        return slot.equals(normalize(stop.mealType())) || slot.equals(normalize(stop.timeSlot()));
    }

    private boolean isVerifiedMealStop(PlanDraftResponse.Place stop) {
        if (stop == null) {
            return false;
        }
        String category = normalize(stop.category());
        if (!isMealCategory(category)) {
            return false;
        }
        String status = stop.businessStatus() == null ? "" : stop.businessStatus().trim().toUpperCase(Locale.ROOT);
        return status.isBlank() || "OPERATIONAL".equals(status);
    }

    private boolean isMealCategory(String category) {
        return "restaurant".equals(category)
                || "cafe".equals(category)
                || "food".equals(category)
                || "dining".equals(category)
                || "bar".equals(category)
                || "bakery".equals(category);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
