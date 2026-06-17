package thesis.project.gu.catalog.domain;

import java.util.List;

public record PlanningZoneSummary(
        String zoneId,
        String name,
        String destinationCity,
        String zoneType,
        List<String> themes,
        ZoneCapabilitySummary capabilities,
        String recommendedAllocation
) {
    public PlanningZoneSummary {
        themes = themes == null ? List.of() : List.copyOf(themes);
        capabilities = capabilities == null
                ? new ZoneCapabilitySummary(0, 0, 0, 0, 0, 0, null, null)
                : capabilities;
    }
}
