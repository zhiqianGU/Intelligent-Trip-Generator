package thesis.project.gu.planning.application;

import org.junit.jupiter.api.Test;
import thesis.project.gu.planning.domain.ParsedPlanningRequest;
import thesis.project.gu.planning.domain.SpecificationValidationResult;
import thesis.project.gu.planning.domain.TripPlanningSpecification;
import thesis.project.gu.planning.domain.ZoneContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningSpecificationValidatorTest {
    private final PlanningSpecificationValidator validator = new PlanningSpecificationValidator();

    @Test
    void repairsIllegalPrimaryZoneUsingLegalFallback() {
        TripPlanningSpecification specification = baseSpec(List.of(new TripPlanningSpecification.DayStrategy(
                1,
                "Invalid day",
                "invalid-zone",
                List.of("brisbane-south-bank", "unknown-zone"),
                "bad allocation",
                List.of("culture"),
                List.of()
        )));

        SpecificationValidationResult result = validator.validate(
                specification,
                List.of(zone("brisbane-cbd", "CBD"), zone("brisbane-south-bank", "South Bank")),
                parsed(List.of())
        );

        assertThat(result.valid()).isTrue();
        assertThat(result.inputValid()).isFalse();
        assertThat(result.repaired()).isTrue();
        TripPlanningSpecification.DayStrategy strategy = result.specification().dayStrategies().getFirst();
        assertThat(strategy.primaryZoneId()).isEqualTo("brisbane-south-bank");
        assertThat(strategy.fallbackZoneIds()).containsExactly("brisbane-cbd");
        assertThat(strategy.allocation()).isEqualTo("FULL_DAY");
        assertThat(result.issues()).extracting(SpecificationValidationResult.Issue::code)
                .contains("illegal-primary-zone", "invalid-allocation", "invalid-fallback-zone");
    }

    @Test
    void insertsMissingDayStrategiesAndRemovesExtraDays() {
        TripPlanningSpecification specification = new TripPlanningSpecification(
                destination(),
                3,
                1200,
                new TripPlanningSpecification.Party(2, 0),
                List.of("culture"),
                "normal",
                "local-fast",
                null,
                TripPlanningSpecification.Constraints.defaults(),
                List.of(),
                List.of(new TripPlanningSpecification.DayStrategy(
                        5,
                        "Outside trip",
                        "brisbane-cbd",
                        List.of(),
                        "FULL_DAY",
                        List.of(),
                        List.of()
                ))
        );

        SpecificationValidationResult result = validator.validate(
                specification,
                List.of(zone("brisbane-cbd", "CBD"), zone("brisbane-south-bank", "South Bank")),
                parsed(List.of())
        );

        assertThat(result.specification().dayStrategies()).hasSize(3);
        assertThat(result.valid()).isTrue();
        assertThat(result.inputValid()).isFalse();
        assertThat(result.specification().dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::day)
                .containsExactly(1, 2, 3);
        assertThat(result.issues()).extracting(SpecificationValidationResult.Issue::code)
                .contains("missing-day-strategy", "extra-day-strategy");
    }

    @Test
    void repairsSpecialEventSemanticsAndRequiredCapabilities() {
        TripPlanningSpecification specification = new TripPlanningSpecification(
                destination(),
                2,
                1200,
                new TripPlanningSpecification.Party(2, 1),
                List.of("culture"),
                "normal",
                "local-fast",
                null,
                TripPlanningSpecification.Constraints.defaults(),
                List.of(new TripPlanningSpecification.SpecialEvent(2, "birthday", List.of())),
                List.of(new TripPlanningSpecification.DayStrategy(
                        2,
                        "Party day",
                        "brisbane-south-bank",
                        List.of("brisbane-cbd"),
                        "FULL_DAY",
                        List.of("culture"),
                        List.of()
                ))
        );

        SpecificationValidationResult result = validator.validate(
                specification,
                List.of(zone("brisbane-cbd", "CBD"), zone("brisbane-south-bank", "South Bank")),
                parsed(List.of(new ParsedPlanningRequest.SpecialDayHint(2, "birthday", "birthday")))
        );

        assertThat(result.specification().specialEvents()).hasSize(1);
        assertThat(result.valid()).isTrue();
        assertThat(result.inputValid()).isFalse();
        assertThat(result.specification().specialEvents().getFirst().type()).isEqualTo("BIRTHDAY");
        assertThat(result.specification().specialEvents().getFirst().requiredCapabilities()).contains("birthday-suitable");
        assertThat(result.specification().dayStrategies().get(1).requiredCapabilities())
                .contains("birthday-suitable", "family-friendly", "indoor-or-rainy-day");
    }

    @Test
    void reducesConsecutivePrimaryZoneRepeatsWhenAlternativeExists() {
        TripPlanningSpecification specification = baseSpec(List.of(
                strategy(1, "brisbane-cbd"),
                strategy(2, "brisbane-cbd")
        ));

        SpecificationValidationResult result = validator.validate(
                specification,
                List.of(zone("brisbane-cbd", "CBD"), zone("brisbane-south-bank", "South Bank")),
                parsed(List.of())
        );

        assertThat(result.specification().dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-cbd", "brisbane-south-bank");
        assertThat(result.valid()).isTrue();
        assertThat(result.inputValid()).isFalse();
        assertThat(result.issues()).extracting(SpecificationValidationResult.Issue::code)
                .contains("repaired-consecutive-zone");
    }

    @Test
    void doesNotMoveSpecialEventDayOnlyToAvoidConsecutiveZoneRepeat() {
        TripPlanningSpecification specification = new TripPlanningSpecification(
                destination(),
                2,
                1200,
                new TripPlanningSpecification.Party(2, 1),
                List.of("culture"),
                "normal",
                "local-fast",
                null,
                TripPlanningSpecification.Constraints.defaults(),
                List.of(new TripPlanningSpecification.SpecialEvent(2, "BIRTHDAY", List.of("birthday-suitable"))),
                List.of(
                        strategy(1, "brisbane-south-bank"),
                        strategy(2, "brisbane-south-bank")
                )
        );

        SpecificationValidationResult result = validator.validate(
                specification,
                List.of(zone("brisbane-south-bank", "South Bank"), zone("brisbane-cbd", "CBD")),
                parsed(List.of(new ParsedPlanningRequest.SpecialDayHint(2, "birthday", "birthday")))
        );

        assertThat(result.specification().dayStrategies())
                .extracting(TripPlanningSpecification.DayStrategy::primaryZoneId)
                .containsExactly("brisbane-south-bank", "brisbane-south-bank");
        assertThat(result.valid()).isTrue();
        assertThat(result.specification().dayStrategies().get(1).requiredCapabilities())
                .contains("birthday-suitable");
        assertThat(result.issues()).extracting(SpecificationValidationResult.Issue::code)
                .doesNotContain("repaired-consecutive-zone");
    }

    private TripPlanningSpecification baseSpec(List<TripPlanningSpecification.DayStrategy> strategies) {
        return new TripPlanningSpecification(
                destination(),
                Math.max(1, strategies.size()),
                1200,
                new TripPlanningSpecification.Party(2, 1),
                List.of("culture"),
                "normal",
                "local-fast",
                null,
                TripPlanningSpecification.Constraints.defaults(),
                List.of(),
                strategies
        );
    }

    private TripPlanningSpecification.DayStrategy strategy(int day, String zoneId) {
        return new TripPlanningSpecification.DayStrategy(
                day,
                "Day " + day,
                zoneId,
                List.of(),
                "FULL_DAY",
                List.of("culture"),
                List.of()
        );
    }

    private ParsedPlanningRequest parsed(List<ParsedPlanningRequest.SpecialDayHint> hints) {
        return new ParsedPlanningRequest(
                "Brisbane",
                2,
                3,
                "normal",
                1200,
                List.of("culture"),
                hints,
                false,
                true,
                true,
                null
        );
    }

    private TripPlanningSpecification.Destination destination() {
        return new TripPlanningSpecification.Destination(
                "AU-QLD-BRISBANE",
                "Brisbane",
                "Queensland",
                "Australia",
                "Australia/Brisbane",
                true
        );
    }

    private ZoneContext zone(String zoneId, String name) {
        return new ZoneContext(
                zoneId,
                name,
                "URBAN_DISTRICT",
                List.of("culture"),
                new ZoneContext.AvailablePoiCounts(4, 2, 3),
                new ZoneContext.MealSupport(3, 3),
                1,
                "INDOOR_READY",
                "FULL_DAY",
                "LOCAL_CURATED",
                "culture dining",
                "local-poi-v1"
        );
    }
}
