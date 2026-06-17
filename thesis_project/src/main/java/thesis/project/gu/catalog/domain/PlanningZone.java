package thesis.project.gu.catalog.domain;

public record PlanningZone(
        String zoneId,
        String name,
        String destinationCity,
        String zoneType,
        ZoneCapabilitySummary capabilities
) {
}
