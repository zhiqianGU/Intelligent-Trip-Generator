package thesis.project.gu.service;

import java.util.List;

public record PlanQualityReport(
        String city,
        Integer days,
        List<String> requestedStyles,
        String requestedPace,
        boolean retryUsed,
        boolean retryRescued,
        List<PlanStageMetrics> stages
) {
}
