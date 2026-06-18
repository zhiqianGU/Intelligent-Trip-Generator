package thesis.project.gu.catalog.domain;

public record AvailableZoneSummary(
        String zoneId,
        int availableAttractionCount,
        int availableIndoorCount,
        int availableFamilyCount,
        int availableLunchCount,
        int availableDinnerCount,
        int requestScopedCapacity,
        String freshnessStatus
) {
    public AvailableZoneSummary {
        freshnessStatus = freshnessStatus == null ? "" : freshnessStatus;
    }
}
