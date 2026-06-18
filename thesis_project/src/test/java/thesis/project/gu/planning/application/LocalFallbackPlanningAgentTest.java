package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.PlanningAgentInput;
import thesis.project.gu.planning.domain.PlanningAgentOutput;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFallbackPlanningAgentTest {
    private final LocalFallbackPlanningAgent agent = new LocalFallbackPlanningAgent();

    @Test
    void rotatesRankedZonesIntoDayStrategies() {
        TripPlanningSpecification specification = specification(3);
        ParsedPlanningRequest parsedRequest = parsed(List.of("culture"), List.of());

        PlanningAgentOutput output = agent.plan(new PlanningAgentInput(
                specification,
                parsedRequest,
                List.of(zone("brisbane-south-bank", "South Bank", 3, 2), zone("brisbane-cbd", "CBD", 2, 2))
        ));

        assertThat(output.fallbackUsed()).isTrue();
        assertThat(output.plannerType()).isEqualTo("LOCAL_FALLBACK");
        assertThat(output.dayStrategies()).hasSize(3);
        assertThat(output.dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank", "brisbane-cbd", "brisbane-south-bank");
        assertThat(output.dayStrategies().getFirst().fallbackZoneIds()).containsExactly("brisbane-cbd");
        assertThat(output.dayStrategies().getFirst().preferredPoiTypes()).contains("culture");
    }

    @Test
    void specialDayUsesBestDiningAndCultureZone() {
        TripPlanningSpecification specification = specification(2);
        ParsedPlanningRequest parsedRequest = parsed(
                List.of("nature"),
                List.of(new ParsedPlanningRequest.SpecialDayHint(2, "birthday", "birthday"))
        );

        PlanningAgentOutput output = agent.plan(new PlanningAgentInput(
                specification,
                parsedRequest,
                List.of(
                        zone("brisbane-nature", "Nature", 1, 1),
                        zone("brisbane-south-bank", "South Bank", 6, 5)
                )
        ));

        assertThat(output.specialEvents()).hasSize(1);
        assertThat(output.specialEvents().getFirst().type()).isEqualTo("BIRTHDAY");
        assertThat(output.dayStrategies().get(1).primaryZoneId()).isEqualTo("brisbane-south-bank");
        assertThat(output.dayStrategies().get(1).requiredCapabilities()).contains("birthday-suitable");
        assertThat(output.dayStrategies().get(1).preferredPoiTypes()).contains("local-dining");
    }

    @Test
    void returnsSafeStrategiesWhenNoZonesExist() {
        PlanningAgentOutput output = agent.plan(new PlanningAgentInput(specification(1), parsed(List.of(), List.of()), null));

        assertThat(output.dayStrategies()).hasSize(1);
        assertThat(output.dayStrategies().getFirst().primaryZoneId()).isNull();
        assertThat(output.dayStrategies().getFirst().fallbackZoneIds()).isEmpty();
    }

    private TripPlanningSpecification specification(int days) {
        return new TripPlanningSpecification(
                new TripPlanningSpecification.Destination("AU-QLD-BRISBANE", "Brisbane", "Queensland", "Australia", "Australia/Brisbane", true),
                days,
                1200,
                new TripPlanningSpecification.Party(2, 1),
                List.of("culture"),
                "normal",
                "local-fast",
                null
        );
    }

    private ParsedPlanningRequest parsed(
            List<String> preferenceHints,
            List<ParsedPlanningRequest.SpecialDayHint> specialDayHints
    ) {
        return new ParsedPlanningRequest(
                "Brisbane",
                2,
                3,
                "normal",
                1200,
                preferenceHints,
                specialDayHints,
                false,
                true,
                true,
                null
        );
    }

    private ZoneContext zone(String zoneId, String name, int lunchOptions, int dinnerOptions) {
        return new ZoneContext(
                zoneId,
                name,
                "URBAN_DISTRICT",
                List.of("culture"),
                new ZoneContext.AvailablePoiCounts(4, 2, 3),
                new ZoneContext.MealSupport(lunchOptions, dinnerOptions),
                1,
                "INDOOR_READY",
                "FULL_DAY",
                "LOCAL_CURATED",
                "culture dining",
                "local-poi-v1"
        );
    }
}
