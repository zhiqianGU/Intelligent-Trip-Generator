package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.domain.AvailableZoneSummary;
import thesis.project.gu.catalog.domain.PlanningZoneSnapshot;
import thesis.project.gu.catalog.domain.PlanningZoneSummary;
import thesis.project.gu.catalog.domain.ZoneCapabilitySummary;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneContextBuilderTest {
    private final ZoneContextBuilder builder = new ZoneContextBuilder();

    @Test
    void buildsCompactContextWithoutExposingPoiLists() {
        PlanningZoneSummary zone = zone("brisbane-south-bank", "South Bank");
        PlanningZoneSnapshot snapshot = new PlanningZoneSnapshot(
                "brisbane-south-bank",
                "URBAN_DISTRICT",
                List.of("culture"),
                List.of("queensland-museum"),
                8,
                6,
                "south-bank culture museum family",
                "local-poi-v1",
                "1970-01-01T00:00:00Z"
        );
        AvailableZoneSummary summary = new AvailableZoneSummary(
                "brisbane-south-bank",
                4,
                2,
                3,
                5,
                4,
                2,
                "LOCAL_CURATED"
        );

        List<ZoneContext> contexts = builder.build(List.of(zone), List.of(snapshot), List.of(summary));

        assertThat(contexts).hasSize(1);
        ZoneContext context = contexts.getFirst();
        assertThat(context.zoneId()).isEqualTo("brisbane-south-bank");
        assertThat(context.themes()).containsExactly("culture");
        assertThat(context.availablePoiCounts().activities()).isEqualTo(4);
        assertThat(context.availablePoiCounts().indoorActivities()).isEqualTo(2);
        assertThat(context.availablePoiCounts().familyFriendlyActivities()).isEqualTo(3);
        assertThat(context.mealSupport().lunchOptions()).isEqualTo(5);
        assertThat(context.mealSupport().dinnerOptions()).isEqualTo(4);
        assertThat(context.capacity()).isEqualTo(2);
        assertThat(context.weatherSuitability()).isEqualTo("INDOOR_READY");
        assertThat(context.dataFreshness()).isEqualTo("LOCAL_CURATED");
        assertThat(context.semanticProfile()).isEqualTo("south-bank culture museum family");
        assertThat(context.snapshotVersion()).isEqualTo("local-poi-v1");
    }

    @Test
    void preservesSelectedZoneOrderAndFallsBackToZoneCapabilitiesWhenSummariesAreMissing() {
        PlanningZoneSummary cbd = zone("brisbane-cbd", "CBD");
        PlanningZoneSummary southBank = zone("brisbane-south-bank", "South Bank");

        List<ZoneContext> contexts = builder.build(List.of(southBank, cbd), null, null);

        assertThat(contexts)
                .extracting(ZoneContext::zoneId)
                .containsExactly("brisbane-south-bank", "brisbane-cbd");
        assertThat(contexts.getFirst().availablePoiCounts().activities()).isEqualTo(3);
        assertThat(contexts.getFirst().mealSupport().lunchOptions()).isEqualTo(2);
        assertThat(contexts.getFirst().dataFreshness()).isEmpty();
    }

    @Test
    void ignoresNullOrInvalidSelectedZones() {
        List<ZoneContext> contexts = builder.build(
                java.util.Arrays.asList(null, zone(null, "Invalid"), zone("brisbane-cbd", "CBD")),
                List.of(),
                List.of()
        );

        assertThat(contexts).extracting(ZoneContext::zoneId).containsExactly("brisbane-cbd");
    }

    private PlanningZoneSummary zone(String zoneId, String name) {
        return new PlanningZoneSummary(
                zoneId,
                name,
                "Brisbane",
                "URBAN_DISTRICT",
                List.of("culture"),
                new ZoneCapabilitySummary(
                        3,
                        4,
                        1,
                        2,
                        2,
                        1,
                        Map.of("museum", 1),
                        Map.of("culture", 2)
                ),
                "FULL_DAY"
        );
    }
}
