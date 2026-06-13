package thesis.project.gu.planning.quality;

import thesis.project.gu.planning.metrics.PlanStageMetrics;

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
