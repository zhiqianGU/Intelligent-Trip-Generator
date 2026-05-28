package thesis.project.gu.service;

import java.util.List;

public record PlanStageMetrics(
        String stageName,
        int totalStops,
        int mealStopCount,
        int verifiedMealStopCount,
        double realMealValidityRate,
        int hallucinatedCorePoiCount,
        int oversizedGapCount,
        int requestedStyleCount,
        int coveredStyleCount,
        double styleCoverageHitRate,
        int validationIssueCount,
        List<String> validationIssues
) {
}
