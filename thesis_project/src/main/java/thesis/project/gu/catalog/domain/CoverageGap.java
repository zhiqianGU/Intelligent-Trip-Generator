package thesis.project.gu.catalog.domain;

import java.util.List;

public record CoverageGap(
        int day,
        String zoneId,
        String slotType,
        List<String> requiredCapabilities,
        int requiredUsageCount,
        int preferredCandidateCount,
        int availableCandidateCount
) {
    public int missingCount() {
        return Math.max(0, requiredUsageCount - availableCandidateCount);
    }
}
