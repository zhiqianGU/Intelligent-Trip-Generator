package thesis.project.gu.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningZoneDomainNullSafetyTest {
    @Test
    void zoneCapabilitySummaryNormalizesNullMaps() {
        ZoneCapabilitySummary summary = new ZoneCapabilitySummary(1, 2, 3, 4, 5, 6, null, null);

        assertThat(summary.categoryCounts()).isEmpty();
        assertThat(summary.styleTagCounts()).isEmpty();
    }

    @Test
    void planningZoneSummaryNormalizesNullThemesAndCapabilities() {
        PlanningZoneSummary summary = new PlanningZoneSummary(
                "zone-1",
                "Zone 1",
                "Brisbane",
                "URBAN_DISTRICT",
                null,
                null,
                "HALF_DAY"
        );

        assertThat(summary.themes()).isEmpty();
        assertThat(summary.capabilities()).isNotNull();
        assertThat(summary.capabilities().categoryCounts()).isEmpty();
    }

    @Test
    void retrievalResultNormalizesNullCandidateListsAndSkipsInvalidCandidates() {
        PlanningZoneSummary validZone = new PlanningZoneSummary(
                "zone-1",
                "Zone 1",
                "Brisbane",
                "URBAN_DISTRICT",
                List.of(),
                null,
                "HALF_DAY"
        );
        PlanningZoneRetrievalResult result = new PlanningZoneRetrievalResult(
                null,
                Arrays.asList(
                        null,
                        new PlanningZoneRetrievalResult.ScoredZone(null, 10, null),
                        new PlanningZoneRetrievalResult.ScoredZone(validZone, 20, null)
                )
        );

        assertThat(result.semanticCandidates()).isEmpty();
        assertThat(result.feasibilityFallbackCandidates()).hasSize(1);
        assertThat(result.feasibilityFallbackCandidates().getFirst().reasons()).isEmpty();
        assertThat(result.orderedZones()).containsExactly(validZone);
    }
}
