package thesis.project.gu.planning.domain;

import java.util.List;
import java.util.Map;

public record PlanningZoneRetrievalQuery(
        String destinationId,
        String destinationCity,
        boolean activeOnly,
        String minimumAllocation,
        String semanticQuery,
        Map<String, Object> detectedHints,
        List<String> preferredStyles,
        String pace,
        int travellers
) {
}
