package thesis.project.gu.service;

import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.application.LocalPlanningZoneRetrievalService;
import thesis.project.gu.catalog.domain.PlanningZoneRetrievalResult;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPlanningZoneRetrievalServiceTest {
    private final LocalPlanningZoneRetrievalService service = new LocalPlanningZoneRetrievalService();

    @Test
    void ranksPreferenceMatchingZonesAsSemanticCandidatesAndKeepsFeasibleFallbacks() {
        PlanningZoneRetrievalQuery query = query(List.of("culture"), Map.of(
                "familyFriendly", true,
                "preferIndoorWhenRaining", true
        ));
        List<PlanningZoneSummary> zones = List.of(
                zone("brisbane-south-bank", "South Bank", "Brisbane", 5, 8, 8, 3, Map.of("culture", 4), Map.of("museum", 2)),
                zone("brisbane-west-end", "West End", "Brisbane", 6, 10, 9, 2, Map.of("local-dining", 4), Map.of("market-shopping", 2)),
                zone("sydney-cbd", "Sydney CBD", "Sydney", 9, 9, 9, 4, Map.of("culture", 9), Map.of("museum", 5))
        );

        PlanningZoneRetrievalResult result = service.retrieve(query, zones);

        assertThat(result.semanticCandidates())
                .extracting(candidate -> candidate.zone().zoneId())
                .containsExactly("brisbane-south-bank");
        assertThat(result.semanticCandidates().getFirst().reasons()).contains("style-match", "indoor-support");
        assertThat(result.feasibilityFallbackCandidates())
                .extracting(candidate -> candidate.zone().zoneId())
                .containsExactly("brisbane-west-end");
        assertThat(result.orderedZones())
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-south-bank", "brisbane-west-end");
    }

    @Test
    void withoutPreferencesUsesFeasibilityAsPrimaryRanking() {
        PlanningZoneRetrievalQuery query = query(List.of(), Map.of());
        List<PlanningZoneSummary> zones = List.of(
                zone("brisbane-small", "Small", "Brisbane", 1, 0, 0, 0, Map.of(), Map.of()),
                zone("brisbane-capable", "Capable", "Brisbane", 6, 8, 8, 3, Map.of(), Map.of())
        );

        PlanningZoneRetrievalResult result = service.retrieve(query, zones);

        assertThat(result.semanticCandidates())
                .extracting(candidate -> candidate.zone().zoneId())
                .containsExactly("brisbane-capable");
    }

    @Test
    void preferenceWithoutSemanticMatchProducesOnlyFeasibilityFallbacks() {
        PlanningZoneRetrievalQuery query = query(List.of("theme_park"), Map.of());
        List<PlanningZoneSummary> zones = List.of(
                zone("brisbane-capable", "Capable", "Brisbane", 6, 8, 8, 3, Map.of("culture", 2), Map.of("museum", 1)),
                zone("brisbane-food", "Food", "Brisbane", 3, 10, 10, 1, Map.of("local-dining", 4), Map.of())
        );

        PlanningZoneRetrievalResult result = service.retrieve(query, zones);

        assertThat(result.semanticCandidates()).isEmpty();
        assertThat(result.feasibilityFallbackCandidates())
                .extracting(candidate -> candidate.zone().zoneId())
                .containsExactly("brisbane-capable", "brisbane-food");
        assertThat(result.orderedZones())
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-capable", "brisbane-food");
    }

    @Test
    void halfDayAllowsSingleAttractionZone() {
        PlanningZoneRetrievalQuery query = query(List.of(), Map.of(), "HALF_DAY", "relaxed");
        PlanningZoneSummary zone = zone("brisbane-small", "Small", "Brisbane", 1, 0, 0, 0, Map.of(), Map.of());

        PlanningZoneRetrievalResult result = service.retrieve(query, List.of(zone));

        assertThat(result.orderedZones())
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-small");
    }

    @Test
    void fullDayRequiresPaceSpecificActivityCapacity() {
        PlanningZoneRetrievalQuery query = query(List.of(), Map.of(), "FULL_DAY", "rush");
        List<PlanningZoneSummary> zones = List.of(
                zone("brisbane-three-attractions", "Three", "Brisbane", 3, 8, 8, 0, Map.of(), Map.of()),
                zone("brisbane-four-attractions", "Four", "Brisbane", 4, 8, 8, 0, Map.of(), Map.of())
        );

        PlanningZoneRetrievalResult result = service.retrieve(query, zones);

        assertThat(result.orderedZones())
                .extracting(PlanningZoneSummary::zoneId)
                .containsExactly("brisbane-four-attractions");
    }

    @Test
    void handlesZonesWithNullOptionalCollections() {
        PlanningZoneRetrievalQuery query = query(List.of("culture"), Map.of("preferIndoorWhenRaining", true));
        PlanningZoneSummary zone = new PlanningZoneSummary(
                "brisbane-null-safe",
                "Null Safe",
                "Brisbane",
                "URBAN_DISTRICT",
                null,
                new ZoneCapabilitySummary(3, 2, 0, 1, 1, 0, null, null),
                "HALF_DAY"
        );

        PlanningZoneRetrievalResult result = service.retrieve(query, List.of(zone));

        assertThat(result.orderedZones()).containsExactly(zone);
        assertThat(result.semanticCandidates()).isEmpty();
        assertThat(result.feasibilityFallbackCandidates()).hasSize(1);
    }

    private PlanningZoneRetrievalQuery query(List<String> styles, Map<String, Object> hints) {
        return query(styles, hints, "FULL_OR_HALF_DAY", "normal");
    }

    private PlanningZoneRetrievalQuery query(List<String> styles, Map<String, Object> hints, String minimumAllocation, String pace) {
        return new PlanningZoneRetrievalQuery(
                "AU-QLD-BRISBANE",
                "Brisbane",
                true,
                minimumAllocation,
                String.join(" ", styles),
                hints,
                styles,
                pace,
                2
        );
    }

    private PlanningZoneSummary zone(
            String zoneId,
            String name,
            String city,
            int attractions,
            int lunches,
            int dinners,
            int familyFriendly,
            Map<String, Integer> styles,
            Map<String, Integer> categories
    ) {
        return new PlanningZoneSummary(
                zoneId,
                name,
                city,
                "URBAN_DISTRICT",
                styles.keySet().stream().toList(),
                new ZoneCapabilitySummary(
                        attractions,
                        lunches + dinners,
                        0,
                        lunches,
                        dinners,
                        familyFriendly,
                        categories,
                        styles
                ),
                "FULL_DAY"
        );
    }
}
