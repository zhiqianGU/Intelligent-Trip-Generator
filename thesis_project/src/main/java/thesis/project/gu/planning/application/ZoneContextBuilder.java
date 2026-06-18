package thesis.project.gu.planning.application;

import org.springframework.stereotype.Service;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ZoneContextBuilder {
    public List<ZoneContext> build(
            List<PlanningZoneSummary> selectedZones,
            List<PlanningZoneSnapshot> snapshots,
            List<AvailableZoneSummary> availableSummaries
    ) {
        if (selectedZones == null || selectedZones.isEmpty()) {
            return List.of();
        }
        Map<String, PlanningZoneSnapshot> snapshotsByZone = indexByZoneId(snapshots, PlanningZoneSnapshot::zoneId);
        Map<String, AvailableZoneSummary> summariesByZone = indexByZoneId(availableSummaries, AvailableZoneSummary::zoneId);
        return selectedZones.stream()
                .filter(zone -> zone != null && zone.zoneId() != null && !zone.zoneId().isBlank())
                .map(zone -> toContext(zone, snapshotsByZone.get(zone.zoneId()), summariesByZone.get(zone.zoneId())))
                .toList();
    }

    private ZoneContext toContext(
            PlanningZoneSummary zone,
            PlanningZoneSnapshot snapshot,
            AvailableZoneSummary summary
    ) {
        int activities = summary == null ? zone.capabilities().attractionCount() : summary.availableAttractionCount();
        int indoorActivities = summary == null ? 0 : summary.availableIndoorCount();
        int familyActivities = summary == null ? zone.capabilities().familyFriendlyCount() : summary.availableFamilyCount();
        int lunchOptions = summary == null ? zone.capabilities().lunchRestaurantCount() : summary.availableLunchCount();
        int dinnerOptions = summary == null ? zone.capabilities().dinnerRestaurantCount() : summary.availableDinnerCount();
        int capacity = summary == null ? zone.capabilities().normalDayCapacity() : summary.requestScopedCapacity();
        return new ZoneContext(
                zone.zoneId(),
                zone.name(),
                zone.zoneType(),
                zone.themes(),
                new ZoneContext.AvailablePoiCounts(activities, indoorActivities, familyActivities),
                new ZoneContext.MealSupport(lunchOptions, dinnerOptions),
                capacity,
                weatherSuitability(activities, indoorActivities),
                zone.recommendedAllocation(),
                summary == null ? "" : summary.freshnessStatus(),
                snapshot == null ? "" : snapshot.semanticProfile(),
                snapshot == null ? "" : snapshot.snapshotVersion()
        );
    }

    private String weatherSuitability(int activities, int indoorActivities) {
        if (activities <= 0 || indoorActivities <= 0) {
            return "OUTDOOR_DEPENDENT";
        }
        if (indoorActivities >= Math.max(1, activities / 2)) {
            return "INDOOR_READY";
        }
        return "PARTIAL_INDOOR_SUPPORT";
    }

    private <T> Map<String, T> indexByZoneId(List<T> values, Function<T, String> zoneIdExtractor) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return values.stream()
                .filter(value -> value != null)
                .filter(value -> {
                    String zoneId = zoneIdExtractor.apply(value);
                    return zoneId != null && !zoneId.isBlank();
                })
                .collect(Collectors.toMap(zoneIdExtractor, Function.identity(), (left, ignored) -> left));
    }
}
