package thesis.project.gu.catalog.domain;

import java.util.List;

public record PlanningZoneSnapshot(
        String zoneId,
        String zoneType,
        List<String> themes,
        List<String> anchorPoiIds,
        int totalAttractionCount,
        int totalRestaurantCount,
        String semanticProfile,
        String snapshotVersion,
        String generatedAt
) {
    public PlanningZoneSnapshot {
        themes = themes == null ? List.of() : List.copyOf(themes);
        anchorPoiIds = anchorPoiIds == null ? List.of() : List.copyOf(anchorPoiIds);
        semanticProfile = semanticProfile == null ? "" : semanticProfile;
        snapshotVersion = snapshotVersion == null ? "" : snapshotVersion;
        generatedAt = generatedAt == null ? "" : generatedAt;
    }
}
