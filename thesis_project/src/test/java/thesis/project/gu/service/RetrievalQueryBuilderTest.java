package thesis.project.gu.service;

import org.junit.jupiter.api.Test;
import thesis.project.gu.catalog.domain.Destination;
import thesis.project.gu.planning.api.dto.CreatePlanReq;
import thesis.project.gu.planning.application.LightweightRequestPreParser;
import thesis.project.gu.planning.application.RetrievalQueryBuilder;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlanningZoneRetrievalQuery;
import thesis.project.gu.planning.domain.TripPlanningSpecification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQueryBuilderTest {
    private final LightweightRequestPreParser parser = new LightweightRequestPreParser();
    private final RetrievalQueryBuilder builder = new RetrievalQueryBuilder();

    @Test
    void buildsStructuredZoneRetrievalQueryFromParsedRequestAndDestination() {
        CreatePlanReq req = new CreatePlanReq(
                "Brisbane",
                3,
                1200,
                new CreatePlanReq.Party(2, 1),
                List.of("culture", "market_shopping"),
                "relaxed",
                "local-fast",
                null
        );
        ParsedPlanningRequest parsed = parser.parse(req);
        Destination destination = new Destination(
                "AU-QLD-BRISBANE",
                "Brisbane",
                "Queensland",
                "Australia",
                "Australia/Brisbane",
                true
        );
        TripPlanningSpecification specification = TripPlanningSpecification.fromRequest(req)
                .withResolvedDestination(destination);

        PlanningZoneRetrievalQuery query = builder.build(parsed, destination, specification);

        assertThat(query.destinationId()).isEqualTo("AU-QLD-BRISBANE");
        assertThat(query.destinationCity()).isEqualTo("Brisbane");
        assertThat(query.activeOnly()).isTrue();
        assertThat(query.minimumAllocation()).isEqualTo("HALF_DAY");
        assertThat(query.preferredStyles()).contains("culture", "shopping", "market_shopping");
        assertThat(query.pace()).isEqualTo("relaxed");
        assertThat(query.travellers()).isEqualTo(3);
        assertThat(query.detectedHints())
                .containsEntry("lateStartPreferred", true)
                .containsEntry("familyFriendly", true)
                .containsEntry("preferIndoorWhenRaining", true)
                .containsEntry("budget", 1200);
        assertThat(query.semanticQuery())
                .contains("culture")
                .contains("shopping")
                .contains("relaxed")
                .contains("family friendly")
                .contains("late start")
                .contains("indoor rainy day");
    }

    @Test
    void fallsBackToSpecificationDestinationWhenResolverResultIsMissing() {
        TripPlanningSpecification specification = new TripPlanningSpecification(
                new TripPlanningSpecification.Destination(
                        "TEST-TESTVILLE",
                        "Testville",
                        "TS",
                        "Testland",
                        "UTC",
                        true
                ),
                1,
                null,
                new TripPlanningSpecification.Party(2, 0),
                List.of(),
                "normal",
                "local-fast",
                null
        );

        PlanningZoneRetrievalQuery query = builder.build(null, null, specification);

        assertThat(query.destinationId()).isEqualTo("TEST-TESTVILLE");
        assertThat(query.destinationCity()).isEqualTo("Testville");
        assertThat(query.minimumAllocation()).isEqualTo("FULL_OR_HALF_DAY");
        assertThat(query.travellers()).isEqualTo(2);
    }
}
